package com.grayzone.app.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reports the app in front from Usage access (UsageStatsManager events). It never reads screen
 * content and costs one small system query per [POLL_MS] while the screen is on, nothing while
 * it is off.
 *
 * [onChange] runs on the main thread with the foreground package ([UsageForegroundTracker.OTHER]
 * for a non-launchable full screen, null when unknown) and the user apps visible on screen.
 */
class UsageForegroundMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val onChange: (foreground: String?, visible: List<String>) -> Unit,
) {
    data class Snapshot(val foreground: String?, val visible: List<String>)

    private val app = context.applicationContext
    private val usm = app.getSystemService(UsageStatsManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    private val tracker = UsageForegroundTracker(::isUserApp)

    private val lock = Any()
    private var queriedUntilMs = 0L
    /** Events already applied from the overlap window (late-logged events are re-queried). */
    private var recentEvents = HashSet<String>()
    private var last: Snapshot? = null
    private var loop: Job? = null
    @Volatile private var lastAccessWarningMs = 0L

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                if (power?.isInteractive == false) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                poll(forceEmit = false)
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    /**
     * Reads new events now and returns the current state, or null when Usage access is missing.
     * Safe from any thread.
     */
    fun refresh(): Snapshot? = synchronized(lock) {
        if (!readNewEvents()) return null
        Snapshot(tracker.foreground(), tracker.visiblePackages())
    }

    /** Re-reports the current app even if unchanged (e.g. after unlock or a service restart). */
    suspend fun resend() = poll(forceEmit = true)

    private suspend fun poll(forceEmit: Boolean) {
        val snapshot = refresh() ?: return
        val changed = synchronized(lock) {
            val differs = snapshot != last
            last = snapshot
            differs
        }
        if (changed || forceEmit) {
            withContext(Dispatchers.Main) { onChange(snapshot.foreground, snapshot.visible) }
        }
    }

    /** False when Usage access is not granted (the system then returns no events). */
    private fun readNewEvents(): Boolean {
        val usm = usm ?: return false
        if (!com.grayzone.app.hasUsageAccess(app)) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAccessWarningMs > 60_000L) {
                lastAccessWarningMs = now
                GrayzoneLogger.w(LogComponent.OVERLAY, "Usage access is off; can't tell which app is in front")
            }
            return false
        }
        val now = System.currentTimeMillis()
        // First read: rebuild the current state from recent history. Later reads overlap the
        // previous window so an event logged a moment late is still seen (and applied once).
        val begin = if (queriedUntilMs == 0L || queriedUntilMs > now) now - HISTORY_MS else queriedUntilMs - OVERLAP_MS
        val events = try {
            usm.queryEvents(begin, now)
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.OVERLAY, "Reading usage events failed", e)
            return true
        }
        val seenNow = HashSet<String>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            if (type != UsageForegroundTracker.ACTIVITY_RESUMED && type != UsageForegroundTracker.ACTIVITY_PAUSED &&
                type != UsageForegroundTracker.ACTIVITY_STOPPED && type != UsageForegroundTracker.DEVICE_SHUTDOWN &&
                type != UsageForegroundTracker.DEVICE_STARTUP
            ) continue
            val pkg = event.packageName ?: continue
            val signature = "${event.timeStamp}|$type|$pkg|${event.className}"
            if (event.timeStamp >= now - OVERLAP_MS) seenNow += signature
            if (signature in recentEvents) continue
            tracker.onEvent(type, pkg, event.className, event.timeStamp)
        }
        recentEvents = seenNow
        queriedUntilMs = now
        return true
    }

    // ── What counts as an app the user opens ─────────────────────────────────

    private val launchableCache = HashMap<String, Boolean>()
    private var launchableCacheResetElapsed = 0L
    private var homePackage: String? = null
    private var homeResolvedAtElapsed = 0L

    /**
     * True for apps a user opens (launcher icon) and for the home screen. System screens OEMs show
     * as activities (control centre, fingerprint UI, permission dialogs) have no launcher entry.
     */
    private fun isUserApp(pkg: String): Boolean {
        if (pkg == app.packageName) return true
        val now = SystemClock.elapsedRealtime()
        if (homePackage == null || now - homeResolvedAtElapsed > 60_000L) {
            homePackage = try {
                app.packageManager.resolveActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY
                )?.activityInfo?.packageName
            } catch (_: Exception) {
                null
            }
            homeResolvedAtElapsed = now
        }
        if (pkg == homePackage) return true
        // Entries expire so apps that toggle their launcher alias at runtime are re-checked.
        if (now - launchableCacheResetElapsed > LAUNCHABLE_CACHE_TTL_MS) {
            launchableCache.clear()
            launchableCacheResetElapsed = now
        }
        launchableCache[pkg]?.let { return it }
        return try {
            (app.packageManager.getLaunchIntentForPackage(pkg) != null).also { launchableCache[pkg] = it }
        } catch (e: Exception) {
            // Unknown: treat as a user app this time, but don't remember the guess.
            true
        }
    }

    private companion object {
        const val POLL_MS = 500L
        const val IDLE_POLL_MS = 2_000L
        const val HISTORY_MS = 6 * 60 * 60 * 1000L
        const val OVERLAP_MS = 3_000L
        const val LAUNCHABLE_CACHE_TTL_MS = 10 * 60 * 1000L
    }
}

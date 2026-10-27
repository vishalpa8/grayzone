package com.grayzone.app.service.vpn.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent
import com.grayzone.app.PrefsKeys
import com.grayzone.app.R
import com.grayzone.app.service.vpn.BlocklistManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Keeps the VPN's lists current with no user action: checks the published set and installs a
 * newer verified one, then hot-swaps it into the running VPN. Does nothing while the VPN is off.
 */
class BlocklistUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PrefsKeys.VPN_ENABLED, false)) return@withContext Result.success()

        val store = BlocklistUpdates.store(context)
        val maxAgeMs = inputData.getLong(KEY_MAX_AGE_MS, DAILY_MAX_AGE_MS)
        val lastCheck = prefs.getLong(PrefsKeys.BLOCKLIST_LAST_CHECK_MS, 0L)
        val freshness = maxOf(lastCheck, store.activeManifest()?.builtAtMs ?: 0L)
        if (System.currentTimeMillis() - freshness < maxAgeMs) return@withContext Result.success()

        when (val outcome = BlocklistUpdater(store).run()) {
            is BlocklistUpdater.Outcome.Updated -> {
                prefs.edit().putLong(PrefsKeys.BLOCKLIST_LAST_CHECK_MS, System.currentTimeMillis()).apply()
                GrayzoneLogger.i(
                    LogComponent.VPN,
                    "Blocklists updated to ${outcome.version} (${outcome.downloaded} downloaded, ${outcome.reused} unchanged)"
                )
                BlocklistManager.reloadLists(context)
                Result.success()
            }
            is BlocklistUpdater.Outcome.UpToDate -> {
                prefs.edit().putLong(PrefsKeys.BLOCKLIST_LAST_CHECK_MS, System.currentTimeMillis()).apply()
                GrayzoneLogger.d(LogComponent.VPN, "Blocklists up to date (${outcome.version})")
                Result.success()
            }
            is BlocklistUpdater.Outcome.Failed -> {
                // Usually just no connectivity; WorkManager backs off and the next period tries again.
                GrayzoneLogger.d(LogComponent.VPN, "Blocklist update check failed: ${outcome.reason}")
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            }
            is BlocklistUpdater.Outcome.Rejected -> {
                GrayzoneLogger.w(LogComponent.VPN, "Blocklist update rejected, keeping the current lists: ${outcome.reason}")
                Result.success()
            }
        }
    }

    companion object {
        internal const val KEY_MAX_AGE_MS = "max_age_ms"
        private const val MAX_RETRIES = 3
        private val DAILY_MAX_AGE_MS = TimeUnit.HOURS.toMillis(20)
        private val STALE_MAX_AGE_MS = TimeUnit.DAYS.toMillis(6)
    }

    /** Scheduling. Idempotent, so it is safe to call on every app start. */
    object Scheduler {
        private const val DAILY = "blocklist_update_daily"
        private const val STALE = "blocklist_update_stale"
        private const val ON_VPN_START = "blocklist_update_vpn_start"

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            // Daily on Wi-Fi / unmetered networks: a full set is ~12 MB.
            wm.enqueueUniquePeriodicWork(
                DAILY, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints(NetworkType.UNMETERED))
                    .setInputData(workDataOf(KEY_MAX_AGE_MS to DAILY_MAX_AGE_MS))
                    .build()
            )
            // Users who are never on Wi-Fi still get updates once their lists are ~a week old.
            wm.enqueueUniquePeriodicWork(
                STALE, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints(NetworkType.CONNECTED))
                    .setInputData(workDataOf(KEY_MAX_AGE_MS to STALE_MAX_AGE_MS))
                    .build()
            )
        }

        /** The VPN just started: catch up right away if the lists are stale. */
        fun onVpnStarted(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ON_VPN_START, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BlocklistUpdateWorker>()
                    .setConstraints(constraints(NetworkType.CONNECTED))
                    .setInputData(workDataOf(KEY_MAX_AGE_MS to STALE_MAX_AGE_MS))
                    .build()
            )
        }

        private fun constraints(network: NetworkType) = Constraints.Builder()
            .setRequiredNetworkType(network)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
    }
}

/** Android wiring for [BlocklistStore]. */
object BlocklistUpdates {

    private val RAW = mapOf(
        "adblock_bloom.bin" to R.raw.adblock_bloom,
        "adult_bloom.bin" to R.raw.adult_bloom,
        "security_bloom.bin" to R.raw.security_bloom,
        "abused_tlds.txt" to R.raw.abused_tlds,
        "doh_domains.txt" to R.raw.doh_domains,
        "telemetry_domains.txt" to R.raw.telemetry_domains,
        "critical_domains.txt" to R.raw.critical_domains,
    )

    fun store(context: Context): BlocklistStore {
        val app = context.applicationContext
        return BlocklistStore(
            root = File(app.filesDir, "blocklists"),
            bundled = object : BlocklistStore.Bundled {
                override fun manifestText(): String? = try {
                    app.resources.openRawResource(R.raw.blocklist_manifest).bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    GrayzoneLogger.e(LogComponent.VPN, "Built-in blocklist manifest unreadable", e)
                    null
                }

                override fun open(name: String) = app.resources.openRawResource(RAW.getValue(name))
            }
        )
    }
}

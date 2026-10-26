package com.grayzone.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(val packageName: String, val name: String, val icon: Bitmap?)

private const val APP_ICON_SIZE_PX = 40

/**
 * App metadata cache with automatic refresh.
 * Prevents N+1 query pattern when loading app lists.
 */
private object AppMetadataCache {
    private var cachedApps: List<AppInfo>? = null
    private var cacheTimestamp = 0L
    private const val CACHE_VALIDITY_MS = 60_000L  // 1 minute
    
    fun get(): List<AppInfo>? {
        val now = System.currentTimeMillis()
        return if (cachedApps != null && (now - cacheTimestamp) < CACHE_VALIDITY_MS) {
            cachedApps
        } else {
            null
        }
    }

    /** Last-loaded list ignoring expiry — for instant UI paint on screen re-entry. */
    fun peek(): List<AppInfo>? = cachedApps

    fun set(apps: List<AppInfo>) {
        cachedApps = apps
        cacheTimestamp = System.currentTimeMillis()
    }
    
    fun invalidate() {
        cachedApps = null
        cacheTimestamp = 0L
    }
}

/**
 * Get installed apps with metadata cache (1-minute validity).
 * Cache is invalidated automatically after 1 minute to detect new installs.
 */
suspend fun getInstalledAppsCached(context: Context): List<AppInfo> {
    val cached = AppMetadataCache.get()
    if (cached != null) return cached
    
    return getInstalledApps(context).also { 
        AppMetadataCache.set(it)
    }
}

/**
 * Synchronous, non-blocking peek at the last-loaded app list (ignores the
 * 1-minute expiry). Lets screens paint instantly on re-entry instead of
 * flashing an empty list / spinner while the async load re-runs.
 */
fun peekCachedApps(): List<AppInfo>? = AppMetadataCache.peek()

suspend fun getInstalledApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    pm.queryIntentActivities(intent, 0).mapNotNull { info ->
        val pkg = info.activityInfo.packageName
        if (pkg == context.packageName) return@mapNotNull null
        val appName = info.loadLabel(pm).toString()
        val icon = try {
            drawableToBitmap(info.loadIcon(pm), APP_ICON_SIZE_PX)
        } catch (_: Exception) {
            null
        }
        AppInfo(pkg, appName, icon)
    }.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
}

fun drawableToBitmap(drawable: Drawable, maxSize: Int = APP_ICON_SIZE_PX): Bitmap? {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        val src = drawable.bitmap
        val scaled = if (src.width <= maxSize && src.height <= maxSize) src else Bitmap.createScaledBitmap(src, maxSize, maxSize, true)
        return if (scaled.config == Bitmap.Config.RGB_565) scaled else scaled.copy(Bitmap.Config.RGB_565, false)
    }
    val w = if (drawable.intrinsicWidth > 0) minOf(drawable.intrinsicWidth, maxSize) else maxSize
    val h = if (drawable.intrinsicHeight > 0) minOf(drawable.intrinsicHeight, maxSize) else maxSize
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
    val c = Canvas(bmp)
    drawable.setBounds(0, 0, c.width, c.height)
    drawable.draw(c)
    return bmp
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    return try {
        val enabledServices = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        AccessibilityServiceIds.isServiceEnabled(
            enabledServicesSetting = enabledServices,
            packageName = context.packageName,
            serviceClassName = com.grayzone.app.service.GrayzoneAccessibilityService::class.java.name
        )
    } catch (e: Exception) {
        GrayzoneLogger.w(
            LogComponent.ACCESSIBILITY,
            "Failed to read accessibility enabled state: ${e.message}"
        )
        false
    }
}

/** Settings page that grants [requirement], so a "fix this" affordance never lands on the wrong screen. */
fun settingsIntentFor(context: Context, requirement: SetupRequirement): Intent = when (requirement) {
    SetupRequirement.ACCESSIBILITY -> {
        // The extras make most Settings apps scroll to and highlight Grayzone in the list.
        val service = android.content.ComponentName(
            context, com.grayzone.app.service.GrayzoneAccessibilityService::class.java
        ).flattenToString()
        Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", service)
            putExtra(
                ":settings:show_fragment_args",
                android.os.Bundle().apply { putString(":settings:fragment_args_key", service) }
            )
        }
    }
    SetupRequirement.OVERLAY ->
        Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}")
        )
    SetupRequirement.NOTIFICATIONS ->
        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            // Older OEM skins read these legacy extras instead.
            putExtra("app_package", context.packageName)
            putExtra("app_uid", context.applicationInfo.uid)
        }
    SetupRequirement.BATTERY ->
        Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:${context.packageName}")
        )
}

/** Starts the monitoring service only when Monitoring is on and the core permissions are granted. */
fun startMonitoringIfReady(context: Context) {
    if (!isMonitoringEnabled(context)) return
    if (!isAccessibilityServiceEnabled(context) || !android.provider.Settings.canDrawOverlays(context)) return
    try {
        context.startForegroundService(Intent(context, com.grayzone.app.service.OverlayService::class.java))
    } catch (e: Exception) {
        GrayzoneLogger.w(LogComponent.OVERLAY, "Could not start monitoring: ${e.message}")
    }
}

/** The master "Monitoring" switch. Off by default: nothing runs until the user turns it on. */
fun isMonitoringEnabled(context: Context): Boolean =
    context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PrefsKeys.GRAYZONE_ENABLED, false)

/**
 * Opens the exact system page for [requirement] (e.g. Grayzone's own Accessibility page, not the
 * generic list). Falls back to a broader page, then App info, on phones that lack the exact one.
 */
fun openSettingsFor(context: Context, requirement: SetupRequirement, logTag: String): Boolean {
    val candidates = buildList {
        if (requirement == SetupRequirement.ACCESSIBILITY && Build.VERSION.SDK_INT >= 34) {
            // Android 14+: the details page of this one service (toggle + description).
            add(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                    putExtra(
                        Intent.EXTRA_COMPONENT_NAME,
                        android.content.ComponentName(
                            context, com.grayzone.app.service.GrayzoneAccessibilityService::class.java
                        ).flattenToString()
                    )
                }
            )
        }
        add(settingsIntentFor(context, requirement))
        if (requirement == SetupRequirement.BATTERY) {
            add(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        add(appInfoSettingsIntent(context))
    }
    return candidates.any { startActivitySafely(context, it, logTag, showToast = false) }
}

fun isBatteryOptimized(context: Context): Boolean =
    !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/**
 * True when the app can post notifications. On API 33+ both the runtime permission and the
 * user-level notification master switch must be on (F-086).
 */
fun areAppNotificationsEnabled(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    return true
}

/** App-info screen used for the Android 13+ "Allow restricted settings" escape hatch (F-092). */
fun appInfoSettingsIntent(context: Context): Intent =
    Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.parse("package:${context.packageName}")
    )

/**
 * F-211: settings / deep-link starts must not crash the UI when the OEM lacks the activity.
 * Logs at WARN and optionally toasts so the user sees a failure instead of a silent no-op.
 */
fun startActivitySafely(
    context: Context,
    intent: Intent,
    logTag: String,
    showToast: Boolean = true,
): Boolean =
    try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        GrayzoneLogger.w(LogComponent.BOOT, "$logTag failed: ${e.message}", e)
        if (showToast) {
            Toast.makeText(context, "Couldn't open settings", Toast.LENGTH_SHORT).show()
        }
        false
    }


package com.grayzone.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.PowerManager
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

/**
 * Force refresh of app cache (call when user manually refreshes).
 */
suspend fun refreshInstalledApps(context: Context): List<AppInfo> {
    AppMetadataCache.invalidate()
    return getInstalledAppsCached(context)
}

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
    val enabledServices = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(context.packageName + "/" + com.grayzone.app.service.GrayzoneAccessibilityService::class.java.name)
}

fun isBatteryOptimized(context: Context): Boolean =
    !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/**
 * Returns true when Android's "Private DNS" (DoT on port 853) is active.
 *
 * Private DNS routes DNS queries directly over TLS to a third-party resolver
 * on port 853, completely bypassing our VPN's DNS-interception mechanism.
 * The user must be warned so they can disable it or understand the limitation.
 *
 * Mode values (android.provider.Settings.Global.PRIVATE_DNS_MODE):
 *   "off"        – feature disabled (safe for us)
 *   "opportunistic" – uses system DoT if available (may bypass us)
 *   "hostname"   – strict mode pointed at a custom resolver (definitely bypasses us)
 */
fun isPrivateDnsActive(context: Context): Boolean {
    return try {
        val mode = android.provider.Settings.Global.getString(
            context.contentResolver,
            "private_dns_mode"          // Settings.Global.PRIVATE_DNS_MODE (API 29+)
        )
        // "off" or null means disabled; anything else means it's on
        mode != null && mode != "off"
    } catch (_: Exception) {
        false
    }
}

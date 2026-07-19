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

private var cachedInstalledApps: List<AppInfo>? = null

suspend fun getInstalledAppsCached(context: Context): List<AppInfo> {
    if (cachedInstalledApps != null) return cachedInstalledApps!!
    return getInstalledApps(context).also { cachedInstalledApps = it }
}

suspend fun getInstalledApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    pm.queryIntentActivities(intent, 0).mapNotNull { info ->
        val pkg = info.activityInfo.packageName
        if (pkg == context.packageName) return@mapNotNull null
        AppInfo(pkg, info.loadLabel(pm).toString(), drawableToBitmap(info.loadIcon(pm)))
    }.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
}

fun drawableToBitmap(drawable: Drawable): Bitmap? {
    val maxSize = 96
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        val src = drawable.bitmap
        return if (src.width <= maxSize && src.height <= maxSize) src
        else Bitmap.createScaledBitmap(src, maxSize, maxSize, true)
    }
    val w = if (drawable.intrinsicWidth > 0) minOf(drawable.intrinsicWidth, maxSize) else maxSize
    val h = if (drawable.intrinsicHeight > 0) minOf(drawable.intrinsicHeight, maxSize) else maxSize
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    drawable.setBounds(0, 0, c.width, c.height)
    drawable.draw(c)
    return bmp
}

fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
    // checkOpNoThrow(String, Int, String) is deprecated at API 29+.
    // unsafeCheckOpNoThrow is the direct replacement for a non-attribution check.
    val mode = appOps.unsafeCheckOpNoThrow(
        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )
    return mode == android.app.AppOpsManager.MODE_ALLOWED
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

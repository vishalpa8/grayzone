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

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
    return enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}

fun isBatteryOptimized(context: Context): Boolean =
    !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

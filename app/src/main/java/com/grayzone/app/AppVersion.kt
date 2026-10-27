package com.grayzone.app

import android.content.Context
import com.grayzone.app.service.vpn.update.BlocklistUpdates
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Human-readable version details for Settings, the startup log and exported logs. */
object AppVersion {

    /** "1.1.0 (build 10100, 43d3bbd1)" */
    val summary: String
        get() = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_COMMIT})"

    /** "set 202610270634, built-in" / "... downloaded"; reads files, so call off the main thread. */
    fun blocklists(context: Context): String = try {
        val store = BlocklistUpdates.store(context)
        val manifest = store.activeManifest()
        if (manifest == null) {
            "unavailable"
        } else {
            val built = manifest.builtAtMs.takeIf { it > 0 }
                ?.let { SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date(it)) }
            "set ${manifest.version}${built?.let { " ($it)" } ?: ""}, ${if (store.usesDownloadedSet()) "downloaded" else "built-in"}"
        }
    } catch (e: Exception) {
        "unreadable (${e.javaClass.simpleName})"
    }

    /** Short date of the active blocklist set for compact UI, or null. */
    fun blocklistDate(context: Context): String? = try {
        BlocklistUpdates.store(context).activeManifest()?.builtAtMs?.takeIf { it > 0 }
            ?.let { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(it)) }
    } catch (e: Exception) {
        null
    }
}

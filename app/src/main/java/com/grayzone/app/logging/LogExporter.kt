package com.grayzone.app.logging

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.grayzone.app.BuildConfig
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent
import com.grayzone.app.service.GrayzoneAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bundles every process's log file into one text file under cache/logs and opens the share sheet.
 * Runs in a process-wide scope so leaving the Settings screen mid-export still shows the chooser.
 */
object LogExporter {

    private val PROCESSES = listOf("main", "a11y")
    private const val KEEP_EXPORTS = 3
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    /**
     * Exports and shares; [onDone] runs on the main thread when the chooser is up or it failed.
     * The chooser is started from [context]'s activity while it is still visible; if the user has
     * left, Android may refuse a background activity start, so they're told to try again instead.
     */
    fun exportAndShare(context: Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        val activityRef = java.lang.ref.WeakReference(context as? androidx.activity.ComponentActivity)
        scope.launch {
            // The accessibility process buffers its own lines; ask it to write them out first.
            try {
                app.sendBroadcast(Intent(GrayzoneAccessibilityService.ACTION_FLUSH_LOGS).setPackage(app.packageName))
                kotlinx.coroutines.delay(300L)
            } catch (e: Exception) {
                GrayzoneLogger.w(LogComponent.UI, "Could not ask the accessibility process to flush logs", e)
            }
            val intent = buildShareIntent(app)
            onDone()
            if (intent == null) {
                Toast.makeText(app, "Couldn't export logs", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val activity = activityRef.get()
            if (activity == null ||
                !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            ) {
                Toast.makeText(app, "Logs are ready. Open Export logs again to share them.", Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                activity.startActivity(Intent.createChooser(intent, "Share Grayzone logs"))
            } catch (e: Exception) {
                GrayzoneLogger.w(LogComponent.UI, "No app available to share logs", e)
                Toast.makeText(app, "No app available to share logs", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun buildShareIntent(context: Context): Intent? = withContext(Dispatchers.IO) {
        try {
            GrayzoneLogger.flush()
            val outDir = File(context.cacheDir, "logs").apply { mkdirs() }
            // A fresh name per export, keeping the last few so a share target that hasn't opened
            // an earlier one yet can still read it.
            outDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_EXPORTS - 1)?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val out = File(outDir, "grayzone-logs-$stamp.txt")
            out.bufferedWriter().use { writer ->
                writeHeader(context, writer)
                writeBundle(GrayzoneLogger.logDirectory(context), writer)
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", out)
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Grayzone logs")
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, out.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            GrayzoneLogger.e(LogComponent.UI, "Exporting logs failed", e)
            null
        }
    }

    private fun writeHeader(context: Context, writer: Writer) {
        writer.appendLine("Grayzone ${com.grayzone.app.AppVersion.summary}, ${BuildConfig.BUILD_TYPE}")
        writer.appendLine("Blocklists: ${com.grayzone.app.AppVersion.blocklists(context)}")
        writer.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        writer.appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
    }

    /**
     * Each process's log, rotated backup first so lines read oldest to newest. A file that can't be
     * read (e.g. rotated by the other process mid-export) is noted instead of failing the export.
     */
    internal fun writeBundle(logDir: File, writer: Writer) {
        for (name in PROCESSES) {
            writer.appendLine()
            writer.appendLine("===== $name process =====")
            var any = false
            for (file in listOf(File(logDir, "$name.log.1"), File(logDir, "$name.log"))) {
                if (!file.exists()) continue
                any = true
                try {
                    file.bufferedReader().useLines { lines -> lines.forEach { writer.appendLine(it) } }
                } catch (e: Exception) {
                    writer.appendLine("(${file.name} unreadable: ${e.javaClass.simpleName})")
                }
            }
            if (!any) writer.appendLine("(no entries)")
        }
    }
}

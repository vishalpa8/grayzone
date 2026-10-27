package com.grayzone.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.grayzone.app.BuildConfig
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent
import com.grayzone.app.service.vpn.update.BlocklistUpdater
import com.grayzone.app.service.vpn.update.HttpFetcher
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Self-update for the GitHub distribution: finds the newest release, downloads its APK, checks it,
 * and hands it to the system installer. Android itself refuses an update signed with a different
 * key, so a tampered APK can never replace the app; the checks here just fail early and clearly.
 */
class AppUpdater(
    private val context: Context,
    private val fetcher: BlocklistUpdater.Fetcher = HttpFetcher(),
) {
    sealed interface CheckResult {
        data class Available(val info: AppUpdateInfo) : CheckResult
        data object UpToDate : CheckResult
        data class Failed(val reason: String) : CheckResult
    }

    /** Network call; run off the main thread. */
    fun check(): CheckResult = try {
        val text = fetcher.open(AppUpdateConfig.LATEST_INFO_URL)?.use { input ->
            val bytes = input.readNBytesCompat(64 * 1024)
            String(bytes, Charsets.UTF_8)
        }
        if (text == null) {
            CheckResult.Failed("No release published yet")
        } else {
            val info = AppUpdateInfo.parse(text)
            when {
                info == null -> CheckResult.Failed("The release information is malformed")
                info.isNewerThan(BuildConfig.VERSION_CODE) -> CheckResult.Available(info)
                else -> CheckResult.UpToDate
            }
        }
    } catch (e: IOException) {
        CheckResult.Failed(friendly(e))
    }

    /**
     * Downloads [info]'s APK into the app cache, verifying size and SHA-256 as it streams, then
     * checks it is this app, newer, and signed like the installed one. Network call; off main thread.
     */
    fun download(info: AppUpdateInfo, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, info.apkName)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val stream = fetcher.open(info.apkUrl) ?: throw IOException("The update file is missing from the release")
        stream.use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var lastReported = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > info.size) throw IOException("The download is larger than expected")
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    val pct = (total * 100 / info.size).toInt()
                    if (pct != lastReported) {
                        lastReported = pct
                        onProgress(pct / 100f)
                    }
                }
            }
        }
        if (total != info.size) throw IOException("The download was incomplete")
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != info.sha256) throw IOException("The download is corrupted (checksum mismatch)")
        verifyApk(target, info)
        return target
    }

    private fun verifyApk(file: File, info: AppUpdateInfo) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val archive = pm.getPackageArchiveInfo(file.path, flags)
            ?: throw IOException("The downloaded file is not a valid app")
        if (archive.packageName != context.packageName) throw IOException("The download is not Grayzone")
        if (archive.longVersionCode != info.versionCode.toLong()) throw IOException("The download's version doesn't match the release")
        if (archive.longVersionCode <= BuildConfig.VERSION_CODE) throw IOException("The download is not newer than this version")
        // Some Android versions don't report signers for archives; the installer still enforces them.
        val downloaded = signers(archive) ?: return
        val installed = signers(pm.getPackageInfo(context.packageName, flags)) ?: return
        if (downloaded != installed) throw IOException("The update is signed by a different key")
    }

    private fun signers(info: PackageInfo): Set<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val signing = info.signingInfo ?: return null
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        if (certs.isNullOrEmpty()) return null
        return certs.map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /** Whether Android lets this app open the installer ("Install unknown apps" for Grayzone). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Opens the system installer for a verified [apk]. */
    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun friendly(e: IOException): String {
        GrayzoneLogger.w(LogComponent.UI, "App update check failed: ${e.javaClass.simpleName}: ${e.message}")
        return when (e) {
            is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
                "Couldn't reach GitHub. Check your connection"
            else -> e.message ?: "Update check failed"
        }
    }

    companion object {
        private const val UPDATES_DIR = "updates"
    }
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > max) throw IOException("Release information is unexpectedly large")
    }
    return out.toByteArray()
}

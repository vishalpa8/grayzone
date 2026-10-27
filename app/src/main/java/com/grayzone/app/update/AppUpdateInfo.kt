package com.grayzone.app.update

/**
 * The newest published app release, read from the `update.txt` asset that
 * .github/workflows/release.yml attaches to every GitHub Release:
 *
 *     format=1
 *     versionCode=10200
 *     versionName=1.2.0
 *     apk=grayzone-1.2.0.apk
 *     sha256=<hex>
 *     size=<bytes>
 *     notes:
 *     <release notes, any number of lines>
 */
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkName: String,
    val sha256: String,
    val size: Long,
    val notes: String,
) {
    val apkUrl: String get() = "${AppUpdateConfig.RELEASES_URL}/download/v$versionName/$apkName"

    fun isNewerThan(installedVersionCode: Int): Boolean = versionCode > installedVersionCode

    companion object {
        private const val FORMAT = 1
        private const val MAX_APK_BYTES = 200L * 1024 * 1024
        private val VERSION_NAME = Regex("^\\d{1,4}\\.\\d{1,2}\\.\\d{1,2}$")
        private val APK_NAME = Regex("^[A-Za-z0-9._-]{1,100}\\.apk$")
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        /** Null unless every field is present and sane, and the code matches the name. */
        fun parse(text: String): AppUpdateInfo? {
            val fields = HashMap<String, String>()
            val notes = StringBuilder()
            var inNotes = false
            for (raw in text.lineSequence()) {
                if (inNotes) {
                    notes.appendLine(raw.trimEnd())
                    continue
                }
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line == "notes:") {
                    inNotes = true
                    continue
                }
                val eq = line.indexOf('=')
                if (eq <= 0) return null
                fields[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            }
            if (fields["format"]?.toIntOrNull() != FORMAT) return null
            val name = fields["versionName"]?.takeIf { VERSION_NAME.matches(it) } ?: return null
            val code = fields["versionCode"]?.toIntOrNull() ?: return null
            val apk = fields["apk"]?.takeIf { APK_NAME.matches(it) } ?: return null
            val sha = fields["sha256"]?.lowercase()?.takeIf { SHA256_HEX.matches(it) } ?: return null
            val size = fields["size"]?.toLongOrNull()?.takeIf { it in 1..MAX_APK_BYTES } ?: return null
            if (code != codeFor(name)) return null
            return AppUpdateInfo(code, name, apk, sha, size, notes.toString().trim())
        }

        /** Mirrors app/build.gradle.kts: MAJOR * 10000 + MINOR * 100 + PATCH. */
        fun codeFor(versionName: String): Int {
            val (major, minor, patch) = versionName.split('.').map { it.toInt() }
            return major * 10000 + minor * 100 + patch
        }
    }
}

object AppUpdateConfig {
    const val RELEASES_URL = "https://github.com/vishalpa8/grayzone/releases"

    /** GitHub serves the newest non-prerelease release here (blocklist sets are prereleases). */
    const val LATEST_INFO_URL = "$RELEASES_URL/latest/download/update.txt"
}

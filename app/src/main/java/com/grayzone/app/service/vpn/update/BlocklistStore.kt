package com.grayzone.app.service.vpn.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where the VPN's lists come from: the copy built into the APK, or a newer verified set
 * downloaded into [root]. Each downloaded set is a complete directory `v<version>/`, and the
 * `current` file names the active one; switching sets is a single atomic rename, so a crash or a
 * full disk mid-update can never leave a half-written set in use.
 */
class BlocklistStore(
    private val root: File,
    private val bundled: Bundled,
    private val files: List<String> = BlocklistUpdateConfig.FILES,
) {
    /** The lists shipped inside the APK. */
    interface Bundled {
        fun manifestText(): String?
        fun open(name: String): InputStream
    }

    val bundledManifest: BlocklistManifest? by lazy {
        bundled.manifestText()?.let { BlocklistManifest.parse(it, files) }
    }

    /** The installed downloaded set, if complete; null otherwise. */
    fun installedManifest(): BlocklistManifest? {
        val version = readCurrentVersion() ?: return null
        val dir = versionDir(version)
        val manifest = File(dir, MANIFEST).takeIf { it.isFile }
            ?.let { BlocklistManifest.parse(it.readText(), files) } ?: return null
        if (manifest.version != version) return null
        if (files.any { !File(dir, it).isFile }) return null
        return manifest
    }

    /** The newer of the downloaded and bundled sets; an app update can ship newer lists than a download. */
    fun activeManifest(): BlocklistManifest? {
        val installed = installedManifest()
        val shipped = bundledManifest
        return when {
            installed == null -> shipped
            shipped == null -> installed
            installed.version > shipped.version -> installed
            else -> shipped
        }
    }

    /** True when the active set is the downloaded one. */
    fun usesDownloadedSet(): Boolean {
        val installed = installedManifest() ?: return false
        return installed.version > (bundledManifest?.version ?: 0L)
    }

    fun open(name: String): InputStream {
        require(name in files) { "Unknown blocklist file $name" }
        if (usesDownloadedSet()) {
            val version = readCurrentVersion()
            if (version != null) {
                val file = File(versionDir(version), name)
                if (file.isFile) return file.inputStream()
            }
        }
        return bundled.open(name)
    }

    /** The file of the active set to copy when it is unchanged in a new set, or null. */
    internal fun reusableSource(name: String, sha256: String): (() -> InputStream)? {
        val installed = installedManifest()
        if (installed?.files?.get(name)?.sha256 == sha256) {
            val file = File(versionDir(installed.version), name)
            return { file.inputStream() }
        }
        if (bundledManifest?.files?.get(name)?.sha256 == sha256) return { bundled.open(name) }
        return null
    }

    /** Fresh, empty staging directory for building [version]. */
    internal fun stagingDir(version: Long): File {
        val dir = File(root, "staging-$version")
        dir.deleteRecursively()
        check(dir.mkdirs()) { "Could not create $dir" }
        return dir
    }

    /** Activates a fully written and validated staging directory. */
    internal fun install(manifest: BlocklistManifest, staging: File) {
        File(staging, MANIFEST).writeText(manifest.serialize())
        val target = versionDir(manifest.version)
        target.deleteRecursively()
        check(staging.renameTo(target)) { "Could not move $staging to $target" }
        val tmp = File(root, "$CURRENT.tmp")
        tmp.writeText(manifest.version.toString())
        try {
            Files.move(tmp.toPath(), File(root, CURRENT).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw IllegalStateException("Could not switch to set ${manifest.version}: ${e.message}", e)
        }
        pruneExcept(manifest.version)
    }

    /** Drops every downloaded set (used when the installed one fails to load). */
    fun discard() {
        File(root, CURRENT).delete()
        pruneExcept(null)
    }

    private fun pruneExcept(keep: Long?) {
        root.listFiles()?.forEach { f ->
            val isSet = f.isDirectory && (f.name.startsWith("v") || f.name.startsWith("staging-"))
            if (isSet && f.name != keep?.let { "v$it" }) f.deleteRecursively()
        }
    }

    private fun readCurrentVersion(): Long? =
        File(root, CURRENT).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()

    private fun versionDir(version: Long) = File(root, "v$version")

    private companion object {
        const val CURRENT = "current"
        const val MANIFEST = "manifest.txt"
    }
}

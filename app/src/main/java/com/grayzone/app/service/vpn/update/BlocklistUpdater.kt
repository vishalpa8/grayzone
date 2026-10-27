package com.grayzone.app.service.vpn.update

import com.grayzone.app.service.vpn.AbusedTldList
import com.grayzone.app.service.vpn.BlocklistManager
import com.grayzone.app.service.vpn.GrayzoneBloomFilter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches the latest published blocklist set and installs it if, and only if, it is newer,
 * signed by [publicKey], and every file matches its manifest hash and loads cleanly.
 * Anything less leaves the active set untouched.
 */
class BlocklistUpdater(
    private val store: BlocklistStore,
    private val fetcher: Fetcher = HttpFetcher(),
    private val baseUrl: String = BlocklistUpdateConfig.BASE_URL,
    private val publicKey: String = BlocklistUpdateConfig.PUBLIC_KEY,
    private val files: List<String> = BlocklistUpdateConfig.FILES,
) {
    /** Opens [url], or returns null when it doesn't exist. Throws [IOException] on network trouble. */
    fun interface Fetcher {
        fun open(url: String): InputStream?
    }

    sealed interface Outcome {
        data class Updated(val version: Long, val downloaded: Int, val reused: Int) : Outcome
        data class UpToDate(val version: Long) : Outcome
        /** Network trouble; try again later. */
        data class Failed(val reason: String) : Outcome
        /** Something published is wrong (bad signature, hash mismatch, corrupt file); never installed. */
        data class Rejected(val reason: String) : Outcome
    }

    fun run(): Outcome {
        val manifestBytes: ByteArray
        val signature: ByteArray
        try {
            manifestBytes = fetchSmall("$baseUrl/${BlocklistUpdateConfig.MANIFEST_NAME}", 64 * 1024)
                ?: return Outcome.Failed("manifest not published")
            signature = fetchSmall("$baseUrl/${BlocklistUpdateConfig.SIGNATURE_NAME}", 1024)
                ?: return Outcome.Failed("signature not published")
        } catch (e: IOException) {
            return Outcome.Failed("${e.javaClass.simpleName}: ${e.message}")
        }

        // The manifest and its signature are uploaded one after the other, so a mismatch can be a
        // publish in progress; it is retried on the next run and never installed.
        if (!BlocklistManifest.verifySignature(manifestBytes, signature, publicKey)) {
            return Outcome.Rejected("manifest signature does not verify")
        }
        val manifest = BlocklistManifest.parse(String(manifestBytes, Charsets.UTF_8), files)
            ?: return Outcome.Rejected("manifest is malformed")

        // Never go backwards: protects against replaying an old signed manifest.
        val active = store.activeManifest()
        if (active != null && manifest.version <= active.version) return Outcome.UpToDate(active.version)

        val staging = store.stagingDir(manifest.version)
        var downloaded = 0
        var reused = 0
        try {
            for (name in files) {
                val entry = manifest.files.getValue(name)
                val target = File(staging, name)
                val reuse = store.reusableSource(name, entry.sha256)
                val reusedOk = reuse != null && try {
                    reuse().use { copyVerified(it, target, entry) }
                    true
                } catch (e: IOException) {
                    // The local copy doesn't match what its manifest claims; fetch it instead.
                    target.delete()
                    false
                }
                if (reusedOk) {
                    reused++
                } else {
                    val stream = fetcher.open("$baseUrl/${manifest.version}-$name")
                        ?: return Outcome.Failed("$name of set ${manifest.version} not published").also { staging.deleteRecursively() }
                    stream.use { copyVerified(it, target, entry) }
                    downloaded++
                }
                validate(name, target)?.let { problem ->
                    staging.deleteRecursively()
                    return Outcome.Rejected("$name: $problem")
                }
            }
            store.install(manifest, staging)
        } catch (e: HashMismatch) {
            staging.deleteRecursively()
            return Outcome.Rejected(e.message ?: "hash mismatch")
        } catch (e: IOException) {
            staging.deleteRecursively()
            return Outcome.Failed("${e.javaClass.simpleName}: ${e.message}")
        } catch (e: IllegalStateException) {
            staging.deleteRecursively()
            return Outcome.Failed(e.message ?: "install failed")
        }
        return Outcome.Updated(manifest.version, downloaded, reused)
    }

    private class HashMismatch(message: String) : IOException(message)

    private fun fetchSmall(url: String, maxBytes: Int): ByteArray? {
        val stream = fetcher.open(url) ?: return null
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > maxBytes) throw IOException("$url is larger than $maxBytes bytes")
            }
            return out.toByteArray()
        }
    }

    /** Streams [input] to [target], refusing anything longer than, or different from, [entry]. */
    private fun copyVerified(input: InputStream, target: File, entry: BlocklistManifest.Entry) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > entry.size) throw HashMismatch("${entry.name} is larger than its manifest size")
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        if (total != entry.size) throw HashMismatch("${entry.name} is $total bytes, manifest says ${entry.size}")
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != entry.sha256) throw HashMismatch("${entry.name} does not match its manifest hash")
    }

    /** Null when [file] loads as the list it claims to be; otherwise the problem. */
    private fun validate(name: String, file: File): String? = try {
        when {
            name.endsWith(".bin") -> { file.inputStream().use { GrayzoneBloomFilter.readFrom(it) }; null }
            name == "abused_tlds.txt" ->
                file.inputStream().use { AbusedTldList.readFrom(it) }.size.let { if (it < 100) "only $it TLDs" else null }
            else -> {
                val min = MIN_ENTRIES[name] ?: 1
                val count = BlocklistManager.parseDomainList(file.readText()).size
                if (count < min) "only $count domains (expected at least $min)" else null
            }
        }
    } catch (e: Exception) {
        "${e.javaClass.simpleName}: ${e.message}"
    }

    private companion object {
        val MIN_ENTRIES = mapOf(
            "critical_domains.txt" to 20,
            "doh_domains.txt" to 100,
            "telemetry_domains.txt" to 50,
        )
    }
}

/** Plain HTTPS GET following GitHub's release redirects. */
class HttpFetcher : BlocklistUpdater.Fetcher {
    override fun open(url: String): InputStream? {
        var current = URL(url)
        repeat(5) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Grayzone-Android")
            }
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> return conn.inputStream
                HttpURLConnection.HTTP_NOT_FOUND -> { conn.disconnect(); return null }
                in 300..399 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    val next = URL(current, location ?: throw IOException("Redirect without Location from $current"))
                    if (next.protocol != "https") throw IOException("Refusing non-HTTPS redirect to $next")
                    current = next
                }
                else -> { conn.disconnect(); throw IOException("HTTP $code from ${current.host}") }
            }
        }
        throw IOException("Too many redirects for $url")
    }
}

package com.grayzone.app.service.vpn.update

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

/**
 * The list of files in one blocklist set, written by scripts/blocklist_tools.ps1:
 *
 *     format=1
 *     version=202610270520          (UTC yyyyMMddHHmm build time; only ever increases)
 *     file <name> <sha256 hex> <size>
 */
data class BlocklistManifest(val version: Long, val files: Map<String, Entry>) {

    data class Entry(val name: String, val sha256: String, val size: Long)

    /** Build time in epoch millis, or 0 when the version isn't a valid timestamp. */
    val builtAtMs: Long get() = versionToEpochMs(version)

    fun serialize(): String = buildString {
        append("format=").append(FORMAT).append('\n')
        append("version=").append(version).append('\n')
        files.values.sortedBy { it.name }.forEach {
            append("file ").append(it.name).append(' ').append(it.sha256).append(' ').append(it.size).append('\n')
        }
    }

    companion object {
        const val FORMAT = 1
        const val MAX_FILE_BYTES = 32L * 1024 * 1024
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val VERSION = Regex("^\\d{12}$")

        /**
         * Parses a manifest, keeping only [requiredFiles]. Returns null unless the format is known
         * and every required file is listed exactly once with a sane hash and size. Names outside
         * [requiredFiles] are ignored, so newer sets can add files without breaking older apps.
         */
        fun parse(text: String, requiredFiles: Collection<String>): BlocklistManifest? {
            var format: Int? = null
            var version: Long? = null
            val files = HashMap<String, Entry>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                when {
                    line.startsWith("format=") -> format = line.removePrefix("format=").toIntOrNull()
                    line.startsWith("version=") -> {
                        val v = line.removePrefix("version=")
                        if (!VERSION.matches(v)) return null
                        version = v.toLong()
                    }
                    line.startsWith("file ") -> {
                        val parts = line.split(' ').filter { it.isNotEmpty() }
                        if (parts.size != 4) return null
                        val name = parts[1]
                        if (name !in requiredFiles) continue
                        val sha = parts[2]
                        val size = parts[3].toLongOrNull() ?: return null
                        if (!SHA256_HEX.matches(sha) || size <= 0 || size > MAX_FILE_BYTES) return null
                        if (files.put(name, Entry(name, sha, size)) != null) return null
                    }
                }
            }
            if (format != FORMAT) return null
            val v = version ?: return null
            if (!files.keys.containsAll(requiredFiles)) return null
            return BlocklistManifest(v, files)
        }

        /** ECDSA P-256 / SHA-256 check of [signature] (DER, as written by `openssl dgst -sign`). */
        fun verifySignature(manifest: ByteArray, signature: ByteArray, publicKeyBase64: String): Boolean =
            try {
                val key = KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(manifest)
                    verify(signature)
                }
            } catch (e: Exception) {
                false
            }

        fun versionToEpochMs(version: Long): Long =
            try {
                SimpleDateFormat("yyyyMMddHHmm", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }.parse(version.toString())?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
    }
}

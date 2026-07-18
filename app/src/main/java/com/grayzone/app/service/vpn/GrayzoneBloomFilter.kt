package com.grayzone.app.service.vpn

import java.io.InputStream
import java.security.MessageDigest

/**
 * A fast, read-only Bloom filter for domain blocking.
 *
 * Memory usage: ~1.5 MB for 800k domains at 0.1% false-positive rate,
 * compared to ~120 MB for an equivalent HashSet<String>.
 *
 * False-positive rate: ~0.1% — 1 in 1000 legitimate domains may be
 * incorrectly blocked. Acceptable for an ad/content blocker.
 *
 * No false negatives: if a domain IS in the list it WILL be caught.
 *
 * ─────────────────────────────────────────────────────────────
 * Binary file format (written by blocklist_tools.ps1):
 *
 *   Offset  Size  Description
 *   ──────  ────  ───────────────────────────────────────
 *   0       4     Magic "GZBL" = 0x47 0x5A 0x42 0x4C
 *   4       4     Version (int32 big-endian) = 1
 *   8       4     numHashes k (int32 big-endian)
 *   12      8     bitCount  m (int64 big-endian) — number of bits
 *   20      m/8   Bit array (little-endian bit order per byte)
 * ─────────────────────────────────────────────────────────────
 *
 * Hash function: Kirsch-Mitzenmacher double hashing.
 *   digest = SHA-256(domain.lowercase())
 *   h1     = first  4 bytes of digest as unsigned 32-bit big-endian → Long
 *   h2     = next   4 bytes of digest as unsigned 32-bit big-endian → Long (min 1)
 *   bit_i  = (h1 + i * h2) % bitCount   for i in [0, k)
 */
class GrayzoneBloomFilter private constructor(
    private val bits: ByteArray,
    private val bitCount: Long,
    private val numHashes: Int
) {
    /**
     * Returns true if [domain] is POSSIBLY in the set (check caller should
     * also walk parent domains for subdomain matching).
     * Returns false if [domain] is DEFINITELY NOT in the set.
     */
    fun mightContain(domain: String): Boolean {
        val (h1, h2) = baseHashes(domain)
        for (i in 0 until numHashes) {
            val bitIndex = (h1 + i.toLong() * h2) % bitCount
            val byteIdx = (bitIndex / 8).toInt()
            val bitOff  = (bitIndex % 8).toInt()
            if (bits[byteIdx].toInt() and (1 shl bitOff) == 0) return false
        }
        return true
    }

    // ─── private helpers ───────────────────────────────────────────────────

    private fun baseHashes(domain: String): Pair<Long, Long> {
        val digest = SHA256.get()!!.digest(domain.toByteArray(Charsets.UTF_8))
        val h1 = readU32(digest, 0)
        val h2 = readU32(digest, 4).let { if (it == 0L) 1L else it }
        return h1 to h2
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong()     and 0xFF) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 8)  or
         (bytes[offset + 3].toLong() and 0xFF)

    // ─── companion ─────────────────────────────────────────────────────────

    companion object {
        private val MAGIC = byteArrayOf(0x47, 0x5A, 0x42, 0x4C)  // "GZBL"

        /** Thread-local SHA-256 to avoid synchronisation overhead. */
        private val SHA256 = ThreadLocal.withInitial<MessageDigest> {
            MessageDigest.getInstance("SHA-256")
        }

        /**
         * Load a bloom filter from a raw resource stream.
         * Throws [IllegalArgumentException] if the file is malformed.
         */
        fun readFrom(stream: InputStream): GrayzoneBloomFilter {
            val data = stream.readBytes()
            var pos = 0

            // Magic
            require(
                data[pos] == MAGIC[0] && data[pos+1] == MAGIC[1] &&
                data[pos+2] == MAGIC[2] && data[pos+3] == MAGIC[3]
            ) { "Not a Grayzone bloom filter file (bad magic)" }
            pos += 4

            // Version
            val version = readI32(data, pos); pos += 4
            require(version == 1) { "Unsupported bloom filter version: $version" }

            // Parameters
            val numHashes = readI32(data, pos); pos += 4
            val bitCount  = readI64(data, pos); pos += 8

            // Bit array
            val bitArray = data.copyOfRange(pos, data.size)
            require(bitArray.size >= ((bitCount + 7) / 8).toInt()) {
                "Bloom filter bit array too short"
            }

            return GrayzoneBloomFilter(bitArray, bitCount, numHashes)
        }

        private fun readI32(d: ByteArray, p: Int): Int =
            ((d[p].toInt()   and 0xFF) shl 24) or
            ((d[p+1].toInt() and 0xFF) shl 16) or
            ((d[p+2].toInt() and 0xFF) shl 8)  or
             (d[p+3].toInt() and 0xFF)

        private fun readI64(d: ByteArray, p: Int): Long =
            ((d[p].toLong()   and 0xFF) shl 56) or
            ((d[p+1].toLong() and 0xFF) shl 48) or
            ((d[p+2].toLong() and 0xFF) shl 40) or
            ((d[p+3].toLong() and 0xFF) shl 32) or
            ((d[p+4].toLong() and 0xFF) shl 24) or
            ((d[p+5].toLong() and 0xFF) shl 16) or
            ((d[p+6].toLong() and 0xFF) shl 8)  or
             (d[p+7].toLong() and 0xFF)
    }
}

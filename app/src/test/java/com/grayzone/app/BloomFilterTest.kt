package com.grayzone.app

import com.grayzone.app.service.vpn.GrayzoneBloomFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class BloomFilterTest {

    private lateinit var testFilter: GrayzoneBloomFilter

    @Before
    fun setup() {
        testFilter = GrayzoneBloomFilter.readFrom(ByteArrayInputStream(bloomBytes(bitCount = 1024, numHashes = 3)))
    }

    @Test
    fun `bloom filter rejects empty and single-label inputs when bits are clear`() {
        assertFalse(testFilter.mightContain(""))
        assertFalse(testFilter.mightContain("com"))
        assertFalse(testFilter.mightContain("localhost"))
    }

    @Test
    fun `bloom filter handles case insensitivity at caller normalized input`() {
        val result1 = testFilter.mightContain("Example.Com".lowercase())
        val result2 = testFilter.mightContain("example.com")
        assertEquals(result1, result2)
    }

    @Test
    fun `bloom filter handles long and unicode domains without throwing`() {
        assertFalse(testFilter.mightContain("a".repeat(100) + ".example.com"))
        assertFalse(testFilter.mightContain("xn--mnchen-3ya.de"))
    }

    @Test
    fun `loader rejects short header`() {
        assertInvalid(ByteArray(8), "header too short")
    }

    @Test
    fun `loader rejects invalid parameters`() {
        assertInvalid(bloomBytes(version = 2), "Unsupported bloom filter version")
        assertInvalid(bloomBytes(numHashes = 0), "Invalid bloom filter hash count")
        assertInvalid(bloomBytes(numHashes = 33), "Invalid bloom filter hash count")
        assertInvalid(bloomBytes(bitCount = 0), "Invalid bloom filter bit count")
        assertInvalid(bloomBytes(bitCount = 1025), "byte aligned")
    }

    @Test
    fun `loader rejects truncated bit arrays`() {
        assertInvalid(bloomBytes(bitCount = 1024).copyOf(40), "bit array too short")
    }

    @Test
    fun `subdomain matching expectation stays outside raw bloom lookup`() {
        val domain = "ads.example.com"
        assertTrue(domain.contains('.'))
    }

    @Test
    fun `mightContain is true only when all hash bits are set`() {
        val domain = "blocked.example"
        val bitCount = 2048L
        val numHashes = 4
        val bits = ByteArray((bitCount / 8).toInt())
        setBitsForDomain(bits, bitCount, numHashes, domain)

        val filter = GrayzoneBloomFilter.readFrom(
            ByteArrayInputStream(bloomBytes(bitCount = bitCount, numHashes = numHashes, bitArray = bits))
        )
        assertTrue(filter.mightContain(domain))
        assertFalse(filter.mightContain("allowed.example"))
        assertFalse(filter.mightContain(""))
    }

    @Test
    fun `loader accepts trailing bytes after a valid filter`() {
        val valid = bloomBytes(bitCount = 1024, numHashes = 3)
        val withTrailing = valid + byteArrayOf(0x0A)
        val filter = GrayzoneBloomFilter.readFrom(ByteArrayInputStream(withTrailing))
        assertFalse(filter.mightContain("anything.example"))
    }

    @Test
    fun `loader rejects bad magic`() {
        val bytes = bloomBytes()
        bytes[0] = 0x00
        assertInvalid(bytes, "bad magic")
    }

    private fun assertInvalid(bytes: ByteArray, expectedMessage: String) {
        try {
            GrayzoneBloomFilter.readFrom(ByteArrayInputStream(bytes))
        } catch (e: IllegalArgumentException) {
            assertTrue("Expected message to contain '$expectedMessage' but was '${e.message}'", e.message!!.contains(expectedMessage))
            return
        }
        throw AssertionError("Expected invalid bloom filter")
    }

    private fun bloomBytes(
        version: Int = 1,
        numHashes: Int = 3,
        bitCount: Long = 1024,
        bitArray: ByteArray? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            dos.write(byteArrayOf(0x47, 0x5A, 0x42, 0x4C))
            dos.writeInt(version)
            dos.writeInt(numHashes)
            dos.writeLong(bitCount)
            if (bitCount > 0 && bitCount % 8L == 0L && bitCount <= 32L * 1024L * 1024L * 8L) {
                dos.write(bitArray ?: ByteArray((bitCount / 8L).toInt()))
            }
        }
        return out.toByteArray()
    }

    /** Mirror of GrayzoneBloomFilter hashing so tests can plant known members. */
    private fun setBitsForDomain(bits: ByteArray, bitCount: Long, numHashes: Int, domain: String) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(domain.toByteArray(Charsets.UTF_8))
        val h1 = readU32(digest, 0)
        val h2 = readU32(digest, 4).let { if (it == 0L) 1L else it }
        for (i in 0 until numHashes) {
            val bitIndex = (h1 + i.toLong() * h2) % bitCount
            val byteIdx = (bitIndex / 8).toInt()
            val bitOff = (bitIndex % 8).toInt()
            bits[byteIdx] = (bits[byteIdx].toInt() or (1 shl bitOff)).toByte()
        }
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
}
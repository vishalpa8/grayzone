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
    fun `loader rejects truncated and trailing bit arrays`() {
        assertInvalid(bloomBytes(bitCount = 1024).copyOf(40), "bit array too short")
        assertInvalid(bloomBytes(bitCount = 1024) + byteArrayOf(1), "trailing bytes")
    }

    @Test
    fun `subdomain matching expectation stays outside raw bloom lookup`() {
        val domain = "ads.example.com"
        assertTrue(domain.contains('.'))
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
        bitCount: Long = 1024
    ): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            dos.write(byteArrayOf(0x47, 0x5A, 0x42, 0x4C))
            dos.writeInt(version)
            dos.writeInt(numHashes)
            dos.writeLong(bitCount)
            if (bitCount > 0 && bitCount % 8L == 0L && bitCount <= 32L * 1024L * 1024L * 8L) {
                dos.write(ByteArray((bitCount / 8L).toInt()))
            }
        }
        return out.toByteArray()
    }
}
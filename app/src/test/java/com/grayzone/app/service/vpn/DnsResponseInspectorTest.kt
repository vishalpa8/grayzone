package com.grayzone.app.service.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsResponseInspectorTest {

    private fun name(vararg labels: String): ByteArray {
        val out = ByteArrayOutputStream()
        labels.forEach { out.write(it.length); out.write(it.toByteArray()) }
        out.write(0)
        return out.toByteArray()
    }

    private fun header(qd: Int, an: Int) = byteArrayOf(
        0x12, 0x34, 0x81.toByte(), 0x80.toByte(),
        0, qd.toByte(), 0, an.toByte(), 0, 0, 0, 0
    )

    private fun record(owner: ByteArray, type: Int, rdata: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(owner)
        out.write(byteArrayOf(0, type.toByte(), 0, 1, 0, 0, 0x0E, 0x10, (rdata.size shr 8).toByte(), rdata.size.toByte()))
        out.write(rdata)
        return out.toByteArray()
    }

    private fun response(question: ByteArray, vararg answers: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header(1, answers.size))
        out.write(question)
        out.write(byteArrayOf(0, 1, 0, 1))
        answers.forEach { out.write(it) }
        return out.toByteArray()
    }

    private val questionPointer = byteArrayOf(0xC0.toByte(), 12)

    @Test
    fun `reads a plain cname target`() {
        val payload = response(
            name("www", "shop", "com"),
            record(questionPointer, 5, name("evil", "example", "net")),
            record(name("evil", "example", "net"), 1, byteArrayOf(1, 2, 3, 4))
        )
        assertEquals(listOf("evil.example.net"), DnsResponseInspector.cnameTargets(payload))
    }

    @Test
    fun `follows compression pointers inside the cname target`() {
        // Target "cdn" + pointer to "shop.com" inside the question name (offset 12 + 4).
        val target = byteArrayOf(3) + "cdn".toByteArray() + byteArrayOf(0xC0.toByte(), 16)
        val payload = response(name("www", "shop", "com"), record(questionPointer, 5, target))
        assertEquals(listOf("cdn.shop.com"), DnsResponseInspector.cnameTargets(payload))
    }

    @Test
    fun `reads every link of a cname chain lowercased`() {
        val payload = response(
            name("a", "com"),
            record(questionPointer, 5, name("B", "Example", "com")),
            record(name("b", "example", "com"), 5, name("c", "bad", "org"))
        )
        assertEquals(listOf("b.example.com", "c.bad.org"), DnsResponseInspector.cnameTargets(payload))
    }

    @Test
    fun `answers without cnames give nothing`() {
        val payload = response(name("a", "com"), record(questionPointer, 1, byteArrayOf(1, 2, 3, 4)))
        assertTrue(DnsResponseInspector.cnameTargets(payload).isEmpty())
    }

    @Test
    fun `a compression loop does not hang or throw`() {
        // Points back at the header, which decodes as junk labels; it must simply terminate.
        val loop = byteArrayOf(0xC0.toByte(), 0)
        val payload = response(name("a", "com"), record(questionPointer, 5, loop))
        DnsResponseInspector.cnameTargets(payload)
    }

    @Test
    fun `self referencing pointer is rejected`() {
        val payload = response(name("a", "com"), record(questionPointer, 5, byteArrayOf(0xC0.toByte(), 0)))
        // Rewrite the rdata pointer to point at itself.
        val selfOffset = payload.size - 2
        payload[payload.size - 1] = selfOffset.toByte()
        assertTrue(DnsResponseInspector.cnameTargets(payload).isEmpty())
    }

    @Test
    fun `truncated and garbage packets are safe`() {
        val payload = response(
            name("www", "shop", "com"),
            record(questionPointer, 5, name("evil", "example", "net"))
        )
        for (cut in 0 until payload.size) {
            DnsResponseInspector.cnameTargets(payload.copyOf(cut))
        }
        DnsResponseInspector.cnameTargets(ByteArray(0))
        DnsResponseInspector.cnameTargets(ByteArray(600) { (it * 37).toByte() })
        DnsResponseInspector.cnameTargets(ByteArray(64) { 0xFF.toByte() })
    }

    @Test
    fun `rdata length past the end stops parsing`() {
        val payload = response(name("a", "com"), record(questionPointer, 5, name("x", "com")))
        payload[payload.size - name("x", "com").size - 1] = 0x7F // claim a much longer rdata
        assertTrue(DnsResponseInspector.cnameTargets(payload).isEmpty())
    }
}

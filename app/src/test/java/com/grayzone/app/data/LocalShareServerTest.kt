package com.grayzone.app.data

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * End-to-end coverage for [LocalShareServer] over a real localhost socket.
 *
 * Verifies the security hardening applied during regression fixes:
 *   - D6: token-gated /api/ endpoints (403 without / with wrong token) and the
 *         defense-in-depth headers on every response; no external font/CDN link.
 *   - D7: the File Share page ships the escapeHtml() guard.
 * Plus the core clipboard round-trip and file-listing behavior.
 *
 * Runs on the JVM: NanoHTTPD is plain Java sockets, and Context is only needed
 * for on-device file downloads (not exercised here), so a mock suffices.
 */
class LocalShareServerTest {

    private lateinit var server: LocalShareServer
    private val token get() = server.token
    private val base get() = "http://127.0.0.1:${server.listeningPort}"

    @Before
    fun setUp() {
        val context = Mockito.mock(Context::class.java)
        server = LocalShareServer(port = 0, context = context)
        server.start(NANO_TIMEOUT, false)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ── D6: auth gating ──────────────────────────────────────────────────────

    @Test
    fun `api clipboard is forbidden without a token`() {
        val (code, _) = httpGet("$base/api/clipboard")
        assertEquals(403, code)
    }

    @Test
    fun `api clipboard is forbidden with a wrong token`() {
        val (code, _) = httpGet("$base/api/clipboard?t=not-the-real-token")
        assertEquals(403, code)
    }

    @Test
    fun `api clipboard is allowed with the correct token`() {
        val (code, body) = httpGet("$base/api/clipboard?t=$token")
        assertEquals(200, code)
        assertTrue(body.contains("\"text\""))
    }

    // ── Clipboard round-trip ─────────────────────────────────────────────────

    @Test
    fun `posted clipboard text is read back`() {
        val postCode = httpPostJson("$base/api/clipboard?t=$token", "{\"text\":\"hello world\"}")
        assertEquals(200, postCode)

        val (getCode, body) = httpGet("$base/api/clipboard?t=$token")
        assertEquals(200, getCode)
        assertTrue(body.contains("hello world"))
        assertEquals("hello world", server.getClipboardText())
    }

    // ── File listing ─────────────────────────────────────────────────────────

    @Test
    fun `files endpoint lists added files and 404s unknown downloads`() {
        val (emptyCode, emptyBody) = httpGet("$base/api/files?t=$token")
        assertEquals(200, emptyCode)
        assertEquals("[]", emptyBody.trim())

        val uri = Mockito.mock(android.net.Uri::class.java)
        server.addFile(
            SharedFile(name = "report.pdf", uri = uri, size = 1234, mimeType = "application/pdf")
        )
        val (listCode, listBody) = httpGet("$base/api/files?t=$token")
        assertEquals(200, listCode)
        assertTrue(listBody.contains("report.pdf"))

        val (missingCode, _) = httpGet("$base/api/files/does-not-exist.txt?t=$token")
        assertEquals(404, missingCode)
    }

    // ── D6/D7: headers, no external CDN, XSS guard shipped ────────────────────

    @Test
    fun `every response carries defense-in-depth headers`() {
        val conn = (URL("$base/").openConnection() as HttpURLConnection)
        try {
            assertEquals(200, conn.responseCode) // HTML page needs no token
            assertEquals("no-referrer", conn.getHeaderField("Referrer-Policy"))
            assertEquals("nosniff", conn.getHeaderField("X-Content-Type-Options"))
            val csp = conn.getHeaderField("Content-Security-Policy")
            assertTrue(csp != null && csp.contains("default-src 'self'"))
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `pages avoid external resources and ship the xss guard`() {
        val (_, clipboardHtml) = httpGet("$base/")
        assertFalse("no external font/CDN link", clipboardHtml.contains("fonts.googleapis.com"))

        val (_, filesHtml) = httpGet("$base/files")
        assertTrue("filename escaping guard present", filesHtml.contains("function escapeHtml"))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun httpGet(url: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPostJson(url: String, json: String): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 5000
            readTimeout = 5000
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(json) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val NANO_TIMEOUT = 5000
    }
}

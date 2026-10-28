package com.grayzone.app.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.StringWriter
import java.nio.file.Files

class LogExporterTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("gzexport").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `bundles the log with the rotated backup first`() {
        File(dir, "main.log.1").writeText("old\n")
        File(dir, "main.log").writeText("new\n")

        val out = StringWriter().also { LogExporter.writeBundle(dir, it) }.toString().lines()

        val mainHeader = out.indexOf("===== main process =====")
        assertTrue(mainHeader >= 0)
        assertEquals(listOf("old", "new"), out.subList(mainHeader + 1, mainHeader + 3))
    }

    @Test
    fun `an empty log is marked instead of omitted`() {
        val out = StringWriter().also { LogExporter.writeBundle(dir, it) }.toString().lines()
        assertEquals("(no entries)", out[out.indexOf("===== main process =====") + 1])
    }

    @Test
    fun `logs from the old accessibility process are not bundled`() {
        File(dir, "main.log").writeText("main line\n")
        File(dir, "a11y.log").writeText("stale\n")
        val out = StringWriter().also { LogExporter.writeBundle(dir, it) }.toString()
        assertFalse(out.contains("stale"))
    }
}

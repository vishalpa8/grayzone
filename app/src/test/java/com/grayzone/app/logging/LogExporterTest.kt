package com.grayzone.app.logging

import org.junit.After
import org.junit.Assert.assertEquals
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
    fun `bundles each process with the rotated backup first`() {
        File(dir, "main.log.1").writeText("old\n")
        File(dir, "main.log").writeText("new\n")
        File(dir, "a11y.log").writeText("a11y line\n")

        val out = StringWriter().also { LogExporter.writeBundle(dir, it) }.toString().lines()

        val mainHeader = out.indexOf("===== main process =====")
        val a11yHeader = out.indexOf("===== a11y process =====")
        assertTrue(mainHeader in 0 until a11yHeader)
        assertEquals(listOf("old", "new"), out.subList(mainHeader + 1, mainHeader + 3))
        assertEquals("a11y line", out[a11yHeader + 1])
    }

    @Test
    fun `a process without logs is marked instead of omitted`() {
        File(dir, "main.log").writeText("only main\n")
        val out = StringWriter().also { LogExporter.writeBundle(dir, it) }.toString().lines()
        assertEquals("(no entries)", out[out.indexOf("===== a11y process =====") + 1])
    }
}

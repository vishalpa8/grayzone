package com.grayzone.app.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LogFileWriterTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("gzlogs").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `append writes lines in order`() {
        val file = File(dir, "main.log")
        val writer = LogFileWriter(file)
        writer.append("first")
        writer.append("second")
        writer.flush()
        assertEquals(listOf("first", "second"), file.readLines())
    }

    @Test
    fun `rotates to a single backup once the size cap is exceeded`() {
        val file = File(dir, "main.log")
        val writer = LogFileWriter(file, maxBytes = 50)
        repeat(10) { writer.appendSync("line-$it-xxxxxxxxxx") } // ~20 bytes each
        assertTrue(file.length() <= 50)
        assertTrue(writer.backupFile.exists())
        assertTrue(writer.backupFile.length() <= 50)
        // Only one backup generation is ever kept.
        assertFalse(File(dir, "main.log.2").exists())
        assertEquals("line-9-xxxxxxxxxx", file.readLines().last())
    }

    @Test
    fun `a single entry larger than the cap is truncated to the cap`() {
        val file = File(dir, "main.log")
        val writer = LogFileWriter(file, maxBytes = 10)
        writer.appendSync("short")
        writer.appendSync("this line is much longer than ten bytes")
        assertTrue(file.length() <= 10)
        assertTrue(file.readText().startsWith("this line"))
        assertEquals(listOf("short"), writer.backupFile.readLines())
    }

    @Test
    fun `creates the log directory on first write`() {
        val file = File(File(dir, "nested"), "a11y.log")
        LogFileWriter(file).appendSync("hello")
        assertEquals(listOf("hello"), file.readLines())
    }
}

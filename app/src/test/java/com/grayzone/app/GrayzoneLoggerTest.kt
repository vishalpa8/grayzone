package com.grayzone.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Renamed from CleanupAndLoggingTest (T078 / F-196). */
class GrayzoneLoggerTest {
    @Test
    fun logger_shouldAllowWarningsAndErrorsInAllBuilds() {
        assertTrue(GrayzoneLogger.shouldLog(LogLevel.WARN))
        assertTrue(GrayzoneLogger.shouldLog(LogLevel.ERROR))
    }

    @Test
    fun `release logs only warnings and errors, to logcat and file`() {
        assertFalse(GrayzoneLogger.shouldLog(LogLevel.DEBUG, verbose = false))
        assertFalse(GrayzoneLogger.shouldLog(LogLevel.INFO, verbose = false))
        assertFalse(GrayzoneLogger.shouldPersist(LogLevel.INFO, verbose = false))
        assertTrue(GrayzoneLogger.shouldPersist(LogLevel.WARN, verbose = false))
        assertTrue(GrayzoneLogger.shouldPersist(LogLevel.ERROR, verbose = false))
    }

    @Test
    fun `debug builds log everything to logcat but never debug lines to the file`() {
        assertTrue(GrayzoneLogger.shouldLog(LogLevel.DEBUG, verbose = true))
        assertFalse(GrayzoneLogger.shouldPersist(LogLevel.DEBUG, verbose = true))
        assertTrue(GrayzoneLogger.shouldPersist(LogLevel.INFO, verbose = true))
    }
}

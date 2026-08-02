package com.grayzone.app

import org.junit.Assert.assertTrue
import org.junit.Test

/** Renamed from CleanupAndLoggingTest (T078 / F-196). */
class GrayzoneLoggerTest {
    @Test
    fun logger_shouldAllowWarningsAndErrorsInAllBuilds() {
        assertTrue(GrayzoneLogger.shouldLog(LogLevel.WARN))
        assertTrue(GrayzoneLogger.shouldLog(LogLevel.ERROR))
    }
}

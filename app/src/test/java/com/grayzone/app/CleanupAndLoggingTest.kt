package com.grayzone.app

import org.junit.Test
import org.junit.Assert.assertTrue

class CleanupAndLoggingTest {
    @Test
    fun logger_shouldAllowWarningsAndErrorsInAllBuilds() {
        assertTrue(GrayzoneLogger.shouldLog(LogLevel.WARN))
        assertTrue(GrayzoneLogger.shouldLog(LogLevel.ERROR))
    }
}

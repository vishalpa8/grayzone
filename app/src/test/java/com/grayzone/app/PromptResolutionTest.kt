package com.grayzone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the "Manage Custom Prompts" business rule — the pause screen must
 * never be blank and must honour the "use custom only" toggle.
 */
class PromptResolutionTest {

    @Test
    fun `default mode with no custom prompts returns the full default set`() {
        val pool = Prompts.resolvePool(emptyList(), useCustomOnly = false)
        assertEquals(Prompts.DEFAULT, pool)
    }

    @Test
    fun `default mode merges defaults then custom prompts in order`() {
        val custom = listOf("Why now?", "Is it worth it?")
        val pool = Prompts.resolvePool(custom, useCustomOnly = false)
        assertEquals(Prompts.DEFAULT.size + custom.size, pool.size)
        assertTrue(pool.containsAll(Prompts.DEFAULT))
        assertTrue(pool.containsAll(custom))
        // Defaults come first, custom appended after.
        assertEquals(Prompts.DEFAULT.first(), pool.first())
        assertEquals(custom.last(), pool.last())
    }

    @Test
    fun `custom-only mode returns exactly the custom prompts`() {
        val custom = listOf("Why now?", "Is it worth it?")
        val pool = Prompts.resolvePool(custom, useCustomOnly = true)
        assertEquals(custom, pool)
        // No default prompt leaks through.
        assertFalse(pool.any { it in Prompts.DEFAULT })
    }

    @Test
    fun `custom-only mode with no custom prompts falls back to defaults (never blank)`() {
        val pool = Prompts.resolvePool(emptyList(), useCustomOnly = true)
        assertEquals(Prompts.DEFAULT, pool)
        assertTrue(pool.isNotEmpty())
    }

    @Test
    fun `single custom prompt in custom-only mode is the only option`() {
        val pool = Prompts.resolvePool(listOf("Solo"), useCustomOnly = true)
        assertEquals(listOf("Solo"), pool)
    }
}

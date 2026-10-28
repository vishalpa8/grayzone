package com.grayzone.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the prompt-once-per-loss state machine for User Story 2 (AP-1..AP-4 in
 * specs/002-fix-timeout-accessibility-bugs/contracts/accessibility-permission.md).
 */
class PermissionLossPromptPolicyTest {

    // AP-1: granted → never prompt
    @Test
    fun `granted does not prompt and clears flag`() {
        val d = PermissionLossPromptPolicy.onObserved(previouslyPromptedForLoss = false, isEnabled = true)
        assertFalse(d.shouldPrompt)
        assertFalse(d.promptedForLoss)
    }

    @Test
    fun `granted clears a previously set flag`() {
        val d = PermissionLossPromptPolicy.onObserved(previouslyPromptedForLoss = true, isEnabled = true)
        assertFalse(d.shouldPrompt)
        assertFalse(d.promptedForLoss)
    }

    // AP-2: first observed loss → prompt exactly once and remember it
    @Test
    fun `first loss prompts once and sets flag`() {
        val d = PermissionLossPromptPolicy.onObserved(previouslyPromptedForLoss = false, isEnabled = false)
        assertTrue(d.shouldPrompt)
        assertTrue(d.promptedForLoss)
    }

    // AP-3: subsequent losses while still disabled → no prompt
    @Test
    fun `subsequent loss does not prompt again`() {
        val d = PermissionLossPromptPolicy.onObserved(previouslyPromptedForLoss = true, isEnabled = false)
        assertFalse(d.shouldPrompt)
        assertTrue(d.promptedForLoss)
    }

    // AP-4: re-grant then lose again → prompts exactly once more
    @Test
    fun `regrant resets so a future loss prompts once more`() {
        // lose → prompt
        val first = PermissionLossPromptPolicy.onObserved(previouslyPromptedForLoss = false, isEnabled = false)
        assertTrue(first.shouldPrompt)
        // re-grant → reset
        val regrant = PermissionLossPromptPolicy.onObserved(previouslyPromptedForLoss = first.promptedForLoss, isEnabled = true)
        assertFalse(regrant.promptedForLoss)
        // lose again → prompt once more
        val second = PermissionLossPromptPolicy.onObserved(previouslyPromptedForLoss = regrant.promptedForLoss, isEnabled = false)
        assertTrue(second.shouldPrompt)
    }
}

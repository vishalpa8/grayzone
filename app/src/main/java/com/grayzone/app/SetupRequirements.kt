package com.grayzone.app

/** A permission Grayzone cannot apply friction without. */
enum class SetupRequirement { ACCESSIBILITY, OVERLAY }

/**
 * Maps observed permission state to the exact requirement(s) still missing.
 *
 * Kept pure so the UI never has to guess: a half-granted setup must name the one
 * permission that is off and route to that permission's settings page, instead of
 * showing a combined "accessibility or overlay is off" message.
 */
object SetupRequirements {

    /** Ordered so the first entry is the one to fix next: detection before drawing. */
    fun missing(accessibilityEnabled: Boolean, overlayEnabled: Boolean): List<SetupRequirement> =
        buildList {
            if (!accessibilityEnabled) add(SetupRequirement.ACCESSIBILITY)
            if (!overlayEnabled) add(SetupRequirement.OVERLAY)
        }

    fun label(requirement: SetupRequirement): String = when (requirement) {
        SetupRequirement.ACCESSIBILITY -> "Accessibility"
        SetupRequirement.OVERLAY -> "Display over other apps"
    }

    fun headline(missing: List<SetupRequirement>): String = when (missing.size) {
        0 -> "Protection ready"
        1 -> "${label(missing.first())} is off"
        else -> "${missing.size} permissions are off"
    }

    fun body(missing: List<SetupRequirement>): String = when (missing.size) {
        0 -> ""
        1 -> when (missing.first()) {
            SetupRequirement.ACCESSIBILITY ->
                "Grayzone can't see which app is open. Turn on Accessibility for Grayzone."
            SetupRequirement.OVERLAY ->
                "Grayzone can't draw the pause screen. Allow it to display over other apps."
        }
        else -> "Turn on ${missing.joinToString(" and ") { label(it) }} to monitor apps."
    }

    /** Label for the action that fixes [missing] next, e.g. "Turn on Accessibility". */
    fun actionLabel(missing: List<SetupRequirement>): String =
        missing.firstOrNull()?.let { "Turn on ${label(it)}" } ?: ""
}

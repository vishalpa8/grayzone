package com.grayzone.app

/** A permission Grayzone cannot apply friction without, or that degrades protection when missing. */
enum class SetupRequirement {
    /** Blocking: detection. */
    ACCESSIBILITY,
    /** Blocking: pause overlay. */
    OVERLAY,
    /** Degrading: ongoing / countdown notifications (API 33+). */
    NOTIFICATIONS,
    /** Degrading: anti-tamper state backup. */
    ALL_FILES,
    /** Degrading: background survival. */
    BATTERY,
}

/**
 * Maps observed permission state to the exact requirement(s) still missing.
 *
 * Kept pure so the UI never has to guess: a half-granted setup must name the one
 * permission that is off and route to that permission's settings page, instead of
 * showing a combined "accessibility or overlay is off" message.
 */
object SetupRequirements {

    private val CORE = setOf(SetupRequirement.ACCESSIBILITY, SetupRequirement.OVERLAY)

    /**
     * Ordered so the first entry is the one to fix next: detection before drawing,
     * then degrading grants (notifications, backup, battery).
     *
     * Newer parameters default to granted so existing two-arg call sites stay valid
     * (onboarding still gates on accessibility + overlay only).
     */
    fun missing(
        accessibilityEnabled: Boolean,
        overlayEnabled: Boolean,
        notificationsEnabled: Boolean = true,
        allFilesGranted: Boolean = true,
        batteryExempt: Boolean = true,
    ): List<SetupRequirement> =
        buildList {
            if (!accessibilityEnabled) add(SetupRequirement.ACCESSIBILITY)
            if (!overlayEnabled) add(SetupRequirement.OVERLAY)
            if (!notificationsEnabled) add(SetupRequirement.NOTIFICATIONS)
            if (!allFilesGranted) add(SetupRequirement.ALL_FILES)
            if (!batteryExempt) add(SetupRequirement.BATTERY)
        }

    fun label(requirement: SetupRequirement): String = when (requirement) {
        SetupRequirement.ACCESSIBILITY -> "Accessibility"
        SetupRequirement.OVERLAY -> "Display over other apps"
        SetupRequirement.NOTIFICATIONS -> "Notifications"
        SetupRequirement.ALL_FILES -> "All files access"
        SetupRequirement.BATTERY -> "Battery exemption"
    }

    /**
     * Absolute "Protection ready" only when nothing is missing.
     * Core grants off → name the permission(s).
     * Only recommended/degrading grants missing → softer copy (not an absolute ready claim).
     */
    fun headline(missing: List<SetupRequirement>): String = when {
        missing.isEmpty() -> "Protection ready"
        missing.size == 1 -> "${label(missing.first())} off"
        missing.none { it in CORE } -> "Setup incomplete"
        else -> "${missing.size} permissions off"
    }

    fun body(missing: List<SetupRequirement>): String = when (missing.size) {
        0 -> ""
        1 -> when (missing.first()) {
            SetupRequirement.ACCESSIBILITY -> "Tap below to enable."
            SetupRequirement.OVERLAY -> "Tap below to allow."
            SetupRequirement.NOTIFICATIONS -> "Tap below to allow."
            SetupRequirement.ALL_FILES -> "Tap below to allow."
            SetupRequirement.BATTERY -> "Tap below to exempt."
        }
        else -> "Enable the missing items below."
    }

    /** Label for the action that fixes [missing] next, e.g. "Enable Accessibility". */
    fun actionLabel(missing: List<SetupRequirement>): String =
        missing.firstOrNull()?.let { "Enable ${label(it)}" } ?: ""

    /**
     * Android 13+ can grey out the accessibility toggle for sideloaded installs until the user
     * allows restricted settings from App info.
     */
    fun needsRestrictedSettingsHint(sdkInt: Int, requirement: SetupRequirement): Boolean =
        sdkInt >= 33 && requirement == SetupRequirement.ACCESSIBILITY
}

package com.grayzone.app

/** A permission Grayzone cannot apply friction without, or that degrades protection when missing. */
enum class SetupRequirement {
    /** Blocking: detection (which app is in front). */
    USAGE_ACCESS,
    /** Blocking: pause overlay. */
    OVERLAY,
    /** Degrading: ongoing / countdown notifications (API 33+). */
    NOTIFICATIONS,
    /** Degrading: background survival. */
    BATTERY,
}

/**
 * Maps observed permission state to the exact requirement(s) still missing.
 *
 * Kept pure so the UI never has to guess: a half-granted setup must name the one
 * permission that is off and route to that permission's settings page, instead of
 * showing a combined "usage access or overlay is off" message.
 */
object SetupRequirements {

    private val CORE = setOf(SetupRequirement.USAGE_ACCESS, SetupRequirement.OVERLAY)

    /** The order the first-run popup walks through the permissions, one at a time. */
    val SETUP_ORDER = listOf(
        SetupRequirement.NOTIFICATIONS,
        SetupRequirement.USAGE_ACCESS,
        SetupRequirement.OVERLAY,
        SetupRequirement.BATTERY,
    )
    /**
     * Ordered so the first entry is the one to fix next: detection before drawing,
     * then degrading grants (notifications, battery).
     *
     * Newer parameters default to granted so existing two-arg call sites stay valid
     * (core gating is usage access + overlay only).
     */
    fun missing(
        usageAccessEnabled: Boolean,
        overlayEnabled: Boolean,
        notificationsEnabled: Boolean = true,
        batteryExempt: Boolean = true,
    ): List<SetupRequirement> =
        buildList {
            if (!usageAccessEnabled) add(SetupRequirement.USAGE_ACCESS)
            if (!overlayEnabled) add(SetupRequirement.OVERLAY)
            if (!notificationsEnabled) add(SetupRequirement.NOTIFICATIONS)
            if (!batteryExempt) add(SetupRequirement.BATTERY)
        }

    fun label(requirement: SetupRequirement): String = when (requirement) {
        SetupRequirement.USAGE_ACCESS -> "Usage access"
        SetupRequirement.OVERLAY -> "Display over other apps"
        SetupRequirement.NOTIFICATIONS -> "Notifications"
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
            SetupRequirement.USAGE_ACCESS -> "Tap below to allow."
            SetupRequirement.OVERLAY -> "Tap below to allow."
            SetupRequirement.NOTIFICATIONS -> "Tap below to allow."
            SetupRequirement.BATTERY -> "Tap below to exempt."
        }
        else -> "Enable the missing items below."
    }

    /** Label for the action that fixes [missing] next, e.g. "Enable Usage access". */
    fun actionLabel(missing: List<SetupRequirement>): String =
        missing.firstOrNull()?.let { "Enable ${label(it)}" } ?: ""

}

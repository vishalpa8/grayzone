package com.grayzone.app

/**
 * Pure mapping from permission state → ongoing notification copy (F-080).
 * Extracted so JVM unit tests can pin honesty without constructing a Notification.
 */
data class ProtectionNotificationCopy(
    val title: String,
    val text: String,
    val isWarning: Boolean,
    /** First missing requirement to deep-link, or null when healthy. */
    val fixRequirement: SetupRequirement?
)

object ProtectionNotificationState {

    fun resolve(accessibilityEnabled: Boolean, overlayEnabled: Boolean): ProtectionNotificationCopy {
        val missing = SetupRequirements.missing(accessibilityEnabled, overlayEnabled)
        if (missing.isEmpty()) {
            return ProtectionNotificationCopy(
                title = "Grayzone is active",
                text = "Monitoring app usage to help you stay focused.",
                isWarning = false,
                fixRequirement = null
            )
        }
        val first = missing.first()
        return ProtectionNotificationCopy(
            title = "Protection is OFF",
            text = SetupRequirements.body(missing),
            isWarning = true,
            fixRequirement = first
        )
    }
}

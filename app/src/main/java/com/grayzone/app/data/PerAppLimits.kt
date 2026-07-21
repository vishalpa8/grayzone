package com.grayzone.app.data

/**
 * Resolves effective session / lockout / wait limits when a monitored app may
 * have per-app overrides. Pure so UI sheets and OverlayService stay in sync.
 */
object PerAppLimits {

    fun sessionMinutes(hasCustom: Boolean, perApp: Int, global: Int, fallback: Int = 10): Int =
        (if (hasCustom) perApp else global).coerceIn(1, 24 * 60).takeIf { it > 0 } ?: fallback

    fun lockoutMinutes(hasCustom: Boolean, perApp: Int, global: Int, fallback: Int = 60): Int =
        (if (hasCustom) perApp else global).coerceIn(15, 24 * 60).takeIf { it > 0 } ?: fallback

    fun waitSeconds(hasCustom: Boolean, perApp: Int, global: Int, fallback: Int = 5): Int =
        (if (hasCustom) perApp else global).coerceIn(1, 60).takeIf { it > 0 } ?: fallback
}

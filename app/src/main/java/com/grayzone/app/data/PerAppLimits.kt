package com.grayzone.app.data

/**
 * Resolves effective session / lockout / wait limits when a monitored app may
 * have per-app overrides. Pure so UI sheets and OverlayService stay in sync.
 *
 * Clamps use [ConfigValidator] ranges so UI, startup repair, and the engine agree.
 */
object PerAppLimits {

    fun sessionMinutes(hasCustom: Boolean, perApp: Int, global: Int): Int {
        val raw = if (hasCustom) perApp else global
        return raw.coerceIn(
            ConfigValidator.SESSION_MINUTES_RANGE.first,
            ConfigValidator.SESSION_MINUTES_RANGE.last
        )
    }

    fun lockoutMinutes(hasCustom: Boolean, perApp: Int, global: Int): Int {
        val raw = if (hasCustom) perApp else global
        return raw.coerceIn(
            ConfigValidator.PER_APP_LOCKOUT_MINUTES_RANGE.first,
            ConfigValidator.PER_APP_LOCKOUT_MINUTES_RANGE.last
        )
    }

    fun waitSeconds(hasCustom: Boolean, perApp: Int, global: Int): Int {
        val raw = if (hasCustom) perApp else global
        return raw.coerceIn(
            ConfigValidator.WAIT_SECONDS_RANGE.first,
            ConfigValidator.WAIT_SECONDS_RANGE.last
        )
    }
}

package com.grayzone.app.service.vpn

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.grayzone.app.PrefsKeys

/** SharedPreferences bridge for [VpnHoldLock.State] in the main Grayzone prefs file. */
object VpnHoldLockStore {

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

    fun read(prefs: SharedPreferences): VpnHoldLock.State = VpnHoldLock.State(
        startedWallMs = prefs.getLong(PrefsKeys.VPN_HOLD_STARTED_WALL, 0L),
        startedElapsedMs = prefs.getLong(PrefsKeys.VPN_HOLD_STARTED_ELAPSED, 0L)
    )

    fun read(context: Context): VpnHoldLock.State = read(prefs(context))

    /**
     * Record enable time only if no hold is tracked yet (restarts/reconnects must not reset the 24h).
     */
    fun markEnabledIfAbsent(
        prefs: SharedPreferences,
        nowWallMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ) {
        if (read(prefs).isTracked) return
        prefs.edit()
            .putLong(PrefsKeys.VPN_HOLD_STARTED_WALL, nowWallMs)
            .putLong(PrefsKeys.VPN_HOLD_STARTED_ELAPSED, nowElapsedMs)
            .apply()
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit()
            .remove(PrefsKeys.VPN_HOLD_STARTED_WALL)
            .remove(PrefsKeys.VPN_HOLD_STARTED_ELAPSED)
            .apply()
    }

    fun clear(context: Context) = clear(prefs(context))
}

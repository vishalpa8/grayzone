package com.grayzone.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent
import com.grayzone.app.PrefsKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SharedPreferences
) {
    private val _grayzoneEnabled = MutableStateFlow(
        prefs.getBoolean(PrefsKeys.GRAYZONE_ENABLED, false)
    )
    val grayzoneEnabled: StateFlow<Boolean> = _grayzoneEnabled.asStateFlow()

    private val _monitoredApps = MutableStateFlow(
        prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()
    )
    val monitoredApps: StateFlow<Set<String>> = _monitoredApps.asStateFlow()

    fun setGrayzoneEnabled(enabled: Boolean) {
        val changed = _grayzoneEnabled.value != enabled
        prefs.edit().putBoolean(PrefsKeys.GRAYZONE_ENABLED, enabled).apply()
        _grayzoneEnabled.value = enabled
        if (changed) {
            GrayzoneLogger.i(LogComponent.SESSION, "Monitoring turned ${if (enabled) "on" else "off"} by the user")
        }
    }

    fun isAppLocked(packageName: String): Boolean {
        return _monitoredApps.value.contains(packageName)
    }

    fun getMonitoredApps(): Set<String> {
        return _monitoredApps.value
    }
    
    fun setMonitoredApps(apps: Set<String>) {
        prefs.edit().putStringSet(PrefsKeys.MONITORED_APPS, apps).apply()
        _monitoredApps.value = apps
    }

    fun clearSession(packageName: String) {
        prefs.edit()
            .remove(PrefsKeys.ACTIVE_UNTIL + packageName)
            .remove(PrefsKeys.LOCKED_UNTIL + packageName)
            .remove(PrefsKeys.REMAINING_MILLIS + packageName)
            .apply()
    }
}

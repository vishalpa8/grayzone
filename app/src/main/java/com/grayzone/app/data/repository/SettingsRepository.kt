package com.grayzone.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.grayzone.app.PrefsKeys
import com.grayzone.app.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SharedPreferences
) {
    private val _themeMode = MutableStateFlow(
        prefs.getString(PrefsKeys.THEME_MODE, ThemeMode.SYSTEM) ?: ThemeMode.SYSTEM
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _waitSeconds = MutableStateFlow(prefs.getInt(PrefsKeys.WAIT_SECONDS, 5))
    val waitSeconds: StateFlow<Int> = _waitSeconds.asStateFlow()

    private val _sessionMinutes = MutableStateFlow(prefs.getInt(PrefsKeys.SESSION_MINUTES, 10))
    val sessionMinutes: StateFlow<Int> = _sessionMinutes.asStateFlow()

    private val _lockoutMinutes = MutableStateFlow(prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60))
    val lockoutMinutes: StateFlow<Int> = _lockoutMinutes.asStateFlow()

    fun setThemeMode(mode: String) {
        prefs.edit().putString(PrefsKeys.THEME_MODE, mode).apply()
        _themeMode.value = mode
    }

    fun setWaitSeconds(seconds: Int) {
        prefs.edit().putInt(PrefsKeys.WAIT_SECONDS, seconds).apply()
        _waitSeconds.value = seconds
    }

    fun setSessionMinutes(minutes: Int) {
        prefs.edit().putInt(PrefsKeys.SESSION_MINUTES, minutes).apply()
        _sessionMinutes.value = minutes
    }

    fun setLockoutMinutes(minutes: Int) {
        prefs.edit().putInt(PrefsKeys.LOCKOUT_MINUTES, minutes).apply()
        _lockoutMinutes.value = minutes
    }
}

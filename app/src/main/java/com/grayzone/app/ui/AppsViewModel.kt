package com.grayzone.app.ui

import com.grayzone.app.ui.components.*

import androidx.lifecycle.ViewModel
import com.grayzone.app.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    val grayzoneEnabled: StateFlow<Boolean> = sessionRepository.grayzoneEnabled
    val monitoredApps: StateFlow<Set<String>> = sessionRepository.monitoredApps

    fun setGrayzoneEnabled(enabled: Boolean) {
        sessionRepository.setGrayzoneEnabled(enabled)
    }

    fun toggleAppMonitored(packageName: String, isMonitored: Boolean) {
        val current = monitoredApps.value.toMutableSet()
        if (isMonitored) {
            current.add(packageName)
        } else {
            current.remove(packageName)
            // Remove active/locked keys
            sessionRepository.clearSession(packageName)
        }
        sessionRepository.setMonitoredApps(current)
    }
}

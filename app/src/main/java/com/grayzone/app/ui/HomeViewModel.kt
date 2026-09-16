package com.grayzone.app.ui

import com.grayzone.app.ui.components.*

import androidx.lifecycle.ViewModel
import com.grayzone.app.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    val monitoredApps: StateFlow<Set<String>> = sessionRepository.monitoredApps
}

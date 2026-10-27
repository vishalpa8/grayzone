package com.grayzone.app.ui

import android.content.Context
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.grayzone.app.BuildConfig
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent
import com.grayzone.app.ui.components.SettingsRow
import com.grayzone.app.ui.theme.*
import com.grayzone.app.update.AppUpdateInfo
import com.grayzone.app.update.AppUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Process-wide update state, so a download carries on while the user moves between tabs. */
object AppUpdateController {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: AppUpdateInfo) : State
        data class Downloading(val info: AppUpdateInfo, val progress: Float) : State
        data class Ready(val info: AppUpdateInfo, val apk: File) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun check(context: Context) {
        val current = _state.value
        if (current is State.Checking || current is State.Downloading) return
        _state.value = State.Checking
        val app = context.applicationContext
        scope.launch {
            _state.value = when (val result = AppUpdater(app).check()) {
                is AppUpdater.CheckResult.Available -> State.Available(result.info)
                AppUpdater.CheckResult.UpToDate -> State.UpToDate
                is AppUpdater.CheckResult.Failed -> State.Failed(result.reason)
            }
        }
    }

    fun download(context: Context, info: AppUpdateInfo) {
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(info, 0f)
        val app = context.applicationContext
        scope.launch {
            _state.value = try {
                val apk = AppUpdater(app).download(info) { p -> _state.value = State.Downloading(info, p) }
                GrayzoneLogger.i(LogComponent.UI, "Update ${info.versionName} downloaded and verified")
                State.Ready(info, apk)
            } catch (e: Exception) {
                GrayzoneLogger.w(LogComponent.UI, "Update ${info.versionName} download failed", e)
                State.Failed(e.message ?: "Download failed")
            }
        }
    }
}

/** "App updates" row for Settings; GitHub builds only (Play updates through the Store). */
@Composable
fun AppUpdateRow() {
    if (!BuildConfig.SELF_UPDATE) return
    val context = LocalContext.current
    val state by AppUpdateController.state.collectAsState()
    var showDetails by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var askInstallPermission by remember { mutableStateOf(false) }
    var installWhenAllowed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (AppUpdateController.state.value is AppUpdateController.State.Idle) AppUpdateController.check(context)
    }

    fun install(apk: File) {
        val updater = AppUpdater(context)
        if (!updater.canInstall()) {
            askInstallPermission = true
            return
        }
        try {
            context.startActivity(updater.installIntent(apk))
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.UI, "Could not open the installer", e)
        }
    }

    // Back from "Install unknown apps": carry on with the install the user asked for.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && installWhenAllowed) {
                installWhenAllowed = false
                val ready = AppUpdateController.state.value as? AppUpdateController.State.Ready
                if (ready != null && AppUpdater(context).canInstall()) install(ready.apk)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val subtitle = when (val s = state) {
        AppUpdateController.State.Idle, AppUpdateController.State.Checking -> "Checking for updates…"
        AppUpdateController.State.UpToDate -> "You're on the latest version (${BuildConfig.VERSION_NAME}) · tap to check again"
        is AppUpdateController.State.Available -> "Version ${s.info.versionName} is available · tap to update"
        is AppUpdateController.State.Downloading -> "Downloading ${s.info.versionName}… ${(s.progress * 100).toInt()}%"
        is AppUpdateController.State.Ready -> "Version ${s.info.versionName} is ready · tap to install"
        is AppUpdateController.State.Failed -> "${s.message} · tap to retry"
    }

    SettingsRow(
        title = "App updates",
        subtitle = subtitle,
        icon = Icons.Filled.SystemUpdate,
        enabled = state !is AppUpdateController.State.Checking && state !is AppUpdateController.State.Downloading,
        onClick = {
            when (val s = state) {
                is AppUpdateController.State.Available -> showDetails = s.info
                is AppUpdateController.State.Ready -> install(s.apk)
                AppUpdateController.State.Checking, is AppUpdateController.State.Downloading -> Unit
                else -> AppUpdateController.check(context)
            }
        }
    )

    showDetails?.let { info ->
        AlertDialog(
            onDismissRequest = { showDetails = null },
            title = { Text("Update to ${info.versionName}") },
            text = {
                Text(
                    info.notes.ifBlank { "A new version of Grayzone is ready." } +
                        "\n\nDownload size: ${"%.1f".format(info.size / 1_048_576f)} MB. Your settings are kept.",
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDetails = null
                    AppUpdateController.download(context, info)
                }) { Text("Update", color = GZPrimary, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showDetails = null }) { Text("Later", color = GZTextSecondary) }
            },
            containerColor = GZSurface,
            titleContentColor = GZTextPrimary,
            textContentColor = GZTextSecondary,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (askInstallPermission) {
        AlertDialog(
            onDismissRequest = { askInstallPermission = false },
            title = { Text("Allow Grayzone to install updates") },
            text = { Text("Android asks once before an app can install its own updates. Turn on \"Allow from this source\", then come back.") },
            confirmButton = {
                TextButton(onClick = {
                    askInstallPermission = false
                    installWhenAllowed = true
                    try {
                        context.startActivity(AppUpdater(context).installPermissionIntent())
                    } catch (e: Exception) {
                        installWhenAllowed = false
                        GrayzoneLogger.w(LogComponent.UI, "Could not open the install permission screen", e)
                    }
                }) { Text("Open settings", color = GZPrimary, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { askInstallPermission = false }) { Text("Not now", color = GZTextSecondary) }
            },
            containerColor = GZSurface,
            titleContentColor = GZTextPrimary,
            textContentColor = GZTextSecondary,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

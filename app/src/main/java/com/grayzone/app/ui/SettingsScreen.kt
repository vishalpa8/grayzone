package com.grayzone.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.grayzone.app.BuildConfig
import com.grayzone.app.PrefsKeys
import com.grayzone.app.data.ConfigValidator
import com.grayzone.app.isRestrictionActive
import kotlin.math.roundToInt
import com.grayzone.app.rememberResumedTicker
import com.grayzone.app.shareApp
import com.grayzone.app.ui.components.*
import com.grayzone.app.ui.theme.*

private fun formatMinutes(total: Int): String {
    val h = total / 60
    val m = total % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

@Composable
fun SettingsScreen(
    onThemeModeChanged: (String) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }

    val vmThemeMode by viewModel.themeMode.collectAsState()
    val vmWaitSeconds by viewModel.waitSeconds.collectAsState()
    val vmSessionMinutes by viewModel.sessionMinutes.collectAsState()
    val vmLockoutMinutes by viewModel.lockoutMinutes.collectAsState()

    var waitSeconds by remember(vmWaitSeconds) { mutableIntStateOf(vmWaitSeconds.coerceIn(ConfigValidator.WAIT_SECONDS_RANGE)) }
    var sessionMinutes by remember(vmSessionMinutes) { mutableIntStateOf(vmSessionMinutes.coerceIn(ConfigValidator.SESSION_MINUTES_RANGE)) }
    var lockoutMinutes by remember(vmLockoutMinutes) { mutableIntStateOf(vmLockoutMinutes.coerceIn(ConfigValidator.LOCKOUT_MINUTES_RANGE)) }

    var showCustomPrompts by remember { mutableStateOf(false) }
    var exportingLogs by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }

    var monitoredApps by remember {
        mutableStateOf(prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet())
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                monitoredApps = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val currentTime by rememberResumedTicker(2000L)
    val locked = remember(monitoredApps, currentTime) {
        isRestrictionActive(context, prefs, monitoredApps, currentTime)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GZBackground)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader(title = "Settings")

        if (locked) {
            GZCard(
                modifier = Modifier.padding(horizontal = GZGutter),
                background = GZAmberContainer,
                border = GZAmber.copy(alpha = 0.25f)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.Lock, tint = GZAmber, size = 36)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Limits are frozen while an app is in a session, timeout, daily limit or schedule.",
                        color = GZTextPrimary,
                        fontSize = 13.sp
                    )
                }
            }
        }

        SectionLabel("Appearance", Modifier.padding(start = GZGutter + 4.dp, top = 4.dp))
        GZCard(modifier = Modifier.padding(horizontal = GZGutter)) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Text("Theme", color = GZTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                ThemeSegmentedControl(
                    selected = vmThemeMode,
                    onSelect = { mode ->
                        viewModel.setThemeMode(mode)
                        onThemeModeChanged(mode)
                    }
                )
            }
        }

        SectionLabel("Default limits", Modifier.padding(start = GZGutter + 4.dp, top = 8.dp))
        GZCard(modifier = Modifier.padding(horizontal = GZGutter)) {
            Column {
                LabeledSlider(
                    label = "Wait",
                    valueText = "${waitSeconds}s",
                    help = "Pause before you can continue into an app",
                    value = waitSeconds.toFloat(),
                    onValueChange = { waitSeconds = it.roundToInt() },
                    valueRange = 3f..30f,
                    steps = 26,
                    enabled = !locked,
                    onValueChangeFinished = { viewModel.setWaitSeconds(waitSeconds) }
                )
                RowDivider()
                LabeledSlider(
                    label = "Session",
                    valueText = formatMinutes(sessionMinutes),
                    help = "Longest time per visit before the app locks",
                    value = sessionMinutes.toFloat(),
                    onValueChange = {
                        sessionMinutes = it.roundToInt()
                        if (sessionMinutes > lockoutMinutes) lockoutMinutes = sessionMinutes
                    },
                    valueRange = 1f..60f,
                    steps = 58,
                    enabled = !locked,
                    onValueChangeFinished = {
                        viewModel.setSessionMinutes(sessionMinutes)
                        viewModel.setLockoutMinutes(lockoutMinutes)
                    }
                )
                RowDivider()
                LabeledSlider(
                    label = "Lockout",
                    valueText = formatMinutes(lockoutMinutes),
                    help = "How long the app stays locked after a session",
                    value = lockoutMinutes.toFloat(),
                    onValueChange = {
                        lockoutMinutes = it.roundToInt()
                        if (sessionMinutes > lockoutMinutes) sessionMinutes = lockoutMinutes
                    },
                    // 15-minute steps up to the 5h the startup repair accepts for the global lockout.
                    valueRange = 15f..300f,
                    steps = 18,
                    enabled = !locked,
                    onValueChangeFinished = {
                        viewModel.setSessionMinutes(sessionMinutes)
                        viewModel.setLockoutMinutes(lockoutMinutes)
                    }
                )
            }
        }

        SectionLabel("Customize", Modifier.padding(start = GZGutter + 4.dp, top = 8.dp))
        GZCard(modifier = Modifier.padding(horizontal = GZGutter)) {
            Column {
                SettingsRow(
                    title = "Reflection prompts",
                    subtitle = "Questions shown on the pause screen",
                    icon = Icons.Filled.FormatQuote,
                    enabled = !locked,
                    onClick = { showCustomPrompts = true }
                )
                RowDivider(startIndent = 68)
                SettingsRow(
                    title = "Schedules & focus",
                    subtitle = "Lock apps automatically at set times",
                    icon = Icons.Filled.Event,
                    enabled = !locked,
                    onClick = { showSchedule = true }
                )
            }
        }

        SectionLabel("About", Modifier.padding(start = GZGutter + 4.dp, top = 8.dp))
        GZCard(modifier = Modifier.padding(horizontal = GZGutter)) {
            Column {
                SettingsRow(
                    title = "Share Grayzone",
                    subtitle = "Send the Play Store link to a friend",
                    icon = Icons.Filled.Share,
                    onClick = { shareApp(context) }
                )
                RowDivider(startIndent = 68)
                SettingsRow(
                    title = "Export logs",
                    subtitle = if (exportingLogs) "Preparing…" else "Share a diagnostic log to report a problem",
                    icon = Icons.Filled.BugReport,
                    enabled = !exportingLogs,
                    onClick = {
                        // A second tap before recomposition would start a second export of the same file.
                        if (!exportingLogs) {
                            exportingLogs = true
                            com.grayzone.app.logging.LogExporter.exportAndShare(context) { exportingLogs = false }
                        }
                    }
                )
            }
        }

        Text(
            "Grayzone ${BuildConfig.VERSION_NAME}",
            color = GZTextTertiary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }

    if (showCustomPrompts) {
        CustomPromptsSheet(onDismiss = { showCustomPrompts = false })
    }
    if (showSchedule) {
        ScheduleSheet(onDismiss = { showSchedule = false })
    }
}

@Composable
private fun ThemeSegmentedControl(selected: String, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GZSurfaceElevated)
            .border(1.dp, GZBorderSubtle, shape)
            .padding(4.dp)
    ) {
        listOf(
            ThemeMode.SYSTEM to "System",
            ThemeMode.LIGHT to "Light",
            ThemeMode.DARK to "Dark",
        ).forEach { (mode, label) ->
            val isSelected = selected == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) GZSurface else androidx.compose.ui.graphics.Color.Transparent)
                    .then(
                        if (isSelected) Modifier.border(1.dp, GZBorder, RoundedCornerShape(9.dp)) else Modifier
                    )
                    .selectable(
                        selected = isSelected,
                        role = androidx.compose.ui.semantics.Role.Tab,
                        onClick = { onSelect(mode) }
                    )
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (isSelected) GZTextPrimary else GZTextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

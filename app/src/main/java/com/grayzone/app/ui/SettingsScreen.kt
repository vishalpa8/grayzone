package com.grayzone.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.grayzone.app.GZCard
import com.grayzone.app.PrefsKeys
import com.grayzone.app.isAnyAppLocked
import com.grayzone.app.shareApk
import com.grayzone.app.ui.theme.*

@Composable
fun SettingsScreen(onThemeModeChanged: (String) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }

    var themeMode by remember {
        mutableStateOf(prefs.getString(PrefsKeys.THEME_MODE, ThemeMode.SYSTEM) ?: ThemeMode.SYSTEM)
    }
    var waitSeconds by remember { mutableStateOf(prefs.getInt(PrefsKeys.WAIT_SECONDS, 5)) }
    var sessionMinutes by remember { mutableStateOf(prefs.getInt(PrefsKeys.SESSION_MINUTES, 10)) }
    var lockoutMinutes by remember { mutableStateOf(prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)) }

    var showCustomPrompts by remember { mutableStateOf(false) }
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
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            currentTime = System.currentTimeMillis()
        }
    }
    val anyAppLockedOrActive = remember(monitoredApps, currentTime) {
        isAnyAppLocked(prefs, monitoredApps, currentTime)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GZBackground)
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Settings", fontSize = 28.sp, color = GZTextPrimary, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(GZPrimary.copy(alpha = 0.15f))
                    .clickable { shareApk(context) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "Share App",
                        tint = GZPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Share", color = GZPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (anyAppLockedOrActive) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(GZRedContainer)
                    .border(1.dp, GZRed.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Settings are locked while an app is active, paused, or in timeout.", color = GZRed, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text("Appearance", color = GZTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(10.dp))
        GZCard {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Theme", color = GZTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("Light, dark, or follow the system", color = GZTextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ThemeMode.SYSTEM to "System",
                        ThemeMode.LIGHT to "Light",
                        ThemeMode.DARK to "Dark",
                    ).forEach { (mode, label) ->
                        val selected = themeMode == mode
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) GZPrimary else GZSurfaceHigh)
                                .clickable {
                                    themeMode = mode
                                    prefs.edit().putString(PrefsKeys.THEME_MODE, mode).apply()
                                    onThemeModeChanged(mode)
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                label,
                                color = if (selected) GZOnPrimary else GZTextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Wait Duration: $waitSeconds seconds",
            color = if (anyAppLockedOrActive) GZTextTertiary else GZTextPrimary,
            fontWeight = FontWeight.Medium
        )
        Slider(
            value = waitSeconds.toFloat(),
            onValueChange = { waitSeconds = it.toInt() },
            valueRange = 3f..30f,
            steps = 26,
            enabled = !anyAppLockedOrActive,
            onValueChangeFinished = { prefs.edit().putInt(PrefsKeys.WAIT_SECONDS, waitSeconds).apply() }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Session Limit: $sessionMinutes minutes",
            color = if (anyAppLockedOrActive) GZTextTertiary else GZTextPrimary,
            fontWeight = FontWeight.Medium
        )
        Text("How long you can use an app after unlocking.", color = GZTextSecondary, fontSize = 12.sp)
        Slider(
            value = sessionMinutes.toFloat(),
            onValueChange = { sessionMinutes = it.toInt().coerceAtMost(lockoutMinutes) },
            valueRange = 1f..60f,
            steps = 58,
            enabled = !anyAppLockedOrActive,
            onValueChangeFinished = {
                prefs.edit().putInt(PrefsKeys.SESSION_MINUTES, sessionMinutes).apply()
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        val lockoutHours = lockoutMinutes / 60
        val lockoutMins = lockoutMinutes % 60
        val lockoutText = if (lockoutHours > 0) "${lockoutHours}h ${lockoutMins}m" else "${lockoutMins}m"
        Text(
            "Lockout Duration: $lockoutText",
            color = if (anyAppLockedOrActive) GZTextTertiary else GZTextPrimary,
            fontWeight = FontWeight.Medium
        )
        Text("How long the app remains locked after your session expires.", color = GZTextSecondary, fontSize = 12.sp)
        Slider(
            value = lockoutMinutes.toFloat(),
            onValueChange = {
                lockoutMinutes = it.toInt()
                if (sessionMinutes > lockoutMinutes) sessionMinutes = lockoutMinutes
            },
            valueRange = 15f..1440f,
            steps = 94,
            enabled = !anyAppLockedOrActive,
            onValueChangeFinished = {
                prefs.edit()
                    .putInt(PrefsKeys.SESSION_MINUTES, sessionMinutes)
                    .putInt(PrefsKeys.LOCKOUT_MINUTES, lockoutMinutes)
                    .apply()
            }
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { showCustomPrompts = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = !anyAppLockedOrActive,
            colors = ButtonDefaults.buttonColors(
                containerColor = GZPrimaryContainer,
                contentColor = GZTextPrimary,
                disabledContainerColor = GZSurfaceHigh,
                disabledContentColor = GZTextTertiary
            )
        ) {
            Text("Manage Custom Prompts", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { showSchedule = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = !anyAppLockedOrActive,
            colors = ButtonDefaults.buttonColors(
                containerColor = GZPrimaryContainer,
                contentColor = GZTextPrimary,
                disabledContainerColor = GZSurfaceHigh,
                disabledContentColor = GZTextTertiary
            )
        ) {
            Text("Manage Schedule Focus Modes", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(64.dp))
    }

    if (showCustomPrompts) {
        CustomPromptsSheet(onDismiss = { showCustomPrompts = false })
    }
    if (showSchedule) {
        ScheduleSheet(onDismiss = { showSchedule = false })
    }
}

package com.grayzone.app.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.AppInfo
import com.grayzone.app.PrefsKeys
import com.grayzone.app.data.ConfigValidator
import kotlin.math.roundToInt
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

/**
 * Per-app limits. [editable] is false while the app is locked or mid-session, so limits can't be
 * loosened (e.g. raising the daily budget) to escape a lock that is already running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppSettingsSheet(
    app: AppInfo,
    editable: Boolean = true,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    val pkg = app.packageName

    var hasCustom by remember { mutableStateOf(prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + pkg, false)) }

    val globalWait = remember { prefs.getInt(PrefsKeys.WAIT_SECONDS, 5) }
    val globalSession = remember { prefs.getInt(PrefsKeys.SESSION_MINUTES, 10) }
    val globalLockout = remember { prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60) }

    var waitSeconds by remember { mutableIntStateOf(prefs.getInt(PrefsKeys.PER_APP_WAIT_SECONDS + pkg, globalWait).coerceIn(ConfigValidator.WAIT_SECONDS_RANGE)) }
    var sessionMinutes by remember { mutableIntStateOf(prefs.getInt(PrefsKeys.PER_APP_SESSION_MINUTES + pkg, globalSession).coerceIn(ConfigValidator.SESSION_MINUTES_RANGE)) }
    var lockoutMinutes by remember { mutableIntStateOf(prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + pkg, globalLockout).coerceIn(ConfigValidator.PER_APP_LOCKOUT_MINUTES_RANGE)) }
    var dailyBudget by remember { mutableIntStateOf(prefs.getInt(PrefsKeys.DAILY_BUDGET_MINUTES + pkg, 0).coerceIn(0, 240)) }
    var confirmBlock by remember { mutableStateOf(false) }

    val slidersEnabled = editable && hasCustom

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = GZBackground,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = GZBorder) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.padding(horizontal = GZGutter + 4.dp)) {
                Text(app.name, color = GZTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (editable) "Limits for this app" else "Limits can't be changed while this app is locked or in a session",
                    color = if (editable) GZTextSecondary else GZAmber,
                    fontSize = 13.sp
                )
            }

            GZCard(modifier = Modifier.padding(horizontal = GZGutter)) {
                Column {
                    SettingsRow(
                        title = "Custom limits",
                        subtitle = "Override the global settings for this app",
                        enabled = editable,
                        trailing = {
                            GZSwitch(
                                checked = hasCustom,
                                enabled = editable,
                                onCheckedChange = {
                                    hasCustom = it
                                    // Persist what the sliders show; otherwise the engine falls back to fixed defaults.
                                    prefs.edit()
                                        .putBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + pkg, it)
                                        .putInt(PrefsKeys.PER_APP_WAIT_SECONDS + pkg, waitSeconds)
                                        .putInt(PrefsKeys.PER_APP_SESSION_MINUTES + pkg, sessionMinutes)
                                        .putInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + pkg, lockoutMinutes)
                                        .apply()
                                }
                            )
                        }
                    )
                    RowDivider()
                    LabeledSlider(
                        label = "Wait",
                        valueText = "${waitSeconds}s",
                        help = "Pause before you can continue",
                        value = waitSeconds.toFloat(),
                        onValueChange = { waitSeconds = it.roundToInt() },
                        valueRange = 3f..30f,
                        steps = 26,
                        enabled = slidersEnabled,
                        onValueChangeFinished = {
                            prefs.edit().putInt(PrefsKeys.PER_APP_WAIT_SECONDS + pkg, waitSeconds).apply()
                        }
                    )
                    RowDivider()
                    LabeledSlider(
                        label = "Session",
                        valueText = formatMinutes(sessionMinutes),
                        help = "How long you can use it before it locks",
                        value = sessionMinutes.toFloat(),
                        onValueChange = {
                            sessionMinutes = it.roundToInt()
                            if (sessionMinutes > lockoutMinutes) lockoutMinutes = sessionMinutes
                        },
                        valueRange = 1f..60f,
                        steps = 58,
                        enabled = slidersEnabled,
                        onValueChangeFinished = {
                            prefs.edit()
                                .putInt(PrefsKeys.PER_APP_SESSION_MINUTES + pkg, sessionMinutes)
                                .putInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + pkg, lockoutMinutes)
                                .apply()
                        }
                    )
                    RowDivider()
                    LabeledSlider(
                        label = "Lockout",
                        valueText = formatMinutes(lockoutMinutes),
                        help = "How long it stays locked after a session",
                        value = lockoutMinutes.toFloat(),
                        onValueChange = {
                            lockoutMinutes = it.roundToInt()
                            if (sessionMinutes > lockoutMinutes) sessionMinutes = lockoutMinutes
                        },
                        valueRange = 15f..1440f,
                        steps = 94,
                        enabled = slidersEnabled,
                        onValueChangeFinished = {
                            prefs.edit()
                                .putInt(PrefsKeys.PER_APP_SESSION_MINUTES + pkg, sessionMinutes)
                                .putInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + pkg, lockoutMinutes)
                                .apply()
                        }
                    )
                }
            }

            GZCard(modifier = Modifier.padding(horizontal = GZGutter)) {
                LabeledSlider(
                    label = "Daily budget",
                    valueText = if (dailyBudget == 0) "Unlimited" else formatMinutes(dailyBudget),
                    help = "Total time allowed per day, whatever the limits above",
                    value = dailyBudget.toFloat(),
                    onValueChange = { dailyBudget = it.roundToInt() },
                    valueRange = 0f..240f,
                    steps = 47,
                    enabled = editable,
                    onValueChangeFinished = {
                        prefs.edit().putInt(PrefsKeys.DAILY_BUDGET_MINUTES + pkg, dailyBudget).apply()
                    }
                )
            }

            GZCard(modifier = Modifier.padding(horizontal = GZGutter)) {
                SettingsRow(
                    title = "Block for 24 hours",
                    subtitle = "Lock ${app.name} right now. This can't be undone.",
                    icon = Icons.Filled.Block,
                    onClick = { confirmBlock = true }
                )
            }
        }
    }

    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text("Block ${app.name} for 24 hours?") },
            text = { Text("It stays locked until this time tomorrow. There is no way to unlock it early.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = false
                    val lockoutMs = 24 * 60 * 60 * 1000L
                    val until = System.currentTimeMillis() + lockoutMs
                    // Any running or banked session is retired, otherwise the policy engine would
                    // still wave the user through on its active-session gate.
                    prefs.edit()
                        .putLong(PrefsKeys.LOCKED_UNTIL + pkg, until)
                        .putLong(PrefsKeys.LOCKED_STARTED_ELAPSED + pkg, android.os.SystemClock.elapsedRealtime())
                        .putLong(PrefsKeys.LOCKED_DURATION_MS + pkg, lockoutMs)
                        .remove(PrefsKeys.ACTIVE_UNTIL + pkg)
                        .remove(PrefsKeys.ACTIVE_STARTED_ELAPSED + pkg)
                        .remove(PrefsKeys.ACTIVE_DURATION_MS + pkg)
                        .remove(PrefsKeys.REMAINING_MILLIS + pkg)
                        .apply()
                    onDismiss()
                }) { Text("Block", color = GZRed, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBlock = false }) { Text("Cancel", color = GZTextSecondary) }
            },
            containerColor = GZSurface,
            titleContentColor = GZTextPrimary,
            textContentColor = GZTextSecondary,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

package com.grayzone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.ResumedInterval
import com.grayzone.app.data.ScheduleManager
import com.grayzone.app.ui.components.*
import com.grayzone.app.ui.theme.*

@Composable
fun QuickFocusCard() {
    val context = LocalContext.current
    val scheduleManager = remember { ScheduleManager(context) }
    val gate = LocalPermissionGate.current

    var isFocusMode by remember { mutableStateOf(scheduleManager.isFocusModeActive()) }
    var focusRemainingMillis by remember { mutableStateOf(scheduleManager.getFocusModeRemainingMillis()) }

    // Countdown only ticks while focus mode is active AND the screen is visible.
    ResumedInterval(enabled = isFocusMode, intervalMs = 1000L) {
        focusRemainingMillis = scheduleManager.getFocusModeRemainingMillis()
        if (focusRemainingMillis <= 0) {
            isFocusMode = false
            false
        } else {
            true
        }
    }

    var selectedMinutes by remember { mutableIntStateOf(30) }
    var confirmStop by remember { mutableStateOf(false) }

    if (confirmStop) {
        EndDeepWorkDialog(
            onConfirm = {
                confirmStop = false
                scheduleManager.stopFocusMode()
                isFocusMode = false
            },
            onDismiss = { confirmStop = false }
        )
    }

    GZCard(modifier = Modifier.padding(horizontal = GZGutter)) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Timer, tint = GZPrimary)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Deep Work", color = GZTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    if (isFocusMode) {
                        val mins = focusRemainingMillis / 60_000
                        val secs = (focusRemainingMillis / 1000) % 60
                        Text(
                            "All monitored apps locked · ${mins}m ${secs.toString().padStart(2, '0')}s left",
                            color = GZPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Text("Lock every monitored app for a while", color = GZTextSecondary, fontSize = 13.sp)
                    }
                }
                if (isFocusMode) {
                    GZSmallButton(
                        text = "End",
                        onClick = { confirmStop = true },
                        containerColor = GZRedContainer,
                        contentColor = GZRed
                    )
                }
            }
            if (!isFocusMode) {
                Spacer(Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(15, 30, 60).forEach { mins ->
                        val selected = selectedMinutes == mins
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) GZPrimaryContainer else GZSurfaceElevated)
                                .border(
                                    1.dp,
                                    if (selected) GZPrimary.copy(alpha = 0.4f) else GZBorderSubtle,
                                    RoundedCornerShape(10.dp)
                                )
                                .selectable(
                                    selected = selected,
                                    role = androidx.compose.ui.semantics.Role.RadioButton,
                                    onClick = { selectedMinutes = mins }
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "${mins}m",
                                color = if (selected) GZPrimary else GZTextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    GZSmallButton(
                        text = "Start",
                        onClick = {
                            gate.requireSetup {
                                scheduleManager.startFocusMode(selectedMinutes)
                                isFocusMode = true
                                focusRemainingMillis = scheduleManager.getFocusModeRemainingMillis()
                            }
                        }
                    )
                }
            }
        }
    }
}

/** Ending Deep Work early is allowed, but only after a deliberate second tap. */
@Composable
fun EndDeepWorkDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("End Deep Work early?") },
        text = { Text("Your monitored apps unlock right away. The time you planned to focus isn't over yet.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("End now", color = GZRed, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep going", color = GZPrimary, fontWeight = FontWeight.SemiBold) }
        },
        containerColor = GZSurface,
        titleContentColor = GZTextPrimary,
        textContentColor = GZTextSecondary,
        shape = RoundedCornerShape(24.dp)
    )
}
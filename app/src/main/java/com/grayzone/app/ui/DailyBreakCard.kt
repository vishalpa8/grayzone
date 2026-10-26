package com.grayzone.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FreeBreakfast
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.ResumedInterval
import com.grayzone.app.data.ScheduleManager
import com.grayzone.app.ui.components.*
import com.grayzone.app.ui.theme.*

/**
 * One-hour daily break: while active, nothing is locked — no friction screens,
 * no lockouts, no schedule blocks. Usable once per calendar day.
 */
@Composable
fun DailyBreakCard() {
    val context = LocalContext.current
    val scheduleManager = remember { ScheduleManager(context) }

    var isBreakActive by remember { mutableStateOf(scheduleManager.isBreakActive()) }
    var canStartToday by remember { mutableStateOf(scheduleManager.canStartBreakToday()) }
    var remainingMillis by remember { mutableStateOf(scheduleManager.getBreakRemainingMillis()) }

    // Countdown only ticks while the break is active AND the screen is visible.
    ResumedInterval(enabled = isBreakActive, intervalMs = 1000L) {
        remainingMillis = scheduleManager.getBreakRemainingMillis()
        if (remainingMillis <= 0) {
            isBreakActive = false
            canStartToday = scheduleManager.canStartBreakToday()
            false
        } else {
            true
        }
    }

    // startDailyBreak() refuses while any app is locked or mid-session, so say so instead of a dead button.
    val prefs = remember { context.getSharedPreferences(com.grayzone.app.PrefsKeys.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val now by com.grayzone.app.rememberResumedTicker(5_000L)
    val blockedByLock = remember(now, isBreakActive) {
        !isBreakActive && com.grayzone.app.isAnyAppLocked(
            prefs,
            prefs.getStringSet(com.grayzone.app.PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet(),
            now
        )
    }

    GZCard(modifier = Modifier.padding(horizontal = GZGutter)) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(Icons.Filled.FreeBreakfast, tint = if (canStartToday || isBreakActive) GZGreen else GZTextTertiary)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Daily Break", color = GZTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                when {
                    isBreakActive -> {
                        val mins = remainingMillis / 60_000
                        val secs = (remainingMillis / 1000) % 60
                        Text(
                            "Everything unlocked · ${mins}m ${secs.toString().padStart(2, '0')}s left",
                            color = GZGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    canStartToday && blockedByLock ->
                        Text("Available once no app is locked or in a session", color = GZTextSecondary, fontSize = 13.sp)
                    canStartToday -> Text("Unlock everything for 1 hour, once a day", color = GZTextSecondary, fontSize = 13.sp)
                    else -> Text("Used today · resets at midnight", color = GZTextSecondary, fontSize = 13.sp)
                }
            }
            when {
                isBreakActive -> GZSmallButton(
                    text = "End",
                    onClick = {
                        scheduleManager.stopBreak()
                        isBreakActive = false
                        canStartToday = scheduleManager.canStartBreakToday()
                    },
                    containerColor = GZRedContainer,
                    contentColor = GZRed
                )
                canStartToday && !blockedByLock -> GZSmallButton(
                    text = "Start",
                    onClick = {
                        if (scheduleManager.startDailyBreak()) {
                            isBreakActive = true
                            canStartToday = false
                            remainingMillis = scheduleManager.getBreakRemainingMillis()
                        }
                    },
                    containerColor = GZGreenContainer,
                    contentColor = GZGreen
                )
            }
        }
    }
}

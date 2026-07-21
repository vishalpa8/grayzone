package com.grayzone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FreeBreakfast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.GZCard
import com.grayzone.app.data.ScheduleManager
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.delay

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

    LaunchedEffect(isBreakActive) {
        if (isBreakActive) {
            while (true) {
                remainingMillis = scheduleManager.getBreakRemainingMillis()
                if (remainingMillis <= 0) {
                    isBreakActive = false
                    canStartToday = scheduleManager.canStartBreakToday()
                    break
                }
                delay(2000)
            }
        }
    }

    GZCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GZGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.FreeBreakfast,
                    contentDescription = null,
                    tint = GZGreen,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                when {
                    isBreakActive -> {
                        val remainingMins = remainingMillis / 1000 / 60
                        val remainingSecs = (remainingMillis / 1000) % 60
                        Text("Break Active", color = GZTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Everything unlocked · $remainingMins m $remainingSecs s left",
                            color = GZGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                    canStartToday -> {
                        Text("Daily Break", color = GZTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Unlock everything for 1 hour · once per day",
                            color = GZTextSecondary, fontSize = 13.sp
                        )
                    }
                    else -> {
                        Text("Daily Break", color = GZTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Used for today · resets at midnight",
                            color = GZTextTertiary, fontSize = 13.sp
                        )
                    }
                }
            }

            if (isBreakActive) {
                Button(
                    onClick = {
                        scheduleManager.stopBreak()
                        isBreakActive = false
                        canStartToday = scheduleManager.canStartBreakToday()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GZRed.copy(alpha = 0.1f), contentColor = GZRed),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("End", fontWeight = FontWeight.Bold)
                }
            } else if (canStartToday) {
                Button(
                    onClick = {
                        if (scheduleManager.startDailyBreak()) {
                            isBreakActive = true
                            canStartToday = false
                            remainingMillis = scheduleManager.getBreakRemainingMillis()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GZGreen, contentColor = Color(0xFF0D2B24)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Start", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

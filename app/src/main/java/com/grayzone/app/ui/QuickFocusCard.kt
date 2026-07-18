package com.grayzone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
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

@Composable
fun QuickFocusCard() {
    val context = LocalContext.current
    val scheduleManager = remember { ScheduleManager(context) }
    
    var isFocusMode by remember { mutableStateOf(scheduleManager.isFocusModeActive()) }
    var focusRemainingMillis by remember { mutableStateOf(scheduleManager.getFocusModeRemainingMillis()) }
    
    LaunchedEffect(isFocusMode) {
        if (isFocusMode) {
            while (true) {
                focusRemainingMillis = scheduleManager.getFocusModeRemainingMillis()
                if (focusRemainingMillis <= 0) {
                    isFocusMode = false
                    break
                }
                delay(1000)
            }
        }
    }

    var selectedMinutes by remember { mutableStateOf(30) }

    GZCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GZAccent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Timer, contentDescription = null, tint = GZAccent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                if (isFocusMode) {
                    val remainingMins = focusRemainingMillis / 1000 / 60
                    val remainingSecs = (focusRemainingMillis / 1000) % 60
                    Text("Focus Mode Active", color = GZTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("$remainingMins m $remainingSecs s left", color = GZAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Text("Deep Work", color = GZTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Lock all apps for $selectedMinutes mins", color = GZTextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60).forEach { mins ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selectedMinutes == mins) GZAccent else GZSurfaceHigh)
                                    .clickable { selectedMinutes = mins }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("${mins}m", color = if (selectedMinutes == mins) Color.White else GZTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            
            if (isFocusMode) {
                Button(
                    onClick = {
                        scheduleManager.stopFocusMode()
                        isFocusMode = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GZRed.copy(alpha = 0.1f), contentColor = GZRed),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Stop", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {
                        scheduleManager.startFocusMode(selectedMinutes)
                        isFocusMode = true
                        focusRemainingMillis = scheduleManager.getFocusModeRemainingMillis()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GZAccent, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Start", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

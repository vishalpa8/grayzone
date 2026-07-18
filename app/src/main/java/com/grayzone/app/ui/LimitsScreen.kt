package com.grayzone.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.*
import com.grayzone.app.ui.theme.*
import androidx.compose.animation.core.animateFloat

@Composable
fun LimitsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var monitoredApps by remember {
        mutableStateOf(prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet())
    }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        installedApps = getInstalledApps(context)
    }

    // Ticker for live lock detection
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    val monitoredList = remember(installedApps, monitoredApps) {
        installedApps.filter { monitoredApps.contains(it.packageName) }
            .sortedBy { it.name.lowercase() }
    }

    Column(modifier = Modifier.fillMaxSize().background(GZBackground)) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(GZPrimary, GZPrimaryContainer, GZBackground)))
                .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 24.dp)
        ) {
            Text("Limits Dashboard", color = GZTextPrimary, fontSize = 28.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            Text(
                if (monitoredList.isEmpty()) "No apps are being monitored."
                else "Active limits and timers for your monitored apps.",
                color = if (monitoredList.isEmpty()) GZTextSecondary else GZPrimaryLight,
                fontSize = 14.sp
            )
        }

        if (monitoredList.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("You are not monitoring any apps.", color = GZTextTertiary, fontSize = 16.sp)
            }
        } else {
            val lockStateMap = remember(monitoredApps, currentTime) {
                monitoredApps.associateWith { pkg ->
                    AppLockState(
                        activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L),
                        lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L),
                        remainingMillis = prefs.getLong(PrefsKeys.REMAINING_MILLIS + pkg, 0L)
                    )
                }
            }

            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(monitoredList, key = { it.packageName }) { app ->
                    val state = lockStateMap[app.packageName] ?: AppLockState(0L, 0L, 0L)
                    val activeUntil = state.activeUntil
                    val lockedUntil = state.lockedUntil
                    val remaining = state.remainingMillis
                    
                    val isAppLocked = currentTime > activeUntil && currentTime < lockedUntil
                    val isActive = currentTime < activeUntil
                    val isPaused = remaining > 0

                    val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + app.packageName, false)
                    val sessionMins = if (hasCustom) prefs.getInt(PrefsKeys.PER_APP_SESSION_MINUTES + app.packageName, 10) else prefs.getInt(PrefsKeys.SESSION_MINUTES, 10)
                    val lockoutMins = if (hasCustom) prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + app.packageName, 60) else prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)

                    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            animation = androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.LinearEasing),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                        )
                    )

                    GZCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(app.name, color = GZTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(modifier = Modifier.weight(1f))
                                if (isAppLocked) {
                                    val remainingMins = ((lockedUntil - currentTime) / (60 * 1000)).coerceAtLeast(1)
                                    Text("🔒 Locked (${remainingMins}m)", color = GZRed.copy(alpha = pulseAlpha), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                } else if (isActive) {
                                    val remainingMins = ((activeUntil - currentTime) / (60 * 1000)).coerceAtLeast(1)
                                    Text("⏳ Active (${remainingMins}m)", color = GZGreen.copy(alpha = pulseAlpha), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                } else if (isPaused) {
                                    val remainingMins = (remaining / (60 * 1000)).coerceAtLeast(1)
                                    Text("⏸ Paused (${remainingMins}m)", color = GZAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Text("Ready", color = GZTextSecondary, fontSize = 12.sp)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Session Pill
                                Row(
                                    modifier = Modifier
                                        .background(GZPrimary.copy(alpha = 0.15f), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Timer, contentDescription = null, tint = GZPrimaryLight, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Session: ${sessionMins}m", color = GZTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                                
                                Spacer(Modifier.width(12.dp))
                                
                                // Lockout Pill
                                Row(
                                    modifier = Modifier
                                        .background(GZRed.copy(alpha = 0.15f), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Timer, contentDescription = null, tint = GZRed, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Lockout: ${lockoutMins}m", color = GZTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

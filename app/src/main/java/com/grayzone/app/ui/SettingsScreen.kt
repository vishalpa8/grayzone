package com.grayzone.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.grayzone.app.PrefsKeys
import com.grayzone.app.ui.theme.*
import com.grayzone.app.isAnyAppLocked
import com.grayzone.app.shareApk
import com.grayzone.app.GZCard

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    // BUG 6 FIX: Wrap in remember so SharedPreferences is not re-obtained on every recomposition.
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }

    var grayscaleEnabled by remember { mutableStateOf(prefs.getBoolean(PrefsKeys.GRAYSCALE_ENABLED, true)) }
    var waitSeconds by remember { mutableStateOf(prefs.getInt(PrefsKeys.WAIT_SECONDS, 5)) }
    var sessionMinutes by remember { mutableStateOf(prefs.getInt(PrefsKeys.SESSION_MINUTES, 10)) }
    var lockoutMinutes by remember { mutableStateOf(prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)) }
    
    var showCustomPrompts by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }

    // BUG FIX: Block settings changes if any app is active, paused, or locked out
    var monitoredApps by remember { mutableStateOf(prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()) }
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

    Column(modifier = Modifier.fillMaxSize().background(GZBackground).padding(24.dp).verticalScroll(rememberScrollState())) {
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
                    Icon(Icons.Filled.Share, contentDescription = "Share App", tint = GZPrimary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share", color = GZPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        
        if (anyAppLockedOrActive) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(GZRed.copy(alpha = 0.1f)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔒", fontSize = 16.sp)
                Spacer(Modifier.width(12.dp))
                Text("Settings are locked while an app is active, paused, or in timeout.", color = GZRed, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        
        Spacer(Modifier.height(32.dp))
        
        // Grayscale toggle
        GZCard {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Grayscale Effect", color = GZTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Apply grayscale filter on monitored apps", color = GZTextSecondary, fontSize = 12.sp)
                }
                Switch(
                    checked = grayscaleEnabled,
                    onCheckedChange = {
                        if (!anyAppLockedOrActive) {
                            grayscaleEnabled = it
                            prefs.edit().putBoolean(PrefsKeys.GRAYSCALE_ENABLED, it).apply()
                        }
                    },
                    enabled = !anyAppLockedOrActive,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = GZPrimary,
                        uncheckedThumbColor = GZTextTertiary,
                        uncheckedTrackColor = GZSurfaceHigh
                    )
                )
            }
        }
        
        Spacer(Modifier.height(24.dp))
        
        Text("Wait Duration: $waitSeconds seconds", color = GZTextPrimary, fontWeight = FontWeight.Medium)
        Slider(
            value = waitSeconds.toFloat(),
            onValueChange = { waitSeconds = it.toInt() },
            valueRange = 3f..30f,
            steps = 26,
            enabled = !anyAppLockedOrActive,
            onValueChangeFinished = { prefs.edit().putInt(PrefsKeys.WAIT_SECONDS, waitSeconds).apply() }
        )
        
        Spacer(Modifier.height(24.dp))
        
        Text("Session Limit: $sessionMinutes minutes", color = GZTextPrimary, fontWeight = FontWeight.Medium)
        Text("How long you can use an app after unlocking.", color = GZTextSecondary, fontSize = 12.sp)
        Slider(
            value = sessionMinutes.toFloat(),
            onValueChange = {
                sessionMinutes = it.toInt().coerceAtMost(lockoutMinutes)
            },
            valueRange = 1f..60f,
            steps = 58,
            enabled = !anyAppLockedOrActive,
            // BUG 13 FIX: Only save session_minutes — session slider can no longer mutate lockoutMinutes.
            onValueChangeFinished = {
                prefs.edit().putInt(PrefsKeys.SESSION_MINUTES, sessionMinutes).apply()
            }
        )
        
        Spacer(Modifier.height(24.dp))
        
        val lockoutHours = lockoutMinutes / 60
        val lockoutMins = lockoutMinutes % 60
        val lockoutText = if (lockoutHours > 0) "${lockoutHours}h ${lockoutMins}m" else "${lockoutMins}m"
        
        Text("Lockout Duration: $lockoutText", color = GZTextPrimary, fontWeight = FontWeight.Medium)
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
        
        Spacer(Modifier.height(32.dp))
        
        // Custom Prompts Button
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
        
        Spacer(Modifier.height(16.dp))
        
        // Schedule Button
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
        
        Spacer(Modifier.height(64.dp)) // padding at bottom
    }
    
    if (showCustomPrompts) {
        CustomPromptsSheet(onDismiss = { showCustomPrompts = false })
    }
    
    if (showSchedule) {
        ScheduleSheet(onDismiss = { showSchedule = false })
    }
}

package com.grayzone.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.AppInfo
import com.grayzone.app.PrefsKeys
import com.grayzone.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppSettingsSheet(
    app: AppInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    
    val pkg = app.packageName
    
    var hasCustom by remember { mutableStateOf(prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + pkg, false)) }
    
    // Global defaults
    val globalWait = remember { prefs.getInt(PrefsKeys.WAIT_SECONDS, 5) }
    val globalSession = remember { prefs.getInt(PrefsKeys.SESSION_MINUTES, 10) }
    val globalLockout = remember { prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60) }
    
    var waitSeconds by remember { mutableStateOf(prefs.getInt(PrefsKeys.PER_APP_WAIT_SECONDS + pkg, globalWait)) }
    var sessionMinutes by remember { mutableStateOf(prefs.getInt(PrefsKeys.PER_APP_SESSION_MINUTES + pkg, globalSession)) }
    var lockoutMinutes by remember { mutableStateOf(prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + pkg, globalLockout)) }
    var dailyBudget by remember { mutableStateOf(prefs.getInt(PrefsKeys.DAILY_BUDGET_MINUTES + pkg, 0)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = GZSurfaceElevated,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Settings for ${app.name}",
                color = GZTextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(24.dp))
            
            // Custom Override Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use Custom Limits", color = GZTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Override global settings for this app", color = GZTextSecondary, fontSize = 13.sp)
                }
                Switch(
                    checked = hasCustom,
                    onCheckedChange = { 
                        hasCustom = it
                        prefs.edit().putBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + pkg, it).apply()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = GZPrimary,
                        uncheckedThumbColor = GZTextTertiary,
                        uncheckedTrackColor = GZSurfaceHigh
                    )
                )
            }
            
            Spacer(Modifier.height(24.dp))
            Divider(color = GZBorder, thickness = 0.5.dp)
            Spacer(Modifier.height(24.dp))
            
            val slidersEnabled = hasCustom
            
            // Wait Duration
            Text("Wait Duration: $waitSeconds seconds", color = if (slidersEnabled) GZTextPrimary else GZTextTertiary, fontWeight = FontWeight.Medium)
            Slider(
                value = waitSeconds.toFloat(),
                onValueChange = { waitSeconds = it.toInt() },
                valueRange = 3f..30f,
                steps = 26,
                enabled = slidersEnabled,
                onValueChangeFinished = { prefs.edit().putInt(PrefsKeys.PER_APP_WAIT_SECONDS + pkg, waitSeconds).apply() }
            )
            
            Spacer(Modifier.height(24.dp))
            
            // Session Limit
            Text("Session Limit: $sessionMinutes minutes", color = if (slidersEnabled) GZTextPrimary else GZTextTertiary, fontWeight = FontWeight.Medium)
            Slider(
                value = sessionMinutes.toFloat(),
                onValueChange = { sessionMinutes = it.toInt().coerceAtMost(lockoutMinutes) },
                valueRange = 1f..60f,
                steps = 58,
                enabled = slidersEnabled,
                onValueChangeFinished = { prefs.edit().putInt(PrefsKeys.PER_APP_SESSION_MINUTES + pkg, sessionMinutes).apply() }
            )
            
            Spacer(Modifier.height(24.dp))
            
            // Lockout Duration
            val lockoutHours = lockoutMinutes / 60
            val lockoutMins = lockoutMinutes % 60
            val lockoutText = if (lockoutHours > 0) "${lockoutHours}h ${lockoutMins}m" else "${lockoutMins}m"
            Text("Lockout Duration: $lockoutText", color = if (slidersEnabled) GZTextPrimary else GZTextTertiary, fontWeight = FontWeight.Medium)
            Slider(
                value = lockoutMinutes.toFloat(),
                onValueChange = { 
                    lockoutMinutes = it.toInt()
                    if (sessionMinutes > lockoutMinutes) sessionMinutes = lockoutMinutes
                },
                valueRange = 15f..300f,
                steps = 284,
                enabled = slidersEnabled,
                onValueChangeFinished = { 
                    prefs.edit()
                        .putInt(PrefsKeys.PER_APP_SESSION_MINUTES + pkg, sessionMinutes)
                        .putInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + pkg, lockoutMinutes)
                        .apply() 
                }
            )
            
            Spacer(Modifier.height(24.dp))
            Divider(color = GZBorder, thickness = 0.5.dp)
            Spacer(Modifier.height(24.dp))
            
            // Daily Budget
            val budgetText = if (dailyBudget == 0) "Unlimited" else {
                val bh = dailyBudget / 60
                val bm = dailyBudget % 60
                if (bh > 0) "${bh}h ${bm}m" else "${bm}m"
            }
            Text("Daily Budget: $budgetText", color = GZTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Total allowed usage time per day. Independent of custom limits switch.", color = GZTextSecondary, fontSize = 13.sp)
            Slider(
                value = dailyBudget.toFloat(),
                onValueChange = { dailyBudget = it.toInt() },
                valueRange = 0f..240f, // up to 4 hours
                steps = 47, // intervals of 5 minutes
                onValueChangeFinished = { prefs.edit().putInt(PrefsKeys.DAILY_BUDGET_MINUTES + pkg, dailyBudget).apply() },
                colors = SliderDefaults.colors(
                    thumbColor = GZAccent,
                    activeTrackColor = GZAccent,
                    inactiveTrackColor = GZSurfaceHigh
                )
            )
        }
    }
}

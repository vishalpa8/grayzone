package com.grayzone.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.grayzone.app.PrefsKeys
import com.grayzone.app.ui.theme.*

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    var hasA11y by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasA11y = isAccessibilityServiceEnabled(context)
                hasOverlay = Settings.canDrawOverlays(context)
                if (hasA11y && hasOverlay) onContinue()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize().background(GZBackground).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Welcome to Grayzone", fontSize = 28.sp, color = GZTextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("To provide friction, Grayzone needs two permissions:", color = GZTextSecondary)
        Spacer(Modifier.height(32.dp))
        Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (hasA11y) GZGreen else GZPrimary)) {
            Text(if (hasA11y) "Accessibility Granted" else "Grant Accessibility")
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (hasOverlay) GZGreen else GZPrimary)) {
            Text(if (hasOverlay) "Overlay Granted" else "Grant Overlay Permission")
        }
        
        if (hasA11y && hasOverlay) {
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onContinue, 
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GZPrimaryLight)
            ) {
                Text("Continue to App", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    // BUG 6 FIX: Wrap in remember so SharedPreferences is not re-obtained on every recomposition.
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }

    var grayscaleEnabled by remember { mutableStateOf(prefs.getBoolean(PrefsKeys.GRAYSCALE_ENABLED, true)) }
    var waitSeconds by remember { mutableStateOf(prefs.getInt(PrefsKeys.WAIT_SECONDS, 8)) }
    var sessionMinutes by remember { mutableStateOf(prefs.getInt(PrefsKeys.SESSION_MINUTES, 10)) }
    var lockoutMinutes by remember { mutableStateOf(prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 30)) }

    // BUG FIX: Block settings changes if any app is active, paused, or locked out
    val monitoredApps = remember { prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet() }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }
    val anyAppLockedOrActive = remember(monitoredApps, currentTime) {
        monitoredApps.any { pkg ->
            val activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L)
            val lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L)
            val remaining = prefs.getLong(PrefsKeys.REMAINING_MILLIS + pkg, 0L)
            (currentTime < activeUntil) || (currentTime in (activeUntil + 1)..<lockedUntil) || (remaining > 0)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(GZBackground).padding(24.dp)) {
        Text("Settings", fontSize = 28.sp, color = GZTextPrimary, fontWeight = FontWeight.Bold)
        
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
                        grayscaleEnabled = it
                        prefs.edit().putBoolean(PrefsKeys.GRAYSCALE_ENABLED, it).apply()
                    },
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
            valueRange = 15f..300f,
            steps = 284,
            enabled = !anyAppLockedOrActive,
            onValueChangeFinished = {
                prefs.edit()
                    .putInt(PrefsKeys.SESSION_MINUTES, sessionMinutes)
                    .putInt(PrefsKeys.LOCKOUT_MINUTES, lockoutMinutes)
                    .apply()
            }
        )
    }
}

@Composable
fun ActivePill(isActive: Boolean) {
    Row(modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (isActive) GZGreenContainer else GZRedContainer).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (isActive) GZGreen else GZRed))
        Spacer(Modifier.width(6.dp))
        Text(if (isActive) "Active" else "Inactive", color = if (isActive) GZGreen else GZRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GZCard(modifier: Modifier = Modifier, background: Color = GZSurface, border: Color = GZBorder, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(background).border(1.dp, border, RoundedCornerShape(16.dp))) { content() }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = GZTextTertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
fun HowItWorksStep(number: String, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Text(number, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = GZTextSecondary, fontSize = 14.sp)
    }
}

@Composable
fun GZLoadingSpinner(modifier: Modifier = Modifier, size: Dp = 40.dp, color: Color = GZPrimary) {
    val infiniteTransition = rememberInfiniteTransition(label = "spinner")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1000, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "angle"
    )
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = 4.dp.toPx()
        drawArc(color = color.copy(alpha = 0.2f), startAngle = 0f, sweepAngle = 360f, useCenter = false, style = Stroke(width = strokeWidth))
        drawArc(color = color, startAngle = angle, sweepAngle = 90f, useCenter = false, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
    }
}

@Composable
fun LimitsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }

    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var monitoredApps by remember { mutableStateOf(prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()) }
    var isLoading by remember { mutableStateOf(true) }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        installedApps = getInstalledApps(context)
        isLoading = false
    }
    
    // Ticker for live countdown updates
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    val limitsList = remember(installedApps, monitoredApps) {
        installedApps.filter { it.packageName in monitoredApps }
    }

    Column(modifier = Modifier.fillMaxSize().background(GZBackground)) {
        // Header
        Column(modifier = Modifier.fillMaxWidth().background(GZSurface).padding(24.dp, 32.dp, 24.dp, 24.dp)) {
            Text("Limits Status", fontSize = 28.sp, color = GZTextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Live status of your monitored apps.", color = GZTextSecondary, fontSize = 14.sp)
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GZLoadingSpinner(color = GZPrimary)
            }
        } else if (limitsList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No apps monitored.", color = GZTextTertiary)
            }
        } else {
            // BUG 5 FIX: Build a single snapshot map of all per-app timestamps once per tick,
            // instead of calling prefs.getLong() inside each LazyColumn item (blocking main thread per item).
            val appStateMap = remember(monitoredApps, currentTime) {
                monitoredApps.associateWith { pkg ->
                    Triple(
                        prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L),
                        prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L),
                        prefs.getLong(PrefsKeys.REMAINING_MILLIS + pkg, 0L)
                    )
                }
            }

            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(limitsList.size) { index ->
                    val app = limitsList[index]
                    val (activeUntil, lockedUntil, remainingPaused) = appStateMap[app.packageName] ?: Triple(0L, 0L, 0L)

                    val isLocked = currentTime < lockedUntil && currentTime > activeUntil
                    val isActive = currentTime < activeUntil
                    val isPaused = remainingPaused > 0
                    
                    val statusText = if (isActive) {
                        val remaining = ((activeUntil - currentTime) / 1000).coerceAtLeast(0).toInt()
                        "Active: ${formatTimeRemaining(remaining)} left"
                    } else if (isPaused) {
                        "Paused: ${formatTimeRemaining((remainingPaused / 1000).toInt())} left"
                    } else if (isLocked) {
                        val remaining = ((lockedUntil - currentTime) / 1000).coerceAtLeast(0).toInt()
                        "Locked: ${formatTimeRemaining(remaining)} left"
                    } else {
                        "Available"
                    }
                    
                    val statusColor = if (isActive) GZGreen else if (isPaused) GZTextPrimary else if (isLocked) GZRed else GZTextTertiary
                    val bgColor = if (isActive) GZGreen.copy(alpha = 0.05f) else if (isPaused) GZTextPrimary.copy(alpha = 0.05f) else if (isLocked) GZRed.copy(alpha = 0.05f) else GZSurface

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(bgColor)
                            .border(1.dp, if (isActive) GZGreen.copy(alpha=0.3f) else if (isLocked) GZRed.copy(alpha=0.3f) else GZBorder, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (app.icon != null) {
                            androidx.compose.foundation.Image(
                                bitmap = app.icon.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                            )
                        } else {
                            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(GZBorder))
                        }
                        
                        Spacer(Modifier.width(16.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.name, color = GZTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(statusText, color = statusColor, fontSize = 14.sp, fontWeight = if (isActive || isLocked) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimeRemaining(seconds: Int): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) String.format("%dh %dm %ds", hrs, mins, secs)
    else String.format("%dm %ds", mins, secs)
}

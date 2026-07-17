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
        Button(onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:\${context.packageName}"))) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (hasOverlay) GZGreen else GZPrimary)) {
            Text(if (hasOverlay) "Overlay Granted" else "Grant Overlay Permission")
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("GrayzonePrefs", Context.MODE_PRIVATE)
    
    var waitSeconds by remember { mutableStateOf(prefs.getInt("wait_seconds", 8)) }
    var sessionMinutes by remember { mutableStateOf(prefs.getInt("session_minutes", 10)) }
    var lockoutMinutes by remember { mutableStateOf(prefs.getInt("lockout_minutes", 30)) }

    Column(modifier = Modifier.fillMaxSize().background(GZBackground).padding(24.dp)) {
        Text("Settings", fontSize = 28.sp, color = GZTextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp))
        
        Text("Wait Duration: $waitSeconds seconds", color = GZTextPrimary, fontWeight = FontWeight.Medium)
        Slider(
            value = waitSeconds.toFloat(), 
            onValueChange = { waitSeconds = it.toInt() }, 
            valueRange = 3f..30f, 
            steps = 27, 
            onValueChangeFinished = { prefs.edit().putInt("wait_seconds", waitSeconds).apply() }
        )
        
        Spacer(Modifier.height(24.dp))
        
        Text("Session Limit: $sessionMinutes minutes", color = GZTextPrimary, fontWeight = FontWeight.Medium)
        Text("How long you can use an app after unlocking.", color = GZTextSecondary, fontSize = 12.sp)
        Slider(
            value = sessionMinutes.toFloat(), 
            onValueChange = { sessionMinutes = it.toInt() }, 
            valueRange = 1f..60f, 
            steps = 59, 
            onValueChangeFinished = { prefs.edit().putInt("session_minutes", sessionMinutes).apply() }
        )
        
        Spacer(Modifier.height(24.dp))
        
        val lockoutHours = lockoutMinutes / 60
        val lockoutMins = lockoutMinutes % 60
        val lockoutText = if (lockoutHours > 0) "${lockoutHours}h ${lockoutMins}m" else "${lockoutMins}m"
        
        Text("Lockout Duration: $lockoutText", color = GZTextPrimary, fontWeight = FontWeight.Medium)
        Text("How long the app remains locked after your session expires.", color = GZTextSecondary, fontSize = 12.sp)
        Slider(
            value = lockoutMinutes.toFloat(), 
            onValueChange = { lockoutMinutes = it.toInt() }, 
            valueRange = 15f..300f, 
            steps = 285, 
            onValueChangeFinished = { prefs.edit().putInt("lockout_minutes", lockoutMinutes).apply() }
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
    val prefs = remember { context.getSharedPreferences("GrayzonePrefs", Context.MODE_PRIVATE) }
    
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var monitoredApps by remember { mutableStateOf(prefs.getStringSet("monitored_apps", emptySet()) ?: emptySet()) }
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
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(limitsList.size) { index ->
                    val app = limitsList[index]
                    val activeUntil = prefs.getLong("active_until_${app.packageName}", 0L)
                    val lockedUntil = prefs.getLong("locked_until_${app.packageName}", 0L)
                    
                    val isLocked = currentTime < lockedUntil && currentTime > activeUntil
                    val isActive = currentTime < activeUntil
                    
                    val statusText = if (isActive) {
                        val remaining = ((activeUntil - currentTime) / 1000).coerceAtLeast(0).toInt()
                        "Active: ${formatTimeRemaining(remaining)} left"
                    } else if (isLocked) {
                        val remaining = ((lockedUntil - currentTime) / 1000).coerceAtLeast(0).toInt()
                        "Locked: ${formatTimeRemaining(remaining)} left"
                    } else {
                        "Available"
                    }
                    
                    val statusColor = if (isActive) GZGreen else if (isLocked) GZRed else GZTextTertiary
                    val bgColor = if (isActive) GZGreen.copy(alpha = 0.05f) else if (isLocked) GZRed.copy(alpha = 0.05f) else GZSurface

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

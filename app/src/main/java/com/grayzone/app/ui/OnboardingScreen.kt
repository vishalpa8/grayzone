package com.grayzone.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.grayzone.app.isAccessibilityServiceEnabled
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.grayzone.app.ui.theme.*

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    var hasUsageAccess by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasBatteryOpt by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasUsageAccess = isAccessibilityServiceEnabled(context)
                hasOverlay = Settings.canDrawOverlays(context)
                hasBatteryOpt = pm.isIgnoringBatteryOptimizations(context.packageName)
                if (hasUsageAccess && hasOverlay && hasBatteryOpt) onContinue()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize().background(GZBackground).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Welcome to Grayzone", fontSize = 28.sp, color = GZTextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("To provide friction, Grayzone needs these permissions:", color = GZTextSecondary)
        Spacer(Modifier.height(32.dp))
        
        // Permission 1: Usage Access
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (hasUsageAccess) GZGreen.copy(alpha = 0.1f) else GZSurface)
                .border(
                    1.dp, 
                    if (hasUsageAccess) GZGreen else GZBorder, 
                    RoundedCornerShape(12.dp)
                )
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (hasUsageAccess) Icons.Filled.CheckCircle else Icons.Filled.Circle,
                    contentDescription = null,
                    tint = if (hasUsageAccess) GZGreen else GZTextTertiary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Accessibility Service",
                        color = GZTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        "Required to instantly detect when a monitored app is opened",
                        color = GZTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
            if (!hasUsageAccess) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = GZPrimary)
                ) {
                    Text("Grant Accessibility")
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        // Permission 2: Overlay
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (hasOverlay) GZGreen.copy(alpha = 0.1f) else GZSurface)
                .border(
                    1.dp,
                    if (hasOverlay) GZGreen else GZBorder,
                    RoundedCornerShape(12.dp)
                )
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (hasOverlay) Icons.Filled.CheckCircle else Icons.Filled.Circle,
                    contentDescription = null,
                    tint = if (hasOverlay) GZGreen else GZTextTertiary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Display Over Other Apps",
                        color = GZTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        "Required to show friction screens and lockouts over blocked apps",
                        color = GZTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
            if (!hasOverlay) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = GZPrimary)
                ) {
                    Text("Grant Overlay Permission")
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        // Permission 3: Battery Optimization
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (hasBatteryOpt) GZGreen.copy(alpha = 0.1f) else GZSurface)
                .border(
                    1.dp,
                    if (hasBatteryOpt) GZGreen else GZBorder,
                    RoundedCornerShape(12.dp)
                )
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (hasBatteryOpt) Icons.Filled.CheckCircle else Icons.Filled.Circle,
                    contentDescription = null,
                    tint = if (hasBatteryOpt) GZGreen else GZTextTertiary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Battery Optimization Exempt",
                        color = GZTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        "Prevents Android from killing the monitoring service in background",
                        color = GZTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
            if (!hasBatteryOpt) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { 
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = GZPrimary)
                ) {
                    Text("Exclude from Battery Opt")
                }
            }
        }
        
        if (hasUsageAccess && hasOverlay && hasBatteryOpt) {
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

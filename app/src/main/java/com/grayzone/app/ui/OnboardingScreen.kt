package com.grayzone.app.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.grayzone.app.PrefsKeys
import com.grayzone.app.SetupRequirement
import com.grayzone.app.SetupRequirements
import com.grayzone.app.appInfoSettingsIntent
import com.grayzone.app.isAccessibilityServiceEnabled
import com.grayzone.app.settingsIntentFor
import com.grayzone.app.startActivitySafely
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
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
                if (hasUsageAccess) {
                    // Permission granted (again): reset prompt-once flag so a future genuine loss
                    // prompts exactly once more.
                    context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(PrefsKeys.ACCESSIBILITY_PROMPTED_FOR_LOSS, false).apply()
                }
                if (hasUsageAccess && hasOverlay) onContinue()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // F-160: scrollable column so large font / landscape never clips permission CTAs.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GZBackground)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Welcome to Grayzone", fontSize = 28.sp, color = GZTextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("To provide friction, Grayzone needs these permissions:", color = GZTextSecondary)
        Spacer(Modifier.height(32.dp))

        // Permission 1: Accessibility
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (hasUsageAccess) GZGreenContainer else GZSurface)
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
                    onClick = {
                        startActivitySafely(
                            context,
                            settingsIntentFor(context, SetupRequirement.ACCESSIBILITY),
                            "onboardingAccessibility"
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GZPrimary,
                        contentColor = Color.White
                    )
                ) {
                    Text("Grant Accessibility")
                }
                // F-092: sideloaded installs on Android 13+ may need Allow restricted settings.
                if (SetupRequirements.needsRestrictedSettingsHint(
                        Build.VERSION.SDK_INT,
                        SetupRequirement.ACCESSIBILITY
                    )
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "On Android 13+, apps installed from a file must first allow " +
                            "restricted settings: ⋮ → Allow restricted settings.",
                        color = GZTextSecondary,
                        fontSize = 12.sp
                    )
                    TextButton(
                        onClick = {
                            startActivitySafely(
                                context,
                                appInfoSettingsIntent(context),
                                "onboardingAppInfo"
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open app info")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Permission 2: Overlay
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (hasOverlay) GZGreenContainer else GZSurface)
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
                    onClick = {
                        startActivitySafely(
                            context,
                            settingsIntentFor(context, SetupRequirement.OVERLAY),
                            "onboardingOverlay"
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GZPrimary,
                        contentColor = Color.White
                    )
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
                .background(if (hasBatteryOpt) GZGreenContainer else GZSurface)
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
                        startActivitySafely(
                            context,
                            settingsIntentFor(context, SetupRequirement.BATTERY),
                            "onboardingBattery",
                            showToast = false
                        )
                        // F-061: follow with OEM-specific autostart / no-kill screen when needed.
                        if (com.grayzone.app.OemBatteryHelper.needsOemOptimization()) {
                            com.grayzone.app.OemBatteryHelper.openBatterySettings(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GZPrimary,
                        contentColor = Color.White
                    )
                ) {
                    Text("Exclude from Battery Opt")
                }
            }
            if (hasBatteryOpt && com.grayzone.app.OemBatteryHelper.needsOemOptimization()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    com.grayzone.app.OemBatteryHelper.getOptimizationInstructions(),
                    color = GZTextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { com.grayzone.app.OemBatteryHelper.openBatterySettings(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open ${com.grayzone.app.OemBatteryHelper.getManufacturerName()} battery settings")
                }
            }
        }

        // Battery exemption is recommended, not required — gating "Continue" on it would trap
        // users who decline it, so only the two functional permissions block entry.
        if (hasUsageAccess && hasOverlay) {
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GZPrimary,
                    contentColor = Color.White
                )
            ) {
                Text("Continue to App", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Spacer(Modifier.height(24.dp))
            Text(
                "Still needed: " + SetupRequirements.missing(hasUsageAccess, hasOverlay)
                    .joinToString(", ") { SetupRequirements.label(it) },
                color = GZTextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

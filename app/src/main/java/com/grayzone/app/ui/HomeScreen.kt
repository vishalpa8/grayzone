package com.grayzone.app.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grayzone.app.*
import com.grayzone.app.R
import com.grayzone.app.data.ProtectionHealthRepository
import com.grayzone.app.data.StateBackupManager
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.launch

data class HomeData(
    val monitoredCount: Int = 0
)

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var accessibilityOn by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notificationsOn by remember { mutableStateOf(areAppNotificationsEnabled(context)) }
    var allFilesOn by remember { mutableStateOf(StateBackupManager.hasAllFilesAccess(context)) }
    var batteryExempt by remember { mutableStateOf(!isBatteryOptimized(context)) }
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    var monitoredCount by remember { mutableStateOf(prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet())?.size ?: 0) }
    val streakScope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityOn = isAccessibilityServiceEnabled(context)
                overlayOn = Settings.canDrawOverlays(context)
                notificationsOn = areAppNotificationsEnabled(context)
                allFilesOn = StateBackupManager.hasAllFilesAccess(context)
                batteryExempt = !isBatteryOptimized(context)
                monitoredCount = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet())?.size ?: 0
                streakScope.launch {
                    try {
                        com.grayzone.app.data.StreakEvaluator.run(context)
                    } catch (e: Exception) {
                        com.grayzone.app.GrayzoneLogger.w(
                            com.grayzone.app.LogComponent.DATABASE,
                            "Streak evaluation from Home failed: ${e.message}"
                        )
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val data = HomeData(monitoredCount = monitoredCount)
    val missingSetup = SetupRequirements.missing(
        accessibilityEnabled = accessibilityOn,
        overlayEnabled = overlayOn,
        notificationsEnabled = notificationsOn,
        allFilesGranted = allFilesOn,
        batteryExempt = batteryExempt,
    )
    val needsSetup = missingSetup.isNotEmpty()
    val onlyBatteryMissing = missingSetup == listOf(SetupRequirement.BATTERY)
    // F-155: permissions alone are not "active" — OverlayService must also be running.
    val health by ProtectionHealthRepository.status.collectAsStateWithLifecycle()
    val coreReady = accessibilityOn && overlayOn
    // F-087: battery exemption is part of "active" — do not show green while optimized.
    val isActive = coreReady && batteryExempt && health.overlayActive && health.accessibilityActive

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(GZBackground),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(GZPrimaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_launcher_monochrome),
                            contentDescription = null,
                            tint = GZPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Grayzone",
                            color = GZTextPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp
                        )
                        Text(
                            "Friction, not hard blocks.",
                            color = GZTextSecondary,
                            fontSize = 13.sp
                        )
                    }
                    ActivePill(isActive = isActive)
                }
            }
        }

        // Primary status affordance — dominates when setup is incomplete
        item {
            val statusBg = when {
                needsSetup -> GZAmberContainer
                else -> GZGreenContainer
            }
            val statusBorder = when {
                needsSetup -> GZAmber.copy(alpha = 0.35f)
                else -> GZGreen.copy(alpha = 0.25f)
            }
            val statusTint = when {
                needsSetup -> GZAmber
                else -> GZGreen
            }
            val headline = if (needsSetup) {
                SetupRequirements.headline(missingSetup)
            } else {
                "Protection ready"
            }
            val body = if (needsSetup) {
                SetupRequirements.body(missingSetup)
            } else {
                "${data.monitoredCount} app${if (data.monitoredCount != 1) "s" else ""} monitored"
            }

            GZCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                background = statusBg,
                border = statusBorder
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (onlyBatteryMissing) Icons.Filled.BatteryAlert else Icons.Filled.Shield,
                            contentDescription = null,
                            tint = statusTint,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                headline,
                                color = GZTextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(body, color = GZTextSecondary, fontSize = 13.sp)
                        }
                    }

                    // One button per missing permission so it is always clear which is
                    // still pending and which settings page each one opens.
                    missingSetup.forEach { requirement ->
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                startActivitySafely(
                                    context,
                                    settingsIntentFor(context, requirement),
                                    "settingsIntentFor(${requirement.name})"
                                )
                                // F-061: OEM autostart screens are separate from the generic opt-out.
                                if (requirement == SetupRequirement.BATTERY &&
                                    OemBatteryHelper.needsOemOptimization()
                                ) {
                                    OemBatteryHelper.openBatterySettings(context)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GZPrimary,
                                contentColor = androidx.compose.ui.graphics.Color.White
                            )
                        ) {
                            Text(
                                SetupRequirements.actionLabel(listOf(requirement)),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        // F-092: sideloaded installs on Android 13+ may need Allow restricted settings.
                        if (SetupRequirements.needsRestrictedSettingsHint(
                                Build.VERSION.SDK_INT,
                                requirement
                            )
                        ) {
                            Spacer(Modifier.height(4.dp))
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
                                        "appInfoSettings"
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Open app info")
                            }
                        }
                    }
                }
            }
        }

        item { QuickFocusCard() }
        item { DailyBreakCard() }
        item { StreakCard() }

        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                SectionLabel("HOW IT WORKS")
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HowItWorksStep("1", "Open a monitored app", GZPrimary)
                    HowItWorksStep("2", "Grayzone overlays a pause screen", GZPrimaryLight)
                    HowItWorksStep("3", "A reflection question prompts mindfulness", GZAccent)
                    HowItWorksStep("4", "Wait the timer or skip — your choice", GZGreen)
                }
            }
        }
    }
}

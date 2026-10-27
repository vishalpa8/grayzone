package com.grayzone.app.ui

import com.grayzone.app.ui.components.*

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
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.launch

private enum class HomeStatus { OFF, PAUSED, NOT_RUNNING, LIMITED, ACTIVE }

data class HomeData(
    val monitoredCount: Int = 0
)

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val gate = LocalPermissionGate.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var accessibilityOn by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var batteryExempt by remember { mutableStateOf(!isBatteryOptimized(context)) }
    var monitoringOn by remember { mutableStateOf(isMonitoringEnabled(context)) }
    var notificationsOn by remember { mutableStateOf(areAppNotificationsEnabled(context)) }
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    var monitoredCount by remember { mutableStateOf(prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet())?.size ?: 0) }
    val streakScope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityOn = isAccessibilityServiceEnabled(context)
                overlayOn = Settings.canDrawOverlays(context)
                batteryExempt = !isBatteryOptimized(context)
                monitoringOn = isMonitoringEnabled(context)
                notificationsOn = areAppNotificationsEnabled(context)
                monitoredCount = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet())?.size ?: 0
                streakScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        com.grayzone.app.data.StreakEvaluator.run(context)
                    } catch (e: Exception) {
                        com.grayzone.app.GrayzoneLogger.w(
                            com.grayzone.app.LogComponent.DATABASE,
                            "Streak evaluation from Home failed", e
                        )
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val data = HomeData(monitoredCount = monitoredCount)
    // F-155: permissions alone are not "active" — OverlayService must also be running.
    val health by ProtectionHealthRepository.status.collectAsStateWithLifecycle()
    val coreReady = accessibilityOn && overlayOn
    val isActive = monitoringOn && coreReady && health.overlayActive && health.accessibilityActive

    // One status, in priority order. Only "Active" is green; anything that weakens protection says so.
    val status = when {
        !monitoringOn -> HomeStatus.OFF
        !coreReady -> HomeStatus.PAUSED
        !isActive -> HomeStatus.NOT_RUNNING
        !batteryExempt || !notificationsOn -> HomeStatus.LIMITED
        else -> HomeStatus.ACTIVE
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(GZBackground),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeader(title = "Grayzone", subtitle = "Friction, not hard blocks.")
        }

        // Status only. Permissions are asked for in the popup, never with buttons here;
        // tapping the card while something core is off simply reopens that popup.
        item {
            val tint = when (status) {
                HomeStatus.OFF -> GZTextTertiary
                HomeStatus.PAUSED, HomeStatus.LIMITED, HomeStatus.NOT_RUNNING -> GZAmber
                HomeStatus.ACTIVE -> GZGreen
            }
            GZCard(
                modifier = Modifier.padding(horizontal = GZGutter),
                onClick = when (status) {
                    HomeStatus.PAUSED, HomeStatus.LIMITED -> ({ gate.requireSetup { startMonitoringIfReady(context) } })
                    HomeStatus.NOT_RUNNING -> ({ startMonitoringIfReady(context) })
                    else -> null
                }
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBadge(Icons.Filled.Shield, tint = tint, size = 44)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when (status) {
                                HomeStatus.OFF -> "Monitoring is off"
                                HomeStatus.PAUSED -> "Protection paused"
                                HomeStatus.NOT_RUNNING -> "Protection not running"
                                HomeStatus.LIMITED, HomeStatus.ACTIVE -> "Protection on"
                            },
                            color = GZTextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            when (status) {
                                HomeStatus.OFF -> "Turn it on in the Apps tab to start."
                                HomeStatus.PAUSED -> "A permission is needed. Tap to review."
                                HomeStatus.NOT_RUNNING -> "Tap to start it again."
                                HomeStatus.LIMITED -> if (!batteryExempt) "Battery saving may stop it. Tap to fix."
                                    else "Notifications are off. Tap to fix."
                                HomeStatus.ACTIVE -> "${data.monitoredCount} app${if (data.monitoredCount != 1) "s" else ""} monitored"
                            },
                            color = GZTextSecondary,
                            fontSize = 13.sp
                        )
                    }
                    StatusChip(
                        text = when (status) {
                            HomeStatus.OFF -> "Off"
                            HomeStatus.PAUSED -> "Paused"
                            HomeStatus.NOT_RUNNING -> "Stopped"
                            HomeStatus.LIMITED -> "Limited"
                            HomeStatus.ACTIVE -> "Active"
                        },
                        tone = when (status) {
                            HomeStatus.OFF -> ChipTone.NEUTRAL
                            HomeStatus.ACTIVE -> ChipTone.GOOD
                            else -> ChipTone.WARN
                        }
                    )
                }
            }
        }
        item { SectionLabel("Focus", Modifier.padding(start = GZGutter + 4.dp, top = 8.dp)) }
        item { QuickFocusCard() }
        item { DailyBreakCard() }
        item { StreakCard() }

        item { SectionLabel("How it works", Modifier.padding(start = GZGutter + 4.dp, top = 8.dp)) }
        item {
            GZCard(modifier = Modifier.padding(horizontal = GZGutter)) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    HowItWorksStep("1", "Open an app you chose to monitor", GZPrimary)
                    HowItWorksStep("2", "Grayzone shows a short pause screen", GZPrimary)
                    HowItWorksStep("3", "A reflection question asks why", GZPrimary)
                    HowItWorksStep("4", "Wait it out, then decide", GZPrimary)
                }
            }
        }
    }
}

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
    // F-155: permissions alone are not "active" — OverlayService must also be running.
    val health by ProtectionHealthRepository.status.collectAsStateWithLifecycle()
    val coreReady = accessibilityOn && overlayOn
    // F-087: battery exemption is part of "active" — do not show green while optimized.
    val isActive = monitoringOn && coreReady && batteryExempt && health.overlayActive && health.accessibilityActive

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

        // Status only. Permissions are asked for in the popup, never with buttons here;
        // tapping the card while something core is off simply reopens that popup.
        item {
            val off = !monitoringOn
            val paused = monitoringOn && !coreReady
            GZCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                background = when { off -> GZSurface; paused -> GZAmberContainer; else -> GZGreenContainer },
                border = when { off -> GZBorder; paused -> GZAmber.copy(alpha = 0.3f); else -> GZGreen.copy(alpha = 0.3f) },
                onClick = if (paused) ({ gate.requireSetup { } }) else null
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = when { off -> GZTextTertiary; paused -> GZAmber; else -> GZGreen },
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when { off -> "Monitoring is off"; paused -> "Protection paused"; else -> "Protection ready" },
                            color = GZTextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (off) "Turn it on in the Apps tab to start."
                            else if (paused) "A permission is needed. Tap to review."
                            else "${data.monitoredCount} app${if (data.monitoredCount != 1) "s" else ""} monitored",
                            color = GZTextSecondary,
                            fontSize = 13.sp
                        )
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
                    HowItWorksStep("2", "Grayzone overlays a pause screen", GZPrimary)
                    HowItWorksStep("3", "A reflection question prompts mindfulness", GZPrimary)
                    HowItWorksStep("4", "Wait the timer or skip — your choice", GZPrimary)
                }
            }
        }
    }
}

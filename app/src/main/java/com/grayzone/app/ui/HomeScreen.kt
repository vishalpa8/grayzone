package com.grayzone.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.grayzone.app.*
import com.grayzone.app.R
import com.grayzone.app.ui.theme.*

data class HomeData(
    val monitoredCount: Int = 0
)

@Composable
fun HomeScreen(onOpenDrawer: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isActive by remember { mutableStateOf(isAccessibilityServiceEnabled(context) && Settings.canDrawOverlays(context)) }
    var batteryIssue by remember { mutableStateOf(isBatteryOptimized(context)) }
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    var monitoredCount by remember { mutableStateOf(prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet())?.size ?: 0) }
    
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // BUG 7 FIX: Re-read usage access status dynamically in case it was disabled in settings
                isActive = isAccessibilityServiceEnabled(context) && Settings.canDrawOverlays(context)
                batteryIssue = isBatteryOptimized(context)
                monitoredCount = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet())?.size ?: 0
                val monitored = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()
                val completedDateKey = DateUtils.getYesterdayDateKey()
                com.grayzone.app.data.StreakManager(context).checkDailyStreakForDate(
                    completedDateKey,
                    stayedUnderAllBudgets(prefs, monitored, completedDateKey)
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val data = HomeData(monitoredCount = monitoredCount)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(GZBackground),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF1B1436), Color(0xFF120C24), GZBackground)
                        )
                    )
            ) {
                // Soft brand glow anchored near the logo for depth on the dark base
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(GZPrimary.copy(alpha = 0.32f), Color.Transparent),
                                center = Offset(140f, 300f),
                                radius = 620f
                            )
                        )
                )
                Column(
                    modifier = Modifier.padding(
                        start = 20.dp, end = 12.dp, top = 52.dp, bottom = 26.dp
                    )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(15.dp))
                                .background(
                                    Brush.linearGradient(listOf(GZPrimaryLight, GZPrimaryDark))
                                )
                                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(15.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_launcher_monochrome),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Grayzone",
                                color = GZTextPrimary,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            )
                            Text(
                                "Friction, not hard blocks.",
                                color = GZTextSecondary,
                                fontSize = 13.sp
                            )
                        }
                        ActivePill(isActive = isActive)
                        IconButton(onClick = onOpenDrawer, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = "Open menu",
                                tint = GZTextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (batteryIssue) {
                    val ctx = LocalContext.current
                    GZCard(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        background = GZAmberContainer,
                        border = GZAmber.copy(alpha = 0.3f)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.BatteryAlert, contentDescription = null,
                                tint = GZAmber, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Battery Optimization On", color = GZAmber,
                                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Grayzone may stop working in background.",
                                    color = GZTextSecondary, fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = {
                                    @SuppressLint("BatteryLife")
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${ctx.packageName}"))
                                    ctx.startActivity(intent)
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = GZAmber)
                            ) { Text("Fix", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }

        item {
            GZCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Shield,
                        contentDescription = null,
                        tint = if (isActive) GZGreen else GZTextTertiary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (isActive) "Protection Active" else "Protection Inactive",
                            color = if (isActive) GZTextPrimary else GZTextSecondary,
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                        )
                        Text(
                            "${data.monitoredCount} app${if (data.monitoredCount != 1) "s" else ""} being monitored",
                            color = GZTextSecondary, fontSize = 12.sp
                        )
                    }
                }
            }
        }

        item {
            QuickFocusCard()
        }

        item {
            DailyBreakCard()
        }

        item {
            StreakCard()
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                SectionLabel("HOW IT WORKS")
                Spacer(Modifier.height(10.dp))
                GZCard {
                    Column(modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        HowItWorksStep("1", "Open a monitored app", GZPrimary)
                        HowItWorksStep("2", "Grayzone overlays a 8-second pause screen", GZPrimaryLight)
                        HowItWorksStep("3", "A reflection question prompts mindfulness", GZAccent)
                        HowItWorksStep("4", "Wait the timer or skip — your choice", GZGreen)
                    }
                }
            }
        }
    }
}

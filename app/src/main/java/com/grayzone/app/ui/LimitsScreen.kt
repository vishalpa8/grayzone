package com.grayzone.app.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.*
import com.grayzone.app.ui.components.*
import com.grayzone.app.ui.theme.*

private fun formatMinutes(total: Int): String {
    val h = total / 60
    val m = total % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

@Composable
fun LimitsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    // Paint instantly from the in-memory cache on re-entry; refresh in background.
    var installedApps by remember { mutableStateOf(peekCachedApps() ?: emptyList()) }
    val monitoredApps = remember {
        prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()
    }
    LaunchedEffect(Unit) {
        installedApps = getInstalledAppsCached(context)
    }

    // Ticker for live lock detection; only fires while the screen is visible.
    val currentTime by rememberResumedTicker(2000L)
    val scheduleManager = remember { com.grayzone.app.data.ScheduleManager(context) }
    val scheduleActive = remember(currentTime) { scheduleManager.isCurrentlyScheduled() }

    val monitoredList = remember(installedApps, monitoredApps) {
        installedApps.filter { monitoredApps.contains(it.packageName) }
            .sortedBy { it.name.lowercase() }
    }

    Column(modifier = Modifier.fillMaxSize().background(GZBackground)) {
        ScreenHeader(
            title = "Limits",
            subtitle = if (monitoredList.isEmpty()) null else "Live timers for your monitored apps"
        )

        if (monitoredList.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconBadge(Icons.Filled.HourglassEmpty, tint = GZTextTertiary, size = 56)
                    Spacer(Modifier.height(16.dp))
                    Text("No monitored apps yet", color = GZTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Turn apps on in the Apps tab to see their limits here.",
                        color = GZTextSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LimitsList(monitoredList, prefs, currentTime, scheduleActive)
        }
    }
}

@Composable
private fun LimitsList(
    monitoredList: List<AppInfo>,
    prefs: android.content.SharedPreferences,
    currentTime: Long,
    scheduleActive: Boolean,
) {
        LazyColumn(
            contentPadding = PaddingValues(start = GZGutter, end = GZGutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(monitoredList, key = { it.packageName }) { app ->
                val pkg = app.packageName
                val snapshot = AppLockState(
                    activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L),
                    lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L),
                    remainingMillis = prefs.getLong(PrefsKeys.REMAINING_MILLIS + pkg, 0L)
                ).statusAt(currentTime)

                val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + pkg, false)
                val globalSession = prefs.getInt(PrefsKeys.SESSION_MINUTES, com.grayzone.app.data.ConfigValidator.DEFAULT_SESSION_MINUTES)
                val globalLockout = prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, com.grayzone.app.data.ConfigValidator.DEFAULT_LOCKOUT_MINUTES)
                // Same resolution and clamping as the engine, so the numbers shown are the ones enforced.
                val sessionMins = com.grayzone.app.data.PerAppLimits.sessionMinutes(
                    hasCustom, prefs.getInt(PrefsKeys.PER_APP_SESSION_MINUTES + pkg, globalSession), globalSession
                )
                val lockoutMins = com.grayzone.app.data.PerAppLimits.lockoutMinutes(
                    hasCustom, prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + pkg, globalLockout), globalLockout
                )
                val budgetMins = prefs.getInt(PrefsKeys.DAILY_BUDGET_MINUTES + pkg, 0)
                val icon = remember(app.icon) { app.icon?.asImageBitmap() }

                GZCard {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (icon != null) {
                                Image(icon, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
                            } else {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(GZSurfaceHigh))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                app.name,
                                color = GZTextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            when (snapshot.status) {
                                AppRuntimeStatus.LOCKED -> StatusChip("Locked · ${snapshot.remainingMinutes}m", ChipTone.BAD)
                                AppRuntimeStatus.ACTIVE -> StatusChip("Session · ${snapshot.remainingMinutes}m", ChipTone.GOOD)
                                AppRuntimeStatus.PAUSED -> StatusChip("Paused · ${snapshot.remainingMinutes}m", ChipTone.WARN)
                                AppRuntimeStatus.READY -> when {
                                    isBudgetExhausted(prefs, pkg) -> StatusChip("Daily limit reached", ChipTone.BAD)
                                    scheduleActive -> StatusChip("Locked by schedule", ChipTone.BAD)
                                    else -> StatusChip("Ready", ChipTone.NEUTRAL)
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LimitStat("Session", formatMinutes(sessionMins), Modifier.weight(1f))
                            LimitStat("Lockout", formatMinutes(lockoutMins), Modifier.weight(1f))
                            LimitStat("Daily", if (budgetMins > 0) formatMinutes(budgetMins) else "No cap", Modifier.weight(1f))
                        }
                    }
                }
            }
        }
}

@Composable
private fun LimitStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(GZSurfaceElevated)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, color = GZTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(value, color = GZTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

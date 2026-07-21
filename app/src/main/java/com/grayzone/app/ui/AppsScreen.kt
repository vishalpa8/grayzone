package com.grayzone.app.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.*
import com.grayzone.app.ui.theme.*

@Composable
fun AppsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    // Paint instantly from the in-memory cache on re-entry; spinner only on the
    // very first cold load. The LaunchedEffect still refreshes in the background.
    val cachedApps = remember { peekCachedApps() }
    var installedApps by remember { mutableStateOf(cachedApps ?: emptyList()) }
    var monitoredApps by remember {
        mutableStateOf(prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet())
    }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(cachedApps == null) }
    var grayzoneEnabled by remember { mutableStateOf(prefs.getBoolean(PrefsKeys.GRAYZONE_ENABLED, true)) }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var selectedAppForSettings by remember { mutableStateOf<AppInfo?>(null) }

    LaunchedEffect(Unit) {
        installedApps = getInstalledAppsCached(context)
        isLoading = false
    }
    
    // Ticker for live lock detection; refresh at a lower cadence to reduce wakeups.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            currentTime = System.currentTimeMillis()
        }
    }

    // Check if ANY app is currently locked, active, or paused
    val anyAppLockedOrActive = remember(monitoredApps, currentTime) {
        isAnyAppLocked(prefs, monitoredApps, currentTime)
    }

    val filtered = remember(installedApps, searchQuery, monitoredApps) {
        val baseList = if (searchQuery.isBlank()) installedApps
        else installedApps.filter { it.name.contains(searchQuery, ignoreCase = true) }
        
        baseList.sortedWith(
            compareBy<AppInfo> { !monitoredApps.contains(it.packageName) }
                .thenBy { it.name.lowercase() }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(GZBackground)) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(GZPrimary, GZPrimaryContainer, GZBackground)))
                .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 24.dp)
        ) {
            Text("Monitored Apps", color = GZTextPrimary, fontSize = 28.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            Text(
                if (monitoredApps.isEmpty()) "Toggle apps to monitor them"
                else "${monitoredApps.size} app${if (monitoredApps.size != 1) "s" else ""} will trigger friction",
                color = if (monitoredApps.isEmpty()) GZTextSecondary else GZPrimaryLight,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(16.dp))
            
            // Master Enable/Disable Switch
            GZCard(
                background = if (grayzoneEnabled) GZPrimary.copy(alpha = 0.1f) else GZSurface,
                border = if (grayzoneEnabled) GZPrimary.copy(alpha = 0.3f) else GZBorder
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = if (grayzoneEnabled) GZPrimary else GZTextTertiary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (grayzoneEnabled) "Monitoring Enabled" else "Monitoring Disabled",
                            color = if (grayzoneEnabled) GZTextPrimary else GZTextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        if (anyAppLockedOrActive && grayzoneEnabled) {
                            Text(
                                "Cannot disable while apps are active or locked",
                                color = GZRed.copy(alpha = 0.8f),
                                fontSize = 11.sp
                            )
                        } else {
                            Text(
                                if (grayzoneEnabled) "Tap to pause all monitoring" else "Tap to resume monitoring",
                                color = GZTextTertiary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Switch(
                        checked = grayzoneEnabled,
                        onCheckedChange = {
                            if (!anyAppLockedOrActive || !grayzoneEnabled) {
                                grayzoneEnabled = it
                                prefs.edit().putBoolean(PrefsKeys.GRAYZONE_ENABLED, it).apply()
                            }
                        },
                        enabled = !anyAppLockedOrActive || !grayzoneEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = GZPrimary,
                            uncheckedThumbColor = GZTextTertiary,
                            uncheckedTrackColor = GZSurfaceHigh,
                            disabledCheckedThumbColor = Color.White.copy(alpha = 0.6f),
                            disabledCheckedTrackColor = GZPrimary.copy(alpha = 0.4f)
                        )
                    )
                }
            }
            // Search bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(GZSurfaceElevated)
                    .border(1.dp, GZBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, contentDescription = null,
                    tint = GZTextTertiary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    textStyle = LocalTextStyle.current.copy(color = GZTextPrimary, fontSize = 15.sp),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (searchQuery.isEmpty()) {
                            Text("Search apps…", color = GZTextTertiary, fontSize = 15.sp)
                        }
                        inner()
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "Clear",
                        tint = GZTextTertiary, modifier = Modifier.size(16.dp)
                            .clickable { searchQuery = "" }
                    )
                }
            }
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GZLoadingSpinner()
            }
        } else {
            val lockStateMap = remember(monitoredApps, currentTime) {
                monitoredApps.associateWith { pkg ->
                    AppLockState(
                        activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L),
                        lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L),
                        remainingMillis = prefs.getLong(PrefsKeys.REMAINING_MILLIS + pkg, 0L)
                    )
                }
            }

            LazyColumn {
                items(filtered, key = { it.packageName }) { app ->
                    val isMonitored = monitoredApps.contains(app.packageName)
                    val state = lockStateMap[app.packageName] ?: AppLockState(0L, 0L, 0L)
                    val activeUntil = state.activeUntil
                    val lockedUntil = state.lockedUntil
                    val remaining = state.remainingMillis
                    
                    val normalizedRemaining = getNormalizedRemainingMillis(remaining)
                    val isAppLocked = currentTime > activeUntil && currentTime < lockedUntil
                    val isActiveOrPaused = currentTime < activeUntil || normalizedRemaining > 0

                    val statusText = when {
                        isAppLocked -> {
                            val remainingMins = ((lockedUntil - currentTime) / (60 * 1000)).coerceAtLeast(1)
                            "🔒 Locked ($remainingMins m)"
                        }
                        currentTime < activeUntil -> {
                            val remainingMins = ((activeUntil - currentTime) / (60 * 1000)).coerceAtLeast(1)
                            "⏳ Session Active ($remainingMins m left)"
                        }
                        normalizedRemaining > 0 -> {
                            val remainingMins = (normalizedRemaining / (60 * 1000)).coerceAtLeast(1)
                            "⏳ Session Paused ($remainingMins m left)"
                        }
                        else -> null
                    }

                    PremiumAppListItem(
                        app = app,
                        isMonitored = isMonitored,
                        isLocked = isAppLocked,
                        isActiveOrPaused = isActiveOrPaused,
                        statusText = statusText,
                        isGloballyDisabled = !grayzoneEnabled,
                        onToggle = { checked ->
                            val updated = monitoredApps.toMutableSet()
                            if (checked) {
                                updated.add(app.packageName)
                            } else {
                                updated.remove(app.packageName)
                                prefs.edit()
                                    .remove(PrefsKeys.ACTIVE_UNTIL + app.packageName)
                                    .remove(PrefsKeys.LOCKED_UNTIL + app.packageName)
                                    .remove(PrefsKeys.REMAINING_MILLIS + app.packageName)
                                    .apply()
                            }
                            monitoredApps = updated
                            prefs.edit().putStringSet(PrefsKeys.MONITORED_APPS, updated).apply()
                        },
                        onLongPress = {
                            selectedAppForSettings = app
                        }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
        
        selectedAppForSettings?.let { app ->
            PerAppSettingsSheet(
                app = app,
                onDismiss = { selectedAppForSettings = null }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PremiumAppListItem(
    app: AppInfo, 
    isMonitored: Boolean, 
    isLocked: Boolean = false, 
    isActiveOrPaused: Boolean = false,
    statusText: String? = null,
    isGloballyDisabled: Boolean = false,
    onToggle: (Boolean) -> Unit,
    onLongPress: () -> Unit = {}
) {
    val isToggleEnabled = !isLocked && !isActiveOrPaused && !isGloballyDisabled
    val rowAlpha = if (isGloballyDisabled && !isLocked && !isActiveOrPaused) 0.5f else 1f
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isToggleEnabled) Modifier.combinedClickable(
                    onClick = { onToggle(!isMonitored) },
                    onLongClick = onLongPress
                )
                else Modifier
            )
            .background(
                if (isLocked) GZRed.copy(alpha = 0.06f)
                else if (isActiveOrPaused) GZGreen.copy(alpha = 0.06f)
                else if (isMonitored && !isGloballyDisabled) GZPrimaryGlow
                else Color.Transparent
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (app.icon != null) {
            Image(
                bitmap = app.icon.asImageBitmap(),
                contentDescription = app.name,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)),
                alpha = rowAlpha
            )
        } else {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(GZSurfaceElevated))
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.name, color = GZTextPrimary.copy(alpha = rowAlpha), fontWeight = FontWeight.Medium, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isLocked) {
                Text(statusText ?: "🔒 Locked", color = GZRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            } else if (isActiveOrPaused) {
                Text(statusText ?: "⏳ Session Active", color = GZGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(app.packageName, color = GZTextTertiary.copy(alpha = rowAlpha), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (isMonitored) {
            IconButton(
                onClick = onLongPress,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = GZTextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Switch(
            checked = isMonitored,
            onCheckedChange = { if (isToggleEnabled) onToggle(it) },
            enabled = isToggleEnabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = if (isLocked) GZRed else if (isActiveOrPaused) GZGreen else GZPrimary,
                uncheckedThumbColor = GZTextTertiary,
                uncheckedTrackColor = GZSurfaceHigh,
                disabledCheckedThumbColor = Color.White.copy(alpha = 0.6f),
                disabledCheckedTrackColor = if (isLocked) GZRed.copy(alpha = 0.5f) else if (isActiveOrPaused) GZGreen.copy(alpha = 0.5f) else GZPrimary.copy(alpha = 0.4f)
            )
        )
    }
    Divider(color = GZBorderSubtle, thickness = 0.5.dp,
        modifier = Modifier.padding(start = 82.dp, end = 20.dp))
}

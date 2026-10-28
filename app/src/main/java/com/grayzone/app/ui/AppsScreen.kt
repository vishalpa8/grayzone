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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.grayzone.app.*
import com.grayzone.app.ui.components.*
import com.grayzone.app.ui.theme.*

@Composable
fun AppsScreen(viewModel: AppsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val gate = LocalPermissionGate.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    // Paint instantly from the in-memory cache on re-entry; spinner only on the
    // very first cold load. The LaunchedEffect still refreshes in the background.
    val cachedApps = remember { peekCachedApps() }
    var installedApps by remember { mutableStateOf(cachedApps ?: emptyList()) }

    val monitoredApps by viewModel.monitoredApps.collectAsState()
    val grayzoneEnabled by viewModel.grayzoneEnabled.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(cachedApps == null) }
    var selectedAppForSettings by remember { mutableStateOf<AppInfo?>(null) }
    var selectedAppEditable by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        installedApps = getInstalledAppsCached(context)
        isLoading = false
    }

    // Ticker for live lock detection; only fires while the screen is visible.
    val currentTime by rememberResumedTicker(2000L)

    val anyAppLockedOrActive = remember(monitoredApps, currentTime) {
        isRestrictionActive(context, prefs, monitoredApps, currentTime)
    }
    val coreReady = remember(currentTime) {
        hasUsageAccess(context) && android.provider.Settings.canDrawOverlays(context)
    }

    var filtered by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    LaunchedEffect(installedApps, searchQuery, monitoredApps) {
        filtered = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val baseList = if (searchQuery.isBlank()) installedApps
            else installedApps.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }

            baseList.sortedWith(
                compareBy<AppInfo> { !monitoredApps.contains(it.packageName) }
                    .thenBy { it.name.lowercase() }
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(GZBackground)) {
        ScreenHeader(
            title = "Apps",
            subtitle = if (monitoredApps.isEmpty()) "Choose the apps that should pause first"
            else "${monitoredApps.size} app${if (monitoredApps.size != 1) "s" else ""} monitored"
        )

        // Master switch: monitoring is off by default and asks for every permission when turned on.
        GZCard(modifier = Modifier.padding(horizontal = GZGutter)) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBadge(Icons.Filled.Shield, tint = if (grayzoneEnabled) GZPrimary else GZTextTertiary)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Monitoring",
                        color = GZTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    val needsPermission = grayzoneEnabled && !coreReady
                    Text(
                        when {
                            needsPermission -> "Paused · a permission is off, tap to fix"
                            anyAppLockedOrActive && grayzoneEnabled -> "Can't turn off while a limit or schedule is active"
                            grayzoneEnabled -> "On · pause screens are active"
                            else -> "Off · turn on to start"
                        },
                        color = if (needsPermission || (anyAppLockedOrActive && grayzoneEnabled)) GZAmber else GZTextSecondary,
                        fontSize = 13.sp,
                        modifier = if (needsPermission) {
                            Modifier.clickable(
                                onClickLabel = "Fix permissions",
                                role = androidx.compose.ui.semantics.Role.Button
                            ) { gate.requireSetup { startMonitoringIfReady(context) } }
                        } else Modifier
                    )
                }
                GZSwitch(
                    checked = grayzoneEnabled,
                    onCheckedChange = {
                        if (!anyAppLockedOrActive || !grayzoneEnabled) {
                            if (it) {
                                gate.requireSetup {
                                    viewModel.setGrayzoneEnabled(true)
                                    startMonitoringIfReady(context)
                                }
                            } else {
                                viewModel.setGrayzoneEnabled(false)
                                context.stopService(
                                    android.content.Intent(context, com.grayzone.app.service.OverlayService::class.java)
                                )
                            }
                        }
                    },
                    enabled = !anyAppLockedOrActive || !grayzoneEnabled
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            modifier = Modifier.padding(horizontal = GZGutter)
        )

        Spacer(Modifier.height(8.dp))

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GZLoadingSpinner()
            }
        } else if (filtered.isEmpty() && searchQuery.isNotBlank()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No apps match \"${searchQuery.trim()}\"", color = GZTextTertiary, fontSize = 14.sp)
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

            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    val isMonitored = monitoredApps.contains(app.packageName)
                    val state = lockStateMap[app.packageName] ?: AppLockState(0L, 0L, 0L)
                    val snapshot = state.statusAt(currentTime)

                    val isAppLocked = snapshot.status == AppRuntimeStatus.LOCKED
                    val isActiveOrPaused = snapshot.status == AppRuntimeStatus.ACTIVE ||
                        snapshot.status == AppRuntimeStatus.PAUSED
                    val budgetUsedUp = isMonitored && isBudgetExhausted(prefs, app.packageName)

                    AppRow(
                        app = app,
                        isMonitored = isMonitored,
                        status = snapshot.status,
                        remainingMinutes = snapshot.remainingMinutes,
                        budgetUsedUp = budgetUsedUp,
                        onToggle = { checked ->
                            if (checked) gate.requireSetup { viewModel.toggleAppMonitored(app.packageName, true) }
                            else viewModel.toggleAppMonitored(app.packageName, false)
                        },
                        onOpenSettings = {
                            // Limits can't be loosened while this app is locked or mid-session.
                            selectedAppEditable = !isAppLocked && !isActiveOrPaused && !budgetUsedUp
                            selectedAppForSettings = app
                        }
                    )
                }
            }
        }

        selectedAppForSettings?.let { app ->
            PerAppSettingsSheet(
                app = app,
                editable = selectedAppEditable,
                onDismiss = { selectedAppForSettings = null }
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(GZControlShape)
            .background(GZSurface)
            .border(1.dp, if (GZIsDark) GZBorder else GZBorderSubtle, GZControlShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = GZTextTertiary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            textStyle = LocalTextStyle.current.copy(color = GZTextPrimary, fontSize = 15.sp),
            cursorBrush = SolidColor(GZPrimary),
            modifier = Modifier.weight(1f),
            singleLine = true,
            decorationBox = { inner ->
                if (query.isEmpty()) Text("Search apps", color = GZTextTertiary, fontSize = 15.sp)
                inner()
            }
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = GZTextTertiary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppInfo,
    isMonitored: Boolean,
    status: AppRuntimeStatus,
    remainingMinutes: Long,
    budgetUsedUp: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val isLocked = status == AppRuntimeStatus.LOCKED
    val isBusy = status == AppRuntimeStatus.ACTIVE || status == AppRuntimeStatus.PAUSED
    // Apps can be picked while Monitoring is off; only a running restriction freezes the switch.
    val isToggleEnabled = !isLocked && !isBusy && !budgetUsedUp
    val icon = remember(app.icon) { app.icon?.asImageBitmap() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isToggleEnabled) Modifier.combinedClickable(
                    onClickLabel = if (isMonitored) "Stop monitoring" else "Monitor",
                    onLongClickLabel = "Open limits",
                    onClick = { onToggle(!isMonitored) },
                    onLongClick = { if (isMonitored) onOpenSettings() }
                ) else Modifier
            )
            .padding(horizontal = GZGutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
            )
        } else {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(GZSurfaceHigh))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.name,
                color = GZTextPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            when (status) {
                AppRuntimeStatus.LOCKED -> StatusLine("Locked · ${remainingMinutes}m left", GZRed)
                AppRuntimeStatus.ACTIVE -> StatusLine("Session · ${remainingMinutes}m left", GZGreen)
                AppRuntimeStatus.PAUSED -> StatusLine("Paused · ${remainingMinutes}m left", GZAmber)
                AppRuntimeStatus.READY -> when {
                    budgetUsedUp -> StatusLine("Daily limit reached", GZRed)
                    isMonitored -> StatusLine("Monitored", GZTextSecondary)
                }
            }
        }
        if (isMonitored) {
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = "Limits for ${app.name}",
                    tint = GZTextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        GZSwitch(
            checked = isMonitored,
            onCheckedChange = { if (isToggleEnabled) onToggle(it) },
            enabled = isToggleEnabled,
            checkedTrackColor = when {
                isLocked || budgetUsedUp -> GZRed
                isBusy -> GZGreen
                else -> GZPrimary
            }
        )
    }
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
}

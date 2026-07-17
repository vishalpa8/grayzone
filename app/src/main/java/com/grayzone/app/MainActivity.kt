package com.grayzone.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.grayzone.app.PrefsKeys
import com.grayzone.app.service.AppAccessibilityService
import com.grayzone.app.service.OverlayService
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

// â”€â”€â”€ Activity â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.grayzone.app.ui.theme.GrayzoneTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = GZBackground) {
                    MainAppContent()
                }
            }
        }
    }
}

// â”€â”€â”€ Navigation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

enum class Screen { ONBOARDING, MAIN }
enum class Tab { HOME, APPS, LIMITS, SETTINGS }

@Composable
fun MainAppContent() {
    val context = LocalContext.current
    var currentScreen by remember {
        mutableStateOf(
            if (isAccessibilityServiceEnabled(context) && Settings.canDrawOverlays(context))
                Screen.MAIN else Screen.ONBOARDING
        )
    }
    when (currentScreen) {
        Screen.ONBOARDING -> OnboardingScreen(onContinue = { currentScreen = Screen.MAIN })
        Screen.MAIN -> MainScreen()
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(Tab.HOME) }
    
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        context.startForegroundService(Intent(context, OverlayService::class.java))
    }

    Scaffold(
        containerColor = GZBackground,
        bottomBar = {
            NavigationBar(
                containerColor = GZSurface,
                tonalElevation = 0.dp,
                modifier = Modifier.border(
                    width = 1.dp,
                    color = GZBorder,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                ).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            ) {
                listOf(
                    Triple(Tab.HOME, Icons.Filled.Home, "Home"),
                    Triple(Tab.APPS, Icons.AutoMirrored.Filled.List, "Apps"),
                    Triple(Tab.LIMITS, Icons.Filled.Lock, "Limits"),
                    Triple(Tab.SETTINGS, Icons.Filled.Settings, "Settings"),
                ).forEach { (tab, icon, label) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 10.sp) },
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GZPrimary,
                            selectedTextColor = GZPrimary,
                            unselectedIconColor = GZTextTertiary,
                            unselectedTextColor = GZTextTertiary,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                Tab.HOME -> HomeScreen()
                Tab.APPS -> AppsScreen()
                Tab.LIMITS -> LimitsScreen()
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

// â”€â”€â”€ Home Screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

data class HomeData(
    val monitoredCount: Int = 0
)

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // BUG 10 FIX: Compute once per lifecycle-resume, not on every recomposition.
    // isAccessibilityServiceEnabled calls ContentResolver; isBatteryOptimized calls PowerManager.
    var isActive by remember { mutableStateOf(isAccessibilityServiceEnabled(context) && Settings.canDrawOverlays(context)) }
    var batteryIssue by remember { mutableStateOf(isBatteryOptimized(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isActive = isAccessibilityServiceEnabled(context) && Settings.canDrawOverlays(context)
                batteryIssue = isBatteryOptimized(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    val monitoredCount = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet())?.size ?: 0
    val data = HomeData(monitoredCount = monitoredCount)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(GZBackground),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // â”€â”€ Header â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(GZPrimaryContainer, GZBackground)
                        )
                    )
                    .padding(start = 24.dp, end = 24.dp, top = 52.dp, bottom = 32.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Grayzone",
                                color = GZTextPrimary,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            )
                            Text(
                                "Friction, not hard blocks.",
                                color = GZTextSecondary,
                                fontSize = 14.sp
                            )
                        }
                        // Active status pill
                        ActivePill(isActive = isActive)
                    }
                }
            }
        }

        // â”€â”€ Battery warning â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        if (batteryIssue) {
            item {
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
                                ctx.startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${ctx.packageName}"))
                                )
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = GZAmber)
                        ) { Text("Fix", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        // â”€â”€ Monitoring status â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

        // â”€â”€ How It Works â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
                        HowItWorksStep("4", "Wait the timer or skip â€” your choice", GZGreen)
                    }
                }
            }
        }
    }
}

// ——— Apps Screen —————————————————————————————————————————————————————————

@Composable
fun AppsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var monitoredApps by remember {
        mutableStateOf(prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet())
    }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var grayzoneEnabled by remember { mutableStateOf(prefs.getBoolean(PrefsKeys.GRAYZONE_ENABLED, true)) }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        installedApps = getInstalledApps(context)
        isLoading = false
    }
    
    // Ticker for live lock detection
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    // Check if ANY app is currently locked
    val anyAppLocked = remember(monitoredApps, currentTime) {
        monitoredApps.any { pkg ->
            val activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L)
            val lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L)
            currentTime > activeUntil && currentTime < lockedUntil
        }
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
                .background(Brush.verticalGradient(listOf(GZPrimaryContainer, GZBackground)))
                .padding(start = 24.dp, end = 24.dp, top = 52.dp, bottom = 24.dp)
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
                        if (anyAppLocked && grayzoneEnabled) {
                            Text(
                                "Cannot disable while apps are locked",
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
                            if (!anyAppLocked || !grayzoneEnabled) {
                                grayzoneEnabled = it
                                prefs.edit().putBoolean(PrefsKeys.GRAYZONE_ENABLED, it).apply()
                            }
                        },
                        enabled = !anyAppLocked || !grayzoneEnabled,
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
            
            Spacer(Modifier.height(16.dp))

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
            // BUG 5 FIX: Build a single snapshot map of lock state per tick rather than
            // calling prefs.getLong() inside every LazyColumn item on the main thread.
            val lockStateMap = remember(monitoredApps, currentTime) {
                monitoredApps.associateWith { pkg ->
                    Pair(
                        prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L),
                        prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L)
                    )
                }
            }

            LazyColumn {
                items(filtered, key = { it.packageName }) { app ->
                    val isMonitored = monitoredApps.contains(app.packageName)
                    val (activeUntil, lockedUntil) = lockStateMap[app.packageName] ?: Pair(0L, 0L)
                    val isAppLocked = currentTime > activeUntil && currentTime < lockedUntil

                    PremiumAppListItem(
                        app = app,
                        isMonitored = isMonitored,
                        isLocked = isAppLocked,
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
                                    .apply()
                            }
                            monitoredApps = updated
                            prefs.edit().putStringSet(PrefsKeys.MONITORED_APPS, updated).apply()
                        }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
fun PremiumAppListItem(
    app: AppInfo, 
    isMonitored: Boolean, 
    isLocked: Boolean = false, 
    isGloballyDisabled: Boolean = false,
    onToggle: (Boolean) -> Unit
) {
    val isToggleEnabled = !isLocked && !isGloballyDisabled
    val rowAlpha = if (isGloballyDisabled && !isLocked) 0.5f else 1f
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isToggleEnabled) Modifier.clickable { onToggle(!isMonitored) }
                else Modifier
            )
            .background(
                if (isLocked) GZRed.copy(alpha = 0.06f)
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
                Text("🔒 Locked", color = GZRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(app.packageName, color = GZTextTertiary.copy(alpha = rowAlpha), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Switch(
            checked = isMonitored,
            onCheckedChange = { if (isToggleEnabled) onToggle(it) },
            enabled = isToggleEnabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = if (isLocked) GZRed else GZPrimary,
                uncheckedThumbColor = GZTextTertiary,
                uncheckedTrackColor = GZSurfaceHigh,
                disabledCheckedThumbColor = Color.White.copy(alpha = 0.6f),
                disabledCheckedTrackColor = if (isLocked) GZRed.copy(alpha = 0.5f) else GZPrimary.copy(alpha = 0.4f)
            )
        )
    }
    Divider(color = GZBorderSubtle, thickness = 0.5.dp,
        modifier = Modifier.padding(start = 82.dp, end = 20.dp))
}

// â”€â”€â”€ Utilities â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

data class AppInfo(val packageName: String, val name: String, val icon: Bitmap?)

suspend fun getInstalledApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    pm.queryIntentActivities(intent, 0).mapNotNull { info ->
        val pkg = info.activityInfo.packageName
        if (pkg == context.packageName) return@mapNotNull null
        AppInfo(pkg, info.loadLabel(pm).toString(), drawableToBitmap(info.loadIcon(pm)))
    }.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
}

fun drawableToBitmap(drawable: Drawable): Bitmap? {
    // BUG 11 FIX: Scale down to max 96x96 to prevent OOM with large app icon sets.
    val maxSize = 96
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        val src = drawable.bitmap
        return if (src.width <= maxSize && src.height <= maxSize) src
        else Bitmap.createScaledBitmap(src, maxSize, maxSize, true)
    }
    val w = if (drawable.intrinsicWidth > 0) minOf(drawable.intrinsicWidth, maxSize) else maxSize
    val h = if (drawable.intrinsicHeight > 0) minOf(drawable.intrinsicHeight, maxSize) else maxSize
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    drawable.setBounds(0, 0, c.width, c.height)
    drawable.draw(c)
    return bmp
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${AppAccessibilityService::class.java.canonicalName}"
    val enabled = Settings.Secure.getString(context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabled)
    while (splitter.hasNext()) { if (splitter.next().equals(expected, true)) return true }
    return false
}

fun isBatteryOptimized(context: Context): Boolean =
    !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

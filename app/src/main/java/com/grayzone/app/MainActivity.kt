package com.grayzone.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.service.OverlayService
import com.grayzone.app.ui.AppDrawerContent
import com.grayzone.app.ui.AppsScreen
import com.grayzone.app.ui.HomeScreen
import com.grayzone.app.ui.LimitsScreen
import com.grayzone.app.ui.StatsScreen
import com.grayzone.app.ui.NetworkToolsScreen
import com.grayzone.app.ui.OnboardingScreen
import com.grayzone.app.ui.SettingsScreen
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.launch

// ─── Activity ──────────────────────────────────────────────────────────────

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

// ─── Navigation ────────────────────────────────────────────────────────────

enum class Screen { ONBOARDING, MAIN }
enum class Tab { HOME, APPS, LIMITS, STATS, SETTINGS }

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
    // VPN and WiFi screens are opened from the drawer, not the bottom nav
    var showVpnScreen  by remember { mutableStateOf(false) }
    var showWifiScreen by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        context.startForegroundService(Intent(context, OverlayService::class.java))
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            com.grayzone.app.ui.AppDrawerContent(
                onClose = { scope.launch { drawerState.close() } },
                onNavigate = { dest ->
                    when (dest) {
                        com.grayzone.app.ui.DrawerDestination.VPN  -> showVpnScreen  = true
                        com.grayzone.app.ui.DrawerDestination.WIFI -> showWifiScreen = true
                    }
                }
            )
        },
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)
    ) {
        if (showVpnScreen) {
            // Full-screen VPN view with back navigation
            com.grayzone.app.ui.VpnScreen(onBack = { showVpnScreen = false })
        } else if (showWifiScreen) {
            // Full-screen WiFi view with back navigation
            NetworkToolsScreen(onBack = { showWifiScreen = false })
        } else {
            Scaffold(
                containerColor = GZBackground,
                bottomBar = {
                    NavigationBar(
                        containerColor = GZSurface,
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .border(1.dp, GZBorder, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    ) {
                        listOf(
                            Triple(Tab.HOME,     Icons.Filled.Home,                  "Home"),
                            Triple(Tab.APPS,     Icons.AutoMirrored.Filled.List,     "Apps"),
                            Triple(Tab.LIMITS,   Icons.Filled.Timer,                 "Limits"),
                            Triple(Tab.STATS,    Icons.Filled.BarChart,              "Stats"),
                            Triple(Tab.SETTINGS, Icons.Filled.Settings,              "Settings"),
                        ).forEach { (tab, icon, label) ->
                            NavigationBarItem(
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label, fontSize = 10.sp, maxLines = 1) },
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
                        Tab.HOME     -> HomeScreen(onOpenDrawer = { scope.launch { drawerState.open() } })
                        Tab.APPS     -> AppsScreen()
                        Tab.LIMITS   -> LimitsScreen()
                        Tab.STATS    -> StatsScreen()
                        Tab.SETTINGS -> SettingsScreen()
                    }
                }
            }
        }
    }
}

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
import com.grayzone.app.ui.AppsScreen
import com.grayzone.app.ui.HomeScreen
import com.grayzone.app.ui.LimitsScreen
import com.grayzone.app.ui.StatsScreen
import com.grayzone.app.ui.theme.*

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
            if (hasUsageStatsPermission(context) && Settings.canDrawOverlays(context))
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
                    Triple(Tab.LIMITS, Icons.Filled.Timer, "Limits"),
                    Triple(Tab.STATS, Icons.Filled.BarChart, "Stats"),
                    Triple(Tab.SETTINGS, Icons.Filled.Settings, "Settings"),
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
                Tab.HOME -> HomeScreen()
                Tab.APPS -> AppsScreen()
                Tab.LIMITS -> LimitsScreen()
                Tab.STATS -> StatsScreen()
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

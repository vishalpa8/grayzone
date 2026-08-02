package com.grayzone.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.grayzone.app.data.ProtectionHealthRepository
import com.grayzone.app.data.StateBackupManager
import com.grayzone.app.service.OverlayService
import com.grayzone.app.service.vpn.AdBlockVpnService
import com.grayzone.app.service.vpn.VpnHoldExpiryScheduler
import com.grayzone.app.service.vpn.VpnHoldLock
import com.grayzone.app.service.vpn.VpnHoldLockStore
import com.grayzone.app.ui.AppsScreen
import com.grayzone.app.ui.HomeScreen
import com.grayzone.app.ui.LimitsScreen
import com.grayzone.app.ui.OnboardingScreen
import com.grayzone.app.ui.SettingsScreen
import com.grayzone.app.ui.VpnScreen
import com.grayzone.app.ui.theme.*

// ─── Activity ──────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onResume() {
        // Covers devices that revoke all-files access on clear-data: the onCreate restore in
        // GrayzoneApplication couldn't read the hidden backup without the permission, but once the
        // user returns from granting it, restore what was wiped. Runs before super so the UI's
        // ON_RESUME screen re-evaluation sees the restored prefs.
        // F-044: skip binder + decrypt after a successful restore this process; otherwise cheap
        // ONBOARDING_COMPLETED sentinel before hasAllFilesAccess.
        if (!StateBackupManager.restoredThisProcess) {
            val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.contains(PrefsKeys.ONBOARDING_COMPLETED)) {
                if (StateBackupManager.hasAllFilesAccess(this)) {
                    // F-088: clearing the prompt flag on grant lets a later revoke re-prompt.
                    prefs.edit().putBoolean(PrefsKeys.ALL_FILES_PROMPTED, false).apply()
                    StateBackupManager.restoreIfWiped(this)
                } else {
                    requestAllFilesAccessOnce(this)
                }
            }
        }
        super.onResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            var themeMode by remember {
                mutableStateOf(prefs.getString(PrefsKeys.THEME_MODE, ThemeMode.SYSTEM) ?: ThemeMode.SYSTEM)
            }
            // Re-read when returning from Settings (prefs may have changed)
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        themeMode = prefs.getString(PrefsKeys.THEME_MODE, ThemeMode.SYSTEM) ?: ThemeMode.SYSTEM
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            GrayzoneTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = GZBackground) {
                    MainAppContent(onThemeModeChanged = { themeMode = it })
                }
            }
        }
    }
}

/**
 * Sends the user, once, to the system "All files access" screen so the anti-tamper state backup can
 * live in a hidden folder that survives a data/cache wipe. If declined, the backup silently falls
 * back to a visible location, so this is best-effort and never blocks the app.
 */
/**
 * Prompts for storage access used by the anti-tamper backup.
 * - API 30+: all-files manager settings
 * - API 29 (F-131): runtime [WRITE_EXTERNAL_STORAGE] via [onRequestWriteStorage]
 */
private fun requestAllFilesAccessOnce(
    context: Context,
    onRequestWriteStorage: (() -> Unit)? = null
) {
    val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
        // F-131: on API 29 the backup needs the legacy write permission.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (prefs.getBoolean(PrefsKeys.ALL_FILES_PROMPTED, false)) return
        onRequestWriteStorage?.invoke()
        return
    }
    if (android.os.Environment.isExternalStorageManager()) return
    if (prefs.getBoolean(PrefsKeys.ALL_FILES_PROMPTED, false)) return
    // F-209: only suppress future prompts after a successful settings launch.
    val primary = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        android.net.Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (startActivitySafely(context, primary, "allFilesAccess", showToast = false)) {
        prefs.edit().putBoolean(PrefsKeys.ALL_FILES_PROMPTED, true).apply()
        return
    }
    val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (startActivitySafely(context, fallback, "allFilesAccessFallback", showToast = false)) {
        prefs.edit().putBoolean(PrefsKeys.ALL_FILES_PROMPTED, true).apply()
    } else {
        GrayzoneLogger.w(
            LogComponent.BOOT,
            "All-files settings unavailable; will retry later"
        )
    }
}

/**
 * Reconciles the AdBlock VPN with its persisted intent after the app (re)starts — notably after a
 * backup restore, which brings back [PrefsKeys.VPN_ENABLED] and the 24h hold timestamps but does not
 * restart the service, leaving the UI showing a hold countdown while the VPN is actually off.
 *
 * Mirrors [com.grayzone.app.service.BootReceiver]: if the hold already elapsed, drop the stale
 * enable flag; otherwise resume the VPN so the app picks up exactly where the user left off. Runs
 * from the foreground (MainScreen), where starting a foreground service is permitted.
 */
private fun reconcileVpnState(context: Context, onConsentNeeded: (Intent) -> Unit) {
    val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(PrefsKeys.VPN_ENABLED, false)) return

    val hold = VpnHoldLockStore.read(prefs)
    val nowWall = System.currentTimeMillis()
    val nowElapsed = android.os.SystemClock.elapsedRealtime()

    if (hold.isTracked && !VpnHoldLock.isHoldActive(hold, nowWall, nowElapsed)) {
        // Hold finished while the app was gone (e.g. restored from an older backup): clear intent so
        // no phantom "stays on" countdown is shown and the toggle is usable again.
        prefs.edit().putBoolean(PrefsKeys.VPN_ENABLED, false).apply()
        VpnHoldLockStore.clear(prefs)
        return
    }

    if (!ProtectionHealthRepository.status.value.vpnActive) {
        // The restored intent says "VPN on" with an active hold, but the tunnel isn't running.
        val consentIntent = android.net.VpnService.prepare(context)
        if (consentIntent != null) {
            // An uninstall/reinstall (or clear-data on some devices) revoked VPN consent, and Android
            // forbids an app from silently starting a VPN. Rather than give up and force the user to
            // hunt for the toggle, surface the one-tap consent dialog automatically. On approval the
            // tunnel resumes with the SAME restored 24h hold (see the launcher in MainScreen) — so
            // reinstalling can't be used to escape the hold. Keep the hold alarm armed regardless so
            // the countdown stays honest even if the dialog is dismissed.
            VpnHoldExpiryScheduler.rearmFromPrefs(context)
            onConsentNeeded(consentIntent)
            return
        }
        // Consent still present → resume silently, no user interaction needed.
        startRestoredVpn(context)
    }
}

/**
 * Starts the AdBlock VPN after its consent is (re)granted. The 24h hold timestamps are already in
 * prefs from the restore, and [AdBlockVpnService] preserves an in-flight hold via markEnabledIfAbsent,
 * so the resumed tunnel picks up exactly where it left off instead of starting a fresh 24h.
 */
private fun startRestoredVpn(context: Context) {
    try {
        context.startForegroundService(
            Intent(context, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_START
            }
        )
        VpnHoldExpiryScheduler.rearmFromPrefs(context)
    } catch (e: Exception) {
        // F-208: fail closed on health — UI must not keep showing a live hold while tunnel is off.
        GrayzoneLogger.e(LogComponent.BOOT, "Failed to resume restored VPN", e)
        ProtectionHealthRepository.markVpnReconnectPending(true)
    }
}

// ─── Navigation ────────────────────────────────────────────────────────────

enum class Screen { ONBOARDING, MAIN }
enum class Tab { HOME, APPS, VPN, LIMITS, SETTINGS }

@Composable
fun MainAppContent(onThemeModeChanged: (String) -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }

    // Decide the screen using the prompt-once-per-loss policy so a revoked Accessibility permission
    // is surfaced at most once per loss instead of trapping the user in a re-prompt loop.
    // Overlay is required to enter MAIN as "ready", but must not drive the accessibility prompt
    // flag — conflating the two incorrectly reset/re-triggered prompts.
    fun evaluateScreen(): Screen {
        val accessibilityOn = isAccessibilityServiceEnabled(context)
        val overlayOn = Settings.canDrawOverlays(context)
        val decision = AccessibilityPromptPolicy.onObserved(
            previouslyPromptedForLoss = prefs.getBoolean(PrefsKeys.ACCESSIBILITY_PROMPTED_FOR_LOSS, false),
            isEnabled = accessibilityOn
        )
        prefs.edit().putBoolean(PrefsKeys.ACCESSIBILITY_PROMPTED_FOR_LOSS, decision.promptedForLoss).apply()
        val ready = accessibilityOn && overlayOn
        if (ready) {
            prefs.edit().putBoolean(PrefsKeys.ONBOARDING_COMPLETED, true).apply()
            return Screen.MAIN
        }
        // First run must finish every required grant before MAIN. Otherwise granting only
        // Accessibility satisfied the prompt policy and dropped the user into MAIN with the
        // overlay still off — protection silently inactive with no indication of what was missing.
        if (!prefs.getBoolean(PrefsKeys.ONBOARDING_COMPLETED, false)) return Screen.ONBOARDING
        return if (decision.shouldPrompt) Screen.ONBOARDING else Screen.MAIN
    }

    var currentScreen by remember { mutableStateOf(evaluateScreen()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentScreen = evaluateScreen()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (currentScreen) {
        Screen.ONBOARDING -> OnboardingScreen(onContinue = {
            prefs.edit().putBoolean(PrefsKeys.ONBOARDING_COMPLETED, true).apply()
            currentScreen = Screen.MAIN
        })
        Screen.MAIN -> MainScreen(onThemeModeChanged = onThemeModeChanged)
    }
}

@Composable
fun MainScreen(onThemeModeChanged: (String) -> Unit = {}) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(Tab.HOME) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        // F-086: do not silently discard the result — surface denial in logs / health.
        if (!granted) {
            GrayzoneLogger.w(
                LogComponent.BOOT,
                "POST_NOTIFICATIONS denied — ongoing protection notification may be hidden"
            )
        }
    }

    val writeStorageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        // F-131/F-209: only suppress re-prompt after the system dialog was shown.
        prefs.edit().putBoolean(PrefsKeys.ALL_FILES_PROMPTED, true).apply()
        if (!granted) {
            GrayzoneLogger.w(LogComponent.BOOT, "WRITE_EXTERNAL_STORAGE denied — anti-tamper backup inert on API 29")
        }
    }

    // Re-grants VPN consent after a reinstall/clear-data wiped it. On approval we resume the tunnel
    // with the restored 24h hold; nothing to do on denial (the honest countdown keeps showing off).
    val vpnConsentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            startRestoredVpn(context)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vpnConsentLauncher) {
        fun runVpnReconcile() {
            reconcileVpnState(context) { consentIntent ->
                try {
                    vpnConsentLauncher.launch(consentIntent)
                } catch (e: Exception) {
                    // F-210: consent UI missing must not leave hold UI looking healthy.
                    GrayzoneLogger.w(
                        LogComponent.BOOT,
                        "VPN consent activity unavailable: ${e.message}"
                    )
                    ProtectionHealthRepository.markVpnReconnectPending(true)
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // After Force stop the OS clears Accessibility and blocks self-start until the user
                // opens Grayzone again — re-sync health and restart OverlayService when grants return.
                val a11yOn = isAccessibilityServiceEnabled(context)
                ProtectionHealthRepository.updateAccessibilityStatus(a11yOn)
                if (a11yOn && Settings.canDrawOverlays(context)) {
                    try {
                        context.startForegroundService(Intent(context, OverlayService::class.java))
                    } catch (e: Exception) {
                        GrayzoneLogger.e(
                            LogComponent.OVERLAY,
                            "Failed to restart OverlayService on resume: ${e.message}"
                        )
                        ProtectionHealthRepository.updateOverlayStatus(false)
                    }
                } else {
                    ProtectionHealthRepository.updateOverlayStatus(false)
                }
                // F-089: re-check VPN consent whenever the user returns to the app.
                runVpnReconcile()
                // F-086: re-check notifications on every resume (not only the one-shot request).
                if (!areAppNotificationsEnabled(context)) {
                    GrayzoneLogger.w(
                        LogComponent.BOOT,
                        "Notifications disabled — ongoing protection notification may be hidden"
                    )
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        requestAllFilesAccessOnce(context) {
            writeStorageLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        try {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        } catch (e: Exception) {
            GrayzoneLogger.e(
                LogComponent.OVERLAY,
                "Failed to start OverlayService from MainScreen",
                e
            )
        }
        reconcileVpnState(context) { consentIntent ->
            try {
                vpnConsentLauncher.launch(consentIntent)
            } catch (e: Exception) {
                GrayzoneLogger.w(
                    LogComponent.BOOT,
                    "VPN consent activity unavailable: ${e.message}"
                )
                ProtectionHealthRepository.markVpnReconnectPending(true)
            }
        }
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
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
            ) {
                listOf(
                    Triple(Tab.HOME,     Icons.Filled.Home,                  "Home"),
                    Triple(Tab.APPS,     Icons.AutoMirrored.Filled.List,     "Apps"),
                    Triple(Tab.VPN,      Icons.Filled.Security,              "VPN"),
                    Triple(Tab.LIMITS,   Icons.Filled.Timer,                 "Limits"),
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
                            indicatorColor = GZPrimaryContainer
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        // Instant tab structure — no animated stacks on switch (SC-005)
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                Tab.HOME     -> HomeScreen()
                Tab.APPS     -> AppsScreen()
                Tab.VPN      -> VpnScreen()
                Tab.LIMITS   -> LimitsScreen()
                Tab.SETTINGS -> SettingsScreen(onThemeModeChanged = onThemeModeChanged)
            }
        }
    }
}

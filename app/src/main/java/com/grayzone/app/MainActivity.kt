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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.grayzone.app.data.ProtectionHealthRepository
import com.grayzone.app.data.VpnRestoreGate
import com.grayzone.app.service.OverlayService
import com.grayzone.app.service.vpn.AdBlockVpnService
import com.grayzone.app.service.vpn.VpnHoldExpiryScheduler
import com.grayzone.app.service.vpn.VpnHoldLock
import com.grayzone.app.service.vpn.VpnHoldLockStore
import com.grayzone.app.ui.AppsScreen
import com.grayzone.app.ui.HomeScreen
import com.grayzone.app.ui.LimitsScreen
import com.grayzone.app.ui.LocalPermissionGate
import com.grayzone.app.ui.PermissionGate
import com.grayzone.app.ui.PermissionPrompt
import com.grayzone.app.ui.SettingsScreen
import com.grayzone.app.ui.VpnScreen
import com.grayzone.app.ui.theme.*

import dagger.hilt.android.AndroidEntryPoint

// ─── Activity ──────────────────────────────────────────────────────────────

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
 * Reconciles VPN with persisted intent (e.g. after the system revoked VPN consent).
 *
 * @param offerConsent when non-null, may open the system VPN dialog **once** per process
 *   ([VpnRestoreGate]). ON_RESUME passes null so returning from the dialog cannot re-stack prompts.
 *   VPN_ENABLED is only cleared here when the 24h hold has ended.
 */
private fun reconcileVpnState(
    context: Context,
    offerConsent: ((Intent) -> Unit)? = null,
) {
    val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(PrefsKeys.VPN_ENABLED, false)) return

    val hold = VpnHoldLockStore.read(prefs)
    val nowWall = System.currentTimeMillis()
    val nowElapsed = android.os.SystemClock.elapsedRealtime()

    if (hold.isTracked && !VpnHoldLock.isHoldActive(hold, nowWall, nowElapsed)) {
        prefs.edit().putBoolean(PrefsKeys.VPN_ENABLED, false).apply()
        VpnHoldLockStore.clear(prefs)
        return
    }

    if (ProtectionHealthRepository.status.value.vpnActive) return

    val consentIntent = try {
        android.net.VpnService.prepare(context)
    } catch (e: Exception) {
        GrayzoneLogger.w(LogComponent.BOOT, "VpnService.prepare failed during reconcile: ${e.message}")
        ProtectionHealthRepository.markVpnReconnectPending(true)
        return
    }

    if (consentIntent != null) {
        // Keep VPN intent; Android requires a fresh consent after reinstall.
        VpnHoldExpiryScheduler.rearmFromPrefs(context)
        ProtectionHealthRepository.markVpnReconnectPending(true)
        if (offerConsent != null && VpnRestoreGate.tryClaimConsentPrompt()) {
            try {
                offerConsent(consentIntent)
            } catch (e: Exception) {
                GrayzoneLogger.w(
                    LogComponent.BOOT,
                    "VPN consent activity unavailable: ${e.message}"
                )
            }
        }
        return
    }
    resumeVpn(context)
}

/**
 * Starts the AdBlock VPN after its consent is (re)granted. The 24h hold timestamps are already in
 * prefs from the restore, and [AdBlockVpnService] preserves an in-flight hold via markEnabledIfAbsent,
 * so the resumed tunnel picks up exactly where it left off instead of starting a fresh 24h.
 */
private fun resumeVpn(context: Context) {
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

enum class Tab { HOME, APPS, VPN, LIMITS, SETTINGS }

/**
 * Always shows the app; permissions are asked for in a small popup, one at a time, never as a
 * full-screen page. Every popup can be declined, and declining moves on to the next one.
 *
 * All flows run the same [PermissionWalk]:
 *  - [WalkReason.FIRST_RUN]: first launch asks everything once; declined ones are not re-asked.
 *  - [WalkReason.FEATURE]: [PermissionGate.requireSetup] asks every missing permission; the feature
 *    runs only if all of them end up granted.
 *  - [WalkReason.RECOVERY]: Monitoring is on but a core permission was turned off outside the app;
 *    asked once per loss ([AccessibilityPromptPolicy]).
 */
@Composable
fun MainAppContent(onThemeModeChanged: (String) -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }

    fun readMissing(): List<SetupRequirement> = SetupRequirements.missing(
        accessibilityEnabled = isAccessibilityServiceEnabled(context),
        overlayEnabled = Settings.canDrawOverlays(context),
        notificationsEnabled = areAppNotificationsEnabled(context),
        batteryExempt = !isBatteryOptimized(context),
    )

    fun skipped(): Set<SetupRequirement> =
        (prefs.getStringSet(PrefsKeys.SETUP_SKIPPED, emptySet()) ?: emptySet())
            .mapNotNull { name -> SetupRequirement.values().firstOrNull { it.name == name } }
            .toSet()

    var missingNow by remember { mutableStateOf(readMissing()) }
    var walk by remember { mutableStateOf<PermissionWalk?>(null) }
    var reason by remember { mutableStateOf(WalkReason.FIRST_RUN) }
    var onGranted by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun begin(newReason: WalkReason, exclude: Set<SetupRequirement> = emptySet(), action: (() -> Unit)?): Boolean {
        val next = PermissionWalk.start(missingNow, exclude) ?: return false
        reason = newReason
        onGranted = action
        walk = next
        return true
    }

    fun finish(finished: PermissionWalk) {
        val action = onGranted
        walk = null
        onGranted = null
        when (reason) {
            WalkReason.FIRST_RUN ->
                prefs.edit().putBoolean(PrefsKeys.ONBOARDING_COMPLETED, true).apply()
            WalkReason.FEATURE, WalkReason.RECOVERY -> {
                if (finished.succeeded(missingNow)) {
                    action?.invoke()
                    startMonitoringIfReady(context)
                } else {
                    // Counts as "asked for this loss", so the recovery popup does not pop straight back.
                    prefs.edit().putBoolean(PrefsKeys.ACCESSIBILITY_PROMPTED_FOR_LOSS, true).apply()
                    android.widget.Toast.makeText(
                        context,
                        if (reason == WalkReason.RECOVERY) "Protection stays paused until permissions are allowed"
                        else "Not turned on — all permissions are needed",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /** Re-reads every permission; ends the current pass or starts a recovery pass when needed. */
    fun refresh() {
        missingNow = readMissing()
        val active = walk
        if (active != null) {
            if (active.isFinished(missingNow)) finish(active)
            return
        }
        if (!prefs.getBoolean(PrefsKeys.ONBOARDING_COMPLETED, false)) {
            if (!begin(WalkReason.FIRST_RUN, exclude = skipped(), action = null)) {
                prefs.edit().putBoolean(PrefsKeys.ONBOARDING_COMPLETED, true).apply()
            }
            return
        }
        val coreReady = SetupRequirement.ACCESSIBILITY !in missingNow && SetupRequirement.OVERLAY !in missingNow
        if (coreReady) {
            prefs.edit().putBoolean(PrefsKeys.ACCESSIBILITY_PROMPTED_FOR_LOSS, false).apply()
            return
        }
        // Nothing to protect while Monitoring is off; the feature walk asks when it is turned on.
        if (!isMonitoringEnabled(context)) return
        val decision = AccessibilityPromptPolicy.onObserved(
            previouslyPromptedForLoss = prefs.getBoolean(PrefsKeys.ACCESSIBILITY_PROMPTED_FOR_LOSS, false),
            isEnabled = false
        )
        prefs.edit().putBoolean(PrefsKeys.ACCESSIBILITY_PROMPTED_FOR_LOSS, decision.promptedForLoss).apply()
        // Protection only depends on the two core grants, so recovery asks just for those.
        if (decision.shouldPrompt) {
            begin(
                WalkReason.RECOVERY,
                exclude = setOf(SetupRequirement.NOTIFICATIONS, SetupRequirement.BATTERY),
                action = null
            )
        }
    }

    // ON_RESUME is replayed right after the observer is added, so this also covers the first launch.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val gate = remember {
        PermissionGate { action ->
            missingNow = readMissing()
            if (missingNow.isEmpty()) {
                action()
            } else {
                // A feature request replaces any pass in progress (e.g. first-run leftovers).
                begin(WalkReason.FEATURE, action = action)
            }
        }
    }

    CompositionLocalProvider(LocalPermissionGate provides gate) {
        MainScreen(onThemeModeChanged = onThemeModeChanged)
    }

    val active = walk
    val requirement = active?.current(missingNow)
    if (active != null && requirement != null) {
        PermissionPrompt(
            requirement = requirement,
            position = active.position(requirement),
            onDecline = {
                if (reason == WalkReason.FIRST_RUN) {
                    prefs.edit()
                        .putStringSet(PrefsKeys.SETUP_SKIPPED, skipped().map { it.name }.toSet() + requirement.name)
                        .apply()
                }
                walk = active.decline(requirement)
                refresh()
            },
            onChanged = { refresh() },
        )
    }
}
@Composable
fun MainScreen(onThemeModeChanged: (String) -> Unit = {}) {
    val context = LocalContext.current
    var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(Tab.HOME) }

    val vpnConsentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            resumeVpn(context)
            ProtectionHealthRepository.markVpnReconnectPending(false)
        } else {
            // Keep VPN_ENABLED + hold; user can retry from the VPN tab.
            ProtectionHealthRepository.markVpnReconnectPending(true)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // After Force stop the OS clears Accessibility and blocks self-start until the user
                // opens Grayzone again — re-sync health and restart OverlayService when grants return.
                val a11yOn = isAccessibilityServiceEnabled(context)
                ProtectionHealthRepository.updateAccessibilityStatus(a11yOn)
                if (a11yOn && Settings.canDrawOverlays(context) && isMonitoringEnabled(context)) {
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
                // Silent only — never offer consent here (would re-prompt after the dialog returns).
                reconcileVpnState(context, offerConsent = null)
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
        // Monitoring is off until the user turns it on (and grants permissions), so nothing runs yet.
        if (!isMonitoringEnabled(context) ||
            !isAccessibilityServiceEnabled(context) ||
            !Settings.canDrawOverlays(context)
        ) {
            reconcileVpnState(context) { consentIntent -> vpnConsentLauncher.launch(consentIntent) }
            return@LaunchedEffect
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
        // Single place that may re-open VPN consent (at most once per process).
        reconcileVpnState(context) { consentIntent ->
            vpnConsentLauncher.launch(consentIntent)
        }
    }

    Scaffold(
        containerColor = GZBackground,
        bottomBar = {
            Column {
            Divider(color = GZBorderSubtle, thickness = 1.dp)
            NavigationBar(
                containerColor = GZSurface,
                tonalElevation = 0.dp,
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
                        label = {
                            Text(
                                label,
                                fontSize = 11.sp,
                                fontWeight = if (selectedTab == tab) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1
                            )
                        },
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GZPrimary,
                            selectedTextColor = GZTextPrimary,
                            unselectedIconColor = GZTextTertiary,
                            unselectedTextColor = GZTextTertiary,
                            indicatorColor = GZPrimaryContainer
                        )
                    )
                }
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

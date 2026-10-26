package com.grayzone.app.ui
import com.grayzone.app.ui.components.*
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent
import com.grayzone.app.service.vpn.AdBlockVpnService
import com.grayzone.app.service.vpn.DnsTrafficBus
import com.grayzone.app.service.vpn.DnsTrafficBus.DnsEvent.Status
import com.grayzone.app.service.vpn.VpnHoldLock
import com.grayzone.app.service.vpn.VpnHoldLockStore
import com.grayzone.app.ui.theme.*
import com.grayzone.app.ui.components.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class VpnView { MAIN, SETTINGS, SPLIT_TUNNELING, CUSTOM_RULES }

@Composable
fun VpnScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val holdPrefs = remember { VpnHoldLockStore.prefs(context) }

    val protectionStatus by com.grayzone.app.data.ProtectionHealthRepository.status.collectAsState()
    val vpnRunning = protectionStatus.vpnActive
    val reconnectPending = protectionStatus.vpnReconnectPending

    var holdState by remember { mutableStateOf(VpnHoldLockStore.read(holdPrefs)) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showEnableConfirm by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var currentElapsed by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var currentView by remember { mutableStateOf(VpnView.MAIN) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                holdState = VpnHoldLockStore.read(holdPrefs)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Tick once per second only while this screen is visible; when the app is
    // backgrounded the countdown refresh suspends instead of waking the CPU.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                currentTime = System.currentTimeMillis()
                currentElapsed = SystemClock.elapsedRealtime()
                holdState = VpnHoldLockStore.read(holdPrefs)
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    val holdRemaining = VpnHoldLock.remainingMs(holdState, currentTime, currentElapsed)
    val holdActive = holdRemaining > 0L

    val vpnPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            startVpnService(context)
        }
    }

    fun beginEnableVpn() {
        statusMessage = null
        try {
            val permIntent = VpnService.prepare(context)
            if (permIntent != null) {
                vpnPermLauncher.launch(permIntent)
            } else {
                startVpnService(context)
            }
        } catch (e: Exception) {
            // F-211: OEM / work-profile builds may lack the VPN consent activity.
            GrayzoneLogger.w(LogComponent.VPN, "VPN enable failed: ${e.message}", e)
            statusMessage = "Couldn't open VPN settings"
            android.widget.Toast.makeText(
                context,
                "Couldn't open VPN settings",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun tryDisableVpn() {
        val state = VpnHoldLockStore.read(holdPrefs)
        val now = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!VpnHoldLock.canUserDisable(state, now, nowElapsed)) {
            statusMessage =
                "VPN stays on for ${VpnHoldLock.formatRemaining(VpnHoldLock.remainingMs(state, now, nowElapsed))} — then turns off automatically"
            return
        }
        stopVpnService(context)
        holdState = VpnHoldLockStore.read(holdPrefs)
        statusMessage = null
    }

    if (currentView == VpnView.SETTINGS) {
        VpnSettingsScreen(
            onBack = { currentView = VpnView.MAIN },
            onNavigateToSplitTunneling = { currentView = VpnView.SPLIT_TUNNELING },
            onNavigateToCustomRules = { currentView = VpnView.CUSTOM_RULES }
        )
        return
    } else if (currentView == VpnView.SPLIT_TUNNELING) {
        SplitTunnelingScreen(onBack = { currentView = VpnView.SETTINGS })
        return
    } else if (currentView == VpnView.CUSTOM_RULES) {
        CustomRulesScreen(onBack = { currentView = VpnView.SETTINGS })
        return
    }

    // F-161: scrollable chrome above a feed with min height so large fonts cannot collapse the list.
    Column(modifier = Modifier.fillMaxSize().background(GZBackground)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
        ) {
        ScreenHeader(
            title = "AdBlock VPN",
            subtitle = when {
                vpnRunning -> "Filtering DNS on this device"
                reconnectPending -> "Interrupted · reconnecting"
                else -> "Off · ads and trackers not blocked"
            },
            onBack = onBack
        ) {
            IconButton(onClick = { currentView = VpnView.SETTINGS }) {
                Icon(Icons.Filled.Settings, contentDescription = "VPN settings", tint = GZTextSecondary)
            }
        }
        if (reconnectPending && !vpnRunning) {
            GZCard(
                modifier = Modifier.padding(horizontal = GZGutter, vertical = 6.dp),
                background = GZAmberContainer,
                border = GZAmber.copy(alpha = 0.35f)
            ) {
                Text(
                    "Protection interrupted — reconnecting…",
                    color = GZAmber,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        // ── Toggle card ───────────────────────────────────────────────────
        GZCard(modifier = Modifier.padding(horizontal = GZGutter, vertical = 6.dp)) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBadge(Icons.Filled.Security, tint = if (vpnRunning) GZPrimary else GZTextTertiary)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "System-wide blocker",
                        color = GZTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        when {
                            vpnRunning && holdActive ->
                                "Locked on · ${VpnHoldLock.formatRemaining(holdRemaining)} left, then turns off by itself"
                            vpnRunning ->
                                "Blocking ads, trackers and adult sites"
                            else ->
                                "Stays on for 24 hours once enabled, with no early off"
                        },
                        color = GZTextSecondary,
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                GZSwitch(
                    checked = vpnRunning,
                    onCheckedChange = { enable ->
                        if (enable) showEnableConfirm = true else tryDisableVpn()
                    },
                    enabled = !vpnRunning || !holdActive
                )
            }
        }

        statusMessage?.let { msg ->
            Text(
                msg,
                color = GZAmber,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = GZGutter + 4.dp, vertical = 4.dp)
            )
        }

        // ── Stats row (shown when running) ────────────────────────────────
        if (vpnRunning) {
            val events by DnsTrafficBus.events.collectAsState()
            val totalQueries = events.size
            val blockedCount = events.count { it.status != Status.ALLOWED }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GZGutter, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatChip(
                    label = "Queries",
                    value = "$totalQueries",
                    color = GZAccent,
                    modifier = Modifier.weight(1f)
                )
                StatChip(
                    label = "Blocked",
                    value = "$blockedCount",
                    color = GZRed,
                    modifier = Modifier.weight(1f)
                )
                StatChip(
                    label = "Allowed",
                    value = "${totalQueries - blockedCount}",
                    color = GZGreen,
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Bloom Filter Disclosure ───────────────────────────────────
            GZCard(
                modifier = Modifier.padding(horizontal = GZGutter, vertical = 6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = GZTextSecondary,
                        modifier = Modifier.size(18.dp).padding(top = 2.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Note: Bloom filters may occasionally block legitimate domains (~0.1% false positive rate, " +
                        "or about 1 in 1,000 sites). If you encounter blocked sites that shouldn't be, " +
                        "disable the VPN temporarily or report the domain.",
                        color = GZTextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // ── Live traffic feed ─────────────────────────────────────────────
        SectionLabel(
            text = if (vpnRunning) "LIVE DNS TRAFFIC" else "DNS TRAFFIC",
            modifier = Modifier.padding(start = GZGutter + 4.dp, top = 16.dp, bottom = 8.dp)
        )

        if (!vpnRunning) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GZGutter)
                    .clip(GZCardShape)
                    .background(GZSurface)
                    .border(1.dp, if (GZIsDark) GZBorder else GZBorderSubtle, GZCardShape)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Enable the VPN above to see live DNS traffic.",
                    color = GZTextTertiary,
                    fontSize = 13.sp
                )
            }
        }
        } // end scrollable chrome

        if (vpnRunning) {
            LiveTrafficFeed(
                modifier = Modifier
                    .padding(horizontal = GZGutter)
                    .weight(1f)
                    .heightIn(min = 120.dp)
            )
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showEnableConfirm) {
        AlertDialog(
            onDismissRequest = { showEnableConfirm = false },
            title = { Text("Enable AdBlock VPN?") },
            text = {
                Text(
                    "Once enabled, the VPN stays on for 24 hours and cannot be turned off early. " +
                        "After 24 hours it turns off automatically. You can enable it again anytime after that."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEnableConfirm = false
                        beginEnableVpn()
                    }
                ) { Text("Enable for 24 hours", color = GZPrimary, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showEnableConfirm = false }) { Text("Cancel", color = GZTextSecondary) }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = GZSurface,
            titleContentColor = GZTextPrimary,
            textContentColor = GZTextSecondary
        )
    }
}

// ─── Live traffic feed ─────────────────────────────────────────────────────

@Composable
private fun LiveTrafficFeed(modifier: Modifier = Modifier) {
    val events   by DnsTrafficBus.events.collectAsState()
    val listState = rememberLazyListState()

    // F-158: key on newest event id so same-size updates still scroll to top.
    LaunchedEffect(events.firstOrNull()?.id) {
        if (events.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Box(
        modifier = modifier
            .clip(GZCardShape)
            .background(GZSurface)
            .border(1.dp, if (GZIsDark) GZBorder else GZBorderSubtle, GZCardShape)
    ) {
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Waiting for DNS queries…", color = GZTextTertiary, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                items(
                    items = events,
                    key = { it.id }
                ) { event ->
                    VpnTrafficRow(event)
                }
            }
        }
    }
}

@Composable
private fun VpnTrafficRow(event: DnsTrafficBus.DnsEvent) {
    val dotColor = when (event.status) {
        Status.ALLOWED     -> GZAccent
        Status.BLOCKED_AD  -> GZRed
        Status.BLOCKED_DOH -> GZAmber
        Status.FAILED      -> GZTextTertiary
    }
    val textColor = when (event.status) {
        Status.ALLOWED     -> GZTextSecondary
        Status.BLOCKED_AD  -> GZRed.copy(alpha = 0.9f)
        Status.BLOCKED_DOH -> GZAmber.copy(alpha = 0.9f)
        Status.FAILED      -> GZTextTertiary
    }
    val badge = when (event.status) {
        Status.ALLOWED     -> null
        Status.BLOCKED_AD  -> "AD"
        Status.BLOCKED_DOH -> "DOH"
        Status.FAILED      -> "FAIL"
    }
    val time = remember(event.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.timestampMs))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = event.domain,
            color = textColor,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        if (badge != null) {
            Text(
                text = badge,
                color = dotColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(dotColor.copy(alpha = 0.12f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(text = time, color = GZTextTertiary, fontSize = 10.sp)
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    GZCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(label, color = GZTextTertiary, fontSize = 12.sp)
        }
    }
}

// ─── helpers ──────────────────────────────────────────────────────────────

private fun startVpnService(context: Context) {
    try {
        context.startForegroundService(
            Intent(context, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_START
            }
        )
    } catch (e: Exception) {
        GrayzoneLogger.w(LogComponent.VPN, "startVpnService failed: ${e.message}", e)
        android.widget.Toast.makeText(
            context,
            "Couldn't start VPN",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

private fun stopVpnService(context: Context) {
    try {
        context.startService(
            Intent(context, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_STOP
            }
        )
    } catch (e: Exception) {
        GrayzoneLogger.w(LogComponent.VPN, "stopVpnService failed: ${e.message}", e)
        android.widget.Toast.makeText(
            context,
            "Couldn't stop VPN",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

fun refreshVpnNotification(context: Context) {
    try {
        context.startService(
            Intent(context, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_REFRESH_NOTIFICATION
            }
        )
    } catch (e: Exception) {
        GrayzoneLogger.w(LogComponent.VPN, "refreshVpnNotification failed: ${e.message}", e)
    }
}

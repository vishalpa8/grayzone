package com.grayzone.app.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
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
import com.grayzone.app.service.vpn.AdBlockVpnService
import com.grayzone.app.service.vpn.DnsTrafficBus
import com.grayzone.app.service.vpn.DnsTrafficBus.DnsEvent.Status
import com.grayzone.app.service.vpn.VpnHoldLock
import com.grayzone.app.service.vpn.VpnHoldLockStore
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

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
        val permIntent = VpnService.prepare(context)
        if (permIntent != null) {
            vpnPermLauncher.launch(permIntent)
        } else {
            startVpnService(context)
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

    Column(modifier = Modifier.fillMaxSize().background(GZBackground)) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = GZTextPrimary
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    Icons.Filled.Security,
                    contentDescription = null,
                    tint = if (vpnRunning) GZAccent else GZTextTertiary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "AdBlock VPN",
                        color = GZTextPrimary,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp
                    )
                    Text(
                        when {
                            vpnRunning -> "Filtering DNS traffic"
                            reconnectPending -> "Protection interrupted — reconnecting"
                            else -> "Off — ads not blocked"
                        },
                        color = when {
                            vpnRunning -> GZAccent
                            reconnectPending -> GZAmber
                            else -> GZTextSecondary
                        },
                        fontSize = 13.sp
                    )
                }
            }
        }

        if (reconnectPending && !vpnRunning) {
            com.grayzone.app.GZCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
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
        com.grayzone.app.GZCard(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            background = if (vpnRunning) GZPrimaryContainer else GZSurface,
            border = if (vpnRunning) GZPrimary.copy(alpha = 0.25f) else GZBorder
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "System-Wide Blocker",
                        color = GZTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        when {
                            vpnRunning && holdActive ->
                                "Stays on for ${VpnHoldLock.formatRemaining(holdRemaining)}, then turns off automatically."
                            vpnRunning ->
                                "Blocks ~800k ad/tracker and ~160k adult domains via local VPN DNS filter."
                            else ->
                                "Once on, it stays locked for 24 hours — you can’t turn it off early."
                        },
                        color = GZTextSecondary,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = vpnRunning,
                    onCheckedChange = { enable ->
                        if (enable) {
                            showEnableConfirm = true
                        } else {
                            tryDisableVpn()
                        }
                    },
                    enabled = !vpnRunning || !holdActive,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = GZAccent,
                        uncheckedThumbColor = GZTextTertiary,
                        uncheckedTrackColor = GZSurfaceHigh,
                        disabledCheckedThumbColor = Color.White,
                        disabledCheckedTrackColor = GZAccent.copy(alpha = 0.5f)
                    )
                )
            }
        }

        if (!vpnRunning) {
            com.grayzone.app.GZCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                background = GZAmberContainer,
                border = GZAmber.copy(alpha = 0.35f)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "24-hour lock",
                        color = GZTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Enabling AdBlock VPN locks it on for 24 hours. After that it turns off by itself — there is no early disable.",
                        color = GZTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        statusMessage?.let { msg ->
            Text(
                msg,
                color = GZAmber,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
        }

        if (vpnRunning && holdActive) {
            com.grayzone.app.GZCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                background = GZPrimaryContainer,
                border = GZPrimary.copy(alpha = 0.25f)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Locked on — ${VpnHoldLock.formatRemaining(holdRemaining)} left",
                        color = GZTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Cannot turn off until the timer ends. It will disable itself automatically.",
                        color = GZTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // ── Stats row (shown when running) ────────────────────────────────
        if (vpnRunning) {
            val events by DnsTrafficBus.events.collectAsState()
            val totalQueries = events.size
            val blockedCount = events.count { it.status != Status.ALLOWED }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
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
            com.grayzone.app.GZCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                background = GZSurfaceElevated,
                border = GZBorder
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
        com.grayzone.app.SectionLabel(
            text = if (vpnRunning) "LIVE DNS TRAFFIC" else "DNS TRAFFIC",
            modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp)
        )

        if (!vpnRunning) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(GZSurface)
                    .border(1.dp, GZBorder, RoundedCornerShape(24.dp))
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Enable the VPN above to see live DNS traffic.",
                    color = GZTextTertiary,
                    fontSize = 13.sp
                )
            }
        } else {
            LiveTrafficFeed(modifier = Modifier.padding(horizontal = 20.dp).weight(1f))
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
                ) { Text("Enable for 24 hours", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showEnableConfirm = false }) { Text("Cancel") }
            },
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
    val scope     = rememberCoroutineScope()

    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) scope.launch { listState.animateScrollToItem(0) }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(GZSurface)
            .border(1.dp, GZBorder, RoundedCornerShape(24.dp))
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
    }
    val textColor = when (event.status) {
        Status.ALLOWED     -> GZTextSecondary
        Status.BLOCKED_AD  -> GZRed.copy(alpha = 0.9f)
        Status.BLOCKED_DOH -> GZAmber.copy(alpha = 0.9f)
    }
    val badge = when (event.status) {
        Status.ALLOWED     -> null
        Status.BLOCKED_AD  -> "AD"
        Status.BLOCKED_DOH -> "DOH"
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
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = color, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        Text(label, color = GZTextTertiary, fontSize = 11.sp)
    }
}

// ─── helpers ──────────────────────────────────────────────────────────────

private fun startVpnService(context: Context) {
    context.startForegroundService(
        Intent(context, AdBlockVpnService::class.java).apply { action = AdBlockVpnService.ACTION_START }
    )
}

private fun stopVpnService(context: Context) {
    context.startService(
        Intent(context, AdBlockVpnService::class.java).apply { action = AdBlockVpnService.ACTION_STOP }
    )
}

fun refreshVpnNotification(context: Context) {
    context.startService(
        Intent(context, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_REFRESH_NOTIFICATION
        }
    )
}

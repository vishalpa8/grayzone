package com.grayzone.app.ui

import android.content.Context
import android.content.Intent
import android.net.VpnService
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.PrefsKeys
import com.grayzone.app.service.vpn.AdBlockVpnService
import com.grayzone.app.service.vpn.DnsTrafficBus
import com.grayzone.app.service.vpn.DnsTrafficBus.DnsEvent.Status
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun VpnScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs   = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }

    var vpnRunning by remember { mutableStateOf(AdBlockVpnService.isRunning) }

    // Poll VPN state every second — lightweight, no broadcast needed
    LaunchedEffect(Unit) {
        while (true) {
            vpnRunning = AdBlockVpnService.isRunning
            kotlinx.coroutines.delay(1000)
        }
    }

    val vpnPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            startVpnService(context)
            prefs.edit().putBoolean(PrefsKeys.VPN_ENABLED, true).apply()
            vpnRunning = true
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(GZBackground)) {

        // ── Header ────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(GZPrimaryDark, GZPrimaryContainer, GZBackground)))
                .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 32.dp)
        ) {
            Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                                tint = GZTextPrimary)
                        }
                        Spacer(Modifier.width(4.dp))
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
                            if (vpnRunning) "Filtering DNS traffic" else "Off — ads not blocked",
                            color = if (vpnRunning) GZAccent else GZTextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // ── Toggle card ───────────────────────────────────────────────────
        com.grayzone.app.GZCard(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            background = if (vpnRunning) GZAccent.copy(alpha = 0.07f) else GZSurface,
            border = if (vpnRunning) GZAccent.copy(alpha = 0.3f) else GZBorder
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
                        "Blocks ~800k ad/tracker and ~160k adult domains via local VPN DNS filter.",
                        color = GZTextSecondary,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = vpnRunning,
                    onCheckedChange = { enable ->
                        if (enable) {
                            val permIntent = VpnService.prepare(context)
                            if (permIntent != null) {
                                vpnPermLauncher.launch(permIntent)
                            } else {
                                startVpnService(context)
                                prefs.edit().putBoolean(PrefsKeys.VPN_ENABLED, true).apply()
                                vpnRunning = true
                            }
                        } else {
                            stopVpnService(context)
                            prefs.edit().putBoolean(PrefsKeys.VPN_ENABLED, false).apply()
                            vpnRunning = false
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = GZAccent,
                        uncheckedThumbColor = GZTextTertiary,
                        uncheckedTrackColor = GZSurfaceHigh
                    )
                )
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
                background = GZSurfaceHigh,
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

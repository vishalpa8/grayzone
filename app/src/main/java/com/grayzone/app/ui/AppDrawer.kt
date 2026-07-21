package com.grayzone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.service.vpn.AdBlockVpnService
import com.grayzone.app.ui.theme.*

/**
 * Slide-in drawer. Each row navigates to a full screen via [onNavigate].
 *
 *   ┌─────────────────────────┐
 *   │  Grayzone               │
 *   │  Menu                   │
 *   ├─────────────────────────┤
 *   │  🔒 AdBlock VPN      ›  │
 *   ├─────────────────────────┤
 *   │  (future items)         │
 *   └─────────────────────────┘
 */
@Composable
fun AppDrawerContent(
    onClose: () -> Unit,
    onNavigate: (DrawerDestination) -> Unit = {}
) {
    val protectionStatus by com.grayzone.app.data.ProtectionHealthRepository.status.collectAsState()
    val vpnRunning = protectionStatus.vpnActive

    // WiFi connected state
    val context     = LocalContext.current
    val wifiManager = remember {
        context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
            as android.net.wifi.WifiManager
    }
    var wifiEnabled by remember { mutableStateOf(wifiManager.isWifiEnabled) }

    LaunchedEffect(Unit) {
        while (true) {
            wifiEnabled = wifiManager.isWifiEnabled
            kotlinx.coroutines.delay(3000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(GZSurface)
            .border(1.dp, GZBorder, RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp))
            .clip(RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp))
            .padding(top = 56.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Grayzone", color = GZTextPrimary, fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp, letterSpacing = (-0.3).sp)
            Text("Menu", color = GZTextTertiary, fontSize = 12.sp)
        }

        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(GZBorder))
        Spacer(Modifier.height(8.dp))

        // ── Nav: AdBlock VPN ──────────────────────────────────────────────
        DrawerNavRow(
            icon = { Icon(Icons.Filled.Security, contentDescription = null,
                tint = if (vpnRunning) GZAccent else GZTextTertiary,
                modifier = Modifier.size(20.dp)) },
            title = "AdBlock VPN",
            subtitle = if (vpnRunning) "Active — filtering DNS" else "Off",
            subtitleColor = if (vpnRunning) GZAccent else GZTextTertiary,
            onClick = { onNavigate(DrawerDestination.VPN); onClose() }
        )

        // ── Nav: WiFi Monitor ─────────────────────────────────────────────
        DrawerNavRow(
            icon = { Icon(Icons.Filled.Wifi, contentDescription = null,
                tint = if (wifiEnabled) GZChartCyan else GZTextTertiary,
                modifier = Modifier.size(20.dp)) },
            title = "Network Tools",
            subtitle = if (wifiEnabled) "Connected — tap to explore" else "WiFi off",
            subtitleColor = if (wifiEnabled) GZChartCyan else GZTextTertiary,
            onClick = { onNavigate(DrawerDestination.WIFI); onClose() }
        )

        // ── (Future nav items — add DrawerNavRow() calls here) ────────────

        Spacer(modifier = Modifier.weight(1f))

        // ── Footer ────────────────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(GZBorder))
        Text("Grayzone", color = GZTextTertiary, fontSize = 11.sp,
            modifier = Modifier.padding(24.dp))
    }
}

// ─── Destinations the drawer can navigate to ──────────────────────────────

enum class DrawerDestination { VPN, WIFI }

// ─── Reusable nav row ─────────────────────────────────────────────────────

@Composable
private fun DrawerNavRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    subtitleColor: androidx.compose.ui.graphics.Color = GZTextTertiary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = GZTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = subtitleColor, fontSize = 11.sp)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null,
            tint = GZTextTertiary, modifier = Modifier.size(18.dp))
    }
}

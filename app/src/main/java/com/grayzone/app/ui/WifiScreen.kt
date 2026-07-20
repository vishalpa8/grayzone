package com.grayzone.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.grayzone.app.data.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.grayzone.app.GZCard
import com.grayzone.app.SectionLabel
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress

// ─── Main screen ──────────────────────────────────────────────────────────

fun getDeviceTypeIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.PHONE      -> Icons.Filled.PhoneAndroid
    DeviceType.LAPTOP     -> Icons.Filled.Laptop
    DeviceType.TABLET     -> Icons.Filled.TabletAndroid
    DeviceType.TV         -> Icons.Filled.Tv
    DeviceType.SMART_HOME -> Icons.Filled.Home
    DeviceType.ROUTER     -> Icons.Filled.Router
    DeviceType.UNKNOWN    -> Icons.Filled.DeviceUnknown
}

private fun openRouterAdminPage(context: Context, routerIp: String) {
    val urls = listOf(
        "http://$routerIp",
        "https://$routerIp",
        "http://$routerIp:80",
        "http://$routerIp:8080"
    )

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urls.first())).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }

    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        val fallback = urls.drop(1).firstOrNull()
        if (fallback != null) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)))
            } catch (_: Exception) {
                Toast.makeText(context, "Unable to open router admin page", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Unable to open router admin page", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun WifiScreen(onBack: () -> Unit = {}) {
    val context    = LocalContext.current
    val repo       = remember { WifiRepository(context) }
    val locPerm    = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)

    var networkInfo by remember { mutableStateOf(WifiNetworkInfo()) }
    var devices     by remember { mutableStateOf<List<WifiDevice>>(emptyList()) }
    var isScanning  by remember { mutableStateOf(false) }

    LaunchedEffect(locPerm.status.isGranted) {
        if (!locPerm.status.isGranted) return@LaunchedEffect
        while (isActive) {
            isScanning = true
            networkInfo = repo.readNetworkInfo()
            devices = repo.scanDevices()
            isScanning = false
            delay(8_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GZBackground)
    ) {
        WifiHeader(networkInfo = networkInfo, onBack = onBack)

        if (!locPerm.status.isGranted) {
            // ── Permission gate ────────────────────────────────────────────
            PermissionGate(
                shouldShowRationale = locPerm.status.shouldShowRationale,
                onRequest = { locPerm.launchPermissionRequest() }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item { NetworkInfoPanel(networkInfo = networkInfo) }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionLabel(
                            text = "CONNECTED DEVICES",
                            modifier = Modifier.weight(1f)
                        )
                        ScanStatus(isScanning = isScanning, deviceCount = devices.size)
                    }
                }

                item {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (devices.isEmpty() && !isScanning) {
                            EmptyDevicesCard()
                        }
                    }
                }

                items(items = devices, key = { it.ip }) { device ->
                    DeviceCard(
                        device   = device,
                        onBlock  = { ip, block ->
                            repo.setBlocked(ip, block)
                            devices = devices.map {
                                if (it.ip == ip) it.copy(isBlocked = block) else it
                            }
                        },
                        onRename = { ip, name ->
                            repo.saveCustomName(ip, name)
                            devices = devices.map {
                                if (it.ip == ip) it.copy(customName = name) else it
                            }
                        }
                    )
                }

                item { WifiTipCard() }
            }
        }
    }
}


// ─── Header ───────────────────────────────────────────────────────────────

@Composable
private fun WifiHeader(networkInfo: WifiNetworkInfo, onBack: () -> Unit) {
    val signalColor = when (networkInfo.signalBars) {
        4    -> GZGreen
        3    -> GZAccent
        2    -> GZAmber
        1    -> GZRed
        else -> GZTextTertiary
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(GZPrimaryDark, GZPrimaryContainer, GZBackground)))
            .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = GZTextPrimary)
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.Wifi, null,
                tint     = if (networkInfo.isConnected) signalColor else GZTextTertiary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text       = if (networkInfo.isConnected) networkInfo.ssid else "WiFi Monitor",
                    color      = GZTextPrimary,
                    fontSize   = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                )
                Text(
                    text  = if (networkInfo.isConnected)
                        "${networkInfo.rssi} dBm · ${networkInfo.band}"
                    else
                        "Not connected to WiFi",
                    color = if (networkInfo.isConnected) signalColor else GZTextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

// ─── Permission gate ──────────────────────────────────────────────────────

@Composable
private fun PermissionGate(shouldShowRationale: Boolean, onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(GZSurface)
            .border(1.dp, GZBorder, RoundedCornerShape(24.dp))
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.LocationOff, null,
                tint     = GZAmber,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Location Permission Required",
                color      = GZTextPrimary,
                fontSize   = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (shouldShowRationale)
                    "Android requires Location access to read your WiFi network name (SSID) " +
                    "and discover nearby devices. No location data is stored or shared."
                else
                    "Tap below to grant Location permission so Grayzone can read your WiFi " +
                    "details and scan for connected devices.",
                color      = GZTextSecondary,
                fontSize   = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRequest,
                colors  = ButtonDefaults.buttonColors(containerColor = GZPrimary),
                shape   = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Lock, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Grant Permission", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ─── Network info panel ───────────────────────────────────────────────────

@Composable
private fun NetworkInfoPanel(networkInfo: WifiNetworkInfo) {
    GZCard(
        modifier   = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        background = GZSurface,
        border     = GZBorder
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "Network Details",
                color      = GZTextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 14.sp
            )
            Spacer(Modifier.height(14.dp))

            if (!networkInfo.isConnected) {
                Text("Not connected to WiFi", color = GZTextTertiary, fontSize = 13.sp)
            } else {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SignalBarsRow(bars = networkInfo.signalBars)
                    Text("${networkInfo.rssi} dBm", color = GZTextSecondary, fontSize = 12.sp)
                }

                Spacer(Modifier.height(14.dp))
                Divider(color = GZBorder, thickness = 1.dp)
                Spacer(Modifier.height(14.dp))

                NetworkInfoGrid(
                    listOf(
                        "IP Address"  to networkInfo.ipAddress,
                        "Gateway"     to networkInfo.gateway,
                        "Subnet"      to "${networkInfo.subnetPrefix}.0/24",
                        "BSSID"       to networkInfo.bssid.ifBlank { "—" },
                        "Band"        to "${networkInfo.band} (ch ${networkInfo.channel})",
                        "Link Speed"  to "${networkInfo.linkSpeed} Mbps",
                        "SSID"        to networkInfo.ssid,
                        "Status"      to "Connected"
                    )
                )
            }
        }
    }
}

@Composable
private fun SignalBarsRow(bars: Int) {
    val color = when (bars) {
        4    -> GZGreen
        3    -> GZAccent
        2    -> GZAmber
        1    -> GZRed
        else -> GZTextTertiary
    }
    val label = when (bars) {
        4    -> "Excellent"
        3    -> "Good"
        2    -> "Fair"
        1    -> "Poor"
        else -> "No Signal"
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 1..4) {
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .height((6 + i * 5).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= bars) color else GZSurfaceHigh)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NetworkInfoGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, color = GZTextTertiary, fontSize = 10.sp)
                        Text(
                            value,
                            color      = GZTextPrimary,
                            fontSize   = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}


// ─── Scan Status ──────────────────────────────────────────────────────────

@Composable
private fun ScanStatus(isScanning: Boolean, deviceCount: Int) {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue  = 0.3f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    
    if (isScanning) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(GZAccent.copy(alpha = alpha))
            )
            Spacer(Modifier.width(6.dp))
            Text("Scanning…", color = GZAccent.copy(alpha = alpha), fontSize = 11.sp)
        }
    } else {
        Text(
            "$deviceCount found",
            color    = GZTextTertiary,
            fontSize = 11.sp
        )
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────

@Composable
private fun EmptyDevicesCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(GZSurface)
            .border(1.dp, GZBorder, RoundedCornerShape(24.dp))
            .padding(36.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.WifiFind, null,
                tint     = GZTextTertiary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "No devices found",
                color      = GZTextSecondary,
                fontSize   = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "A ping sweep is sent across the subnet to populate the ARP table. " +
                "Devices that block ICMP may not appear.",
                color      = GZTextTertiary,
                fontSize   = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

// ─── Device card ──────────────────────────────────────────────────────────

@Composable
private fun DeviceCard(
    device   : WifiDevice,
    onBlock  : (ip: String, block: Boolean) -> Unit,
    onRename : (ip: String, name: String)   -> Unit
) {
    var expanded       by remember { mutableStateOf(false) }
    var showRename     by remember { mutableStateOf(false) }
    var renameText     by remember(device.customName) { mutableStateOf(device.customName) }
    val focusRequester = remember { FocusRequester() }
    val focusManager   = LocalFocusManager.current

    val displayName = inferDeviceDisplayName(device.ip, device.hostname, device.customName, device.isGateway)
    val borderColor = if (device.isBlocked) GZRed.copy(alpha = 0.35f) else GZBorder
    val bgColor     = if (device.isBlocked) GZRed.copy(alpha = 0.04f) else GZSurface

    GZCard(
        modifier   = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
        background = bgColor,
        border     = borderColor
    ) {
        Column {
            // ── Collapsed row ────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Device type icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (device.isBlocked) GZRed.copy(alpha = 0.12f)
                            else GZPrimary.copy(alpha = 0.10f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = getDeviceTypeIcon(device.deviceType),
                        contentDescription = null,
                        tint               = if (device.isBlocked) GZRed else GZPrimaryLight,
                        modifier           = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        displayName,
                        color      = if (device.isBlocked) GZRed else GZTextPrimary,
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    Text(
                        if (device.isGateway) "Gateway • router" else device.ip,
                        color = GZTextTertiary,
                        fontSize = 11.sp
                    )
                }
                if (device.isBlocked) {
                    Text(
                        "BLOCKED",
                        color      = GZRed,
                        fontSize   = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier   = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(GZRed.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint     = GZTextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // ── Expanded detail (always composed, height controlled) ──────
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                ) {
                Divider(color = GZBorder, thickness = 1.dp)
                Spacer(Modifier.height(12.dp))

                Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        InfoPill("IP Address", device.ip)
                        InfoPill(
                            "Type",
                            device.deviceType.name.lowercase().replaceFirstChar { it.uppercase() }
                        )
                    }
                Spacer(Modifier.height(14.dp))

                // Rename field
                if (showRename) {
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    OutlinedTextField(
                            value           = renameText,
                            onValueChange   = { renameText = it },
                            label           = { Text("Custom name", fontSize = 12.sp) },
                            singleLine      = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                onRename(device.ip, renameText.trim())
                                focusManager.clearFocus()
                                showRename = false
                            }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = GZPrimary,
                                unfocusedBorderColor = GZBorder,
                                cursorColor          = GZPrimary,
                                focusedLabelColor    = GZPrimary,
                                focusedTextColor     = GZTextPrimary,
                                unfocusedTextColor   = GZTextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    // Action buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (showRename) {
                                    onRename(device.ip, renameText.trim())
                                    focusManager.clearFocus()
                                }
                                showRename = !showRename
                            },
                            border   = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = androidx.compose.ui.graphics.SolidColor(GZPrimary.copy(alpha = 0.5f))
                            ),
                            shape    = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                if (showRename) Icons.Filled.Check else Icons.Filled.Edit,
                                null, tint = GZPrimary, modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (showRename) "Save" else "Rename",
                                color = GZPrimary, fontSize = 12.sp
                            )
                        }

                        if (device.isGateway) {
                            val context = LocalContext.current
                            OutlinedButton(
                                onClick = { openRouterAdminPage(context, device.ip) },
                                border = ButtonDefaults.outlinedButtonBorder.copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(GZPrimary.copy(alpha = 0.5f))
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = GZPrimary.copy(alpha = 0.08f)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(Icons.Filled.Router, null, tint = GZPrimary, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Manage", color = GZPrimary, fontSize = 12.sp)
                            }
                        } else {
                            val blockColor  = if (device.isBlocked) GZGreen else GZRed
                            val blockIcon   = if (device.isBlocked) Icons.Filled.LockOpen else Icons.Filled.Block
                            val blockLabel  = if (device.isBlocked) "Unblock" else "Block"

                            OutlinedButton(
                                onClick  = { onBlock(device.ip, !device.isBlocked) },
                                border   = ButtonDefaults.outlinedButtonBorder.copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(blockColor.copy(alpha = 0.4f))
                                ),
                                colors   = ButtonDefaults.outlinedButtonColors(
                                    containerColor = blockColor.copy(alpha = 0.10f)
                                ),
                                shape    = RoundedCornerShape(10.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(blockIcon, null, tint = blockColor, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(blockLabel, color = blockColor, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}


// ─── Small helpers ────────────────────────────────────────────────────────

@Composable
private fun InfoPill(label: String, value: String) {
    Column {
        Text(label, color = GZTextTertiary, fontSize = 10.sp)
        Text(
            value,
            color      = GZTextPrimary,
            fontSize   = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun WifiTipCard() {
    GZCard(
        modifier   = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        background = GZSurfaceHigh,
        border     = GZBorder
    ) {
        Row(
            modifier          = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Filled.Info, null,
                tint     = GZTextSecondary,
                modifier = Modifier
                    .size(18.dp)
                    .padding(top = 2.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Device discovery sends ICMP pings across the /24 subnet. " +
                "Devices that block pings won't appear. The Block button " +
                "records the IP locally — router-level enforcement requires root or " +
                "router admin access. Note: Tracking relies on IP; if a device's IP changes, it will appear as new.",
                color      = GZTextSecondary,
                fontSize   = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

package com.grayzone.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.grayzone.app.GZCard
import com.grayzone.app.SectionLabel
import com.grayzone.app.data.*
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun NetworkToolsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { WifiRepository(context) }
    val locPerm = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)

    var networkInfo by remember { mutableStateOf(WifiNetworkInfo()) }
    var devices by remember { mutableStateOf<List<WifiDevice>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }

    var showClipboardShare by remember { mutableStateOf(false) }
    var showFileShare by remember { mutableStateOf(false) }
    var showSpeedTest by remember { mutableStateOf(false) }
    var showPortScanner by remember { mutableStateOf(false) }
    var selectedScanIp by remember { mutableStateOf("") }
    var showWolDialog by remember { mutableStateOf(false) }

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

    if (showClipboardShare) {
        ClipboardShareScreen(networkInfo = networkInfo, onNavigateBack = { showClipboardShare = false })
    } else if (showFileShare) {
        FileShareScreen(networkInfo = networkInfo, onNavigateBack = { showFileShare = false })
    } else if (showSpeedTest) {
        SpeedTestScreen(networkInfo = networkInfo, onBack = { showSpeedTest = false })
    } else if (showPortScanner) {
        PortScannerScreen(ip = selectedScanIp, onBack = { showPortScanner = false })
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(GZBackground)
        ) {
            WifiHeader(networkInfo = networkInfo, onBack = onBack)

            if (!locPerm.status.isGranted) {
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
                        SectionLabel(
                            text = "NETWORK TOOLS",
                            modifier = Modifier.padding(start = 24.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)
                        )
                    }

                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                ToolCard(
                                    modifier = Modifier.weight(1f),
                                    title = "Clipboard Share",
                                    subtitle = "Sync across devices",
                                    icon = Icons.Filled.ContentPaste,
                                    color = GZChartCyan,
                                    onClick = { showClipboardShare = true }
                                )
                                Spacer(Modifier.width(12.dp))
                                ToolCard(
                                    modifier = Modifier.weight(1f),
                                    title = "File Share",
                                    subtitle = "Local transfer",
                                    icon = Icons.Filled.Share,
                                    color = GZAccent,
                                    onClick = { showFileShare = true }
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                ToolCard(
                                    modifier = Modifier.weight(1f),
                                    title = "Speed Test",
                                    subtitle = "Measure bandwidth",
                                    icon = Icons.Filled.Speed,
                                    color = GZChartBlue,
                                    onClick = { showSpeedTest = true }
                                )
                                Spacer(Modifier.width(12.dp))
                                ToolCard(
                                    modifier = Modifier.weight(1f),
                                    title = "Port Scanner",
                                    subtitle = "Find open ports",
                                    icon = Icons.Filled.Search,
                                    color = GZChartPink,
                                    onClick = {
                                        selectedScanIp = networkInfo.gateway
                                        showPortScanner = true
                                    }
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                ToolCard(
                                    modifier = Modifier.weight(1f),
                                    title = "Wake-on-LAN",
                                    subtitle = "Wake sleeping devices",
                                    icon = Icons.Filled.PowerSettingsNew,
                                    color = GZChartOrange,
                                    onClick = { showWolDialog = true }
                                )
                                Spacer(Modifier.width(12.dp))
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
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
                        SimpleDeviceCard(
                            device = device,
                            onClick = {
                                selectedScanIp = device.ip
                                showPortScanner = true
                            },
                            onManage = { openRouterAdminPage(context, device.ip) }
                        )
                    }

                    item { WifiTipCard() }
                }
            }
        }
    }
}

@Composable
fun ToolCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    GZCard(
        modifier = modifier,
        background = GZSurface,
        border = GZBorder,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                title,
                color = GZTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = GZTextSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
fun SimpleDeviceCard(
    device: WifiDevice,
    onClick: () -> Unit,
    onManage: () -> Unit
) {
    val displayName = inferDeviceDisplayName(device.ip, device.hostname, device.isGateway)
    
    GZCard(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
        background = GZSurface,
        border = GZBorder,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(GZPrimary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getDeviceTypeIcon(device.deviceType),
                    contentDescription = null,
                    tint = GZPrimaryLight,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    displayName,
                    color = GZTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (device.isGateway) "Gateway • router" else device.ip,
                    color = GZTextTertiary,
                    fontSize = 11.sp
                )
            }
            
            if (device.isGateway) {
                OutlinedButton(
                    onClick = onManage,
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(GZPrimary.copy(alpha = 0.5f))
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = GZPrimary.copy(alpha = 0.08f)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Filled.Router, null, tint = GZPrimary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Manage", color = GZPrimary, fontSize = 11.sp)
                }
            } else {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = GZTextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun getDeviceTypeIcon(type: DeviceType): ImageVector = when (type) {
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
                    text       = if (networkInfo.isConnected) networkInfo.ssid else "Network Tools",
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
                "Tap any device to run a port scan. The Network Tools provide additional functionality like file sharing and WOL. Gateway devices include a button to directly open their admin panel.",
                color      = GZTextSecondary,
                fontSize   = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

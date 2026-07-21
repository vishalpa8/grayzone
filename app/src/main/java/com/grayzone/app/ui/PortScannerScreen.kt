package com.grayzone.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.GZCard
import com.grayzone.app.SectionLabel
import com.grayzone.app.data.PortResult
import com.grayzone.app.data.PortScanner
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortScannerScreen(
    ip: String = "",
    onBack: () -> Unit = {}
) {
    var targetIp by remember { mutableStateOf(ip) }
    var results by remember { mutableStateOf<List<PortResult>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var scanComplete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(ip) {
        if (ip.isNotBlank()) {
            isScanning = true
            results = PortScanner.scanPorts(ip) { scanned, total ->
                progress = scanned.toFloat() / total
            }
            isScanning = false
            scanComplete = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GZBackground)
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(GZPrimaryContainer, GZBackground)
                    )
                )
                .padding(top = 48.dp, bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = GZTextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = GZPrimaryLight,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Port Scanner",
                        color = GZTextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (targetIp.isNotBlank()) targetIp else "Scan open ports",
                        color = GZTextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                GZCard(
                    modifier = Modifier.padding(top = 8.dp),
                    background = GZSurfaceElevated,
                    border = GZBorder
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        OutlinedTextField(
                            value = targetIp,
                            onValueChange = { targetIp = it },
                            label = { Text("Target IP Address", color = GZTextSecondary) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                focusedBorderColor = GZPrimary,
                                unfocusedBorderColor = GZBorderSubtle,
                                cursorColor = GZPrimary,
                                focusedTextColor = GZTextPrimary,
                                unfocusedTextColor = GZTextPrimary,
                                containerColor = GZSurface
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isScanning,
                            singleLine = true
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Button(
                            onClick = {
                                scope.launch {
                                    if (targetIp.isNotBlank()) {
                                        isScanning = true
                                        scanComplete = false
                                        progress = 0f
                                        results = emptyList()
                                        results = PortScanner.scanPorts(targetIp) { scanned, total ->
                                            progress = scanned.toFloat() / total
                                        }
                                        isScanning = false
                                        scanComplete = true
                                    }
                                }
                            },
                            enabled = !isScanning && targetIp.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GZPrimary,
                                disabledContainerColor = GZSurfaceHigh
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text(
                                text = if (isScanning) "Scanning..." else "Scan",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isScanning) GZTextSecondary else GZTextPrimary
                            )
                        }
                    }
                }
            }

            if (isScanning) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = GZPrimary,
                            trackColor = GZSurfaceHigh
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            color = GZTextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            if (scanComplete) {
                item {
                    SectionLabel("RESULTS", Modifier.padding(top = 8.dp))
                }

                val openPorts = results.filter { it.isOpen }

                if (openPorts.isEmpty()) {
                    item {
                        GZCard(background = GZSurface, border = GZBorderSubtle) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No open ports found.",
                                    color = GZTextSecondary,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                } else {
                    items(openPorts) { port ->
                        GZCard(
                            background = GZSurface,
                            border = GZBorderSubtle,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(GZGreen)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(
                                            text = port.port.toString(),
                                            color = GZTextPrimary,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = port.serviceName,
                                            color = GZTextSecondary,
                                            fontSize = 14.sp
                                        )
                                    }
                                }

                                if (port.port in listOf(80, 443, 8080, 8443)) {
                                    IconButton(
                                        onClick = { openInBrowser(context, targetIp, port.port) },
                                        modifier = Modifier
                                            .background(GZSurfaceHigh, CircleShape)
                                            .size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.OpenInBrowser,
                                            contentDescription = "Open in browser",
                                            tint = GZPrimaryLight,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (!isScanning) {
                item {
                    GZCard(background = GZSurface, border = GZBorderSubtle) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Enter an IP to scan",
                                color = GZTextSecondary,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }

            item {
                GZCard(background = GZSurfaceHigh.copy(alpha = 0.5f), border = GZBorderSubtle) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = GZTextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Scans 25 common ports to identify available services. Scanning takes a few seconds.",
                            color = GZTextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

private fun openInBrowser(context: Context, ip: String, port: Int) {
    val scheme = if (port == 443 || port == 8443) "https" else "http"
    val url = if (port == 80 || port == 443) "$scheme://$ip" else "$scheme://$ip:$port"
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

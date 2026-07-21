package com.grayzone.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.GZCard
import com.grayzone.app.SectionLabel
import com.grayzone.app.data.SpeedTestResult
import com.grayzone.app.data.SpeedTestRunner
import com.grayzone.app.data.WifiNetworkInfo
import com.grayzone.app.data.formatBytes
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SpeedTestScreen(
    networkInfo: WifiNetworkInfo = WifiNetworkInfo(),
    onBack: () -> Unit = {}
) {
    var result by remember { mutableStateOf(SpeedTestResult()) }
    var isTesting by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    var liveSpeed by remember { mutableStateOf(0.0) }
    val scope = rememberCoroutineScope()
    var lastTestTime by remember { mutableStateOf<Long?>(null) }

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
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = GZPrimaryLight,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Speed Test",
                        color = GZTextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = networkInfo.ssid.ifEmpty { "Network" },
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
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        SpeedGauge(
                            speedMbps = if (isTesting) liveSpeed else result.downloadSpeedMbps,
                            isRunning = isTesting
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Text(
                            text = if (isTesting) phase else if (result.downloadSpeedMbps > 0) "Complete!" else "Ready to test",
                            color = if (isTesting) GZAccent else GZTextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Button(
                            onClick = {
                                scope.launch {
                                    isTesting = true
                                    phase = "Testing gateway ping..."
                                    val gPing = SpeedTestRunner.pingHost(networkInfo.gateway)
                                    phase = "Testing internet ping..."
                                    val iPing = SpeedTestRunner.pingHost("8.8.8.8")
                                    phase = "Measuring download speed..."
                                    // Normally we would update liveSpeed here, for now just call the runner
                                    val dl = SpeedTestRunner.measureDownloadSpeed()
                                    result = dl.copy(gatewayPingMs = gPing, internetPingMs = iPing)
                                    lastTestTime = System.currentTimeMillis()
                                    isTesting = false
                                    phase = ""
                                }
                            },
                            enabled = !isTesting,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GZPrimary,
                                disabledContainerColor = GZSurfaceHigh
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Text(
                                text = if (isTesting) "Testing..." else "Start Test",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isTesting) GZTextSecondary else GZTextPrimary
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatChip(
                        title = "Gateway Ping",
                        value = if (result.gatewayPingMs >= 0) "${result.gatewayPingMs} ms" else "—",
                        color = getPingColor(result.gatewayPingMs),
                        modifier = Modifier.weight(1f)
                    )
                    StatChip(
                        title = "Internet Ping",
                        value = if (result.internetPingMs >= 0) "${result.internetPingMs} ms" else "—",
                        color = getPingColor(result.internetPingMs),
                        modifier = Modifier.weight(1f)
                    )
                    StatChip(
                        title = "Download",
                        value = if (result.downloadSpeedMbps > 0) String.format(Locale.US, "%.1f", result.downloadSpeedMbps) else "—",
                        subtitle = "Mbps",
                        color = GZChartBlue,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                GZCard(
                    background = GZSurface,
                    border = GZBorderSubtle
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        SectionLabel("TEST DETAILS")
                        Spacer(modifier = Modifier.height(16.dp))
                        DetailRow("Downloaded bytes", if (result.downloadedBytes > 0) formatBytes(result.downloadedBytes) else "—")
                        DetailRow("Test duration", if (result.durationMs > 0) "${result.durationMs} ms" else "—")
                        DetailRow("Test server", "Cloudflare")
                        DetailRow("Last test", lastTestTime?.let { 
                            SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(it))
                        } ?: "Never")
                    }
                }
            }
            
            item {
                GZCard(background = GZPrimaryGlow, border = GZBorder) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = GZPrimaryLight,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Speed tests consume data. Ensure you have an adequate data plan before running tests frequently.",
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

@Composable
fun SpeedGauge(speedMbps: Double, maxSpeed: Double = 100.0, isRunning: Boolean) {
    val animatedSpeed by animateFloatAsState(
        targetValue = speedMbps.toFloat().coerceIn(0f, maxSpeed.toFloat()),
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "speed"
    )

    Box(
        modifier = Modifier
            .size(220.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 12.dp.toPx()
            val arcSize = size.minDimension - strokeWidth
            val arcOffset = Offset(strokeWidth / 2, strokeWidth / 2)
            
            // Background arc (270 degrees, from 135 to 405)
            drawArc(
                color = GZSurfaceHigh,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = arcOffset,
                size = Size(arcSize, arcSize),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            
            // Filled arc
            val sweepAngle = (animatedSpeed / maxSpeed.toFloat()) * 270f
            if (sweepAngle > 0) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(GZChartBlue, GZChartCyan, GZChartBlue),
                        center = Offset(size.width / 2, size.height / 2)
                    ),
                    startAngle = 135f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = arcOffset,
                    size = Size(arcSize, arcSize),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format(Locale.US, "%.1f", animatedSpeed),
                color = GZTextPrimary,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Mbps",
                color = GZTextSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun StatChip(
    title: String,
    value: String,
    color: Color,
    subtitle: String = "",
    modifier: Modifier = Modifier
) {
    GZCard(
        modifier = modifier,
        background = GZSurface,
        border = GZBorderSubtle
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = GZTextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    color = GZTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                if (subtitle.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = subtitle,
                        color = GZTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = GZTextSecondary,
            fontSize = 14.sp
        )
        Text(
            text = value,
            color = GZTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun getPingColor(ping: Long): Color {
    return when {
        ping < 0 -> GZRed
        ping < 20 -> GZGreen
        ping < 50 -> GZAccent
        ping < 100 -> GZAmber
        else -> GZRed
    }
}

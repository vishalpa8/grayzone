package com.grayzone.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.grayzone.app.ActivePill
import com.grayzone.app.GZCard
import com.grayzone.app.HowItWorksStep
import com.grayzone.app.SectionLabel
import com.grayzone.app.data.LocalShareServer
import com.grayzone.app.data.WifiNetworkInfo
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

fun generateQrCode(text: String, size: Int = 512): Bitmap {
    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    return bitmap
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardShareScreen(
    networkInfo: WifiNetworkInfo,
    onNavigateBack: () -> Unit
) {
    var server by remember { mutableStateOf<LocalShareServer?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var clipboardText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    val token = remember { java.util.UUID.randomUUID().toString().replace("-", "").take(12) }
    val shareUrl = if (networkInfo.ipAddress != "—") "http://${networkInfo.ipAddress}:8765/?t=$token" else ""

    DisposableEffect(Unit) {
        val s = LocalShareServer(8765, context, token)
        try {
            s.start()
            server = s
            isRunning = true
        } catch (e: Exception) {
            isRunning = false
        }
        onDispose {
            s.stop()
            server = null
            isRunning = false
        }
    }

    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        while (isActive) {
            server?.let {
                val serverText = it.getClipboardText()
                if (serverText != clipboardText && serverText.isNotEmpty()) {
                    clipboardText = serverText
                }
            }
            delay(1000)
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
                .background(Brush.verticalGradient(listOf(GZPrimaryContainer.copy(alpha = 0.5f), GZBackground)))
                .padding(top = 48.dp, bottom = 24.dp, start = 16.dp, end = 16.dp)
        ) {
            Column {
                IconButton(onClick = onNavigateBack, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = GZTextPrimary)
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(GZPrimary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, tint = GZPrimary)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Clipboard Share", color = GZTextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("Share text securely over local WiFi", color = GZTextSecondary, fontSize = 16.sp)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                GZCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (shareUrl.isNotEmpty() && isRunning) {
                            val qrBitmap = remember(shareUrl) { generateQrCode(shareUrl) }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White)
                                    .padding(16.dp)
                            ) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "QR Code",
                                    modifier = Modifier.size(200.dp)
                                )
                            }
                            Spacer(Modifier.height(24.dp))
                            Text(shareUrl, color = GZPrimaryLight, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            Text("Scan this QR code from any device on the same WiFi", color = GZTextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(200.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(GZSurfaceHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No Network", color = GZTextSecondary)
                            }
                            Spacer(Modifier.height(24.dp))
                            Text("Connect to WiFi to share", color = GZTextSecondary, fontSize = 14.sp)
                        }
                        
                        Spacer(Modifier.height(24.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            ActivePill(isActive = isRunning)
                            Spacer(Modifier.width(12.dp))
                            Button(
                                onClick = {
                                    if (isRunning) {
                                        server?.stop()
                                        isRunning = false
                                    } else {
                                        try {
                                            server?.start()
                                            isRunning = true
                                        } catch (e: Exception) {
                                            isRunning = false
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GZSurfaceHigh)
                            ) {
                                Text(if (isRunning) "Stop Server" else "Start Server", color = GZTextPrimary)
                            }
                        }
                    }
                }
            }

            item {
                GZCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        SectionLabel("CLIPBOARD TEXT")
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = clipboardText,
                            onValueChange = { clipboardText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GZPrimary,
                                unfocusedBorderColor = GZBorder,
                                focusedTextColor = GZTextPrimary,
                                unfocusedTextColor = GZTextPrimary,
                                cursorColor = GZPrimary
                            ),
                            placeholder = { Text("Enter text to share...", color = GZTextSecondary) }
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { 
                                clipboardText = ""
                                server?.setClipboardText("")
                            }) {
                                Text("Clear", color = GZRed)
                            }
                            Row {
                                TextButton(onClick = {
                                    if (clipboardManager.hasPrimaryClip()) {
                                        val item = clipboardManager.primaryClip?.getItemAt(0)
                                        val text = item?.text?.toString() ?: ""
                                        clipboardText = text
                                        server?.setClipboardText(text)
                                    }
                                }) {
                                    Text("Paste from Android", color = GZAccent)
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = { server?.setClipboardText(clipboardText) },
                                    colors = ButtonDefaults.buttonColors(containerColor = GZPrimary)
                                ) {
                                    Text("Share", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
            
            item {
                GZCard {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                        SectionLabel("HOW IT WORKS")
                        Spacer(Modifier.height(16.dp))
                        HowItWorksStep("1", "Connect both devices to the same WiFi network.", GZAccent)
                        Spacer(Modifier.height(12.dp))
                        HowItWorksStep("2", "Scan the QR code with your other device's camera.", GZChartBlue)
                        Spacer(Modifier.height(12.dp))
                        HowItWorksStep("3", "Type or paste text to share it instantly.", GZPrimary)
                    }
                }
            }
        }
    }
}

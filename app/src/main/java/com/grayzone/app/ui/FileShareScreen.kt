package com.grayzone.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.ActivePill
import com.grayzone.app.GZCard
import com.grayzone.app.HowItWorksStep
import com.grayzone.app.SectionLabel
import com.grayzone.app.data.LocalShareServer
import com.grayzone.app.data.SharedFile
import com.grayzone.app.data.WifiNetworkInfo
import com.grayzone.app.ui.theme.*

fun getFileInfo(context: Context, uri: Uri): SharedFile? {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val name = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            val size = it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE))
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            return SharedFile(name, uri, size, mimeType)
        }
    }
    return null
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@Composable
fun FileShareScreen(
    networkInfo: WifiNetworkInfo,
    onNavigateBack: () -> Unit
) {
    var server by remember { mutableStateOf<LocalShareServer?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var sharedFiles by remember { mutableStateOf<List<SharedFile>>(emptyList()) }
    val context = LocalContext.current

    val token = remember { java.util.UUID.randomUUID().toString().replace("-", "").take(12) }
    val shareUrl = if (networkInfo.ipAddress != "—") "http://${networkInfo.ipAddress}:8766/files?t=$token" else ""

    DisposableEffect(Unit) {
        val s = LocalShareServer(8766, context, token)
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

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        server?.let { s ->
            uris.forEach { uri ->
                getFileInfo(context, uri)?.let { file ->
                    s.addFile(file)
                }
            }
            sharedFiles = s.getSharedFiles()
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
                .background(Brush.verticalGradient(listOf(GZAccentDim, GZBackground)))
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
                            .background(GZAccent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = GZAccent)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("File Share", color = GZTextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("Share files securely over local WiFi", color = GZTextSecondary, fontSize = 16.sp)
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
                            Text(shareUrl, color = GZAccent, fontSize = 18.sp, fontWeight = FontWeight.Medium)
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("SHARED FILES")
                    Button(
                        onClick = { launcher.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = GZPrimary),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Files", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add Files")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (sharedFiles.isEmpty()) {
                item {
                    GZCard {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No files shared yet", color = GZTextSecondary)
                        }
                    }
                }
            } else {
                items(sharedFiles) { file ->
                    GZCard(modifier = Modifier.padding(bottom = 8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(GZSurfaceHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = GZPrimaryLight)
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    color = GZTextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = formatFileSize(file.size),
                                    color = GZTextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            IconButton(onClick = {
                                server?.let { s ->
                                    s.removeFile(file.name)
                                    sharedFiles = s.getSharedFiles()
                                }
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = GZRed)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                GZCard {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                        SectionLabel("HOW IT WORKS")
                        Spacer(Modifier.height(16.dp))
                        HowItWorksStep("1", "Connect both devices to the same WiFi network.", GZAccent)
                        Spacer(Modifier.height(12.dp))
                        HowItWorksStep("2", "Scan the QR code with your other device's camera.", GZChartBlue)
                        Spacer(Modifier.height(12.dp))
                        HowItWorksStep("3", "Browse and download the shared files from the web page.", GZPrimary)
                    }
                }
            }
        }
    }
}

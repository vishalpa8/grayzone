package com.grayzone.app

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.grayzone.app.ui.theme.*

@Composable
fun ActivePill(isActive: Boolean) {
    Row(modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (isActive) GZGreenContainer else GZRedContainer).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (isActive) GZGreen else GZRed))
        Spacer(Modifier.width(6.dp))
        Text(if (isActive) "Active" else "Inactive", color = if (isActive) GZGreen else GZRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GZCard(
    modifier: Modifier = Modifier, 
    background: Color = GZSurface, 
    border: Color = GZBorder,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(24.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) { content() }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = GZTextTertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
fun HowItWorksStep(number: String, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Text(number, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = GZTextSecondary, fontSize = 14.sp)
    }
}

@Composable
fun GZLoadingSpinner(modifier: Modifier = Modifier, size: Dp = 40.dp, color: Color = GZPrimary) {
    val infiniteTransition = rememberInfiniteTransition(label = "spinner")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1000, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "angle"
    )
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = 4.dp.toPx()
        drawArc(color = color.copy(alpha = 0.2f), startAngle = 0f, sweepAngle = 360f, useCenter = false, style = Stroke(width = strokeWidth))
        drawArc(color = color, startAngle = angle, sweepAngle = 90f, useCenter = false, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
    }
}


fun shareApk(context: Context) {
    try {
        val app = context.applicationInfo
        val filePath = app.sourceDir
        val file = java.io.File(filePath)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Grayzone"))
    } catch (e: Exception) {
        android.util.Log.e("ShareApk", "Could not share APK", e)
    }
}

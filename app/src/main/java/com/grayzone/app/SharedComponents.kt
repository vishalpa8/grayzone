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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.delay

/**
 * A wall-clock ticker that only fires while the hosting screen is actually visible
 * (lifecycle at least RESUMED). When the app is backgrounded the coroutine is
 * suspended, so these once-per-second/two-second UI refreshes stop waking the CPU
 * until the user returns. Returns a State<Long> holding the latest currentTimeMillis().
 */
@Composable
fun rememberResumedTicker(intervalMs: Long): State<Long> {
    val time = remember { mutableStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, intervalMs) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                time.value = System.currentTimeMillis()
                delay(intervalMs)
            }
        }
    }
    return time
}

/**
 * Runs [block] on an interval only while the screen is visible (RESUMED) AND [enabled]
 * is true. Used for countdown loops that should freeze both when the app is backgrounded
 * and when the countdown isn't active. [block] returns false to stop the loop early.
 */
@Composable
fun ResumedInterval(
    enabled: Boolean,
    intervalMs: Long,
    block: suspend () -> Boolean
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(enabled, lifecycleOwner, intervalMs) {
        if (!enabled) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (!block()) break
                delay(intervalMs)
            }
        }
    }
}



/** Shares the Play Store listing (not the raw APK, which store installs must not redistribute). */
fun shareApp(context: Context) {
    val link = "https://play.google.com/store/apps/details?id=${context.packageName}"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Grayzone")
        putExtra(Intent.EXTRA_TEXT, "Grayzone adds a mindful pause before distracting apps: $link")
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Share Grayzone"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Couldn't open share", android.widget.Toast.LENGTH_SHORT).show()
    }
}
package com.grayzone.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.grayzone.app.SetupRequirement
import com.grayzone.app.appInfoSettingsIntent
import com.grayzone.app.settingsIntentFor
import com.grayzone.app.startActivitySafely
import com.grayzone.app.ui.theme.*

private data class PromptCopy(val title: String, val reason: String)

private fun copyFor(requirement: SetupRequirement) = when (requirement) {
    SetupRequirement.NOTIFICATIONS -> PromptCopy(
        "Allow notifications",
        "Shows that protection is on, plus a live timer while a session is running."
    )
    SetupRequirement.ACCESSIBILITY -> PromptCopy(
        "Turn on Accessibility",
        "Lets Grayzone know which app is open so the pause screen appears at the right moment. " +
            "It does not read, store, or share what is on your screen."
    )
    SetupRequirement.OVERLAY -> PromptCopy(
        "Allow display over other apps",
        "Lets Grayzone draw the pause and timer screens on top of the apps you limit."
    )
    SetupRequirement.BATTERY -> PromptCopy(
        "Keep protection running",
        "Exempting Grayzone from battery optimization keeps it working when the screen is off."
    )
}

/**
 * A small popup that asks for exactly one permission. It floats over the normal app (never a
 * full-screen page) and can always be declined.
 *
 * @param position "Step n of m" shown during first-run setup; null when asked from a feature.
 * @param onDecline the user said no (button or back press).
 * @param onChanged called when the answer may have changed (e.g. after the system dialog returns).
 */
@Composable
fun PermissionPrompt(
    requirement: SetupRequirement,
    position: Pair<Int, Int>?,
    onDecline: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val copy = copyFor(requirement)
    // After one system-dialog denial Android stops showing it, so the next tap opens settings.
    var notificationDialogShown by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationDialogShown = true
        onChanged()
    }

    fun allow() {
        if (requirement == SetupRequirement.NOTIFICATIONS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationDialogShown
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // Only the one screen for this permission — no chained OEM screens on top of it.
        startActivitySafely(
            context,
            settingsIntentFor(context, requirement),
            "prompt${requirement.name}",
            showToast = requirement != SetupRequirement.BATTERY
        )
    }

    Dialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(GZSurface)
                .border(1.dp, GZBorder, shape)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (position != null) {
                Text(
                    "Step ${position.first} of ${position.second}",
                    color = GZTextTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                copy.title,
                color = GZTextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                copy.reason,
                color = GZTextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = ::allow,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GZPrimary,
                    contentColor = GZOnPrimary
                )
            ) {
                Text("Allow", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            if (requirement == SetupRequirement.ACCESSIBILITY && Build.VERSION.SDK_INT >= 33) {
                TextButton(
                    onClick = {
                        startActivitySafely(context, appInfoSettingsIntent(context), "promptAppInfo")
                    }
                ) {
                    Text("Toggle greyed out? Open app info", color = GZPrimary, fontSize = 13.sp)
                }
            }
            TextButton(onClick = onDecline) {
                Text("Decline", color = GZTextSecondary, fontSize = 14.sp)
            }
        }
    }
}

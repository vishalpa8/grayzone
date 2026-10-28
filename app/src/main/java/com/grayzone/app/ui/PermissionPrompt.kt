package com.grayzone.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
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
import com.grayzone.app.openSettingsFor
import com.grayzone.app.ui.theme.*

private data class PromptCopy(val title: String, val reason: String)

private fun copyFor(requirement: SetupRequirement) = when (requirement) {
    SetupRequirement.NOTIFICATIONS -> PromptCopy(
        "Allow notifications",
        "Shows that protection is on, plus a live timer while a session is running."
    )
    SetupRequirement.USAGE_ACCESS -> PromptCopy(
        "Allow usage access",
        "Lets Grayzone see which app is open so the pause screen appears at the right moment. " +
            "Find Grayzone in the list and turn it on. It never sees what is on your screen."
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
 * @param position "Step n of m" for this pass; hidden when null or when the pass has one step.
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
        // The system dialog only helps while the runtime permission itself is missing and Android
        // will still show it. If the permission is granted but notifications are switched off, or
        // the dialog was already refused, the app's notification settings page is the right place.
        if (requirement == SetupRequirement.NOTIFICATIONS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationDialogShown &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // Only the one screen for this permission — no chained OEM screens on top of it.
        if (!openSettingsFor(context, requirement, "prompt${requirement.name}")) {
            android.widget.Toast.makeText(
                context,
                "Couldn't open settings. Open Settings > Apps > Grayzone to allow it.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
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
            if (position != null && position.second > 1) {
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
                onClick = { allow() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GZPrimary,
                    contentColor = GZOnPrimary
                )
            ) {
                Text("Allow", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }

            TextButton(onClick = onDecline) {
                Text("Decline", color = GZTextSecondary, fontSize = 14.sp)
            }
        }
    }
}

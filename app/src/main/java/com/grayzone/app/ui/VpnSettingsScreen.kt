package com.grayzone.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.PrefsKeys
import com.grayzone.app.service.vpn.BlocklistManager
import com.grayzone.app.ui.components.GZCard
import com.grayzone.app.ui.components.GZSwitch
import com.grayzone.app.ui.theme.*

@Composable
fun VpnSettingsScreen(
    onBack: () -> Unit,
    onNavigateToSplitTunneling: () -> Unit,
    onNavigateToCustomRules: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    var blockTelemetry by remember { mutableStateOf(prefs.getBoolean(PrefsKeys.VPN_BLOCK_TELEMETRY, false)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GZBackground)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = GZTextPrimary
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Advanced VPN Settings",
                    color = GZTextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Split Tunneling
        GZCard(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            background = GZSurface,
            border = GZBorder
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToSplitTunneling() }
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = GZTextTertiary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Split Tunneling (Bypass Apps)", color = GZTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Exclude specific apps from the VPN", color = GZTextSecondary, fontSize = 12.sp)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = GZTextTertiary)
            }
        }

        // Custom Rules
        GZCard(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            background = GZSurface,
            border = GZBorder
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToCustomRules() }
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = GZTextTertiary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Custom Rules (Allow / Deny)", color = GZTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Add your own domain filters", color = GZTextSecondary, fontSize = 12.sp)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = GZTextTertiary)
            }
        }
        
        Spacer(Modifier.height(16.dp))
        Text(
            "PRIVACY & PERFORMANCE",
            color = GZTextTertiary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
        )

        // Telemetry Blocker
        GZCard(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            background = if (blockTelemetry) GZPrimaryContainer else GZSurface,
            border = if (blockTelemetry) GZPrimary.copy(alpha = 0.25f) else GZBorder
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = if (blockTelemetry) GZPrimary else GZTextTertiary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Block Background Telemetry", color = if (blockTelemetry) GZTextPrimary else GZTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Block aggressive OS tracking to save battery and data", color = GZTextSecondary, fontSize = 12.sp)
                }
                Spacer(Modifier.width(12.dp))
                GZSwitch(
                    checked = blockTelemetry,
                    onCheckedChange = {
                        blockTelemetry = it
                        prefs.edit().putBoolean(PrefsKeys.VPN_BLOCK_TELEMETRY, it).apply()
                        BlocklistManager.reloadCustomRules(context)
                    }
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

package com.grayzone.app.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.grayzone.app.PrefsKeys
import com.grayzone.app.service.vpn.BlocklistManager
import com.grayzone.app.service.vpn.update.BlocklistUpdates
import com.grayzone.app.ui.components.*
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun VpnSettingsScreen(
    onBack: () -> Unit,
    onNavigateToSplitTunneling: () -> Unit,
    onNavigateToCustomRules: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    var blockTelemetry by remember { mutableStateOf(prefs.getBoolean(PrefsKeys.VPN_BLOCK_TELEMETRY, false)) }
    val listsBuiltAt by produceState(0L) {
        value = withContext(Dispatchers.IO) {
            BlocklistUpdates.store(context).activeManifest()?.builtAtMs ?: 0L
        }
    }

    androidx.activity.compose.BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GZBackground)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader(title = "VPN settings", onBack = onBack)

        SectionLabel("Filtering", Modifier.padding(start = GZGutter + 4.dp))
        GZCard(modifier = Modifier.padding(horizontal = GZGutter)) {
            Column {
                SettingsRow(
                    title = "Split tunneling",
                    subtitle = "Let chosen apps skip the VPN",
                    icon = Icons.AutoMirrored.Filled.List,
                    onClick = onNavigateToSplitTunneling
                )
                RowDivider(startIndent = 68)
                SettingsRow(
                    title = "Custom rules",
                    subtitle = "Always allow or always block a domain",
                    icon = Icons.Filled.Security,
                    onClick = onNavigateToCustomRules
                )
                RowDivider(startIndent = 68)
                SettingsRow(
                    title = "Blocklists",
                    subtitle = if (listsBuiltAt > 0L) {
                        "Updated automatically · current lists from " +
                            DateUtils.getRelativeTimeSpanString(listsBuiltAt, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)
                                .toString().lowercase()
                    } else {
                        "Updated automatically"
                    },
                    icon = Icons.Filled.Update
                )
            }
        }

        SectionLabel("Privacy", Modifier.padding(start = GZGutter + 4.dp, top = 8.dp))
        GZCard(modifier = Modifier.padding(horizontal = GZGutter)) {
            SettingsRow(
                title = "Block background telemetry",
                subtitle = "Stop tracking requests from the system and apps",
                icon = Icons.Filled.VisibilityOff,
                trailing = {
                    GZSwitch(
                        checked = blockTelemetry,
                        onCheckedChange = {
                            blockTelemetry = it
                            prefs.edit().putBoolean(PrefsKeys.VPN_BLOCK_TELEMETRY, it).apply()
                            BlocklistManager.reloadCustomRules(context)
                        }
                    )
                }
            )
        }
    }
}

package com.grayzone.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.PrefsKeys
import com.grayzone.app.service.vpn.BlocklistManager
import com.grayzone.app.ui.components.*
import com.grayzone.app.ui.theme.*

@Composable
fun CustomRulesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    
    var allowlist by remember { mutableStateOf(prefs.getStringSet(PrefsKeys.VPN_CUSTOM_ALLOWLIST, emptySet())?.toList() ?: emptyList()) }
    var denylist by remember { mutableStateOf(prefs.getStringSet(PrefsKeys.VPN_CUSTOM_DENYLIST, emptySet())?.toList() ?: emptyList()) }
    
    var currentTab by remember { mutableStateOf(0) } // 0 = Deny, 1 = Allow
    var newDomain by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    androidx.activity.compose.BackHandler(onBack = onBack)
    
    fun saveRules() {
        prefs.edit()
            .putStringSet(PrefsKeys.VPN_CUSTOM_ALLOWLIST, allowlist.toSet())
            .putStringSet(PrefsKeys.VPN_CUSTOM_DENYLIST, denylist.toSet())
            .apply()
        BlocklistManager.reloadCustomRules(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GZBackground)
    ) {
        ScreenHeader(title = "Custom rules", onBack = onBack)
        Text(
            "Block or allow a domain and all its subdomains. Allow overrides every list, malware and phishing " +
                "included, so only allow sites you trust (encrypted-DNS servers stay blocked). Rules apply right " +
                "away while the VPN is on; an app with " +
                "an open connection may keep using it until it reconnects.",
            color = GZTextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = GZGutter, end = GZGutter, bottom = 8.dp)
        )

        TabRow(
            selectedTabIndex = currentTab,
            containerColor = GZBackground,
            contentColor = GZPrimary,
            indicator = { tabPositions ->
                TabRowDefaults.Indicator(
                    Modifier.tabIndicatorOffset(tabPositions[currentTab]),
                    color = GZPrimary
                )
            }
        ) {
            Tab(
                selected = currentTab == 0,
                onClick = { currentTab = 0 },
                text = { Text("Deny List", color = if (currentTab == 0) GZPrimary else GZTextTertiary) }
            )
            Tab(
                selected = currentTab == 1,
                onClick = { currentTab = 1 },
                text = { Text("Allow List", color = if (currentTab == 1) GZPrimary else GZTextTertiary) }
            )
        }
        
        Spacer(Modifier.height(16.dp))

        // Input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GZGutter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(GZControlShape)
                    .background(GZSurface)
                    .border(1.dp, if (inputError != null) GZRed else if (GZIsDark) GZBorder else GZBorderSubtle, GZControlShape)
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                if (newDomain.isEmpty()) {
                    Text("e.g. example.com", color = GZTextTertiary, fontSize = 15.sp)
                }
                BasicTextField(
                    value = newDomain,
                    onValueChange = { newDomain = it; inputError = null },
                    textStyle = LocalTextStyle.current.copy(color = GZTextPrimary, fontSize = 15.sp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    val normalized = BlocklistManager.normalizeDomain(newDomain)
                    inputError = if (normalized == null) "That doesn't look like a domain, e.g. example.com" else null
                    if (normalized != null) {
                        if (currentTab == 0 && !denylist.contains(normalized)) {
                            denylist = denylist + normalized
                            saveRules()
                        } else if (currentTab == 1 && !allowlist.contains(normalized)) {
                            allowlist = allowlist + normalized
                            saveRules()
                        }
                        newDomain = ""
                    }
                },
                enabled = newDomain.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = GZPrimary, contentColor = GZOnPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
            }
        }

        inputError?.let {
            Text(it, color = GZRed, fontSize = 12.sp, modifier = Modifier.padding(start = GZGutter + 4.dp, top = 6.dp))
        }

        Spacer(Modifier.height(16.dp))

        val currentList = if (currentTab == 0) denylist else allowlist
        val emptyMessage = if (currentTab == 0) "No denied domains" else "No allowed domains"
        
        if (currentList.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(emptyMessage, color = GZTextTertiary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = GZGutter, vertical = 8.dp)
            ) {
                items(currentList.sorted(), key = { it }) { domain ->
                    GZCard(
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                domain,
                                color = GZTextPrimary,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    if (currentTab == 0) {
                                        denylist = denylist - domain
                                    } else {
                                        allowlist = allowlist - domain
                                    }
                                    saveRules()
                                }
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove $domain", tint = GZTextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

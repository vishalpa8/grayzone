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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import com.grayzone.app.ui.components.GZCard
import com.grayzone.app.ui.theme.*

@Composable
fun CustomRulesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    
    var allowlist by remember { mutableStateOf(prefs.getStringSet(PrefsKeys.VPN_CUSTOM_ALLOWLIST, emptySet())?.toList() ?: emptyList()) }
    var denylist by remember { mutableStateOf(prefs.getStringSet(PrefsKeys.VPN_CUSTOM_DENYLIST, emptySet())?.toList() ?: emptyList()) }
    
    var currentTab by remember { mutableStateOf(0) } // 0 = Deny, 1 = Allow
    var newDomain by remember { mutableStateOf("") }
    
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
                    "Custom Rules",
                    color = GZTextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                )
            }
        }

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
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GZSurfaceElevated)
                    .border(1.dp, GZBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                if (newDomain.isEmpty()) {
                    Text("e.g. example.com", color = GZTextTertiary, fontSize = 15.sp)
                }
                BasicTextField(
                    value = newDomain,
                    onValueChange = { newDomain = it },
                    textStyle = LocalTextStyle.current.copy(color = GZTextPrimary, fontSize = 15.sp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    val normalized = BlocklistManager.normalizeDomain(newDomain)
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
                colors = ButtonDefaults.buttonColors(containerColor = GZPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
            }
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
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                items(currentList.sorted()) { domain ->
                    GZCard(
                        modifier = Modifier.padding(vertical = 4.dp),
                        background = GZSurface,
                        border = GZBorder
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
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = GZTextTertiary)
                            }
                        }
                    }
                }
            }
        }
    }
}

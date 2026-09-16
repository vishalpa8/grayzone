package com.grayzone.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.AppInfo
import com.grayzone.app.PrefsKeys
import com.grayzone.app.getInstalledAppsCached
import com.grayzone.app.peekCachedApps
import com.grayzone.app.service.vpn.AdBlockVpnService
import com.grayzone.app.ui.components.GZLoadingSpinner
import com.grayzone.app.ui.components.GZSwitch
import com.grayzone.app.ui.theme.*

@Composable
fun SplitTunnelingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    
    val cachedApps = remember { peekCachedApps() }
    var installedApps by remember { mutableStateOf(cachedApps ?: emptyList()) }
    var isLoading by remember { mutableStateOf(cachedApps == null) }
    var searchQuery by remember { mutableStateOf("") }
    
    var bypassApps by remember { 
        mutableStateOf(prefs.getStringSet(PrefsKeys.VPN_BYPASS_APPS, emptySet())?.toSet() ?: emptySet()) 
    }

    LaunchedEffect(Unit) {
        installedApps = getInstalledAppsCached(context)
        isLoading = false
    }

    fun toggleBypass(pkg: String, bypass: Boolean) {
        val newSet = if (bypass) bypassApps + pkg else bypassApps - pkg
        bypassApps = newSet
        prefs.edit().putStringSet(PrefsKeys.VPN_BYPASS_APPS, newSet).apply()
        
        // Re-apply bypass lists by sending START to the VPN service
        if (prefs.getBoolean(PrefsKeys.VPN_ENABLED, false)) {
            context.startService(Intent(context, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_START
            })
        }
    }

    val filtered = remember(installedApps, searchQuery, bypassApps) {
        val baseList = if (searchQuery.isBlank()) installedApps
        else installedApps.filter { it.name.contains(searchQuery, ignoreCase = true) }
        
        baseList.sortedWith(
            compareBy<AppInfo> { !bypassApps.contains(it.packageName) }
                .thenBy { it.name.lowercase() }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(GZBackground)) {
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
                    "Split Tunneling",
                    color = GZTextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Selected apps will completely bypass the VPN. Use this for banking or streaming apps that don't work with VPNs.",
                color = GZTextSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            
            // Search bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(GZSurfaceElevated)
                    .border(1.dp, GZBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = GZTextTertiary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    textStyle = LocalTextStyle.current.copy(color = GZTextPrimary, fontSize = 15.sp),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (searchQuery.isEmpty()) {
                            Text("Search apps…", color = GZTextTertiary, fontSize = 15.sp)
                        }
                        inner()
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "Clear",
                        tint = GZTextTertiary, modifier = Modifier.size(16.dp)
                            .clickable { searchQuery = "" }
                    )
                }
            }
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GZLoadingSpinner()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.packageName }) { app ->
                    val isBypassed = bypassApps.contains(app.packageName)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { toggleBypass(app.packageName, !isBypassed) }
                            .background(if (isBypassed) GZPrimaryContainer.copy(alpha = 0.35f) else Color.Transparent)
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (app.icon != null) {
                            Image(
                                bitmap = app.icon.asImageBitmap(),
                                contentDescription = app.name,
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                            )
                        } else {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(GZSurfaceElevated))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.name, color = GZTextPrimary, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (isBypassed) "Bypassing VPN" else app.packageName,
                                color = if (isBypassed) GZPrimary else GZTextTertiary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        GZSwitch(
                            checked = isBypassed,
                            onCheckedChange = { toggleBypass(app.packageName, it) }
                        )
                    }
                    Divider(color = GZBorderSubtle, thickness = 0.5.dp, modifier = Modifier.padding(start = 84.dp, end = 24.dp))
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

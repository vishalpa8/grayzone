package com.grayzone.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
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
import com.grayzone.app.ui.components.*
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
    // The list the running tunnel was built with; reload only when the screen's list differs.
    var appliedSet by remember { mutableStateOf(bypassApps) }

    LaunchedEffect(Unit) {
        installedApps = getInstalledAppsCached(context)
        isLoading = false
    }

    fun toggleBypass(pkg: String, bypass: Boolean) {
        val newSet = if (bypass) bypassApps + pkg else bypassApps - pkg
        bypassApps = newSet
        prefs.edit().putStringSet(PrefsKeys.VPN_BYPASS_APPS, newSet).apply()
    }

    // Applying the list restarts the tunnel, so do it once when leaving the screen rather than
    // on every toggle. "Leaving" includes Home / Recents (ON_STOP), not just Back: the usual case
    // is enabling a banking app here and jumping straight into it.
    fun applyIfChanged() {
        val running = com.grayzone.app.data.ProtectionHealthRepository.status.value.vpnActive
        if (com.grayzone.app.service.vpn.SplitTunnelPolicy.shouldReload(appliedSet, bypassApps, running)) {
            try {
                context.startService(Intent(context, AdBlockVpnService::class.java).apply {
                    action = AdBlockVpnService.ACTION_RELOAD_RULES
                })
                com.grayzone.app.GrayzoneLogger.i(com.grayzone.app.LogComponent.VPN, "Split tunneling changed; rebuilding the tunnel")
            } catch (e: Exception) {
                com.grayzone.app.GrayzoneLogger.w(com.grayzone.app.LogComponent.VPN, "Split tunnel reload failed", e)
                return
            }
        }
        appliedSet = bypassApps
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) applyIfChanged()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            applyIfChanged()
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

    androidx.activity.compose.BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().background(GZBackground)) {
        ScreenHeader(title = "Split tunneling", onBack = onBack)
        Column(modifier = Modifier.fillMaxWidth().padding(start = GZGutter, end = GZGutter, bottom = 12.dp)) {
            Text(
                "Apps you turn on here skip the VPN completely, e.g. banking or streaming apps that refuse VPNs. You can change this any time; it only has an effect while the VPN is running, and changes apply when you leave this screen.",
                color = GZTextSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            // Search bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(GZControlShape)
                    .background(GZSurface)
                    .border(1.dp, if (GZIsDark) GZBorder else GZBorderSubtle, GZControlShape)
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
                            Text("Search apps", color = GZTextTertiary, fontSize = 15.sp)
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
                            .toggleable(
                                value = isBypassed,
                                role = androidx.compose.ui.semantics.Role.Switch,
                                onValueChange = { toggleBypass(app.packageName, it) }
                            )
                            .background(if (isBypassed) GZPrimaryContainer.copy(alpha = 0.35f) else Color.Transparent)
                            .padding(horizontal = GZGutter, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = remember(app.icon) { app.icon?.asImageBitmap() }
                        if (icon != null) {
                            Image(
                                bitmap = icon,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                            )
                        } else {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(GZSurfaceElevated))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.name, color = GZTextPrimary, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (isBypassed) {
                                Text(
                                    "Skips the VPN",
                                    color = GZPrimary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        GZSwitch(
                            checked = isBypassed,
                            onCheckedChange = null
                        )
                    }
                    Divider(color = GZBorderSubtle, thickness = 0.5.dp, modifier = Modifier.padding(start = 80.dp, end = GZGutter))
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

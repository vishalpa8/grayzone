package com.grayzone.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import java.io.File

data class WifiDevice(
    val ip: String,
    val hostname: String,
    val isBlocked: Boolean = false,
    val customName: String = "",
    val deviceType: DeviceType = DeviceType.UNKNOWN,
    val isGateway: Boolean = false
)

enum class DeviceType { PHONE, LAPTOP, TABLET, TV, SMART_HOME, ROUTER, UNKNOWN }

data class WifiNetworkInfo(
    val ssid: String = "",
    val bssid: String = "",
    val rssi: Int = 0,
    val frequency: Int = 0,
    val linkSpeed: Int = 0,
    val ipAddress: String = "—",
    val gateway: String = "—",
    val subnetPrefix: String = "",
    val isConnected: Boolean = false
) {
    val signalBars: Int get() = when {
        rssi >= -50 -> 4
        rssi >= -65 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else        -> 0
    }
    val band: String get() = when {
        !isConnected     -> "—"
        frequency < 3000 -> "2.4 GHz"
        frequency < 6000 -> "5 GHz"
        else             -> "6 GHz"
    }
    val channel: Int get() = when {
        frequency in 2412..2484 -> (frequency - 2407) / 5
        frequency in 5180..5825 -> (frequency - 5000) / 5
        else                    -> 0
    }
}

/** Little-endian int from WifiInfo → dotted-decimal string. */
fun intToIp(ip: Int): String =
    "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L     -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L         -> "%.1f KB".format(bytes / 1_000.0)
    else                    -> "$bytes B"
}

private fun normalizeHostname(hostname: String, ip: String): String {
    val raw = hostname.trim().removeSurrounding("\"").trim()
    if (raw.isBlank() || raw.equals(ip, ignoreCase = true) || raw.contains("::")) return ""
    return raw.removeSuffix(".local")
        .removeSuffix(".lan")
        .removeSuffix(".home")
        .split(".")
        .firstOrNull()
        ?.trim()
        .orEmpty()
}

fun inferDeviceDisplayName(ip: String, hostname: String, customName: String, isGateway: Boolean): String {
    if (customName.isNotBlank()) return customName
    if (isGateway) return "Router / Gateway"

    val normalizedHost = normalizeHostname(hostname, ip)
    if (normalizedHost.isNotBlank()) return normalizedHost

    return if (ip.endsWith(".1") || ip.endsWith(".254")) "Router / Gateway" else "Device $ip"
}

fun inferDeviceType(ip: String, hostname: String, isGateway: Boolean): DeviceType {
    if (isGateway) return DeviceType.ROUTER

    val normalizedHost = normalizeHostname(hostname, ip).lowercase()
    return when {
        normalizedHost.contains("phone") || normalizedHost.contains("android") || normalizedHost.contains("iphone") || normalizedHost.contains("pixel") -> DeviceType.PHONE
        normalizedHost.contains("laptop") || normalizedHost.contains("desktop") || normalizedHost.contains("pc") || normalizedHost.contains("windows") || normalizedHost.contains("macbook") || normalizedHost.contains("thinkpad") -> DeviceType.LAPTOP
        normalizedHost.contains("tablet") || normalizedHost.contains("ipad") || normalizedHost.contains("tab") -> DeviceType.TABLET
        normalizedHost.contains("tv") || normalizedHost.contains("roku") || normalizedHost.contains("chromecast") -> DeviceType.TV
        normalizedHost.contains("home") || normalizedHost.contains("speaker") || normalizedHost.contains("echo") || normalizedHost.contains("nest") || normalizedHost.contains("hub") -> DeviceType.SMART_HOME
        else -> DeviceType.UNKNOWN
    }
}

class WifiRepository(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val prefs = context.getSharedPreferences("wifi_screen_prefs", Context.MODE_PRIVATE)

    fun getCustomName(ip: String): String = prefs.getString("name_$ip", "") ?: ""
    fun saveCustomName(ip: String, name: String) =
        prefs.edit().putString("name_$ip", name).apply()
    fun getBlockedIps(): Set<String> = prefs.getStringSet("blocked_ips", emptySet()) ?: emptySet()
    fun setBlocked(ip: String, blocked: Boolean) {
        val s = getBlockedIps().toMutableSet()
        if (blocked) s.add(ip) else s.remove(ip)
        prefs.edit().putStringSet("blocked_ips", s).apply()
    }

    suspend fun readNetworkInfo(): WifiNetworkInfo = withContext(Dispatchers.IO) {
        val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connMgr.activeNetwork
        val caps    = connMgr.getNetworkCapabilities(network)
        val isWifi  = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!isWifi) return@withContext WifiNetworkInfo()

        @Suppress("DEPRECATION")
        val wifiInfo: WifiInfo? = wifiManager.connectionInfo

        val rssi      = wifiInfo?.rssi ?: -100
        val freq      = wifiInfo?.frequency ?: 0
        val linkSpeed = wifiInfo?.linkSpeed ?: 0

        @Suppress("DEPRECATION")
        val rawSsid = wifiInfo?.ssid
            ?.removePrefix("\"")?.removeSuffix("\"")
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            ?: "Hidden / No Permission"

        @Suppress("DEPRECATION")
        val bssid = wifiInfo?.bssid ?: ""

        @Suppress("DEPRECATION")
        val rawIp = wifiInfo?.ipAddress ?: 0
        val formattedIp = if (rawIp != 0) intToIp(rawIp) else "—"

        val dhcpInfo = wifiManager.dhcpInfo
        val gatewayIp = dhcpInfo.gateway.takeIf { it != 0 }?.let(::intToIp) ?: "—"
        val subnetPrefix = if (gatewayIp != "—") {
            gatewayIp.split(".").take(3).joinToString(".")
        } else {
            formattedIp.split(".").take(3).joinToString(".")
        }

        WifiNetworkInfo(
            ssid         = rawSsid,
            bssid        = bssid,
            rssi         = rssi,
            frequency    = freq,
            linkSpeed    = linkSpeed,
            ipAddress    = formattedIp,
            gateway      = gatewayIp,
            subnetPrefix = subnetPrefix,
            isConnected  = true
        )
    }

    private fun readArpTable(): String {
        return try {
            File("/proc/net/arp").readText()
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun scanDevices(): List<WifiDevice> = withContext(Dispatchers.IO) {
        val info = readNetworkInfo()
        if (!info.isConnected || info.ipAddress == "—") return@withContext emptyList()

        val subnet = info.subnetPrefix.ifBlank { info.ipAddress.split(".").take(3).joinToString(".") }
        if (subnet.isBlank()) return@withContext emptyList()

        val blocked = getBlockedIps()
        val results = java.util.concurrent.CopyOnWriteArrayList<WifiDevice>()

        val arpHosts = try {
            parseArpTable(readArpTable())
        } catch (_: Exception) {
            emptyList()
        }

        if (arpHosts.isNotEmpty()) {
            arpHosts.forEach { ip ->
                if (ip.startsWith(subnet)) {
                    val hostname = try {
                        InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip } ?: ip
                    } catch (_: Exception) { ip }
                    val isGateway = ip == info.gateway || ip.endsWith(".1")

                    results.add(
                        WifiDevice(
                            ip         = ip,
                            hostname   = hostname,
                            isBlocked  = blocked.contains(ip),
                            customName = getCustomName(ip),
                            deviceType = inferDeviceType(ip, hostname, isGateway),
                            isGateway  = isGateway
                        )
                    )
                }
            }
            return@withContext results.toList()
        }

        val pingSemaphore = Semaphore(20)
        coroutineScope {
            (1..254).map { host ->
                async {
                    pingSemaphore.withPermit {
                        val ip = "$subnet.$host"
                        try {
                            val process = Runtime.getRuntime().exec("ping -c 1 -W 1 $ip")
                            val exitValue = process.waitFor()
                            if (exitValue == 0) {
                                val hostname = try {
                                    InetAddress.getByName(ip).canonicalHostName
                                        .takeIf { it != ip } ?: ip
                                } catch (_: Exception) { ip }

                                val isGateway = ip == info.gateway || ip.endsWith(".1")
                                results.add(
                                    WifiDevice(
                                        ip         = ip,
                                        hostname   = hostname,
                                        isBlocked  = blocked.contains(ip),
                                        customName = getCustomName(ip),
                                        deviceType = inferDeviceType(ip, hostname, isGateway),
                                        isGateway  = isGateway
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            }.forEach { it.await() }
        }

        results.toList().sortedBy { 
            it.ip.split(".").lastOrNull()?.toIntOrNull() ?: 0 
        }
    }
}

internal fun parseArpTable(arpRaw: String): List<String> {
    val ips = mutableListOf<String>()
    val lines = arpRaw.lines()
    for (line in lines) {
        val parts = line.split("\\s+".toRegex())
        if (parts.size >= 4 && parts[3] != "00:00:00:00:00:00" && parts[0].matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            ips.add(parts[0])
        }
    }
    return ips
}

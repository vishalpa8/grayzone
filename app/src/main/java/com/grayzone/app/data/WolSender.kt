package com.grayzone.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class WolDevice(
    val name: String,
    val macAddress: String
)

class WolSender(private val context: Context) {
    private val prefs = context.getSharedPreferences("wol_prefs", Context.MODE_PRIVATE)

    fun getSavedDevices(): List<WolDevice> {
        val json = prefs.getString("wol_devices", null) ?: return emptyList()
        return try {
            com.google.gson.Gson().fromJson(json, Array<WolDevice>::class.java).toList()
        } catch (_: Exception) { emptyList() }
    }

    fun saveDevice(device: WolDevice) {
        val devices = getSavedDevices().toMutableList()
        devices.removeAll { it.macAddress.equals(device.macAddress, ignoreCase = true) }
        devices.add(device)
        prefs.edit().putString("wol_devices", com.google.gson.Gson().toJson(devices)).apply()
    }

    fun removeDevice(macAddress: String) {
        val devices = getSavedDevices().filter { !it.macAddress.equals(macAddress, ignoreCase = true) }
        prefs.edit().putString("wol_devices", com.google.gson.Gson().toJson(devices)).apply()
    }

    suspend fun sendMagicPacket(macAddress: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val macBytes = WolPacket.parseMac(macAddress) ?: return@withContext false
            val packet = WolPacket.buildMagicPacket(macBytes)

            val address = InetAddress.getByName("255.255.255.255")
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val dgram = DatagramPacket(packet, packet.size, address, 9)
                socket.send(dgram)
                // Also send on port 7 for broader compatibility
                socket.send(DatagramPacket(packet, packet.size, address, 7))
            }
            true
        } catch (_: Exception) { false }
    }
}

package com.grayzone.app.service.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.grayzone.app.MainActivity
import com.grayzone.app.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.*

class AdBlockVpnService : VpnService() {

    companion object {
        const val TAG = "AdBlockVpnService"
        const val ACTION_START = "com.grayzone.app.START_VPN"
        const val ACTION_STOP = "com.grayzone.app.STOP_VPN"
        const val NOTIF_ID = 2
        const val CHANNEL_ID = "adblock_vpn_channel"

        var isRunning = false
            private set

        /**
         * Ordered list of real DNS servers to try.
         * We try each in order; if the first times out we fall back automatically.
         */
        private val DNS_SERVERS = listOf(
            "8.8.8.8",   // Google Primary
            "1.1.1.1",   // Cloudflare Primary (fallback 1)
            "9.9.9.9"    // Quad9 (fallback 2)
        )

        private const val DNS_TIMEOUT_MS = 4000
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        BlocklistManager.load(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        if (vpnInterface == null) {
            startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return

        try {
            val builder = Builder()
                .addAddress("10.0.0.2", 32)
                .addDnsServer("10.0.0.3")
                .addRoute("10.0.0.3", 32)
                .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
                .addDnsServer("fd00:1:fd00:1:fd00:1:fd00:2")
                .addRoute("fd00:1:fd00:1:fd00:1:fd00:2", 128)
                .setSession("Grayzone AdBlock")
                .setBlocking(true)

            vpnInterface = builder.establish()
            isRunning = true

            // Persist the VPN-active flag so BootReceiver can restart it after reboot.
            getSharedPreferences(com.grayzone.app.PrefsKeys.PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(com.grayzone.app.PrefsKeys.VPN_ENABLED, true).apply()

            vpnThread = Thread { runVpnLoop() }
            vpnThread?.start()

            startForeground(NOTIF_ID, buildNotification())
            Log.d(TAG, "VPN Started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopSelf()
        }
    }

    private fun stopVpn() {
        isRunning = false
        vpnThread?.interrupt()
        try {
            vpnInterface?.close()
        } catch (e: Exception) { /* ignored */ }
        vpnInterface = null

        DnsTrafficBus.clear()

        // Clear the persisted flag so BootReceiver does not auto-restart after reboot.
        getSharedPreferences(com.grayzone.app.PrefsKeys.PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(com.grayzone.app.PrefsKeys.VPN_ENABLED, false).apply()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "VPN Stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopVpn()
    }

    private fun runVpnLoop() {
        val pfd = vpnInterface ?: return
        val inputStream = FileInputStream(pfd.fileDescriptor)
        val outputStream = FileOutputStream(pfd.fileDescriptor)

        try {
            while (isRunning) {
                val packet = ByteArray(32767)
                val length = inputStream.read(packet)
                if (length > 0) {
                    if (DnsPacketHelper.isDnsQuery(packet, length)) {
                        val domain = DnsPacketHelper.getDomainName(packet, length)
                        if (domain != null) {
                            handleDnsQuery(domain, packet, length, outputStream)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (isRunning) Log.e(TAG, "VPN read loop error", e)
        }
    }

    /**
     * Route a parsed DNS query domain:
     *  1. Firefox DoH canary → NXDOMAIN (so Firefox disables DoH and uses system DNS)
     *  2. Known DoH/DoT bypass endpoint → NXDOMAIN (force apps back to plain DNS53)
     *  3. Blocked ad/adult domain → sinkhole (0.0.0.0)
     *  4. Everything else → forward to real DNS with fallback servers
     */
    private fun handleDnsQuery(
        domain: String,
        packet: ByteArray,
        length: Int,
        outputStream: FileOutputStream
    ) {
        when {
            // Firefox DoH canary — return NXDOMAIN so Firefox falls back to system DNS
            domain.equals("use-application-dns.net", ignoreCase = true) -> {
                DnsTrafficBus.emit(DnsTrafficBus.DnsEvent(domain, DnsTrafficBus.DnsEvent.Status.BLOCKED_DOH))
                writeNxDomain(packet, length, outputStream)
            }

            // Known DoH/DoT resolver — return NXDOMAIN so the app can't resolve
            // the DoH endpoint and is forced to use plain DNS53 (which we intercept)
            BlocklistManager.isDoHBypass(domain) -> {
                DnsTrafficBus.emit(DnsTrafficBus.DnsEvent(domain, DnsTrafficBus.DnsEvent.Status.BLOCKED_DOH))
                writeNxDomain(packet, length, outputStream)
            }

            // Ad or adult content domain → sinkhole to 0.0.0.0
            BlocklistManager.isBlocked(domain) -> {
                DnsTrafficBus.emit(DnsTrafficBus.DnsEvent(domain, DnsTrafficBus.DnsEvent.Status.BLOCKED_AD))
                writeSinkhole(packet, length, outputStream)
            }

            // Allowed — forward to real DNS with fallback
            else -> {
                DnsTrafficBus.emit(DnsTrafficBus.DnsEvent(domain, DnsTrafficBus.DnsEvent.Status.ALLOWED))
                serviceScope.launch {
                    forwardDnsQueryWithFallback(packet, length, outputStream)
                }
            }
        }
    }

    private fun writeNxDomain(packet: ByteArray, length: Int, outputStream: FileOutputStream) {
        val nxDomain = DnsPacketHelper.createNxDomainResponse(packet, length)
        if (nxDomain != null) {
            synchronized(outputStream) { outputStream.write(nxDomain) }
        }
    }

    private fun writeSinkhole(packet: ByteArray, length: Int, outputStream: FileOutputStream) {
        val sinkhole = DnsPacketHelper.createSinkholeResponse(packet, length)
        if (sinkhole != null) {
            synchronized(outputStream) { outputStream.write(sinkhole) }
        }
    }

    /**
     * Forward DNS query to real resolvers, trying each server in [DNS_SERVERS] order.
     * If a server times out or returns an error we move on to the next one.
     * This prevents a single flaky upstream from causing DNS failures that make
     * sites appear blocked when they shouldn't be.
     */
    private fun forwardDnsQueryWithFallback(
        packet: ByteArray,
        length: Int,
        outputStream: FileOutputStream
    ) {
        for ((index, serverIp) in DNS_SERVERS.withIndex()) {
            val server = InetAddress.getByName(serverIp)
            val success = trySingleDnsServer(packet, length, server, outputStream)
            if (success) {
                if (index > 0) Log.d(TAG, "DNS succeeded via fallback server: $serverIp")
                return
            }
            Log.w(TAG, "DNS server $serverIp failed, trying next…")
        }
        Log.e(TAG, "All DNS servers failed for this query")
    }

    /**
     * Try forwarding a DNS query to a single upstream server.
     * @return true if we received and wrote a valid response, false otherwise.
     */
    private fun trySingleDnsServer(
        packet: ByteArray,
        length: Int,
        server: InetAddress,
        outputStream: FileOutputStream
    ): Boolean {
        var udpSocket: DatagramSocket? = null
        return try {
            udpSocket = DatagramSocket()
            protect(udpSocket)
            udpSocket.soTimeout = DNS_TIMEOUT_MS

            val ihl = (packet[0].toInt() and 0x0F) * 4
            val dnsPayloadLength = length - ihl - 8
            if (dnsPayloadLength <= 0) return false

            val outPacket = DatagramPacket(packet, ihl + 8, dnsPayloadLength, server, 53)
            udpSocket.send(outPacket)

            val respBuf = ByteArray(4096)
            val respPacket = DatagramPacket(respBuf, respBuf.size)
            udpSocket.receive(respPacket)

            val wrapped = DnsPacketHelper.wrapDnsResponse(packet, length, respBuf, respPacket.length)
            if (wrapped != null) {
                synchronized(outputStream) { outputStream.write(wrapped) }
                true
            } else {
                Log.e(TAG, "Failed to wrap DNS response from $server")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "DNS forward error via $server: ${e.message}")
            false
        } finally {
            udpSocket?.close()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Grayzone AdBlock",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "AdBlock VPN is running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, AdBlockVpnService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AdBlock VPN is active")
            .setContentText("Blocking ads & adult content")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(0, "Stop", stopPending)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }
}

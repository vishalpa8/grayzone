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
    
    // Exception handler for coroutines to prevent silent failures
    private val coroutineExceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        com.grayzone.app.GrayzoneLogger.e(
            com.grayzone.app.LogComponent.VPN,
            "Uncaught exception in VPN service coroutine",
            throwable
        )
    }
    
    private val serviceScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + coroutineExceptionHandler
    )
    
    // DNS request deduplication: prevent multiple identical queries in flight
    private val pendingQueries = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()


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
            
            // Show user-friendly error message
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    this,
                    "Failed to start VPN service. Another VPN may be active. Please disable it and try again.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            
            com.grayzone.app.GrayzoneLogger.e(
                com.grayzone.app.LogComponent.VPN,
                "VPN startup failed, possibly due to another active VPN or permission denial",
                e
            )
            
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

        var consecutiveErrors = 0
        val maxConsecutiveErrors = 5
        var lastErrorTime = 0L

        // NOTE: FileInputStream.read() on VPN file descriptors can block indefinitely
        // if no packets arrive. The VPN interface is typically closed gracefully via
        // pfd.close() when stopVpn() is called, which will unblock the read() and throw IOException.
        // This is the standard Android VPN pattern. For additional safety, we have:
        // 1. Error recovery with exponential backoff
        // 2. Service restart on consecutive failures
        // 3. Graceful shutdown via isRunning flag
        
        try {
            while (isRunning) {
                try {
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
                    
                    // Reset error counter on successful iteration
                    consecutiveErrors = 0
                    
                } catch (e: java.io.IOException) {
                    val now = System.currentTimeMillis()
                    consecutiveErrors++
                    
                    com.grayzone.app.GrayzoneLogger.w(
                        com.grayzone.app.LogComponent.VPN,
                        "VPN loop error #$consecutiveErrors: ${e.message}"
                    )
                    
                    // Reset counter if errors are not consecutive (>5 seconds apart)
                    if (now - lastErrorTime > 5000) {
                        consecutiveErrors = 1
                    }
                    lastErrorTime = now
                    
                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        com.grayzone.app.GrayzoneLogger.e(
                            com.grayzone.app.LogComponent.VPN,
                            "VPN failed $maxConsecutiveErrors times consecutively, restarting service",
                            e
                        )
                        
                        // Restart VPN service with cooldown
                        serviceScope.launch {
                            stopVpn()
                            kotlinx.coroutines.delay(2000)  // Brief cooldown
                            if (isRunning) {
                                startVpn()
                            }
                        }
                        break
                    }
                    
                    // Gradual exponential backoff: 100ms, 200ms, 400ms, 800ms, 1600ms
                    // This gives the system time to recover without immediately retrying
                    val backoffMs = (100L * (1 shl (consecutiveErrors - 1))).coerceAtMost(1600L)
                    com.grayzone.app.GrayzoneLogger.d(
                        com.grayzone.app.LogComponent.VPN,
                        "Backing off for ${backoffMs}ms before retry"
                    )
                    Thread.sleep(backoffMs)
                }
            }
        } catch (e: Exception) {
            if (isRunning) {
                com.grayzone.app.GrayzoneLogger.e(
                    com.grayzone.app.LogComponent.VPN,
                    "Fatal VPN error",
                    e
                )
                // Try one last restart
                serviceScope.launch {
                    kotlinx.coroutines.delay(3000)
                    if (isRunning) startVpn()
                }
            }
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

            // Allowed — forward to real DNS with fallback and deduplication
            else -> {
                DnsTrafficBus.emit(DnsTrafficBus.DnsEvent(domain, DnsTrafficBus.DnsEvent.Status.ALLOWED))
                serviceScope.launch {
                    forwardDnsQueryWithDeduplication(domain, packet, length, outputStream)
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
     * Forward DNS query with deduplication to prevent redundant lookups.
     * If multiple identical queries arrive simultaneously, only one upstream query is made.
     * 
     * Benefits:
     * - Reduces bandwidth usage
     * - Faster response for duplicate queries (they share the result)
     * - Reduces load on upstream DNS servers
     */
    private suspend fun forwardDnsQueryWithDeduplication(
        domain: String,
        packet: ByteArray,
        length: Int,
        outputStream: FileOutputStream
    ) {
        // Check if there's already a query in flight for this domain
        val existingQuery = pendingQueries[domain]
        if (existingQuery != null) {
            com.grayzone.app.GrayzoneLogger.d(
                com.grayzone.app.LogComponent.DNS,
                "Deduplicating DNS query",
                mapOf("domain" to domain)
            )
            // Wait for the existing query to complete and reuse its result
            val result = existingQuery.await()
            if (result != null) {
                synchronized(outputStream) { outputStream.write(result) }
            }
            return
        }
        
        // Create a new deferred for this query
        val deferred = CompletableDeferred<ByteArray?>()
        pendingQueries[domain] = deferred
        
        try {
            // Perform the actual DNS query
            val result = withContext(Dispatchers.IO) {
                performDnsQuery(packet, length)
            }
            
            // Complete the deferred with the result
            deferred.complete(result)
            
            // Write the response
            if (result != null) {
                synchronized(outputStream) { outputStream.write(result) }
            }
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(
                com.grayzone.app.LogComponent.DNS,
                "DNS query failed for $domain",
                e
            )
            deferred.complete(null)
        } finally {
            // Remove from pending queries
            pendingQueries.remove(domain)
        }
    }
    
    /**
     * Perform the actual DNS query with server fallback.
     * Returns the wrapped DNS response packet, or null on failure.
     */
    private fun performDnsQuery(packet: ByteArray, length: Int): ByteArray? {
        for ((index, serverIp) in DNS_SERVERS.withIndex()) {
            val server = InetAddress.getByName(serverIp)
            val result = trySingleDnsServer(packet, length, server)
            if (result != null) {
                if (index > 0) {
                    com.grayzone.app.GrayzoneLogger.d(
                        com.grayzone.app.LogComponent.DNS,
                        "DNS succeeded via fallback server: $serverIp"
                    )
                }
                return result
            }
            com.grayzone.app.GrayzoneLogger.w(
                com.grayzone.app.LogComponent.DNS,
                "DNS server $serverIp failed, trying next…"
            )
        }
        com.grayzone.app.GrayzoneLogger.e(
            com.grayzone.app.LogComponent.DNS,
            "All DNS servers failed for this query"
        )
        return null
    }


    /**
     * Try forwarding a DNS query to a single upstream server.
     * @return wrapped DNS response packet if successful, null otherwise.
     */
    private fun trySingleDnsServer(
        packet: ByteArray,
        length: Int,
        server: InetAddress
    ): ByteArray? {
        var udpSocket: DatagramSocket? = null
        val startTime = System.nanoTime()
        
        return try {
            udpSocket = DatagramSocket()
            protect(udpSocket)
            udpSocket.soTimeout = DNS_TIMEOUT_MS

            val ihl = (packet[0].toInt() and 0x0F) * 4
            val dnsPayloadLength = length - ihl - 8
            if (dnsPayloadLength <= 0) return null

            val outPacket = DatagramPacket(packet, ihl + 8, dnsPayloadLength, server, 53)
            udpSocket.send(outPacket)

            val respBuf = ByteArray(4096)
            val respPacket = DatagramPacket(respBuf, respBuf.size)
            udpSocket.receive(respPacket)
            
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            
            // Log warning if DNS query is slow (>50ms)
            if (elapsedMs > 50) {
                com.grayzone.app.GrayzoneLogger.w(
                    com.grayzone.app.LogComponent.DNS,
                    "Slow DNS query: ${elapsedMs}ms via $server"
                )
            } else {
                com.grayzone.app.GrayzoneLogger.d(
                    com.grayzone.app.LogComponent.DNS,
                    "DNS query latency: ${elapsedMs}ms via $server"
                )
            }

            val wrapped = DnsPacketHelper.wrapDnsResponse(packet, respBuf, respPacket.length)
            if (wrapped == null) {
                com.grayzone.app.GrayzoneLogger.e(
                    com.grayzone.app.LogComponent.DNS,
                    "Failed to wrap DNS response from $server"
                )
            }
            wrapped
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            com.grayzone.app.GrayzoneLogger.w(
                com.grayzone.app.LogComponent.DNS,
                "DNS forward error via $server after ${elapsedMs}ms: ${e.message}"
            )
            null
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

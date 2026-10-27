package com.grayzone.app.service.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.grayzone.app.MainActivity
import com.grayzone.app.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import kotlinx.coroutines.*

class AdBlockVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.grayzone.app.START_VPN"
        const val ACTION_STOP = "com.grayzone.app.STOP_VPN"
        /** Alarm-driven stop after the 24h hold expires (always authorized). */
        const val ACTION_HOLD_EXPIRED = "com.grayzone.app.VPN_HOLD_EXPIRED"
        /** Rebuild the foreground notification (e.g. after hold state changes). */
        const val ACTION_REFRESH_NOTIFICATION = "com.grayzone.app.REFRESH_VPN_NOTIFICATION"
        /** Reload custom blocklist rules dynamically. */
        const val ACTION_RELOAD_RULES = "com.grayzone.app.RELOAD_RULES"
        /** Must not reuse OverlayService's ids (1 protection, 2 recovery, 3 countdown). */
        const val NOTIF_ID = 4
        const val CHANNEL_ID = "adblock_vpn_channel"

        @Volatile
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

        private const val DNS_TIMEOUT_MS = 2000

    }

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var vpnThread: Thread? = null
    
    @Volatile private var resolverPool: DnsResolverPool? = null

    /** Serializes start/stop so establish cannot interleave with shutdown (F-022). */
    private val lifecycleLock = Any()
    
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
    
    // DNS request deduplication: prevent multiple identical questions from hitting upstream at once.
    private val pendingQueries = java.util.concurrent.ConcurrentHashMap<DnsPacketHelper.DnsQuestionKey, CompletableDeferred<ByteArray?>>()

    // Aggressive DNS Caching (10 minute TTL) to speed up resolution and save bandwidth
    private data class CachedDnsResponse(val payload: ByteArray, val timestamp: Long)
    private val DNS_CACHE_TTL_MS = 10 * 60 * 1000L
    private val dnsCache = object : java.util.LinkedHashMap<DnsPacketHelper.DnsQuestionKey, CachedDnsResponse>(1000, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DnsPacketHelper.DnsQuestionKey, CachedDnsResponse>?): Boolean {
            return size > 2000
        }
    }

    override fun onCreate() {
        super.onCreate()
        BlocklistManager.load(this)
        createNotificationChannel()
        resolverPool = createResolverPool()
    }

    private fun createResolverPool() = DnsResolverPool(
        maxConcurrentSockets = 20,
        socketTimeoutMs = DNS_TIMEOUT_MS
    ) { socket ->
        try {
            if (!protect(socket)) {
                com.grayzone.app.GrayzoneLogger.e(
                    com.grayzone.app.LogComponent.VPN,
                    "protect(socket) returned false — DNS would re-enter the tunnel"
                )
                throw IllegalStateException("VpnService.protect returned false")
            }
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(
                com.grayzone.app.LogComponent.VPN,
                "protect(socket) failed — refusing to use unprotected resolver socket",
                e
            )
            throw e
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_NOTIFICATION) {
            if (vpnInterface != null || isRunning) {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification())
            } else if (!isVpnEnabled()) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            return START_STICKY
        }

        if (intent?.action == ACTION_HOLD_EXPIRED) {
            stopVpnAfterHoldExpired()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RELOAD_RULES) {
            BlocklistManager.load(this)
            if (isRunning) {
                // Bounce the tunnel to apply new Split Tunnel bypass routes real-time without releasing the 24h hold
                shutdownVpn(clearPersistedFlag = false, stopService = false)
                startVpn(allowWhenDisabled = true)
            } else if (!isVpnEnabled()) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            com.grayzone.app.GrayzoneLogger.i(com.grayzone.app.LogComponent.VPN, "Reloaded blocklist rules and tunnel")
            return START_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            val prefs = VpnHoldLockStore.prefs(this)
            val state = VpnHoldLockStore.read(prefs)
            val nowWall = System.currentTimeMillis()
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            if (!VpnHoldLock.canUserDisable(state, nowWall, nowElapsed)) {
                com.grayzone.app.GrayzoneLogger.w(
                    com.grayzone.app.LogComponent.VPN,
                    "Ignoring ACTION_STOP — VPN hold active for ${VpnHoldLock.formatRemaining(VpnHoldLock.remainingMs(state, nowWall, nowElapsed))}"
                )
                // Hold still wants the tunnel; VPN_ENABLED should already be true.
                if (vpnInterface == null) startVpn(allowWhenDisabled = false)
                return START_STICKY
            }
            // Hold already elapsed (or never tracked): allow manual stop.
            stopVpn()
            VpnHoldLockStore.clear(prefs)
            VpnHoldExpiryScheduler.cancel(this)
            return START_NOT_STICKY
        }

        // Fresh start or sticky restart: if hold already expired, auto-disable instead of running.
        val prefs = VpnHoldLockStore.prefs(this)
        val hold = VpnHoldLockStore.read(prefs)
        val nowWall = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        // UI, boot and Always-on VPN all use startForegroundService(); stopping before
        // startForeground() crashes the process with ForegroundServiceDidNotStartInTimeException.
        val foregroundStart = intent?.action == ACTION_START || intent?.action == SERVICE_INTERFACE
        if (hold.isTracked && !VpnHoldLock.isHoldActive(hold, nowWall, nowElapsed)) {
            if (foregroundStart) enterForegroundBeforeStop()
            stopVpnAfterHoldExpired()
            return START_NOT_STICKY
        }

        if (vpnInterface == null) {
            if (intent?.action == SERVICE_INTERFACE && !isVpnEnabled()) {
                com.grayzone.app.GrayzoneLogger.i(
                    com.grayzone.app.LogComponent.VPN,
                    "Always-on VPN start ignored — protection is switched off in Grayzone"
                )
                enterForegroundBeforeStop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            // Explicit START may not have persisted VPN_ENABLED yet; sticky/boot already has it.
            startVpn(allowWhenDisabled = intent?.action == ACTION_START)
        }
        return START_STICKY
    }

    private fun isVpnEnabled(): Boolean =
        getSharedPreferences(com.grayzone.app.PrefsKeys.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(com.grayzone.app.PrefsKeys.VPN_ENABLED, false)

    private fun enterForegroundBeforeStop() {
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.w(
                com.grayzone.app.LogComponent.VPN,
                "startForeground before stop failed",
                e
            )
        }
    }

    private fun stopVpnAfterHoldExpired() {
        // F-021: alarm may fire early under clock skew — re-check dual-clock hold first.
        val hold = VpnHoldLockStore.read(this)
        val nowWall = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        if (VpnHoldLock.isHoldActive(hold, nowWall, nowElapsed)) {
            com.grayzone.app.GrayzoneLogger.w(
                com.grayzone.app.LogComponent.VPN,
                "Hold-expiry alarm fired while hold still active; re-arming"
            )
            VpnHoldExpiryScheduler.rearmFromPrefs(this)
            return
        }
        stopVpn()
        VpnHoldLockStore.clear(this)
        VpnHoldExpiryScheduler.cancel(this)
        com.grayzone.app.GrayzoneLogger.i(
            com.grayzone.app.LogComponent.VPN,
            "VPN stopped after 24h hold expiry"
        )
    }

    /**
     * @param allowWhenDisabled true for an explicit user/boot [ACTION_START] where `VPN_ENABLED`
     *   may not yet be persisted; false for reconnect paths that must honour a concurrent stop (F-022).
     */
    private fun startVpn(allowWhenDisabled: Boolean = false) {
        synchronized(lifecycleLock) {
            if (isRunning) return

            val prefs = getSharedPreferences(com.grayzone.app.PrefsKeys.PREFS_NAME, MODE_PRIVATE)
            // Re-check intent under the lock so a stop during restartVpnAfterDelay's delay cannot
            // race past the post-delay read and resurrect the tunnel / 24h hold.
            if (!allowWhenDisabled &&
                !prefs.getBoolean(com.grayzone.app.PrefsKeys.VPN_ENABLED, false)
            ) {
                com.grayzone.app.GrayzoneLogger.i(
                    com.grayzone.app.LogComponent.VPN,
                    "startVpn aborted — VPN_ENABLED cleared before establish"
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            // Satisfy the foreground-service contract up front. This service is launched via
            // startForegroundService(), which requires startForeground() to be called within ~5s or the
            // system kills the process. establish() below can fail (VPN consent revoked on reinstall, or
            // another VPN active) and throw before we'd otherwise post the notification, so post it first.
            try {
                startForeground(NOTIF_ID, buildNotification())
            } catch (e: Exception) {
                com.grayzone.app.GrayzoneLogger.e(
                    com.grayzone.app.LogComponent.VPN,
                    "startForeground failed — VPN cannot start",
                    e
                )
                com.grayzone.app.data.ProtectionHealthRepository.updateVpnStatus(false)
                stopSelf()
                return
            }

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

                applyAppBypassList(builder, prefs)

                // establish() returns null when the app is no longer the prepared VPN
                // (permission revoked or another VPN took over). Treat it as a failure
                // instead of silently entering a broken "running without interface" state.
                vpnInterface = builder.establish()
                    ?: throw IllegalStateException("VpnService.Builder.establish() returned null - VPN permission revoked or another VPN is active")

                // The pool is destroyed on shutdown, so recreate it when (re)starting.
                if (resolverPool == null) {
                    resolverPool = createResolverPool()
                }
                isRunning = true

                // Persist the VPN-active flag so BootReceiver can restart it after reboot.
                prefs.edit().putBoolean(com.grayzone.app.PrefsKeys.VPN_ENABLED, true).apply()
                // Start (or keep) the 24h hold — reconnects must not reset the timer.
                VpnHoldLockStore.markEnabledIfAbsent(prefs)
                VpnHoldExpiryScheduler.rearmFromPrefs(this)

                vpnThread = Thread({ runVpnLoop() }, "Grayzone-VPN-DNS")
                vpnThread?.start()

                com.grayzone.app.data.ProtectionHealthRepository.updateVpnStatus(true)
                com.grayzone.app.GrayzoneLogger.i(com.grayzone.app.LogComponent.VPN, "VPN started")
            } catch (e: Exception) {

                // F-036: distinguish consent/establish failure from other startup errors.
                val toastMessage = if (e is IllegalStateException) {
                    "Grayzone can't start its VPN. Re-allow the VPN connection request, or turn off any other VPN app."
                } else {
                    "Grayzone couldn't start VPN filtering (${e.javaClass.simpleName}). Please try again."
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        this,
                        toastMessage,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }

                com.grayzone.app.GrayzoneLogger.e(
                    com.grayzone.app.LogComponent.VPN,
                    "VPN startup failed, possibly due to another active VPN or permission denial",
                    e
                )

                // We already entered the foreground above; tear it down cleanly so the process is never
                // killed for "started foreground service but stopped without a matching state".
                com.grayzone.app.data.ProtectionHealthRepository.updateVpnStatus(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Excludes the apps the user picked in Split tunneling (nothing by default). Applied each
     * time the tunnel is built. addDisallowedApplication throws NameNotFoundException for apps
     * that aren't installed, so each package is applied individually.
     */
    private fun applyAppBypassList(builder: Builder, prefs: SharedPreferences) {
        val userBypass = prefs.getStringSet(com.grayzone.app.PrefsKeys.VPN_BYPASS_APPS, emptySet<String>()) ?: emptySet<String>()

        var applied = 0
        userBypass.forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
                applied++
            } catch (e: PackageManager.NameNotFoundException) {
                // App not installed on this device; nothing to bypass.
            }
        }
        com.grayzone.app.GrayzoneLogger.i(
            com.grayzone.app.LogComponent.VPN,
            "VPN split tunneling: $applied app(s) bypass the tunnel"
        )
    }

    private fun stopVpn() {
        shutdownVpn(clearPersistedFlag = true, stopService = true)
        com.grayzone.app.GrayzoneLogger.i(com.grayzone.app.LogComponent.VPN, "VPN stopped")
    }

    private fun shutdownVpn(clearPersistedFlag: Boolean, stopService: Boolean) {
        synchronized(lifecycleLock) {
            val thread = vpnThread
            isRunning = false
            thread?.interrupt()
            try {
                vpnInterface?.close()
            } catch (e: Exception) {
                com.grayzone.app.GrayzoneLogger.e(
                    com.grayzone.app.LogComponent.VPN,
                    "Failed to close tun interface (clearPersistedFlag=$clearPersistedFlag)",
                    e
                )
            }
            // F-029: closing the tun fd should unblock read(); join with a bound so we can detect stalls.
            try {
                thread?.join(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread?.isAlive == true) {
                com.grayzone.app.GrayzoneLogger.w(
                    com.grayzone.app.LogComponent.VPN,
                    "VPN packet thread still alive 1s after tun close — read() did not unblock"
                )
            }
            vpnInterface = null
            vpnThread = null
            // F-038: complete in-flight followers before dropping map entries.
            pendingQueries.values.forEach { it.complete(null) }
            pendingQueries.clear()
            synchronized(dnsCache) { dnsCache.clear() }

            DnsTrafficBus.clear()

            // Synchronous shutdown: no dependency on serviceScope, which may already
            // be cancelled when this runs from onDestroy().
            resolverPool?.shutdown()
            resolverPool = null

            if (clearPersistedFlag) {
                val prefs = getSharedPreferences(com.grayzone.app.PrefsKeys.PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putBoolean(com.grayzone.app.PrefsKeys.VPN_ENABLED, false).apply()
                VpnHoldLockStore.clear(prefs)
                VpnHoldExpiryScheduler.cancel(this)
            }

            if (stopService) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            com.grayzone.app.data.ProtectionHealthRepository.updateVpnStatus(false)
        }
    }

    private fun restartVpnAfterDelay(delayMs: Long) {
        serviceScope.launch {
            val prefs = getSharedPreferences(com.grayzone.app.PrefsKeys.PREFS_NAME, MODE_PRIVATE)
            val wantsVpn = prefs.getBoolean(com.grayzone.app.PrefsKeys.VPN_ENABLED, false)
            if (wantsVpn) {
                com.grayzone.app.data.ProtectionHealthRepository.markVpnReconnectPending(true)
            }
            shutdownVpn(clearPersistedFlag = false, stopService = false)
            kotlinx.coroutines.delay(delayMs)
            // F-023: user may have stopped VPN during the delay — do not resurrect intent.
            val stillWants = prefs.getBoolean(com.grayzone.app.PrefsKeys.VPN_ENABLED, false)
            if (!stillWants) {
                com.grayzone.app.data.ProtectionHealthRepository.markVpnReconnectPending(false)
                return@launch
            }
            startVpn()
        }
    }

    override fun onRevoke() {
        // F-025: framework signal that consent was revoked or another VPN took the slot.
        com.grayzone.app.GrayzoneLogger.w(
            com.grayzone.app.LogComponent.VPN,
            "VPN consent revoked by the user or another VPN app — protection stopping"
        )
        com.grayzone.app.data.ProtectionHealthRepository.markVpnReconnectPending(true)
        // The system has already deactivated the tunnel; close our fd and stop the loop now so it
        // does not spin on I/O errors and schedule a reconnect against a VPN slot we no longer own.
        shutdownVpn(clearPersistedFlag = false, stopService = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT clear the persisted VPN_ENABLED flag here: onDestroy also fires
        // when the OS kills the service, and the flag represents user intent that
        // BootReceiver / START_STICKY use to restore protection. The flag is only
        // cleared by an explicit ACTION_STOP / hold expiry (stopVpn).
        val prefs = getSharedPreferences(com.grayzone.app.PrefsKeys.PREFS_NAME, MODE_PRIVATE)
        val wantsVpn = prefs.getBoolean(com.grayzone.app.PrefsKeys.VPN_ENABLED, false)
        if (wantsVpn) {
            com.grayzone.app.data.ProtectionHealthRepository.markVpnReconnectPending(true)
        }
        shutdownVpn(clearPersistedFlag = false, stopService = false)
        serviceScope.cancel()
    }

    private fun runVpnLoop() {
        val pfd = vpnInterface ?: return
        var consecutiveErrors = 0
        val maxConsecutiveErrors = 5
        var lastErrorTime = 0L
        try {
            val inputStream = FileInputStream(pfd.fileDescriptor)
            val outputStream = FileOutputStream(pfd.fileDescriptor)
            
            Thread.currentThread().setUncaughtExceptionHandler { _, e ->
                com.grayzone.app.GrayzoneLogger.e(
                    com.grayzone.app.LogComponent.VPN,
                    "Uncaught exception on VPN thread",
                    e
                )
                if (isRunning && vpnInterface === pfd) restartVpnAfterDelay(3000)
            }

            // Closing the tun fd (shutdownVpn) unblocks a parked FileInputStream.read.
            // The identity check stops a loop that outlived a tunnel bounce from driving the new tunnel.
            val packet = ByteArray(32767) // reuse large buffer to avoid per-iteration GC pressure
            while (isRunning && vpnInterface === pfd) {
                try {
                    val length = inputStream.read(packet)
                    if (length < 0) throw java.io.EOFException("tun interface returned EOF")

                    if (length > 0) {
                        if (DnsPacketHelper.isDnsQuery(packet, length)) {
                            val domain = DnsPacketHelper.getDomainName(packet, length)
                            if (domain != null) {
                                // Copy only the used slice for async handling to avoid races with reused buffer
                                val pktCopy = packet.copyOf(length)
                                handleDnsQuery(domain, pktCopy, pktCopy.size, outputStream)
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
                        "VPN loop I/O error #$consecutiveErrors (${e.javaClass.simpleName})",
                        e
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

                        if (isRunning && vpnInterface === pfd) restartVpnAfterDelay(2000)
                        break
                    }

                    // Gradual exponential backoff: 100ms, 200ms, 400ms, 800ms, 1600ms
                    val backoffMs = (100L * (1 shl (consecutiveErrors - 1))).coerceAtMost(1600L)
                    com.grayzone.app.GrayzoneLogger.d(
                        com.grayzone.app.LogComponent.VPN,
                        "Backing off for ${backoffMs}ms before retry"
                    )
                    try {
                        Thread.sleep(backoffMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                } catch (e: Exception) {
                    // F-028: programming errors are not transient — log and escalate immediately.
                    com.grayzone.app.GrayzoneLogger.e(
                        com.grayzone.app.LogComponent.VPN,
                        "VPN loop non-I/O error (${e.javaClass.simpleName}); restarting",
                        e
                    )
                    if (isRunning && vpnInterface === pfd) restartVpnAfterDelay(2000)
                    break
                }
            }
        } catch (e: Exception) {
            if (isRunning && vpnInterface === pfd) {
                com.grayzone.app.GrayzoneLogger.e(
                    com.grayzone.app.LogComponent.VPN,
                    "Fatal VPN error",
                    e
                )
                restartVpnAfterDelay(3000)
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
        val normalizedDomain = BlocklistManager.normalizeDomain(domain)

        when (DnsQueryClassifier.classify(domain)) {
            // Firefox canary + known DoH/DoT resolvers → NXDOMAIN so apps fall back to DNS-53
            DnsQueryClassifier.Action.NXDOMAIN_DOH -> {
                DnsTrafficBus.emit(domain, DnsTrafficBus.DnsEvent.Status.BLOCKED_DOH)
                writeNxDomain(packet, length, outputStream)
            }

            // Ad or adult content domain → sinkhole / negative answer
            DnsQueryClassifier.Action.SINKHOLE_BLOCK -> {
                DnsTrafficBus.emit(domain, DnsTrafficBus.DnsEvent.Status.BLOCKED_AD)
                writeNxDomain(packet, length, outputStream)
            }

            // Allowed — forward to real DNS with fallback and deduplication.
            // Emit ALLOWED/FAILED only after the upstream outcome is known (F-032).
            DnsQueryClassifier.Action.FORWARD -> {
                serviceScope.launch {
                    forwardDnsQueryWithDeduplication(domain, normalizedDomain, packet, length, outputStream)
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
        normalizedDomain: String?,
        packet: ByteArray,
        length: Int,
        outputStream: FileOutputStream
    ) {
        val key = normalizedDomain?.let { DnsPacketHelper.getQuestionKey(packet, length, it) }
        if (key == null) {
            val dnsPayload = withContext(Dispatchers.IO) { performDnsQuery(packet, length) }
            writeForwardedResponse(domain, packet, dnsPayload, outputStream)
            return
        }

        val cached = synchronized(dnsCache) { dnsCache[key] }
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < DNS_CACHE_TTL_MS) {
            writeForwardedResponse(domain, packet, cached.payload, outputStream)
            return
        }

        val deferred = CompletableDeferred<ByteArray?>()
        val existingQuery = pendingQueries.putIfAbsent(key, deferred)
        if (existingQuery != null) {
            writeForwardedResponse(domain, packet, existingQuery.await(), outputStream)
            return
        }

        try {
            val dnsPayload = withContext(Dispatchers.IO) { performDnsQuery(packet, length) }
            if (dnsPayload != null && isCacheable(dnsPayload)) {
                synchronized(dnsCache) {
                    dnsCache[key] = CachedDnsResponse(dnsPayload, System.currentTimeMillis())
                }
            }
            deferred.complete(dnsPayload)
            writeForwardedResponse(domain, packet, dnsPayload, outputStream)
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(
                com.grayzone.app.LogComponent.DNS,
                "DNS query failed",
                e
            )
            deferred.complete(null)
            DnsTrafficBus.emit(domain, DnsTrafficBus.DnsEvent.Status.FAILED)
        } finally {
            pendingQueries.remove(key, deferred)
        }
    }

    /** SERVFAIL/REFUSED and truncated (TC) replies are transient; caching them breaks the domain for the full TTL. */
    private fun isCacheable(payload: ByteArray): Boolean {
        if (payload.size < 12) return false
        val truncated = (payload[2].toInt() and 0x02) != 0
        val rcode = payload[3].toInt() and 0x0F
        return !truncated && (rcode == 0 || rcode == 3)
    }

    private fun writeForwardedResponse(
        domain: String,
        requestPacket: ByteArray,
        dnsPayload: ByteArray?,
        outputStream: FileOutputStream
    ) {
        if (dnsPayload == null) {
            DnsTrafficBus.emit(domain, DnsTrafficBus.DnsEvent.Status.FAILED)
            return
        }
        val wrapped = DnsPacketHelper.wrapDnsResponse(requestPacket, dnsPayload, dnsPayload.size) ?: run {
            DnsTrafficBus.emit(domain, DnsTrafficBus.DnsEvent.Status.FAILED)
            return
        }
        synchronized(outputStream) { outputStream.write(wrapped) }
        DnsTrafficBus.emit(domain, DnsTrafficBus.DnsEvent.Status.ALLOWED)
    }
    
    /**
     * Perform the actual DNS query with server fallback.
     * Returns the DNS response payload, or null on failure.
     */
    private suspend fun performDnsQuery(packet: ByteArray, length: Int): ByteArray? {
        val dnsOffset = DnsPacketHelper.getDnsPayloadOffset(packet, length) ?: return null
        val dnsPayloadLength = DnsPacketHelper.getDnsPayloadLength(packet, length) ?: return null

        val pool = resolverPool ?: return null
        
        for (serverIp in DNS_SERVERS) {
            val server = InetAddress.getByName(serverIp)
            val result = pool.performQuery(packet, dnsOffset, dnsPayloadLength, server)
            if (result != null) return result
            com.grayzone.app.GrayzoneLogger.d(com.grayzone.app.LogComponent.DNS, "DNS server $serverIp failed, trying next")
        }
        // Offline, every query fails: one line a minute is enough to see it in the log.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAllServersFailedLogMs >= 60_000L) {
            lastAllServersFailedLogMs = now
            com.grayzone.app.GrayzoneLogger.w(
                com.grayzone.app.LogComponent.DNS,
                "All upstream DNS servers failed (offline or blocked network?)"
            )
        }
        return null
    }

    @Volatile private var lastAllServersFailedLogMs = 0L


    

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
        val hold = VpnHoldLockStore.read(this)
        val nowWall = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val remaining = VpnHoldLock.remainingMs(hold, nowWall, nowElapsed)
        val holdActive = remaining > 0L
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Grayzone Protection Active")
            .setContentText(
                if (holdActive)
                    "Locked on for ${VpnHoldLock.formatRemaining(remaining)} — then auto-off"
                else
                    "DNS monitoring is securing your network."
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF0F766E.toInt())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        // Hide Stop while the 24h hold is active — disable only after auto-expiry.
        if (!holdActive) {
            val stopIntent = Intent(this, AdBlockVpnService::class.java).apply { action = ACTION_STOP }
            val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "Stop", stopPending)
        }
        return builder.build()
    }
}


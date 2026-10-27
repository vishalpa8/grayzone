package com.grayzone.app.service.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * A highly concurrent bounded pool for DNS resolution via UDP.
 * Limits the maximum number of concurrent in-flight DNS queries to prevent File Descriptor exhaustion.
 */
class DnsResolverPool(
    private val maxConcurrentSockets: Int = 20,
    private val socketTimeoutMs: Int = 5000,
    private val protectSocket: (DatagramSocket) -> Unit
) {
    private val semaphore = Semaphore(maxConcurrentSockets)
    private val idleSockets = mutableListOf<DatagramSocket>()
    // Plain lock (all critical sections are non-suspending) so shutdown()
    // can be called synchronously from Service.onDestroy().
    private val lock = Any()
    @Volatile private var isShutdown = false

    /**
     * Acquires a socket from the pool (or creates one), executes the query, 
     * awaits the response with a timeout, and returns the socket to the pool.
     */
    suspend fun performQuery(
        packet: ByteArray,
        offset: Int,
        length: Int,
        server: InetAddress,
        timeoutMs: Long = socketTimeoutMs.toLong()
    ): ByteArray? {
        if (isShutdown) return null

        return semaphore.withPermit {
            val socket = acquireSocket() ?: return@withPermit null
            var reusable = true

            try {
                // Send and receive the query on the IO dispatcher
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(timeoutMs) {
                        val outPacket = DatagramPacket(packet, offset, length, server, 53)
                        socket.send(outPacket)

                        val receiveBuffer = ByteArray(32767)
                        val inPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                        // Monotonic: a wall-clock change mid-query must not stretch or break the deadline.
                        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
                        var response: ByteArray? = null

                        try {
                            while (response == null) {
                                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                                if (remaining <= 0L) break
                                // SO_TIMEOUT is the real bound: withTimeoutOrNull cannot interrupt a blocking receive.
                                socket.soTimeout = remaining.coerceAtMost(timeoutMs).toInt().coerceAtLeast(1)
                                inPacket.length = receiveBuffer.size
                                socket.receive(inPacket)
                                // A pooled socket can still receive the late reply to an earlier timed-out query,
                                // and only the server we asked may answer.
                                if (inPacket.address == server && inPacket.port == 53 &&
                                    isReplyTo(packet, offset, length, inPacket)
                                ) {
                                    response = receiveBuffer.copyOf(inPacket.length)
                                }
                            }
                        } catch (_: SocketTimeoutException) {
                        }
                        response
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                reusable = false
                throw e
            } catch (e: Exception) {
                // The socket may be in an undefined state after an unexpected error;
                // don't return it to the pool.
                reusable = false
                // Per query and per server, so offline this would flood the log; the VPN logs one
                // rate-limited "all upstream servers failed" line instead.
                com.grayzone.app.GrayzoneLogger.d(
                    com.grayzone.app.LogComponent.DNS,
                    "Upstream DNS query failed (${e.javaClass.simpleName}: ${e.message})"
                )
                null
            } finally {
                releaseSocket(socket, reusable)
            }
        }
    }

    private fun acquireSocket(): DatagramSocket? {
        synchronized(lock) {
            if (isShutdown) return null
            if (idleSockets.isNotEmpty()) {
                return idleSockets.removeAt(idleSockets.size - 1)
            }
        }

        // Create a new socket if the pool is empty
        return try {
            val socket = DatagramSocket()
            try {
                protectSocket(socket)
            } catch (e: Exception) {
                closeQuietly(socket, "protect failed")
                throw e
            }
            socket
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(
                com.grayzone.app.LogComponent.DNS,
                "Failed to create DatagramSocket",
                e
            )
            null
        }
    }

    private fun isReplyTo(query: ByteArray, offset: Int, length: Int, reply: DatagramPacket): Boolean {
        if (length < 2 || reply.length < 12) return false
        val data = reply.data
        val base = reply.offset
        val isResponse = (data[base + 2].toInt() and 0x80) != 0
        return isResponse && data[base] == query[offset] && data[base + 1] == query[offset + 1]
    }

    private fun releaseSocket(socket: DatagramSocket, reusable: Boolean) {
        if (!reusable || socket.isClosed) {
            closeQuietly(socket, "release non-reusable")
            return
        }
        synchronized(lock) {
            if (isShutdown) {
                closeQuietly(socket, "release after shutdown")
            } else {
                idleSockets.add(socket)
            }
        }
    }

    fun shutdown() {
        synchronized(lock) {
            isShutdown = true
            idleSockets.forEach { closeQuietly(it, "pool shutdown") }
            idleSockets.clear()
        }
    }

    /** F-031: never swallow close failures without a log. */
    private fun closeQuietly(socket: DatagramSocket, reason: String) {
        try {
            socket.close()
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.d(
                com.grayzone.app.LogComponent.DNS,
                "socket.close failed ($reason): ${e.javaClass.simpleName}"
            )
        }
    }
}

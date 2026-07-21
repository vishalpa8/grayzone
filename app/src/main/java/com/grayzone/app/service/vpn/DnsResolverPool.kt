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
                        
                        // Set the SO_TIMEOUT on the socket itself as an additional safeguard
                        socket.soTimeout = timeoutMs.toInt()
                        
                        try {
                            socket.receive(inPacket)
                            receiveBuffer.copyOf(inPacket.length)
                        } catch (e: SocketTimeoutException) {
                            null
                        }
                    }
                }
            } catch (e: Exception) {
                // The socket may be in an undefined state after an unexpected error;
                // don't return it to the pool.
                reusable = false
                com.grayzone.app.GrayzoneLogger.w(
                    com.grayzone.app.LogComponent.DNS,
                    "DNS query failed: ${e.message}"
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
            protectSocket(socket)
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

    private fun releaseSocket(socket: DatagramSocket, reusable: Boolean) {
        if (!reusable || socket.isClosed) {
            try { socket.close() } catch (_: Exception) {}
            return
        }
        synchronized(lock) {
            if (isShutdown) {
                try { socket.close() } catch (_: Exception) {}
            } else {
                idleSockets.add(socket)
            }
        }
    }

    fun shutdown() {
        synchronized(lock) {
            isShutdown = true
            idleSockets.forEach { 
                try { it.close() } catch (e: Exception) {} 
            }
            idleSockets.clear()
        }
    }
}

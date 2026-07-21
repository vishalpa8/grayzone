package com.grayzone.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket

data class PortResult(
    val port: Int,
    val isOpen: Boolean,
    val serviceName: String
)

object PortScanner {
    private val commonPorts = mapOf(
        21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP",
        53 to "DNS", 80 to "HTTP", 110 to "POP3", 143 to "IMAP",
        443 to "HTTPS", 445 to "SMB", 554 to "RTSP", 993 to "IMAPS",
        995 to "POP3S", 1883 to "MQTT", 3306 to "MySQL", 3389 to "RDP",
        5000 to "UPnP", 5353 to "mDNS", 5900 to "VNC", 8080 to "HTTP-Alt",
        8443 to "HTTPS-Alt", 8888 to "HTTP-Proxy", 9090 to "Web-Console",
        32400 to "Plex", 49152 to "UPnP"
    )

    /** Service label for a well-known port, or "Unknown". */
    fun serviceNameFor(port: Int): String = commonPorts[port] ?: "Unknown"

    fun commonPortList(): List<Int> = commonPorts.keys.toList()

    suspend fun scanPorts(
        ip: String,
        ports: List<Int> = commonPorts.keys.toList(),
        timeoutMs: Int = 800,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> }
    ): List<PortResult> = withContext(Dispatchers.IO) {
        val results = java.util.concurrent.CopyOnWriteArrayList<PortResult>()
        val semaphore = Semaphore(15)
        // Scans run on up to 15 threads concurrently, so the progress counter
        // must be atomic to avoid lost updates.
        val scanned = java.util.concurrent.atomic.AtomicInteger(0)
        coroutineScope {
            ports.map { port ->
                async {
                    semaphore.withPermit {
                        val isOpen = try {
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                                true
                            }
                        } catch (_: Exception) { false }
                        results.add(PortResult(port, isOpen, commonPorts[port] ?: "Unknown"))
                        onProgress(scanned.incrementAndGet(), ports.size)
                    }
                }
            }.forEach { it.await() }
        }
        results.filter { it.isOpen }.sortedBy { it.port }
    }
}

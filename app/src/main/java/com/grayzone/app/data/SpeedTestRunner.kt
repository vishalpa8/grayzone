package com.grayzone.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

data class SpeedTestResult(
    val gatewayPingMs: Long = -1,
    val internetPingMs: Long = -1,
    val downloadSpeedMbps: Double = 0.0,
    val downloadedBytes: Long = 0,
    val durationMs: Long = 0
)

object SpeedTestRunner {
    suspend fun pingHost(host: String): Long = withContext(Dispatchers.IO) {
        try {
            val start = System.nanoTime()
            val reachable = InetAddress.getByName(host).isReachable(3000)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            if (reachable) elapsed else -1L
        } catch (_: Exception) { -1L }
    }

    suspend fun measureDownloadSpeed(url: String = "https://speed.cloudflare.com/__down?bytes=10000000"): SpeedTestResult = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            val buffer = ByteArray(8192)
            var totalBytes = 0L
            val start = System.nanoTime()
            conn.inputStream.use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    totalBytes += bytesRead
                }
            }
            val durationNs = System.nanoTime() - start
            val durationMs = durationNs / 1_000_000
            val speedMbps = if (durationMs > 0) (totalBytes * 8.0) / (durationMs * 1000.0) else 0.0
            SpeedTestResult(downloadSpeedMbps = speedMbps, downloadedBytes = totalBytes, durationMs = durationMs)
        } catch (_: Exception) {
            SpeedTestResult()
        }
    }

    suspend fun runFullTest(gatewayIp: String): SpeedTestResult = withContext(Dispatchers.IO) {
        val gPing = pingHost(gatewayIp)
        val iPing = pingHost("8.8.8.8")
        val dl = measureDownloadSpeed()
        dl.copy(gatewayPingMs = gPing, internetPingMs = iPing)
    }
}

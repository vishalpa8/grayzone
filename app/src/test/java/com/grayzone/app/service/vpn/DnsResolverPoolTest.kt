package com.grayzone.app.service.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class DnsResolverPoolTest {

    @Test
    fun `pool limits concurrency to max sockets`() = runBlocking {
        val maxSockets = 5
        val socketsCreated = AtomicInteger(0)

        val pool = DnsResolverPool(maxConcurrentSockets = maxSockets) {
            socketsCreated.incrementAndGet()
        }

        // We launch 20 concurrent requests.
        // Because of the Semaphore(5), at most 5 should be running at any given time,
        // so at most 5 sockets should be created in the pool.
        val jobs = (1..20).map {
            launch(Dispatchers.IO) {
                val fakePacket = ByteArray(10)
                val server = InetAddress.getByName("127.0.0.1")
                pool.performQuery(fakePacket, 0, 10, server, timeoutMs = 50L)
            }
        }
        
        jobs.forEach { it.join() }
        
        // Assert that we never created more than maxSockets
        assertTrue(socketsCreated.get() <= maxSockets)
        // Ensure we actually created sockets
        assertTrue(socketsCreated.get() > 0)
    }
}

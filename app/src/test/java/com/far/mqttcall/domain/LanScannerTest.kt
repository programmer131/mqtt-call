package com.far.mqttcall.domain

import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanScannerTest {
    @Test
    fun `subnet hosts exclude network broadcast and own address`() {
        assertEquals(
            listOf("192.168.4.9", "192.168.4.10", "192.168.4.11", "192.168.4.12", "192.168.4.13", "192.168.4.14"),
            subnetHosts("192.168.4.8", 29),
        )
        assertFalse(subnetHosts("192.168.4.10", 28).contains("192.168.4.10"))
    }

    @Test
    fun `large subnets are bounded to local slash twenty four`() {
        val hosts = subnetHosts("10.2.3.7", 16)
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("10.2.3.") })
    }

    @Test
    fun `probe reports only an open TCP port`() = runBlocking {
        ServerSocket(0).use { listener ->
            assertTrue(probeTcpPort("127.0.0.1", listener.localPort, 100))
            val closedPort = ServerSocket(0).use { it.localPort }
            assertFalse(probeTcpPort("127.0.0.1", closedPort, 100))
        }
    }
}

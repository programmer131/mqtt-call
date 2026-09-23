package com.far.mqttcall.transport

import com.far.mqttcall.domain.AppDefaults
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttToken
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PahoMqttTransportTest {
    @Test(timeout = 5_000)
    fun `abort force closes a client still waiting for connection acknowledgment`() = runBlocking {
        val client = PendingClient()
        val transport = PahoMqttTransport(clientFactory = { _, _ -> client })
        val connection = async(Dispatchers.IO) {
            transport.connect(AppDefaults.defaultBroker, "call/channel/3344")
        }
        try {
            assertTrue(client.started.await(2, TimeUnit.SECONDS))
            transport.abort()
            withTimeout(2_000) { connection.await() }

            assertTrue(client.closed.await(1, TimeUnit.SECONDS))
            assertTrue(client.forceClosed)
            assertFalse(client.subscribed)
            assertTrue(client.tokenTimeout in 1..10_000)
        } finally {
            transport.abort()
            client.release.countDown()
        }
    }

    @Test
    fun `abort permanently rejects new connections without opening a socket`() = runTest {
        val transport = PahoMqttTransport()
        transport.abort()
        transport.abort()

        val result = runCatching { transport.connect(AppDefaults.defaultBroker, "call/channel/3344") }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        transport.disconnect()
    }

    private class PendingClient : MqttAsyncClient(
        "tcp://127.0.0.1:1883", "pending-test", MemoryPersistence(),
    ) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        @Volatile var forceClosed = false
        @Volatile var subscribed = false
        @Volatile var tokenTimeout = 0L

        override fun connect(options: MqttConnectOptions): IMqttToken {
            started.countDown()
            return object : MqttToken("pending-test") {
                override fun waitForCompletion(timeout: Long) {
                    tokenTimeout = timeout
                    check(release.await(timeout, TimeUnit.MILLISECONDS)) { "Connect timed out" }
                }
            }
        }

        override fun subscribe(topic: String, qos: Int): IMqttToken {
            subscribed = true
            return MqttToken("pending-test")
        }

        override fun disconnectForcibly(quiesceTimeout: Long, disconnectTimeout: Long, sendDisconnect: Boolean) {
            forceClosed = true
            release.countDown()
        }

        override fun close(force: Boolean) {
            super.close(force)
            closed.countDown()
        }
    }
}

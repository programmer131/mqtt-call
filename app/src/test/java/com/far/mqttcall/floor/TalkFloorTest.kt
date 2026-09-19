package com.far.mqttcall.floor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkFloorTest {
    private val localSession = remoteSession(9)

    @Test
    fun `remote claim disables local transmit until release or expiry`() {
        val clock = FakeClock(0)
        val floor = TalkFloor(clock::now)
        floor.onRemoteClaim(remoteSession(1), expiresAtMs = 1_000)

        assertFalse(floor.canTransmit())
        clock.advance(1_001)
        assertTrue(floor.canTransmit())
    }

    @Test
    fun `local claim can be renewed while audio is flowing`() {
        val clock = FakeClock(0)
        val floor = TalkFloor(clock::now)
        assertTrue(floor.localClaim(localSession, expiresAtMs = 1_000))

        clock.advance(900)
        assertTrue(floor.renewLocal(localSession, expiresAtMs = 1_900))
        clock.advance(200)

        assertEquals(TalkState.LOCAL_TALKING, floor.state())
    }

    @Test
    fun `release clears the active remote session`() {
        val floor = TalkFloor { 0 }
        val remote = remoteSession(2)
        floor.onRemoteClaim(remote, expiresAtMs = 1_000)

        floor.onRemoteRelease(remote)

        assertTrue(floor.canTransmit())
        assertTrue(floor.activeRemoteSession() == null)
    }

    @Test
    fun `higher session id wins a simultaneous claim`() {
        val floor = TalkFloor { 0 }
        val local = remoteSession(1)
        val remote = remoteSession(2)

        assertTrue(floor.localClaim(local, expiresAtMs = 1_000))
        floor.onRemoteClaim(remote, expiresAtMs = 1_000)

        assertFalse(floor.canTransmit())
        assertArrayEquals(remote, floor.activeRemoteSession())
    }

    private fun remoteSession(lastByte: Int): ByteArray = ByteArray(16).also {
        it[it.lastIndex] = lastByte.toByte()
    }

    private class FakeClock(private var currentMs: Long) {
        fun now(): Long = currentMs
        fun advance(amountMs: Long) {
            currentMs += amountMs
        }
    }
}

package com.far.mqttcall.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TalkClaimTest {
    @Test
    fun `claim keeps optional user name after expiry`() {
        assertEquals(
            TalkClaim(1234L, "Alice"),
            decodeTalkClaim(encodeTalkClaim(1234L, "Alice")),
        )
    }

    @Test
    fun `empty name preserves old expiry-only claim format`() {
        assertEquals(TalkClaim(1234L, null), decodeTalkClaim(encodeTalkClaim(1234L, "")))
        assertNull(decodeTalkClaim(byteArrayOf(1, 2, 3)))
    }
}

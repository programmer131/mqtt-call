package com.far.mqttcall.floor

private const val TALK_LEASE_MS = 1_000L

data class TalkClaim(
    val sessionId: ByteArray,
    val expiresAtMs: Long,
)

enum class TalkState {
    IDLE,
    LOCAL_TALKING,
    REMOTE_TALKING,
    BUSY,
}

class TalkFloor(private val clockMs: () -> Long) {
    private var localSession: TalkClaim? = null
    private var remoteSession: TalkClaim? = null
    private var localWasRejected = false

    fun onRemoteClaim(sessionId: ByteArray, expiresAtMs: Long) {
        pruneExpired()
        if (expiresAtMs <= clockMs() || sessionId.isEmpty()) return

        val remote = TalkClaim(sessionId.copyOf(), expiresAtMs)
        val local = localSession
        if (local != null) {
            if (compareSessionIds(remote.sessionId, local.sessionId) > 0) {
                localSession = null
                remoteSession = remote
                localWasRejected = true
            }
            return
        }

        val current = remoteSession
        if (current == null || compareSessionIds(remote.sessionId, current.sessionId) >= 0) {
            remoteSession = remote
        }
    }

    fun onRemoteAudio(sessionId: ByteArray, expiresAtMs: Long = clockMs() + TALK_LEASE_MS) {
        onRemoteClaim(sessionId, expiresAtMs)
    }

    fun onRemoteRelease(sessionId: ByteArray) {
        if (remoteSession?.sessionId?.contentEquals(sessionId) == true) {
            remoteSession = null
            localWasRejected = false
        }
        pruneExpired()
    }

    fun canTransmit(): Boolean {
        pruneExpired()
        return remoteSession == null
    }

    fun activeRemoteSession(): ByteArray? {
        pruneExpired()
        return remoteSession?.sessionId?.copyOf()
    }

    fun localClaim(sessionId: ByteArray, expiresAtMs: Long): Boolean {
        pruneExpired()
        if (sessionId.isEmpty() || expiresAtMs <= clockMs()) return false

        val remote = remoteSession
        if (remote != null && compareSessionIds(sessionId, remote.sessionId) <= 0) {
            localWasRejected = true
            return false
        }

        remoteSession = null
        localWasRejected = false
        localSession = TalkClaim(sessionId.copyOf(), expiresAtMs)
        return true
    }

    fun releaseLocal() {
        localSession = null
        localWasRejected = false
        pruneExpired()
    }

    fun state(): TalkState {
        pruneExpired()
        return when {
            localWasRejected && remoteSession != null -> TalkState.BUSY
            localSession != null -> TalkState.LOCAL_TALKING
            remoteSession != null -> TalkState.REMOTE_TALKING
            else -> TalkState.IDLE
        }
    }

    private fun pruneExpired() {
        val now = clockMs()
        if (localSession?.expiresAtMs ?: Long.MAX_VALUE <= now) localSession = null
        if (remoteSession?.expiresAtMs ?: Long.MAX_VALUE <= now) remoteSession = null
        if (remoteSession == null) localWasRejected = false
    }
}

private fun compareSessionIds(first: ByteArray, second: ByteArray): Int {
    val length = minOf(first.size, second.size)
    for (index in 0 until length) {
        val difference = (first[index].toInt() and 0xFF) - (second[index].toInt() and 0xFF)
        if (difference != 0) return difference
    }
    return first.size - second.size
}

package com.far.mqttcall.crypto

class JvmCryptoEngine : CryptoEngine {
    override val securityLevel: SecurityLevel = SecurityLevel.SOFTWARE

    override fun prepare(channel: String, passphrase: CharArray): CryptoSession =
        AesGcmCryptoSession(deriveChannelKey(channel, passphrase))
}

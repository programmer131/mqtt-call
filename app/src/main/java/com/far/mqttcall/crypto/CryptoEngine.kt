package com.far.mqttcall.crypto

import com.far.mqttcall.protocol.DecodedPacket
import com.far.mqttcall.protocol.PacketCodec
import com.far.mqttcall.protocol.PacketHeader
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class SecurityLevel {
    STRONGBOX,
    TRUSTED_ENVIRONMENT,
    SOFTWARE,
}

interface CryptoEngine {
    val securityLevel: SecurityLevel

    fun prepare(channel: String, passphrase: CharArray): CryptoSession
}

interface CryptoSession {
    fun encrypt(header: PacketHeader, plaintext: ByteArray): ByteArray

    fun decrypt(packet: ByteArray): DecodedPacket?
}

internal const val KDF_ITERATIONS = 100_000
internal const val DERIVED_KEY_BITS = 256

internal fun deriveChannelKey(channel: String, passphrase: CharArray): SecretKey {
    val salt = "mqtt-ptt-v1/$channel".toByteArray(StandardCharsets.UTF_8)
    val spec = PBEKeySpec(passphrase, salt, KDF_ITERATIONS, DERIVED_KEY_BITS)
    return try {
        val bytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } catch (_: NoSuchAlgorithmException) {
            derivePbkdf2HmacSha256Fallback(
                passphrase,
                salt,
                KDF_ITERATIONS,
                DERIVED_KEY_BITS,
            )
        }
        SecretKeySpec(bytes, "AES")
    } finally {
        spec.clearPassword()
    }
}

internal fun derivePbkdf2HmacSha256Fallback(
    password: CharArray,
    salt: ByteArray,
    iterations: Int,
    keyBits: Int,
): ByteArray {
    require(iterations > 0) { "PBKDF2 requires at least one iteration" }
    require(keyBits > 0 && keyBits % 8 == 0) { "PBKDF2 key length must be a positive byte multiple" }

    val passwordBytes = password.concatToString().toByteArray(StandardCharsets.UTF_8)
    return try {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(passwordBytes, "HmacSHA256"))
        }
        val keyLength = keyBits / 8
        val blockLength = mac.macLength
        val blockCount = (keyLength + blockLength - 1) / blockLength
        val result = ByteArray(keyLength)
        val input = ByteArray(salt.size + 4)
        salt.copyInto(input)
        var resultOffset = 0

        for (blockIndex in 1..blockCount) {
            input[salt.size] = (blockIndex ushr 24).toByte()
            input[salt.size + 1] = (blockIndex ushr 16).toByte()
            input[salt.size + 2] = (blockIndex ushr 8).toByte()
            input[salt.size + 3] = blockIndex.toByte()

            var u = mac.doFinal(input)
            val xorResult = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                xorResult.indices.forEach { index ->
                    xorResult[index] = (xorResult[index].toInt() xor u[index].toInt()).toByte()
                }
            }

            val copyLength = minOf(xorResult.size, result.size - resultOffset)
            xorResult.copyInto(result, resultOffset, endIndex = copyLength)
            resultOffset += copyLength
        }
        result
    } finally {
        passwordBytes.fill(0)
    }
}

internal class AesGcmCryptoSession(
    private val key: SecretKey,
    private val random: SecureRandom = SecureRandom(),
    private val providerGeneratedNonce: Boolean = false,
) : CryptoSession {
    private val decryptedPackets = mutableSetOf<PacketIdentity>()

    override fun encrypt(header: PacketHeader, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val actualHeader = if (providerGeneratedNonce) {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val nonce = cipher.iv
            require(nonce.size == 12) { "AES-GCM provider returned an invalid nonce" }
            header.copy(nonce = nonce)
        } else {
            val nonce = ByteArray(12).also(random::nextBytes)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            header.copy(nonce = nonce)
        }
        cipher.updateAAD(PacketCodec.headerBytes(actualHeader))
        return PacketCodec.encode(actualHeader, cipher.doFinal(plaintext))
    }

    override fun decrypt(packet: ByteArray): DecodedPacket? {
        val parsed = PacketCodec.decode(packet) ?: return null
        val identity = PacketIdentity(
            parsed.header.kind,
            parsed.header.sessionId.toHex(),
            parsed.header.sequence,
        )
        if (identity in decryptedPackets) return null

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, parsed.header.nonce),
            )
            cipher.updateAAD(PacketCodec.headerBytes(parsed.header))
            val plaintext = cipher.doFinal(parsed.ciphertext)
            decryptedPackets += identity
            DecodedPacket(parsed.header, plaintext)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private data class PacketIdentity(
        val kind: Any,
        val sessionId: String,
        val sequence: Long,
    )
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

package com.far.mqttcall.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

class AndroidCryptoEngine(private val context: Context) : CryptoEngine {
    override var securityLevel: SecurityLevel = SecurityLevel.SOFTWARE
        private set

    override fun prepare(channel: String, passphrase: CharArray): CryptoSession {
        val derived = deriveChannelKey(channel, passphrase)
        val alias = "mqtt-ptt-${channel.sha256Prefix()}"
        val imported = importKey(alias, derived, preferStrongBox = true)
            ?: importKey(alias, derived, preferStrongBox = false)

        if (imported == null) {
            securityLevel = SecurityLevel.SOFTWARE
            return AesGcmCryptoSession(derived)
        }

        securityLevel = inspectSecurityLevel(imported)
        return AesGcmCryptoSession(imported, providerGeneratedNonce = true)
    }

    private fun importKey(alias: String, key: SecretKey, preferStrongBox: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching { if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias) }

        return try {
            val protection = KeyProtection.Builder(
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .apply {
                    if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setIsStrongBoxBacked(
                            context.packageManager.hasSystemFeature(
                                PackageManager.FEATURE_STRONGBOX_KEYSTORE,
                            ),
                        )
                    }
                }
                .build()
            keyStore.setEntry(alias, KeyStore.SecretKeyEntry(key), protection)
            keyStore.getKey(alias, null) as? SecretKey
        } catch (_: StrongBoxUnavailableException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun inspectSecurityLevel(key: SecretKey): SecurityLevel {
        return runCatching {
            val factory = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.getSecurityLevel()) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> SecurityLevel.STRONGBOX
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ->
                        SecurityLevel.TRUSTED_ENVIRONMENT
                    else -> SecurityLevel.SOFTWARE
                }
            } else if (info.isInsideSecureHardware()) {
                SecurityLevel.TRUSTED_ENVIRONMENT
            } else {
                SecurityLevel.SOFTWARE
            }
        }.getOrDefault(SecurityLevel.SOFTWARE)
    }
}

private fun String.sha256Prefix(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray())
    .take(10)
    .joinToString("") { "%02x".format(it) }

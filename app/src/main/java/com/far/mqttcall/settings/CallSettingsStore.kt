package com.far.mqttcall.settings

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import com.far.mqttcall.domain.AppDefaults
import com.far.mqttcall.domain.BrokerProfile
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedCallSettings(
    val broker: BrokerProfile,
    val channel: String,
    val key: String,
    val keepConnected: Boolean = false,
    val microphonePermissionPrompted: Boolean = false,
)

interface CallSettingsStore {
    fun load(): SavedCallSettings

    fun save(settings: SavedCallSettings)

    fun setKeepConnected(keepConnected: Boolean)

    fun markMicrophonePermissionPrompted()
}

class InMemoryCallSettingsStore(
    private var current: SavedCallSettings = SavedCallSettings(
        broker = AppDefaults.defaultBroker,
        channel = AppDefaults.defaultChannel,
        key = AppDefaults.defaultKey,
    ),
) : CallSettingsStore {
    override fun load(): SavedCallSettings = current

    override fun save(settings: SavedCallSettings) {
        current = settings.copy(
            keepConnected = current.keepConnected,
            microphonePermissionPrompted = current.microphonePermissionPrompted,
        )
    }

    override fun setKeepConnected(keepConnected: Boolean) {
        current = current.copy(keepConnected = keepConnected)
    }

    override fun markMicrophonePermissionPrompted() {
        current = current.copy(microphonePermissionPrompted = true)
    }
}

class AndroidCallSettingsStore(context: Context) : CallSettingsStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): SavedCallSettings {
        val broker = BrokerProfile(
            name = preferences.getString(KEY_BROKER_NAME, AppDefaults.defaultBroker.name)!!,
            host = preferences.getString(KEY_BROKER_HOST, AppDefaults.defaultBroker.host)!!,
            port = preferences.getInt(KEY_BROKER_PORT, AppDefaults.defaultBroker.port),
            tls = preferences.getBoolean(KEY_BROKER_TLS, AppDefaults.defaultBroker.tls),
            username = preferences.getString(KEY_BROKER_USERNAME, null),
            password = preferences.getString(KEY_BROKER_PASSWORD, null),
        )
        val encryptedKey = preferences.getString(KEY_ENCRYPTED_KEY, null)
        val key = encryptedKey?.let(::decryptKey) ?: AppDefaults.defaultKey
        return SavedCallSettings(
            broker = broker,
            channel = preferences.getString(KEY_CHANNEL, AppDefaults.defaultChannel)!!,
            key = key,
            keepConnected = preferences.getBoolean(KEY_KEEP_CONNECTED, false),
            microphonePermissionPrompted = preferences.getBoolean(KEY_MICROPHONE_PERMISSION_PROMPTED, false),
        )
    }

    override fun save(settings: SavedCallSettings) {
        preferences.edit()
            .putString(KEY_BROKER_NAME, settings.broker.name)
            .putString(KEY_BROKER_HOST, settings.broker.host)
            .putInt(KEY_BROKER_PORT, settings.broker.port)
            .putBoolean(KEY_BROKER_TLS, settings.broker.tls)
            .putString(KEY_BROKER_USERNAME, settings.broker.username)
            .putString(KEY_BROKER_PASSWORD, settings.broker.password)
            .putString(KEY_CHANNEL, settings.channel)
            .putString(KEY_ENCRYPTED_KEY, encryptKey(settings.key))
            .apply()
    }

    override fun setKeepConnected(keepConnected: Boolean) {
        preferences.edit().putBoolean(KEY_KEEP_CONNECTED, keepConnected).apply()
    }

    override fun markMicrophonePermissionPrompted() {
        preferences.edit().putBoolean(KEY_MICROPHONE_PERMISSION_PROMPTED, true).apply()
    }

    private fun encryptKey(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val nonce = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(nonce + ciphertext, Base64.NO_WRAP)
    }

    private fun decryptKey(encoded: String): String? = runCatching {
        val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
        require(encrypted.size > 12)
        val nonce = encrypted.copyOfRange(0, 12)
        val ciphertext = encrypted.copyOfRange(12, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, nonce))
        String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return generateWrappingKey(keyStore, preferStrongBox = true)
            ?: generateWrappingKey(keyStore, preferStrongBox = false)
            ?: error("Android Keystore AES key is unavailable")
    }

    private fun generateWrappingKey(keyStore: KeyStore, preferStrongBox: Boolean): SecretKey? =
        try {
            val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true)
            }
            generator.init(builder.build())
            generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            null
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val PREFERENCES = "mqtt_call_settings"
        const val KEY_ALIAS = "mqtt-call-settings-wrap-v1"
        const val KEY_BROKER_NAME = "broker_name"
        const val KEY_BROKER_HOST = "broker_host"
        const val KEY_BROKER_PORT = "broker_port"
        const val KEY_BROKER_TLS = "broker_tls"
        const val KEY_BROKER_USERNAME = "broker_username"
        const val KEY_BROKER_PASSWORD = "broker_password"
        const val KEY_KEEP_CONNECTED = "keep_connected"
        const val KEY_MICROPHONE_PERMISSION_PROMPTED = "microphone_permission_prompted"
        const val KEY_CHANNEL = "channel"
        const val KEY_ENCRYPTED_KEY = "encrypted_key"
    }
}

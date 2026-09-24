package com.far.mqttcall.settings

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import com.far.mqttcall.domain.AppDefaults
import com.far.mqttcall.domain.BrokerProfile
import com.far.mqttcall.domain.defaultAudioPacketIntervalUnits
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedCallSettings(
    val broker: BrokerProfile,
    val channel: String,
    val keySlots: List<String>,
    val activeKeyIndex: Int = 0,
    val savedBrokers: List<BrokerProfile> = emptyList(),
    val savedChannels: List<String> = listOf(AppDefaults.defaultChannel),
    val keepConnected: Boolean = false,
    val microphonePermissionPrompted: Boolean = false,
) {
    init {
        require(keySlots.size == KEY_SLOT_COUNT) { "Exactly three key slots are required" }
        require(activeKeyIndex in keySlots.indices) { "Active key slot is out of range" }
    }

    val activeKey: String get() = keySlots[activeKeyIndex]
}

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
        keySlots = AppDefaults.defaultKeySlots,
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
        val brokerHost = preferences.getString(KEY_BROKER_HOST, AppDefaults.defaultBroker.host)!!
        val broker = BrokerProfile(
            name = preferences.getString(KEY_BROKER_NAME, AppDefaults.defaultBroker.name)!!,
            host = brokerHost,
            port = preferences.getInt(KEY_BROKER_PORT, AppDefaults.defaultBroker.port),
            tls = preferences.getBoolean(KEY_BROKER_TLS, AppDefaults.defaultBroker.tls),
            username = preferences.getString(KEY_BROKER_USERNAME, null),
            password = preferences.getString(KEY_BROKER_PASSWORD, null),
            audioPacketIntervalUnits = preferences.getInt(
                KEY_AUDIO_PACKET_INTERVAL_UNITS,
                defaultAudioPacketIntervalUnits(brokerHost),
            ),
        )
        val legacyKey = preferences.getString(KEY_ENCRYPTED_KEY, null)?.let(::decryptKey)
        val keySlots = List(KEY_SLOT_COUNT) { index ->
            preferences.getString(encryptedKeySlot(index), null)?.let(::decryptKey)
                ?: if (index == 0) legacyKey ?: AppDefaults.defaultKey else ""
        }
        return SavedCallSettings(
            broker = broker,
            channel = preferences.getString(KEY_CHANNEL, AppDefaults.defaultChannel)!!,
            keySlots = keySlots,
            activeKeyIndex = preferences.getInt(KEY_ACTIVE_KEY_INDEX, 0).coerceIn(0, KEY_SLOT_COUNT - 1),
            savedBrokers = loadSavedBrokers(),
            savedChannels = (loadSavedChannels() + preferences.getString(KEY_CHANNEL, AppDefaults.defaultChannel)!!).distinct(),
            keepConnected = preferences.getBoolean(KEY_KEEP_CONNECTED, false),
            microphonePermissionPrompted = preferences.getBoolean(KEY_MICROPHONE_PERMISSION_PROMPTED, false),
        )
    }

    override fun save(settings: SavedCallSettings) {
        val editor = preferences.edit()
            .putString(KEY_BROKER_NAME, settings.broker.name)
            .putString(KEY_BROKER_HOST, settings.broker.host)
            .putInt(KEY_BROKER_PORT, settings.broker.port)
            .putBoolean(KEY_BROKER_TLS, settings.broker.tls)
            .putString(KEY_BROKER_USERNAME, settings.broker.username)
            .putString(KEY_BROKER_PASSWORD, settings.broker.password)
            .putInt(KEY_AUDIO_PACKET_INTERVAL_UNITS, settings.broker.audioPacketIntervalUnits)
            .putString(KEY_CHANNEL, settings.channel)
            .putString(KEY_ENCRYPTED_KEY, encryptKey(settings.keySlots.first()))
            .putInt(KEY_ACTIVE_KEY_INDEX, settings.activeKeyIndex)
        settings.keySlots.forEachIndexed { index, key ->
            editor.putString(encryptedKeySlot(index), encryptKey(key))
        }

        val previousBrokerCount = preferences.getInt(KEY_SAVED_BROKER_COUNT, 0)
        repeat(maxOf(previousBrokerCount, settings.savedBrokers.size)) { index ->
            brokerField(index, "name").also(editor::remove)
            brokerField(index, "host").also(editor::remove)
            brokerField(index, "port").also(editor::remove)
            brokerField(index, "tls").also(editor::remove)
            brokerField(index, "username").also(editor::remove)
            brokerField(index, "password").also(editor::remove)
            brokerField(index, "audio_packet_interval_units").also(editor::remove)
        }
        editor.putInt(KEY_SAVED_BROKER_COUNT, settings.savedBrokers.size)
        settings.savedBrokers.forEachIndexed { index, broker ->
            editor.putString(brokerField(index, "name"), broker.name)
            editor.putString(brokerField(index, "host"), broker.host)
            editor.putInt(brokerField(index, "port"), broker.port)
            editor.putBoolean(brokerField(index, "tls"), broker.tls)
            editor.putString(brokerField(index, "username"), broker.username)
            editor.putString(brokerField(index, "password"), broker.password)
            editor.putInt(brokerField(index, "audio_packet_interval_units"), broker.audioPacketIntervalUnits)
        }
        val previousChannelCount = preferences.getInt(KEY_SAVED_CHANNEL_COUNT, 0)
        repeat(maxOf(previousChannelCount, settings.savedChannels.size)) { index ->
            editor.remove("$KEY_SAVED_CHANNEL_PREFIX$index")
        }
        editor.putInt(KEY_SAVED_CHANNEL_COUNT, settings.savedChannels.size)
        settings.savedChannels.forEachIndexed { index, channel ->
            editor.putString("$KEY_SAVED_CHANNEL_PREFIX$index", channel)
        }
        editor.apply()
    }

    override fun setKeepConnected(keepConnected: Boolean) {
        preferences.edit().putBoolean(KEY_KEEP_CONNECTED, keepConnected).apply()
    }

    override fun markMicrophonePermissionPrompted() {
        preferences.edit().putBoolean(KEY_MICROPHONE_PERMISSION_PROMPTED, true).apply()
    }

    private fun loadSavedBrokers(): List<BrokerProfile> {
        val count = preferences.getInt(KEY_SAVED_BROKER_COUNT, 0).coerceIn(0, MAX_SAVED_BROKERS)
        return (0 until count).mapNotNull { index ->
            val host = preferences.getString(brokerField(index, "host"), null)?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            BrokerProfile(
                name = preferences.getString(brokerField(index, "name"), host) ?: host,
                host = host,
                port = preferences.getInt(brokerField(index, "port"), 1883),
                tls = preferences.getBoolean(brokerField(index, "tls"), false),
                username = preferences.getString(brokerField(index, "username"), null),
                password = preferences.getString(brokerField(index, "password"), null),
                audioPacketIntervalUnits = preferences.getInt(
                    brokerField(index, "audio_packet_interval_units"),
                    defaultAudioPacketIntervalUnits(host),
                ),
            )
        }
    }

    private fun loadSavedChannels(): List<String> {
        val count = preferences.getInt(KEY_SAVED_CHANNEL_COUNT, 0).coerceIn(0, MAX_SAVED_CHANNELS)
        val saved = (0 until count).mapNotNull { index ->
            preferences.getString("$KEY_SAVED_CHANNEL_PREFIX$index", null)
                ?.takeIf { com.far.mqttcall.domain.channelTopic(it).isSuccess }
        }
        return (listOf(AppDefaults.defaultChannel) + saved).distinct()
    }

    private fun brokerField(index: Int, field: String): String = "$KEY_SAVED_BROKER_PREFIX$index$FIELD_SEPARATOR$field"

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
        const val KEY_AUDIO_PACKET_INTERVAL_UNITS = "audio_packet_interval_units"
        const val KEY_CHANNEL = "channel"
        const val KEY_ENCRYPTED_KEY = "encrypted_key"
        const val KEY_ACTIVE_KEY_INDEX = "active_key_index"
        const val KEY_KEEP_CONNECTED = "keep_connected"
        const val KEY_MICROPHONE_PERMISSION_PROMPTED = "microphone_permission_prompted"
        const val KEY_ENCRYPTED_KEY_SLOT_PREFIX = "encrypted_key_slot_"
        const val KEY_SAVED_BROKER_COUNT = "saved_broker_count"
        const val KEY_SAVED_BROKER_PREFIX = "saved_broker_"
        const val FIELD_SEPARATOR = "_"
        const val MAX_SAVED_BROKERS = 20
        const val KEY_SAVED_CHANNEL_COUNT = "saved_channel_count"
        const val KEY_SAVED_CHANNEL_PREFIX = "saved_channel_"
        const val MAX_SAVED_CHANNELS = 100
    }

    private fun encryptedKeySlot(index: Int): String = KEY_ENCRYPTED_KEY_SLOT_PREFIX + index
}

const val KEY_SLOT_COUNT = 3

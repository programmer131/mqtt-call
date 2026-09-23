package com.far.mqttcall.domain

data class BrokerProfile(
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String? = null,
    val password: String? = null,
    /** Number of 100 ms units in each outgoing audio packet. */
    val audioPacketIntervalUnits: Int = 4,
) {
    init {
        require(audioPacketIntervalUnits in 1..10) {
            "Audio packet interval must be between 1 and 10 (100ms to 1s)"
        }
    }
}

fun defaultAudioPacketIntervalUnits(host: String): Int {
    val value = host.trim().lowercase()
    val lan = value == "localhost" ||
        value == "::1" ||
        value.startsWith("10.") ||
        value.startsWith("192.168.") ||
        (value.startsWith("172.") && value.substringAfter("172.").substringBefore('.').toIntOrNull() in 16..31)
    return if (lan) 1 else 4
}

fun defaultBrokerProfiles(): List<BrokerProfile> = listOf(
    BrokerProfile(
        name = "EMQX public",
        host = "broker.emqx.io",
        port = 1883,
        tls = false,
    ),
    BrokerProfile(
        name = "EMQX public TLS",
        host = "broker.emqx.io",
        port = 8883,
        tls = true,
    ),
    BrokerProfile(
        name = "Mosquitto public",
        host = "test.mosquitto.org",
        port = 1883,
        tls = false,
    ),
    BrokerProfile(
        name = "Mosquitto public TLS",
        host = "test.mosquitto.org",
        port = 8883,
        tls = true,
    ),
    BrokerProfile(
        name = "DietPi LAN",
        host = "192.168.1.107",
        port = 1886,
        tls = false,
        audioPacketIntervalUnits = 1,
    ),
)

package com.far.mqttcall.domain

data class BrokerProfile(
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String? = null,
    val password: String? = null,
    /** Number of 100ms units in each outgoing audio packet. */
    val audioPacketIntervalUnits: Int = 4,

) {
    init {
        require(audioPacketIntervalUnits in 1..10) {
            "Audio packet interval must be between 1 and 10 (100ms to 1s)"
        }
    }
}

fun defaultBrokerProfiles(): List<BrokerProfile> = listOf(
    BrokerProfile(
        name = "EMQX public",
        host = "broker.emqx.io",
        port = 1883,
        tls = false,
        audioPacketIntervalUnits = 4,
    ),
    BrokerProfile(
        name = "EMQX public TLS",
        host = "broker.emqx.io",
        port = 8883,
        tls = true,
        audioPacketIntervalUnits = 4,
    ),
    BrokerProfile(
        name = "Mosquitto public",
        host = "test.mosquitto.org",
        port = 1883,
        tls = false,
        audioPacketIntervalUnits = 4,
    ),
    BrokerProfile(
        name = "Mosquitto public TLS",
        host = "test.mosquitto.org",
        port = 8883,
        tls = true,
        audioPacketIntervalUnits = 4,
    ),
    BrokerProfile(
        name = "lan2",
        host = "192.168.16.153",
        port = 1883,
        tls = false,
        audioPacketIntervalUnits = 1,
    ),
)

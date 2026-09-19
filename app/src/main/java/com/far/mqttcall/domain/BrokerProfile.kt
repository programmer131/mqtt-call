package com.far.mqttcall.domain

data class BrokerProfile(
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String? = null,
    val password: String? = null,
)

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
)

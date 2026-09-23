package com.far.mqttcall.domain

object AppDefaults {
    val defaultBroker: BrokerProfile = defaultBrokerProfiles().first()
    const val defaultChannel: String = "3344"
    const val defaultKey: String = "PTT-DEMO-3344"
    val defaultKeySlots: List<String> = listOf(defaultKey, "", "")
}

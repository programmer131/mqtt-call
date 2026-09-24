package com.far.mqttcall.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A bounded TCP port scan of the current Wi-Fi IPv4 segment. */
fun subnetHosts(address: String, prefixLength: Int): List<String> {
    val parts = address.split('.').mapNotNull { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 } }
    if (parts.size != 4 || prefixLength !in 1..30) return emptyList()
    // Cap broad routes to the device's /24 so a prototype scan finishes promptly.
    val prefix = maxOf(prefixLength, 24)
    val ip = parts.fold(0L) { result, part -> (result shl 8) or part.toLong() }
    val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
    val network = ip and mask
    val broadcast = network or (mask.inv() and 0xffffffffL)
    return (network + 1 until broadcast).filter { it != ip }.map { value ->
        (24 downTo 0 step 8).joinToString(".") { shift -> ((value shr shift) and 255).toString() }
    }
}

suspend fun probeTcpPort(host: String, port: Int = 1883, timeoutMs: Int = 200): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        }.getOrDefault(false)
    }

suspend fun scanLan(context: Context): Result<List<String>> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager.activeNetwork
        ?: return Result.failure(IllegalStateException("No active Wi-Fi network"))
    if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
        return Result.failure(IllegalStateException("Connect to Wi-Fi to scan LAN"))
    }
    val link = manager.getLinkProperties(network)?.linkAddresses
        ?.firstOrNull { it.address is Inet4Address }
        ?: return Result.failure(IllegalStateException("No Wi-Fi IPv4 address"))
    val hosts = subnetHosts(link.address.hostAddress ?: "", link.prefixLength)
    if (hosts.isEmpty()) return Result.failure(IllegalStateException("No scannable Wi-Fi subnet"))
    val found = withTimeoutOrNull(6_000) {
        coroutineScope {
            val permits = Semaphore(24)
            hosts.map { host ->
                async { if (permits.withPermit { probeTcpPort(host) }) host else null }
            }.awaitAll().filterNotNull()
        }
    } ?: emptyList()
    return Result.success(found)
}

package com.localmodel.gateway

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {

    fun lanIp(): String? = runCatching {
        val addresses = NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .filter { it.isNotBlank() }

        addresses.firstOrNull { it.startsWith("192.168.") }
            ?: addresses.firstOrNull { it.startsWith("10.") || it.startsWith("172.") }
            ?: addresses.firstOrNull()
    }.getOrNull()

    fun lanBaseUrl(port: Int): String = "http://${lanIp() ?: "192.168.1.1"}:$port"

    fun loopbackBaseUrl(port: Int): String = "http://127.0.0.1:$port"
}

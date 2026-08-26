package com.airmate.android.network

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object PairingCode {
    data class Target(val host: String, val port: Int)

    fun parse(rawValue: String): Target? = runCatching {
        val uri = URI(rawValue)
        if (uri.scheme != "airmate" || uri.host != "pair") return null
        val parameters = uri.rawQuery.orEmpty().split('&').mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = URLDecoder.decode(entry.substring(0, separator), StandardCharsets.UTF_8.name())
            val value = URLDecoder.decode(entry.substring(separator + 1), StandardCharsets.UTF_8.name())
            key to value
        }.toMap()
        val host = parameters["host"]?.takeIf { it.isNotBlank() } ?: return null
        val port = parameters["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        Target(host, port)
    }.getOrNull()
}

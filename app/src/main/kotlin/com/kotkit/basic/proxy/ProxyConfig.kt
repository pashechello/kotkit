package com.kotkit.basic.proxy

import com.kotkit.basic.data.remote.api.models.ProxyConfigDto

/**
 * Domain model for proxy configuration.
 * Converted from ProxyConfigDto (API DTO) before use.
 */
data class ProxyConfig(
    val taskId: String,
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val protocol: String,
    val sessionId: String
) {
    /**
     * Produces socks5://user:pass@host:port (or http://) for tun2socks.
     */
    fun toProxyUrl(): String {
        val auth = if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            "$username:$password@"
        } else ""
        return "$protocol://${auth}${host}:$port"
    }

    /** Safe for logging — credentials replaced with ***. */
    fun toMaskedUrl(): String {
        val auth = if (!username.isNullOrBlank()) "***:***@" else ""
        return "$protocol://${auth}${host}:$port"
    }
}

fun ProxyConfigDto.toDomain(taskId: String) = ProxyConfig(
    taskId = taskId,
    host = host,
    port = port,
    username = username,
    password = password,
    protocol = protocol,
    sessionId = sessionId
)

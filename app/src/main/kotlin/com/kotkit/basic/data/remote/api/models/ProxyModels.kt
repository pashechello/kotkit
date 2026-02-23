package com.kotkit.basic.data.remote.api.models

import com.google.gson.annotations.SerializedName

/**
 * Proxy API Models — Phase 2 VPN Module
 *
 * Corresponds to backend endpoints in:
 *   tiktok-agent/api/routes/proxy.py
 *   tiktok-agent/api/schemas/proxy.py
 */

// ============================================================================
// Proxy Config — GET /api/v1/proxy/config/{task_id}
// ============================================================================

data class ProxyConfigResponse(
    @SerializedName("proxy") val proxy: ProxyConfigDto?,
    @SerializedName("required") val required: Boolean = true
)

data class ProxyConfigDto(
    @SerializedName("host")        val host: String,
    @SerializedName("port")        val port: Int,
    @SerializedName("username")    val username: String?,
    @SerializedName("password")    val password: String?,
    @SerializedName("protocol")    val protocol: String = "socks5",
    @SerializedName("session_id")  val sessionId: String
)

// ============================================================================
// Proxy Session Events — POST /api/v1/proxy/sessions/{id}/connect|disconnect
// ============================================================================

data class ProxyConnectRequest(
    @SerializedName("task_id")    val taskId: String,
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("exit_ip")    val exitIp: String?
)

/** Separate model for disconnect events — semantically distinct from connect. */
data class ProxyDisconnectRequest(
    @SerializedName("task_id")    val taskId: String,
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("exit_ip")    val exitIp: String?
)

data class ProxyEventResponse(
    @SerializedName("status") val status: String
)

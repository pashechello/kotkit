package com.kotkit.basic.proxy

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================================
// State
// ============================================================================

sealed class ProxyState {
    object Idle          : ProxyState()
    object Connecting    : ProxyState()
    data class Connected(val exitIp: String?, val sessionId: String) : ProxyState()
    data class Failed(val reason: String) : ProxyState()
    object Disconnecting : ProxyState()
}

// ============================================================================
// Manager
// ============================================================================

/**
 * Manages the VPN proxy lifecycle for Network Mode task execution.
 *
 * Integrates [KotKitVpnService] (Android VpnService) and [IpVerifier] to:
 * - Bring up a SOCKS5 TUN tunnel before posting to TikTok
 * - Verify the exit IP matches the proxy
 * - Tear down the tunnel after posting (or on failure)
 *
 * Injected into [com.kotkit.basic.network.NetworkTaskExecutor] as a singleton.
 */
@Singleton
class ProxyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ipVerifier: IpVerifier
) {
    companion object {
        private const val TAG = "ProxyManager"
        private const val CONNECT_TIMEOUT_MS  = 15_000L
        private const val POLL_INTERVAL_MS    = 200L
        private const val IP_VERIFY_TIMEOUT_MS = 10_000L
    }

    private val _state = MutableStateFlow<ProxyState>(ProxyState.Idle)
    val state: StateFlow<ProxyState> = _state

    /**
     * Bring up the SOCKS5 VPN tunnel for the given config.
     *
     * @return Exit IP string (may be null if ipify unreachable — non-fatal)
     * @throws Exception if the tunnel fails to come up within [CONNECT_TIMEOUT_MS]
     *
     * Must be called AFTER [VpnConsentActivity] consent has been obtained.
     */
    suspend fun connect(config: ProxyConfig): String? {
        Timber.tag(TAG).i("Connecting proxy: ${config.host}:${config.port} session=${config.sessionId}")
        _state.value = ProxyState.Connecting

        return try {
            KotKitVpnService.start(context, config)

            // Poll until TUN is up or timeout
            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (KotKitVpnService.isTunnelUp()) break
                delay(POLL_INTERVAL_MS)
            }

            if (!KotKitVpnService.isTunnelUp()) {
                throw IllegalStateException("VPN tunnel did not come up within ${CONNECT_TIMEOUT_MS}ms")
            }

            // Verify exit IP — non-fatal (VPN proceeds even if ipify unreachable)
            val exitIp = try {
                withTimeout(IP_VERIFY_TIMEOUT_MS) {
                    ipVerifier.getExitIp()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w("Exit IP verification failed (non-fatal): ${e.message}")
                null
            }

            Timber.tag(TAG).i("Proxy connected. exitIp=${exitIp ?: "unknown"}")
            _state.value = ProxyState.Connected(exitIp, config.sessionId)
            exitIp

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Proxy connection failed")
            _state.value = ProxyState.Failed(e.message ?: "Unknown error")
            // Best-effort teardown before re-throwing
            try { KotKitVpnService.stop(context) } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Tear down the VPN tunnel. Idempotent — safe to call multiple times.
     */
    suspend fun disconnect() {
        if (_state.value is ProxyState.Idle) return
        Timber.tag(TAG).i("Disconnecting proxy")
        _state.value = ProxyState.Disconnecting
        try {
            KotKitVpnService.stop(context)
        } catch (e: Exception) {
            Timber.tag(TAG).w("Error during proxy disconnect: ${e.message}")
        } finally {
            _state.value = ProxyState.Idle
        }
    }
}

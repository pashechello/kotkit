package com.autoposter.adb.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * MdnsDiscovery - Discovers ADB Wireless Debugging services via mDNS.
 *
 * Android Wireless Debugging advertises two services:
 * - Pairing service: _adb-tls-pairing._tcp (for initial pairing with 6-digit code)
 * - Connect service: _adb-tls-connect._tcp (for connecting after pairing)
 *
 * Flow:
 * 1. User opens Settings > Developer Options > Wireless Debugging
 * 2. User taps "Pair device with pairing code"
 * 3. Device starts advertising pairing service
 * 4. This class discovers the pairing port
 * 5. App uses SPAKE2 to pair with the code
 * 6. Device then advertises connect service
 * 7. App connects to the connect port for ADB
 */
@Singleton
class MdnsDiscovery @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MdnsDiscovery"

        // Service types for ADB Wireless Debugging
        const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp."
        const val SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp."

        // Discovery timeout
        const val DISCOVERY_TIMEOUT_MS = 30_000L
    }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * Discovered service info
     */
    data class AdbService(
        val name: String,
        val host: String,
        val port: Int,
        val type: ServiceType
    )

    enum class ServiceType {
        PAIRING,
        CONNECT
    }

    /**
     * Discover pairing service (when user opens pairing dialog).
     * Returns the pairing port, or null if not found within timeout.
     */
    suspend fun discoverPairingService(): AdbService? {
        return discoverService(SERVICE_TYPE_PAIRING, ServiceType.PAIRING)
    }

    /**
     * Discover connect service (after pairing is complete).
     * Returns the connect port, or null if not found within timeout.
     */
    suspend fun discoverConnectService(): AdbService? {
        return discoverService(SERVICE_TYPE_CONNECT, ServiceType.CONNECT)
    }

    /**
     * Generic service discovery.
     */
    private suspend fun discoverService(serviceType: String, type: ServiceType): AdbService? {
        return withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val serviceChannel = Channel<NsdServiceInfo>(Channel.CONFLATED)

                val listener = object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        Log.e(TAG, "Discovery start failed: $errorCode")
                        continuation.resume(null)
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        Log.e(TAG, "Discovery stop failed: $errorCode")
                    }

                    override fun onDiscoveryStarted(serviceType: String) {
                        Log.i(TAG, "Discovery started for: $serviceType")
                    }

                    override fun onDiscoveryStopped(serviceType: String) {
                        Log.i(TAG, "Discovery stopped for: $serviceType")
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        Log.i(TAG, "Service found: ${serviceInfo.serviceName}")
                        // Resolve the service to get host and port
                        resolveService(serviceInfo) { resolved ->
                            if (resolved != null) {
                                val service = AdbService(
                                    name = resolved.serviceName,
                                    host = resolved.host?.hostAddress ?: "127.0.0.1",
                                    port = resolved.port,
                                    type = type
                                )
                                continuation.resume(service)
                            }
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        Log.i(TAG, "Service lost: ${serviceInfo.serviceName}")
                    }
                }

                discoveryListener = listener

                try {
                    nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start discovery", e)
                    continuation.resume(null)
                }

                continuation.invokeOnCancellation {
                    stopDiscovery()
                }
            }
        }.also {
            stopDiscovery()
        }
    }

    /**
     * Resolve service to get actual host and port.
     */
    private fun resolveService(serviceInfo: NsdServiceInfo, callback: (NsdServiceInfo?) -> Unit) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode")
                callback(null)
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                Log.i(TAG, "Resolved: ${resolved.serviceName} at ${resolved.host}:${resolved.port}")
                callback(resolved)
            }
        }

        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve service", e)
            callback(null)
        }
    }

    /**
     * Stop ongoing discovery.
     */
    fun stopDiscovery() {
        discoveryListener?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                // Already stopped or not started
            }
            discoveryListener = null
        }
    }
}

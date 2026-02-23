package com.kotkit.basic.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks the public exit IP via ipify.org after the VPN tunnel is up.
 *
 * Uses a bare OkHttpClient with no interceptors — same pattern as VideoDownloader.downloadClient.
 * Must be called AFTER the TUN interface is up so the traffic exits via the proxy.
 */
@Singleton
class IpVerifier @Inject constructor() {

    companion object {
        private const val TAG = "IpVerifier"
        private const val IP_CHECK_URL = "https://api4.ipify.org?format=text"
    }

    // Bare client — no auth/correlation headers that could interfere
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Returns the current public exit IP as a string, or null on failure.
     * Non-fatal: VPN usage proceeds even if this check fails (e.g. ipify unreachable).
     */
    suspend fun getExitIp(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(IP_CHECK_URL).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()?.trim()?.also { ip ->
                        Timber.tag(TAG).i("Exit IP verified: $ip")
                    }
                } else {
                    Timber.tag(TAG).w("ipify returned ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("IP verification failed (non-fatal): ${e.message}")
            null
        }
    }
}

package com.kotkit.basic.data.remote.api

import com.kotkit.basic.security.IntegrityChecker
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber

/**
 * Interceptor that adds X-App-Signature header to all API requests.
 *
 * Sends the SHA-256 hash of the APK signing certificate so the backend
 * can verify the request comes from an official build signed with our key.
 * The signing key is private and not in the repository.
 */
class AppSignatureInterceptor(
    private val integrityChecker: IntegrityChecker
) : Interceptor {

    companion object {
        private const val TAG = "AppSignature"
        private const val HEADER_APP_SIGNATURE = "X-App-Signature"
    }

    @Volatile
    private var cachedSignature: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val signature = getSignature()

        val request = if (signature != null) {
            chain.request().newBuilder()
                .header(HEADER_APP_SIGNATURE, signature)
                .build()
        } else {
            chain.request()
        }

        return chain.proceed(request)
    }

    private fun getSignature(): String? {
        cachedSignature?.let { return it }

        return try {
            integrityChecker.getSignatureHash()?.also {
                cachedSignature = it
                Timber.tag(TAG).i("App signature cached: ${it.take(12)}...")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to get app signature")
            null
        }
    }
}

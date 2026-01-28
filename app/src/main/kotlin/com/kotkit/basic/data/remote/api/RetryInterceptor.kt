package com.kotkit.basic.data.remote.api

import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RetryInterceptor(
    private val maxRetries: Int = 3,
    private val initialDelayMs: Long = 1000,
    private val maxDelayMs: Long = 10000,
    private val backoffMultiplier: Double = 2.0
) : Interceptor {

    companion object {
        private const val TAG = "RetryInterceptor"
        private val RETRYABLE_CODES = setOf(
            408, // Request Timeout
            429, // Too Many Requests
            500, // Internal Server Error
            502, // Bad Gateway
            503, // Service Unavailable
            504  // Gateway Timeout
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var lastException: IOException? = null
        var currentDelay = initialDelayMs

        Timber.tag(TAG).d("Request: ${request.method} ${request.url}")

        repeat(maxRetries) { attempt ->
            try {
                if (attempt > 0) {
                    Timber.tag(TAG).i("Retry attempt ${attempt + 1}/$maxRetries for ${request.url}")
                }

                val response = chain.proceed(request)

                // Success or non-retryable client error
                if (response.isSuccessful || !shouldRetry(response.code)) {
                    Timber.tag(TAG).d("Response: ${response.code} for ${request.url}")
                    return response
                }

                Timber.tag(TAG).w("Retryable response code ${response.code} for ${request.url}")

                // Close body before retry
                response.close()

                // Wait before next attempt
                if (attempt < maxRetries - 1) {
                    Thread.sleep(currentDelay)
                    currentDelay = (currentDelay * backoffMultiplier).toLong()
                        .coerceAtMost(maxDelayMs)
                }

            } catch (e: SocketTimeoutException) {
                Timber.tag(TAG).e(e, "SocketTimeout for ${request.url} (attempt ${attempt + 1})")
                lastException = e
                if (attempt < maxRetries - 1) {
                    Thread.sleep(currentDelay)
                    currentDelay = (currentDelay * backoffMultiplier).toLong()
                        .coerceAtMost(maxDelayMs)
                }
            } catch (e: UnknownHostException) {
                // No network - wait longer
                Timber.tag(TAG).e(e, "UnknownHost for ${request.url} (attempt ${attempt + 1})")
                lastException = e
                if (attempt < maxRetries - 1) {
                    Thread.sleep(currentDelay * 2)
                    currentDelay = (currentDelay * backoffMultiplier).toLong()
                        .coerceAtMost(maxDelayMs)
                }
            } catch (e: IOException) {
                Timber.tag(TAG).e(e, "IOException for ${request.url} (attempt ${attempt + 1})")
                lastException = e
                if (attempt < maxRetries - 1) {
                    Thread.sleep(currentDelay)
                    currentDelay = (currentDelay * backoffMultiplier).toLong()
                        .coerceAtMost(maxDelayMs)
                }
            }
        }

        Timber.tag(TAG).e(lastException, "Request FAILED after $maxRetries attempts: ${request.url}")
        throw lastException ?: IOException("Request failed after $maxRetries attempts")
    }

    private fun shouldRetry(code: Int): Boolean {
        return code in RETRYABLE_CODES
    }
}

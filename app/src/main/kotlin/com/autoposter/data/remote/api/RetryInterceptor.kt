package com.autoposter.data.remote.api

import okhttp3.Interceptor
import okhttp3.Response
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

        repeat(maxRetries) { attempt ->
            try {
                val response = chain.proceed(request)

                // Success or non-retryable client error
                if (response.isSuccessful || !shouldRetry(response.code)) {
                    return response
                }

                // Close body before retry
                response.close()

                // Wait before next attempt
                if (attempt < maxRetries - 1) {
                    Thread.sleep(currentDelay)
                    currentDelay = (currentDelay * backoffMultiplier).toLong()
                        .coerceAtMost(maxDelayMs)
                }

            } catch (e: SocketTimeoutException) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    Thread.sleep(currentDelay)
                    currentDelay = (currentDelay * backoffMultiplier).toLong()
                        .coerceAtMost(maxDelayMs)
                }
            } catch (e: UnknownHostException) {
                // No network - wait longer
                lastException = e
                if (attempt < maxRetries - 1) {
                    Thread.sleep(currentDelay * 2)
                    currentDelay = (currentDelay * backoffMultiplier).toLong()
                        .coerceAtMost(maxDelayMs)
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    Thread.sleep(currentDelay)
                    currentDelay = (currentDelay * backoffMultiplier).toLong()
                        .coerceAtMost(maxDelayMs)
                }
            }
        }

        throw lastException ?: IOException("Request failed after $maxRetries attempts")
    }

    private fun shouldRetry(code: Int): Boolean {
        return code in RETRYABLE_CODES
    }
}

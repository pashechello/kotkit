package com.kotkit.basic.data.remote.api

import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.util.UUID

/**
 * Interceptor that adds X-Correlation-Id header to all requests.
 *
 * The correlation ID allows tracing requests across:
 * - Android app (local logs)
 * - Backend API (structlog)
 * - Error reports (ErrorReporter)
 *
 * A new session ID is generated on app launch and reused for all requests
 * in that session. This allows grouping all requests from a single session.
 */
class CorrelationIdInterceptor : Interceptor {

    companion object {
        private const val TAG = "CorrelationId"
        private const val HEADER_CORRELATION_ID = "X-Correlation-Id"

        // Session-level correlation ID - generated once per app session
        @Volatile
        private var sessionCorrelationId: String? = null
        private val lock = Any()

        /**
         * Get current session correlation ID.
         * Thread-safe with double-checked locking.
         * Useful for including in error reports and local logs.
         */
        fun getSessionCorrelationId(): String {
            // Fast path - already initialized
            sessionCorrelationId?.let { return it }

            // Slow path - need to initialize with synchronization
            synchronized(lock) {
                // Double-check after acquiring lock
                sessionCorrelationId?.let { return it }
                return generateNewSessionId()
            }
        }

        /**
         * Reset session ID (e.g., on logout or app restart)
         */
        fun resetSessionId() {
            synchronized(lock) {
                sessionCorrelationId = null
            }
            Timber.tag(TAG).d("Session correlation ID reset")
        }

        private fun generateNewSessionId(): String {
            return UUID.randomUUID().toString().also {
                sessionCorrelationId = it
                Timber.tag(TAG).i("New session correlation ID: %s", it)
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val correlationId = getSessionCorrelationId()

        val request = chain.request().newBuilder()
            .header(HEADER_CORRELATION_ID, correlationId)
            .build()

        val response = chain.proceed(request)

        // Log response correlation ID (backend may return a different one for sub-requests)
        val responseCorrelationId = response.header(HEADER_CORRELATION_ID)
        if (responseCorrelationId != null && responseCorrelationId != correlationId) {
            Timber.tag(TAG).d(
                "Request: %s, Response correlation: %s",
                correlationId,
                responseCorrelationId
            )
        }

        return response
    }
}

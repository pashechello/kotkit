package com.kotkit.basic.data.repository

import timber.log.Timber
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.AnalyticsEvent
import com.kotkit.basic.data.remote.api.models.EventType
import com.kotkit.basic.security.DeviceFingerprint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for tracking analytics events to backend.
 * Fire-and-forget pattern - failures are logged but don't block main flow.
 */
@Singleton
class AnalyticsRepository @Inject constructor(
    private val apiService: ApiService,
    private val deviceFingerprint: DeviceFingerprint
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "AnalyticsRepository"
    }

    /**
     * Track a post started event.
     */
    fun trackPostStarted(postId: Long, sessionId: String) {
        trackEvent(
            eventType = EventType.POST_STARTED,
            postId = postId,
            sessionId = sessionId
        )
    }

    /**
     * Track a successful post completion.
     */
    fun trackPostCompleted(postId: Long, sessionId: String, durationMs: Long) {
        trackEvent(
            eventType = EventType.POST_COMPLETED,
            postId = postId,
            sessionId = sessionId,
            durationMs = durationMs
        )
    }

    /**
     * Track a failed post.
     */
    fun trackPostFailed(
        postId: Long,
        sessionId: String,
        errorMessage: String,
        durationMs: Long? = null
    ) {
        trackEvent(
            eventType = EventType.POST_FAILED,
            postId = postId,
            sessionId = sessionId,
            errorMessage = errorMessage,
            durationMs = durationMs
        )
    }

    /**
     * Track a retry attempt.
     */
    fun trackPostRetrying(postId: Long, sessionId: String, attemptNumber: Int) {
        trackEvent(
            eventType = EventType.POST_RETRYING,
            postId = postId,
            sessionId = sessionId,
            data = mapOf("attempt" to attemptNumber)
        )
    }

    /**
     * Generic event tracking - fire and forget.
     */
    private fun trackEvent(
        eventType: String,
        postId: Long? = null,
        sessionId: String? = null,
        data: Map<String, Any>? = null,
        errorMessage: String? = null,
        durationMs: Long? = null
    ) {
        scope.launch {
            try {
                val event = AnalyticsEvent(
                    eventType = eventType,
                    postId = postId,
                    sessionId = sessionId,
                    deviceId = deviceFingerprint.getDeviceId(),
                    data = data,
                    errorMessage = errorMessage,
                    durationMs = durationMs?.toInt()
                )
                val response = apiService.trackEvent(event)
                if (response.success) {
                    Timber.tag(TAG).d("Event tracked: $eventType for post $postId")
                } else {
                    Timber.tag(TAG).w("Event tracking returned success=false: $eventType")
                }
            } catch (e: Exception) {
                // Don't fail the main flow - just log
                Timber.tag(TAG).w(e, "Failed to track event: $eventType")
            }
        }
    }
}

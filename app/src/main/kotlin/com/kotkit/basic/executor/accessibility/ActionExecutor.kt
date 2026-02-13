package com.kotkit.basic.executor.accessibility

import android.content.Context
import android.content.Intent
import com.kotkit.basic.data.remote.api.models.ActionType
import timber.log.Timber
import com.kotkit.basic.data.remote.api.models.AnalyzeResponse
import com.kotkit.basic.executor.humanizer.BasicHumanizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ActionExecutor"
    }

    private val humanizer = BasicHumanizer()

    /**
     * Execute action with optional cancellation check.
     * @param action The action to execute
     * @param isCancelled Function to check if operation was cancelled (optional)
     */
    suspend fun execute(
        action: AnalyzeResponse,
        isCancelled: (() -> Boolean)? = null
    ): ExecutionResult {
        val service = TikTokAccessibilityService.getInstance()
            ?: return ExecutionResult.Error("Accessibility service not available", recoverable = true)

        // Check cancellation before starting
        if (isCancelled?.invoke() == true) {
            return ExecutionResult.Cancelled
        }

        // Add human-like pre-action delay (cancellable)
        val preDelay = humanizer.generatePreActionDelay()
        if (!cancellableDelay(preDelay, isCancelled)) {
            return ExecutionResult.Cancelled
        }

        val result = when (action.action) {
            ActionType.TAP -> executeTap(service, action)
            ActionType.SWIPE -> executeSwipe(service, action)
            ActionType.TYPE_TEXT, ActionType.TYPE -> executeType(service, action)
            ActionType.WAIT -> executeWait(action, isCancelled)
            ActionType.BACK -> executeBack(service, action)
            // NOTE: LAUNCH_TIKTOK removed - Share Intent is the only supported flow
            ActionType.DISMISS_POPUP -> executeDismissPopup(service, action)
            ActionType.NAVIGATE_TO_FEED -> executeNavigateToFeed(service)
            ActionType.FINISH, ActionType.DONE -> ExecutionResult.Done(action.message)
            ActionType.ERROR -> ExecutionResult.Error(action.message, action.recoverable ?: false)
            else -> ExecutionResult.Error("Unknown action: ${action.action}", recoverable = false)
        }

        // Check cancellation after action
        if (isCancelled?.invoke() == true) {
            return ExecutionResult.Cancelled
        }

        // Add human-like post-action delay (unless it's a terminal state)
        if (result !is ExecutionResult.Done && result !is ExecutionResult.Error && result !is ExecutionResult.Cancelled) {
            val postDelay = action.waitAfter?.toLong() ?: humanizer.generatePostActionDelay()
            if (!cancellableDelay(postDelay, isCancelled)) {
                return ExecutionResult.Cancelled
            }
        }

        return result
    }

    /**
     * Cancellable delay - checks isCancelled every 100ms.
     * @return true if completed, false if cancelled
     */
    private suspend fun cancellableDelay(
        durationMs: Long,
        isCancelled: (() -> Boolean)?
    ): Boolean {
        if (isCancelled == null) {
            delay(durationMs)
            return true
        }

        val iterations = (durationMs / 100).toInt()
        repeat(iterations) {
            if (isCancelled()) return false
            delay(100)
        }
        val remaining = durationMs % 100
        if (remaining > 0) delay(remaining)
        return !isCancelled()
    }

    private suspend fun executeTap(
        service: TikTokAccessibilityService,
        action: AnalyzeResponse
    ): ExecutionResult {
        val x = action.x ?: return ExecutionResult.Failed("Missing x coordinate")
        val y = action.y ?: return ExecutionResult.Failed("Missing y coordinate")

        // Apply humanization with element size for adaptive jitter
        val humanized = humanizer.humanizeTap(
            targetX = x,
            targetY = y,
            elementWidth = action.elementWidth,
            elementHeight = action.elementHeight
        )

        Timber.tag(TAG).d("Tap: target=($x, $y) -> humanized=(${humanized.x}, ${humanized.y}), duration=${humanized.durationMs}ms")

        val success = service.tap(humanized.x, humanized.y, humanized.durationMs)

        return if (success) {
            // Check if VLM flagged this as the publish button tap
            if (action.isPublishAction) {
                Timber.tag(TAG).i("Publish button tapped (isPublishAction=true) - marking for Feed verification")
                ExecutionResult.PublishTapped
            } else {
                ExecutionResult.Success
            }
        } else {
            ExecutionResult.Failed("Tap failed at (${humanized.x}, ${humanized.y})")
        }
    }

    private suspend fun executeSwipe(
        service: TikTokAccessibilityService,
        action: AnalyzeResponse
    ): ExecutionResult {
        val startX = action.startX ?: return ExecutionResult.Failed("Missing startX")
        val startY = action.startY ?: return ExecutionResult.Failed("Missing startY")
        val endX = action.endX ?: return ExecutionResult.Failed("Missing endX")
        val endY = action.endY ?: return ExecutionResult.Failed("Missing endY")
        val baseDuration = action.duration?.toLong() ?: 300L

        // Apply humanization to swipe
        val humanized = humanizer.humanizeSwipe(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            baseDuration = baseDuration
        )

        Timber.tag(TAG).d("Swipe: (${startX}, ${startY}) -> (${endX}, ${endY}) humanized to " +
                "(${humanized.startX}, ${humanized.startY}) -> (${humanized.endX}, ${humanized.endY}), " +
                "duration=${humanized.durationMs}ms")

        val success = service.swipe(
            humanized.startX,
            humanized.startY,
            humanized.endX,
            humanized.endY,
            humanized.durationMs
        )

        return if (success) {
            ExecutionResult.Success
        } else {
            ExecutionResult.Failed("Swipe failed")
        }
    }

    private suspend fun executeType(
        service: TikTokAccessibilityService,
        action: AnalyzeResponse
    ): ExecutionResult {
        val text = action.text ?: return ExecutionResult.Failed("Missing text to type")

        Timber.tag(TAG).d("Type: '$text'")

        val success = service.type(text)

        return if (success) ExecutionResult.Success else ExecutionResult.Failed("Type failed")
    }

    private suspend fun executeWait(
        action: AnalyzeResponse,
        isCancelled: (() -> Boolean)?
    ): ExecutionResult {
        val duration = action.duration?.toLong() ?: action.waitAfter?.toLong() ?: 1000L
        Timber.tag(TAG).d("Wait: ${duration}ms")
        return if (cancellableDelay(duration, isCancelled)) {
            ExecutionResult.Success
        } else {
            ExecutionResult.Cancelled
        }
    }

    private suspend fun executeBack(
        service: TikTokAccessibilityService,
        action: AnalyzeResponse
    ): ExecutionResult {
        Timber.tag(TAG).d("Back pressed")
        val success = service.pressBack()
        return if (success) ExecutionResult.Success else ExecutionResult.Failed("Back press failed")
    }

    // NOTE: executeLaunchTikTok() REMOVED - Share Intent is the only supported flow

    private suspend fun executeNavigateToFeed(
        service: TikTokAccessibilityService
    ): ExecutionResult {
        Timber.tag(TAG).d("Navigate to feed: pressing BACK up to 3 times")
        repeat(3) {
            service.pressBack()
            delay(500)
        }
        return ExecutionResult.Success
    }

    private suspend fun executeDismissPopup(
        service: TikTokAccessibilityService,
        action: AnalyzeResponse
    ): ExecutionResult {
        Timber.tag(TAG).d("Dismiss popup: ${action.reason}")

        // Try to find and click X/close button, or press back as fallback
        val clicked = service.clickByText("×", exactMatch = true) ||
                service.clickByText("X", exactMatch = true) ||
                service.clickByText("Close", exactMatch = false) ||
                service.clickByText("Закрыть", exactMatch = false) ||
                service.clickByText("Cancel", exactMatch = false) ||
                service.clickByText("Отмена", exactMatch = false)

        if (clicked) {
            return ExecutionResult.Success
        }

        // Fallback to back button
        val backPressed = service.pressBack()
        return if (backPressed) ExecutionResult.Success else ExecutionResult.Failed("Failed to dismiss popup")
    }
}

sealed class ExecutionResult {
    object Success : ExecutionResult()
    object PublishTapped : ExecutionResult()  // Publish button was tapped, need Feed verification
    object Cancelled : ExecutionResult()  // User cancelled the operation
    data class Done(val message: String?) : ExecutionResult()
    data class Failed(val reason: String) : ExecutionResult()
    data class Error(val message: String?, val recoverable: Boolean) : ExecutionResult()
}

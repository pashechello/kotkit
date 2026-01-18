package com.autoposter.executor.accessibility

import android.content.Context
import android.content.Intent
import android.util.Log
import com.autoposter.data.remote.api.models.ActionType
import com.autoposter.data.remote.api.models.AnalyzeResponse
import com.autoposter.executor.humanizer.BasicHumanizer
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

    suspend fun execute(action: AnalyzeResponse): ExecutionResult {
        val service = TikTokAccessibilityService.getInstance()
            ?: return ExecutionResult.Error("Accessibility service not available", recoverable = true)

        // Add human-like pre-action delay
        val preDelay = humanizer.generatePreActionDelay()
        delay(preDelay)

        val result = when (action.action) {
            ActionType.TAP -> executeTap(service, action)
            ActionType.SWIPE -> executeSwipe(service, action)
            ActionType.TYPE_TEXT, ActionType.TYPE -> executeType(service, action)
            ActionType.WAIT -> executeWait(action)
            ActionType.BACK -> executeBack(service, action)
            ActionType.LAUNCH_TIKTOK, ActionType.OPEN_APP -> executeLaunchTikTok(action)
            ActionType.DISMISS_POPUP -> executeDismissPopup(service, action)
            ActionType.FINISH, ActionType.DONE -> ExecutionResult.Done(action.message)
            ActionType.ERROR -> ExecutionResult.Error(action.message, action.recoverable ?: false)
            else -> ExecutionResult.Error("Unknown action: ${action.action}", recoverable = false)
        }

        // Add human-like post-action delay (unless it's a terminal state)
        if (result !is ExecutionResult.Done && result !is ExecutionResult.Error) {
            val postDelay = action.waitAfter?.toLong() ?: humanizer.generatePostActionDelay()
            delay(postDelay)
        }

        return result
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

        Log.d(TAG, "Tap: target=($x, $y) -> humanized=(${humanized.x}, ${humanized.y}), duration=${humanized.durationMs}ms")

        val success = service.tap(humanized.x, humanized.y, humanized.durationMs)

        return if (success) {
            ExecutionResult.Success
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

        Log.d(TAG, "Swipe: (${startX}, ${startY}) -> (${endX}, ${endY}) humanized to " +
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

        Log.d(TAG, "Type: '$text'")

        val success = service.type(text)

        return if (success) ExecutionResult.Success else ExecutionResult.Failed("Type failed")
    }

    private suspend fun executeWait(action: AnalyzeResponse): ExecutionResult {
        val duration = action.duration?.toLong() ?: action.waitAfter?.toLong() ?: 1000L
        Log.d(TAG, "Wait: ${duration}ms")
        delay(duration)
        return ExecutionResult.Success
    }

    private suspend fun executeBack(
        service: TikTokAccessibilityService,
        action: AnalyzeResponse
    ): ExecutionResult {
        Log.d(TAG, "Back pressed")
        val success = service.pressBack()
        return if (success) ExecutionResult.Success else ExecutionResult.Failed("Back press failed")
    }

    private suspend fun executeLaunchTikTok(action: AnalyzeResponse): ExecutionResult {
        val packageName = action.packageName ?: "com.zhiliaoapp.musically"

        Log.d(TAG, "Launch TikTok: $packageName")

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            // Try TikTok Lite as fallback
            val liteIntent = context.packageManager.getLaunchIntentForPackage("com.ss.android.ugc.trill")
            if (liteIntent == null) {
                return ExecutionResult.Failed("TikTok not found")
            }
            liteIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(liteIntent)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        delay(2000L)
        return ExecutionResult.Success
    }

    private suspend fun executeDismissPopup(
        service: TikTokAccessibilityService,
        action: AnalyzeResponse
    ): ExecutionResult {
        Log.d(TAG, "Dismiss popup: ${action.reason}")

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
    data class Done(val message: String?) : ExecutionResult()
    data class Failed(val reason: String) : ExecutionResult()
    data class Error(val message: String?, val recoverable: Boolean) : ExecutionResult()
}

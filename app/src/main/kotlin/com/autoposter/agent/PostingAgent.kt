package com.autoposter.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.autoposter.data.local.db.entities.PostEntity
import com.autoposter.data.remote.api.ApiService
import com.autoposter.data.remote.api.models.AnalyzeContext
import com.autoposter.data.remote.api.models.AnalyzeRequest
import com.autoposter.executor.accessibility.ActionExecutor
import com.autoposter.executor.accessibility.ExecutionResult
import com.autoposter.executor.accessibility.TikTokAccessibilityService
import com.autoposter.executor.accessibility.portal.UITreeParser
import com.autoposter.executor.screen.ScreenUnlocker
import com.autoposter.executor.screen.ScreenWaker
import com.autoposter.executor.screen.UnlockResult
import com.autoposter.executor.screenshot.CaptureResult
import com.autoposter.executor.screenshot.ScreenshotManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PostingAgent @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService,
    private val screenshotManager: ScreenshotManager,
    private val screenUnlocker: ScreenUnlocker,
    private val screenWaker: ScreenWaker,
    private val actionExecutor: ActionExecutor,
    private val uiTreeParser: UITreeParser
) {
    companion object {
        private const val TAG = "PostingAgent"
        private const val MAX_STEPS = 50
        private const val DEFAULT_WAIT = 500L
        private const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
        private const val TIKTOK_LITE_PACKAGE = "com.ss.android.ugc.trill"
    }

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state

    suspend fun executePost(post: PostEntity): PostResult {
        Log.i(TAG, "Starting post execution: ${post.id}")
        var step = 0
        val sessionId = UUID.randomUUID().toString()
        val previousActions = mutableListOf<String>()

        try {
            // 1. Unlock screen if needed
            _state.value = AgentState.UnlockingScreen
            when (val unlockResult = screenUnlocker.ensureUnlocked()) {
                is UnlockResult.Failed -> {
                    _state.value = AgentState.Failed(unlockResult.reason)
                    return PostResult.Failed(unlockResult.reason)
                }
                is UnlockResult.NeedUserAction -> {
                    _state.value = AgentState.NeedUserAction(unlockResult.message)
                    return PostResult.NeedUserAction(unlockResult.message)
                }
                is UnlockResult.NotSupported -> {
                    _state.value = AgentState.Failed(unlockResult.message)
                    return PostResult.Failed(unlockResult.message)
                }
                else -> { /* OK */ }
            }

            // Keep screen on during posting
            screenWaker.keepScreenOn()

            // 2. Open TikTok
            _state.value = AgentState.OpeningTikTok
            if (!openTikTok()) {
                _state.value = AgentState.Failed("TikTok not installed")
                return PostResult.Failed("TikTok not installed")
            }
            delay(2000)

            // Wait for TikTok to be ready
            _state.value = AgentState.WaitingForTikTok
            if (!waitForTikTok()) {
                _state.value = AgentState.Failed("TikTok failed to open")
                return PostResult.Failed("TikTok failed to open")
            }

            // 3. Main posting loop
            while (step < MAX_STEPS) {
                try {
                    _state.value = AgentState.WaitingForServer(step)

                    // Take screenshot
                    val captureResult = screenshotManager.capture()
                    if (captureResult is CaptureResult.Failed) {
                        Log.e(TAG, "Screenshot failed: ${captureResult.reason}")
                        return PostResult.Failed("Screenshot failed: ${captureResult.reason}")
                    }
                    val screenshot = (captureResult as CaptureResult.Success).base64

                    // Get UI tree
                    val service = TikTokAccessibilityService.getInstance()
                    if (service == null) {
                        return PostResult.Failed("Accessibility service disconnected")
                    }
                    val uiTree = service.getUITree()

                    // Send to server for analysis
                    val request = AnalyzeRequest(
                        screenshot = screenshot,
                        uiTree = uiTree.toApiModel(),
                        context = AnalyzeContext(
                            task = "post_video",
                            sessionId = sessionId,
                            step = step,
                            videoFilename = post.videoPath,
                            caption = post.caption,
                            previousActions = previousActions
                        )
                    )

                    val response = apiService.analyze(request)

                    // Execute the action
                    _state.value = AgentState.ExecutingStep(step, response.action)
                    previousActions.add("${response.action}${response.reason?.let { " ($it)" } ?: ""}")

                    Log.d(TAG, "Step $step: ${response.action} - ${response.reason}")

                    when (val result = actionExecutor.execute(response)) {
                        is ExecutionResult.Done -> {
                            _state.value = AgentState.Completed(result.message)
                            screenWaker.releaseWakeLock()
                            return PostResult.Success(result.message)
                        }
                        is ExecutionResult.Error -> {
                            if (!result.recoverable) {
                                _state.value = AgentState.Failed(result.message ?: "Unknown error")
                                screenWaker.releaseWakeLock()
                                return PostResult.Failed(result.message ?: "Unknown error")
                            }
                            // Recoverable error - continue
                            Log.w(TAG, "Recoverable error: ${result.message}")
                        }
                        is ExecutionResult.Failed -> {
                            // Action failed but may recover on next step
                            Log.w(TAG, "Action failed: ${result.reason}")
                        }
                        ExecutionResult.Success -> {
                            // Continue to next step
                        }
                    }

                    step++
                    delay(response.waitAfter?.toLong() ?: DEFAULT_WAIT)

                } catch (e: Exception) {
                    Log.e(TAG, "Error at step $step", e)
                    _state.value = AgentState.Failed("Exception: ${e.message}")
                    screenWaker.releaseWakeLock()
                    return PostResult.Failed("Exception: ${e.message}")
                }
            }

            _state.value = AgentState.Failed("Max steps exceeded ($MAX_STEPS)")
            screenWaker.releaseWakeLock()
            return PostResult.Failed("Max steps exceeded")

        } finally {
            screenWaker.releaseWakeLock()
            _state.value = AgentState.Idle
        }
    }

    private fun openTikTok(): Boolean {
        val tiktokIntent = context.packageManager.getLaunchIntentForPackage(TIKTOK_PACKAGE)
            ?: context.packageManager.getLaunchIntentForPackage(TIKTOK_LITE_PACKAGE)

        if (tiktokIntent == null) {
            Log.e(TAG, "TikTok not found")
            return false
        }

        tiktokIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(tiktokIntent)
        return true
    }

    private suspend fun waitForTikTok(timeoutMs: Long = 10000): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val service = TikTokAccessibilityService.getInstance()
            if (service?.isTikTokInForeground() == true) {
                return true
            }
            delay(500)
        }

        return false
    }
}

sealed class PostResult {
    data class Success(val message: String?) : PostResult()
    data class Failed(val reason: String) : PostResult()
    data class NeedUserAction(val message: String) : PostResult()
}

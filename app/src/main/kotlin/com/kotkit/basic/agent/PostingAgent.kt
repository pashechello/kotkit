package com.kotkit.basic.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import timber.log.Timber
import com.kotkit.basic.data.local.db.entities.PostEntity
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.AnalyzeContext
import com.kotkit.basic.data.remote.api.models.AnalyzeRequest
import com.kotkit.basic.data.remote.api.models.VerifyFeedRequest
import com.kotkit.basic.executor.accessibility.ActionExecutor
import com.kotkit.basic.executor.accessibility.ExecutionResult
import com.kotkit.basic.executor.accessibility.TikTokAccessibilityService
import com.kotkit.basic.permission.AutostartHelper
import com.kotkit.basic.ui.MainActivity
import com.kotkit.basic.executor.accessibility.portal.UITreeParser
import com.kotkit.basic.executor.screen.AudioMuter
import com.kotkit.basic.executor.screen.ProximitySensor
import com.kotkit.basic.executor.screen.ScreenUnlocker
import com.kotkit.basic.executor.screen.ScreenWaker
import com.kotkit.basic.executor.screen.UnlockResult
import com.kotkit.basic.executor.screenshot.CaptureResult
import com.kotkit.basic.executor.screenshot.ScreenshotManager
import com.kotkit.basic.network.ErrorReporter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class PostingAgent @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService,
    private val screenshotManager: ScreenshotManager,
    private val screenUnlocker: ScreenUnlocker,
    private val screenWaker: ScreenWaker,
    private val proximitySensor: ProximitySensor,
    private val actionExecutor: ActionExecutor,
    private val uiTreeParser: UITreeParser,
    private val errorReporter: ErrorReporter,
    private val autostartHelper: AutostartHelper,
    private val audioMuter: AudioMuter
) {
    companion object {
        private const val TAG = "PostingAgent"
        private const val MAX_STEPS = 50
        private const val DEFAULT_WAIT_MIN = 400L
        private const val DEFAULT_WAIT_MAX = 1200L
        private const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
        private const val TIKTOK_LITE_PACKAGE = "com.ss.android.ugc.trill"
        private const val FILE_PROVIDER_AUTHORITY = "com.kotkit.basic.fileprovider"

        // Static reference for cancellation support
        @Volatile
        private var instance: PostingAgent? = null

        fun getInstance(): PostingAgent? = instance
    }

    init {
        instance = this
    }

    // Launch method tracking for backend AI
    // NOTE: Only SHARE_INTENT supported - gallery flow removed
    enum class TikTokLaunchMethod {
        SHARE_INTENT     // Video passed directly via Share Intent
    }

    // Result types for launch attempts
    sealed class TikTokLaunchResult {
        data class Success(
            val method: TikTokLaunchMethod,
            val packageName: String,
            val grantedUri: android.net.Uri? = null  // For Share Intent permission tracking
        ) : TikTokLaunchResult()

        data class ShareNotSupported(val packageName: String) : TikTokLaunchResult()
        object NotInstalled : TikTokLaunchResult()
        data class Failed(val reason: String) : TikTokLaunchResult()
    }

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state

    // Cancellation flag for emergency stop
    @Volatile
    private var isCancelled = false

    /**
     * Cancel the current posting task.
     * Called when user taps the floating logo.
     */
    fun cancelCurrentTask() {
        Timber.tag(TAG).w("Cancel requested by user")
        isCancelled = true
        _state.value = AgentState.Failed("Cancelled by user")

        // Press Home to close TikTok
        TikTokAccessibilityService.getInstance()?.pressHome()
    }

    suspend fun executePost(post: PostEntity): PostResult {
        // Reset cancellation flag at start
        isCancelled = false
        val totalStartTime = System.currentTimeMillis()
        Timber.tag(TAG).i("⏱️ Starting post execution: ${post.id}")
        var step = 0
        val sessionId = UUID.randomUUID().toString()
        val previousActions = mutableListOf<String>()
        var launchMethod: TikTokLaunchMethod? = null
        // LOCAL variables for URI permission tracking - thread safe!
        var grantedVideoUri: android.net.Uri? = null
        var grantedPackageName: String? = null

        try {
            // 1. Check proximity sensor (phone in pocket? → reschedule)
            val proximityStartTime = System.currentTimeMillis()
            _state.value = AgentState.CheckingProximity
            when (proximitySensor.quickCheck()) {
                ProximitySensor.CheckResult.Blocked -> {
                    Timber.tag(TAG).w("Proximity blocked - phone in pocket, rescheduling post")
                    _state.value = AgentState.Idle
                    return PostResult.Retry("Phone appears to be in pocket")
                }
                ProximitySensor.CheckResult.Clear -> {
                    Timber.tag(TAG).i("Proximity clear, proceeding with post")
                }
                ProximitySensor.CheckResult.SensorUnavailable -> {
                    Timber.tag(TAG).w("Proximity sensor unavailable, proceeding anyway")
                }
            }
            Timber.tag(TAG).i("⏱️ Proximity check: ${System.currentTimeMillis() - proximityStartTime}ms")

            // Check for cancellation before unlock
            if (isCancelled) {
                Timber.tag(TAG).w("Posting cancelled before unlock")
                return PostResult.Failed("Cancelled by user")
            }

            // 2. Unlock screen if needed
            val unlockStartTime = System.currentTimeMillis()
            _state.value = AgentState.UnlockingScreen
            when (val unlockResult = screenUnlocker.ensureUnlocked()) {
                is UnlockResult.Failed -> {
                    _state.value = AgentState.Failed(unlockResult.reason)
                    errorReporter.report(
                        errorType = "screen_unlock_failed",
                        errorMessage = unlockResult.reason,
                        context = mapOf("post_id" to post.id.toString())
                    )
                    return PostResult.Failed(unlockResult.reason)
                }
                is UnlockResult.NeedUserAction -> {
                    _state.value = AgentState.NeedUserAction(unlockResult.message)
                    return PostResult.NeedUserAction(unlockResult.message)
                }
                is UnlockResult.NotSupported -> {
                    _state.value = AgentState.Failed(unlockResult.message)
                    errorReporter.report(
                        errorType = "screen_unlock_not_supported",
                        errorMessage = unlockResult.message,
                        context = mapOf("post_id" to post.id.toString())
                    )
                    return PostResult.Failed(unlockResult.message)
                }
                else -> { /* OK */ }
            }
            Timber.tag(TAG).i("⏱️ Screen unlock: ${System.currentTimeMillis() - unlockStartTime}ms")

            // Keep screen on during posting
            screenWaker.keepScreenOn()

            // Mute all audio streams to prevent TikTok sounds
            audioMuter.muteAll()

            // Check for cancellation before opening TikTok
            if (isCancelled) {
                Timber.tag(TAG).w("Posting cancelled before opening TikTok")
                screenWaker.releaseWakeLock()
                return PostResult.Failed("Cancelled by user")
            }

            // 3. Pre-launch delay: simulate user finishing what they were doing
            // A real user doesn't instantly open TikTok the moment they wake their phone
            val preLaunchDelayMs = Random.nextLong(1000, 4000)
            Timber.tag(TAG).i("Pre-launch humanization delay: ${preLaunchDelayMs}ms")
            repeat((preLaunchDelayMs / 100).toInt()) {
                if (isCancelled) {
                    screenWaker.releaseWakeLock()
                    return PostResult.Failed("Cancelled by user")
                }
                delay(100)
            }

            // 4. Open TikTok
            val tiktokOpenStartTime = System.currentTimeMillis()
            _state.value = AgentState.OpeningTikTok

            // OEM workaround: bring KotKit to foreground first on devices that block
            // background activity starts (MIUI, ColorOS, FuntouchOS, etc.)
            // Skip if user confirmed MIUI permissions are enabled - no longer needed
            if (autostartHelper.isAutostartRequired() && !autostartHelper.isAutostartConfirmed()) {
                Timber.tag(TAG).i("MIUI permissions not confirmed, using bringToForeground workaround")
                bringToForeground()
            } else if (autostartHelper.isAutostartRequired()) {
                Timber.tag(TAG).i("MIUI permissions confirmed, launching TikTok directly")
            }

            // Share Intent is the ONLY supported method - no gallery fallback
            val shareResult = openTikTokWithVideo(post.videoPath)
            Timber.tag(TAG).d("Share Intent attempt: $shareResult")

            val finalLaunchResult: TikTokLaunchResult = when (shareResult) {
                is TikTokLaunchResult.Success -> {
                    Timber.tag(TAG).i("TikTok opened successfully via Share Intent")
                    shareResult
                }

                is TikTokLaunchResult.ShareNotSupported -> {
                    _state.value = AgentState.Failed("Share intent not supported")
                    errorReporter.report(
                        errorType = "share_intent_not_supported",
                        errorMessage = "TikTok doesn't support share intent on this device",
                        context = mapOf("post_id" to post.id.toString(), "package" to shareResult.packageName),
                        includeScreenshot = true
                    )
                    return PostResult.Failed("Share intent not supported by TikTok")
                }

                is TikTokLaunchResult.Failed -> {
                    _state.value = AgentState.Failed("Share intent failed: ${shareResult.reason}")
                    errorReporter.report(
                        errorType = "share_intent_failed",
                        errorMessage = "Share intent failed: ${shareResult.reason}",
                        context = mapOf("post_id" to post.id.toString(), "error" to shareResult.reason),
                        includeScreenshot = true
                    )
                    return PostResult.Failed("Share intent failed: ${shareResult.reason}")
                }

                is TikTokLaunchResult.NotInstalled -> {
                    _state.value = AgentState.Failed("TikTok not installed")
                    errorReporter.report(
                        errorType = "tiktok_not_installed",
                        errorMessage = "TikTok app not found on device",
                        context = mapOf("post_id" to post.id.toString())
                    )
                    return PostResult.Failed("TikTok not installed")
                }
            }

            // At this point, finalLaunchResult is GUARANTEED to be Success
            val successResult = finalLaunchResult as TikTokLaunchResult.Success
            launchMethod = successResult.method
            grantedVideoUri = successResult.grantedUri
            grantedPackageName = if (successResult.grantedUri != null) {
                successResult.packageName
            } else {
                null
            }
            Timber.tag(TAG).i("Final launch method: $launchMethod")
            Timber.tag(TAG).i("⏱️ TikTok launch intent: ${System.currentTimeMillis() - tiktokOpenStartTime}ms")

            // Wait for TikTok to be ready (no fixed delay needed, waitForTikTok polls)
            val waitForTikTokStart = System.currentTimeMillis()
            _state.value = AgentState.WaitingForTikTok
            if (!waitForTikTok()) {
                _state.value = AgentState.Failed("TikTok failed to open")
                errorReporter.report(
                    errorType = "tiktok_launch_failed",
                    errorMessage = "TikTok failed to open after 15s timeout",
                    context = mapOf(
                        "post_id" to post.id.toString(),
                        "launch_method" to (launchMethod?.name ?: "unknown"),
                        "wait_time_ms" to (System.currentTimeMillis() - waitForTikTokStart).toString()
                    ),
                    includeScreenshot = true
                )
                return PostResult.Failed("TikTok failed to open")
            }
            Timber.tag(TAG).i("⏱️ Wait for TikTok foreground: ${System.currentTimeMillis() - waitForTikTokStart}ms")
            Timber.tag(TAG).i("⏱️ TOTAL before main loop: ${System.currentTimeMillis() - totalStartTime}ms")

            // 4. Main posting loop
            while (step < MAX_STEPS) {
                // Check for cancellation
                if (isCancelled) {
                    Timber.tag(TAG).w("Posting cancelled by user at step $step")
                    screenWaker.releaseWakeLock()
                    return PostResult.Failed("Cancelled by user")
                }

                try {
                    val stepStartTime = System.currentTimeMillis()
                    _state.value = AgentState.WaitingForServer(step)

                    // Take screenshot (with retry — MIUI can return null on first attempt)
                    val screenshotStartTime = System.currentTimeMillis()
                    val maxScreenshotRetries = 3
                    var captureResult: CaptureResult = CaptureResult.Failed("Not attempted")
                    for (attempt in 1..maxScreenshotRetries) {
                        // On step 0 first attempt, give TikTok extra time to render
                        if (step == 0 && attempt == 1) {
                            delay(500)
                        }
                        captureResult = screenshotManager.capture()
                        if (captureResult is CaptureResult.Success) break
                        if (attempt < maxScreenshotRetries) {
                            Timber.tag(TAG).w("Screenshot attempt $attempt/$maxScreenshotRetries failed: ${(captureResult as CaptureResult.Failed).reason}, retrying in ${attempt * 1000}ms...")
                            delay(attempt * 1000L)
                        }
                    }
                    if (captureResult is CaptureResult.Failed) {
                        Timber.tag(TAG).e("Screenshot failed after $maxScreenshotRetries attempts: ${captureResult.reason}")
                        errorReporter.report(
                            errorType = "screenshot_failed",
                            errorMessage = "Screenshot capture failed after $maxScreenshotRetries attempts: ${captureResult.reason}",
                            context = mapOf("post_id" to post.id.toString(), "step" to step.toString())
                        )
                        return PostResult.Failed("Screenshot failed: ${captureResult.reason}")
                    }
                    val screenshot = (captureResult as CaptureResult.Success).base64
                    Timber.tag(TAG).d("⏱️ Step $step screenshot: ${System.currentTimeMillis() - screenshotStartTime}ms")

                    // Check for cancellation after screenshot
                    if (isCancelled) {
                        Timber.tag(TAG).w("Posting cancelled after screenshot at step $step")
                        screenWaker.releaseWakeLock()
                        return PostResult.Failed("Cancelled by user")
                    }

                    // Get UI tree (with retry if sparse — screen may still be loading after transition)
                    var service = TikTokAccessibilityService.getInstance()
                    if (service == null) {
                        errorReporter.report(
                            errorType = "accessibility_disconnected",
                            errorMessage = "Accessibility service disconnected during posting",
                            context = mapOf("post_id" to post.id.toString(), "step" to step.toString()),
                            includeScreenshot = true
                        )
                        return PostResult.Failed("Accessibility service disconnected")
                    }
                    var uiTree = service.getUITree()
                    var currentScreenshot = screenshot
                    val minPortalElements = 5
                    if (uiTree.elements.size < minPortalElements) {
                        Timber.tag(TAG).w("Step $step UI tree sparse: ${uiTree.elements.size} elements, waiting for screen to load...")
                        for (retryAttempt in 1..3) {
                            delay(800L * retryAttempt)
                            service = TikTokAccessibilityService.getInstance() ?: break
                            uiTree = service.getUITree()
                            if (uiTree.elements.size >= minPortalElements) {
                                // Re-capture screenshot too (screen has changed since transition)
                                val reCapture = screenshotManager.capture()
                                if (reCapture is CaptureResult.Success) {
                                    currentScreenshot = reCapture.base64
                                }
                                Timber.tag(TAG).i("Step $step UI tree recovered after ${retryAttempt} retries: ${uiTree.elements.size} elements")
                                break
                            }
                            Timber.tag(TAG).w("Step $step UI tree retry $retryAttempt/3: still ${uiTree.elements.size} elements")
                        }
                    }
                    Timber.tag(TAG).i("Step $step UI tree: ${uiTree.elements.size} elements (pkg=${uiTree.packageName})")

                    // Send to server for analysis
                    val request = AnalyzeRequest(
                        screenshot = currentScreenshot,
                        uiTree = uiTree.toApiModel(),
                        context = AnalyzeContext(
                            // Share Intent is the only supported flow
                            task = "post_video_share",
                            sessionId = sessionId,
                            step = step,
                            videoFilename = post.videoPath,
                            caption = post.caption,
                            previousActions = previousActions,
                            launchMethod = launchMethod?.name
                        )
                    )

                    val apiStartTime = System.currentTimeMillis()
                    val response = apiService.analyze(request)
                    Timber.tag(TAG).d("⏱️ Step $step API analyze: ${System.currentTimeMillis() - apiStartTime}ms")

                    // Check for cancellation after API call
                    if (isCancelled) {
                        Timber.tag(TAG).w("Posting cancelled after API call at step $step")
                        screenWaker.releaseWakeLock()
                        return PostResult.Failed("Cancelled by user")
                    }

                    // Execute the action
                    _state.value = AgentState.ExecutingStep(step, response.action)
                    previousActions.add("${response.action}${response.reason?.let { " ($it)" } ?: ""}")

                    Timber.tag(TAG).d("Step $step: ${response.action} - ${response.reason}" +
                        (response.resolutionMethod?.let { " [CASCADE: $it]" } ?: ""))

                    // Save screenshot BEFORE publish action for screen change detection
                    val screenshotBeforePublish: String? = if (response.isPublishAction) {
                        Timber.tag(TAG).d("Saving screenshot before publish for comparison")
                        screenshot
                    } else null

                    when (val result = actionExecutor.execute(response) { isCancelled }) {
                        is ExecutionResult.Done -> {
                            _state.value = AgentState.Completed(result.message)
                            screenWaker.releaseWakeLock()
                            lockScreen()
                            return PostResult.Success(result.message)
                        }
                        ExecutionResult.Cancelled -> {
                            Timber.tag(TAG).w("Action cancelled by user at step $step")
                            screenWaker.releaseWakeLock()
                            return PostResult.Failed("Cancelled by user")
                        }
                        is ExecutionResult.Error -> {
                            if (!result.recoverable) {
                                _state.value = AgentState.Failed(result.message ?: "Unknown error")
                                screenWaker.releaseWakeLock()
                                errorReporter.report(
                                    errorType = "action_execution_error",
                                    errorMessage = result.message ?: "Unknown action error",
                                    context = mapOf("post_id" to post.id.toString(), "step" to step.toString()),
                                    includeScreenshot = true
                                )
                                return PostResult.Failed(result.message ?: "Unknown error")
                            }
                            // Recoverable error - continue
                            Timber.tag(TAG).w("Recoverable error: ${result.message}")
                        }
                        is ExecutionResult.Failed -> {
                            // Action failed but may recover on next step
                            Timber.tag(TAG).w("Action failed: ${result.reason}")
                        }
                        ExecutionResult.Success -> {
                            // Continue to next step
                        }
                        ExecutionResult.PublishTapped -> {
                            // Publish button tapped - check if screen changed (like autobot phash)
                            Timber.tag(TAG).i("Publish tapped, checking screen change...")

                            // Cancellable delay - check every 100ms
                            repeat(15) {
                                if (isCancelled) {
                                    Timber.tag(TAG).w("Posting cancelled during publish wait")
                                    screenWaker.releaseWakeLock()
                                    return PostResult.Failed("Cancelled by user")
                                }
                                delay(100)
                            }

                            // Take new screenshot and compare
                            val afterCapture = screenshotManager.capture()
                            if (afterCapture is CaptureResult.Success && screenshotBeforePublish != null) {
                                val screenChanged = screenshotManager.screensChanged(
                                    screenshotBeforePublish,
                                    afterCapture.base64
                                )

                                if (screenChanged) {
                                    Timber.tag(TAG).i("✓ Screen changed after publish - SUCCESS!")
                                    _state.value = AgentState.Completed("Video published (screen changed)")
                                    screenWaker.releaseWakeLock()
                                    lockScreen()
                                    return PostResult.Success(
                                        message = "Video published successfully",
                                        tiktokVideoId = null,
                                        tiktokPostUrl = null
                                    )
                                } else {
                                    Timber.tag(TAG).w("Screen NOT changed - publish may have failed, continuing...")
                                }
                            } else {
                                Timber.tag(TAG).w("Could not compare screenshots, continuing VLM loop...")
                            }
                        }
                    }

                    // Check for cancellation after action execution
                    if (isCancelled) {
                        Timber.tag(TAG).w("Posting cancelled after action at step $step")
                        screenWaker.releaseWakeLock()
                        return PostResult.Failed("Cancelled by user")
                    }

                    step++
                    // Randomize wait time: if VLM provided a value, add ±30% jitter;
                    // otherwise use random value in DEFAULT_WAIT_MIN..DEFAULT_WAIT_MAX
                    val baseWait = response.waitAfter?.toLong()
                    val waitAfterMs = if (baseWait != null && baseWait > 0) {
                        val jitter = (baseWait * 0.3).toLong()
                        Random.nextLong(baseWait - jitter, baseWait + jitter + 1)
                            .coerceAtLeast(DEFAULT_WAIT_MIN)
                    } else {
                        Random.nextLong(DEFAULT_WAIT_MIN, DEFAULT_WAIT_MAX + 1)
                    }

                    // Cancellable delay - check every 100ms
                    val waitIterations = (waitAfterMs / 100).toInt()
                    repeat(waitIterations) {
                        if (isCancelled) {
                            Timber.tag(TAG).w("Posting cancelled during waitAfter at step ${step-1}")
                            screenWaker.releaseWakeLock()
                            return PostResult.Failed("Cancelled by user")
                        }
                        delay(100)
                    }
                    // Remaining time if not divisible by 100
                    val remainingMs = waitAfterMs % 100
                    if (remainingMs > 0) delay(remainingMs)

                    Timber.tag(TAG).d("⏱️ Step ${step-1} TOTAL: ${System.currentTimeMillis() - stepStartTime}ms (waitAfter=${waitAfterMs}ms)")

                } catch (e: Exception) {
                    Timber.tag(TAG).e("Error at step $step", e)
                    _state.value = AgentState.Failed("Exception: ${e.message}")
                    screenWaker.releaseWakeLock()
                    errorReporter.report(
                        errorType = "posting_exception",
                        errorMessage = "Exception during posting: ${e.message}",
                        throwable = e,
                        context = mapOf("post_id" to post.id.toString(), "step" to step.toString()),
                        includeScreenshot = true
                    )
                    return PostResult.Failed("Exception: ${e.message}")
                }
            }

            _state.value = AgentState.Failed("Max steps exceeded ($MAX_STEPS)")
            screenWaker.releaseWakeLock()
            errorReporter.report(
                errorType = "max_steps_exceeded",
                errorMessage = "Posting loop exceeded $MAX_STEPS steps",
                context = mapOf("post_id" to post.id.toString(), "max_steps" to MAX_STEPS.toString()),
                includeScreenshot = true
            )
            return PostResult.Failed("Max steps exceeded")

        } finally {
            audioMuter.restoreAll()
            screenWaker.releaseWakeLock()

            // Revoke URI permissions granted to TikTok (package-specific)
            grantedVideoUri?.let { uri ->
                grantedPackageName?.let { pkg ->
                    try {
                        // Revoke permission specifically from TikTok package
                        context.revokeUriPermission(
                            pkg,
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        Timber.tag(TAG).d("✓ Revoked URI permission for $pkg")
                    } catch (e: Exception) {
                        Timber.tag(TAG).w("Failed to revoke URI permission for $pkg: ${e.message}")
                    }
                }
            }

            _state.value = AgentState.Idle
        }
    }

    /**
     * Check if a TikTok package is installed
     */
    private fun isTikTokInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Open TikTok via Share Intent - passes video directly to TikTok
     * This is the PRIMARY method as we always have a specific video file
     */
    private fun openTikTokWithVideo(videoPath: String): TikTokLaunchResult {
        try {
            val videoFile = File(videoPath)

            // Validate file exists and is readable
            if (!videoFile.exists()) {
                Timber.tag(TAG).e("Video file not found: $videoPath")
                return TikTokLaunchResult.Failed("Video file not found")
            }

            if (!videoFile.canRead()) {
                Timber.tag(TAG).e("Cannot read video file: $videoPath")
                return TikTokLaunchResult.Failed("Cannot read video file")
            }

            // SECURITY: Validate path is within allowed FileProvider directories
            val allowedPaths = listOf(
                File(context.filesDir, "videos"),
                File(context.filesDir, "network_videos"),
                File(context.cacheDir, "videos"),
                context.getExternalFilesDir(null)?.let { File(it, "videos") }
            ).filterNotNull()

            val canonicalPath = videoFile.canonicalPath
            val isPathSafe = allowedPaths.any { allowedDir ->
                canonicalPath.startsWith(allowedDir.canonicalPath)
            }

            if (!isPathSafe) {
                Timber.tag(TAG).e("Video path outside FileProvider scope: $videoPath")
                Timber.tag(TAG).e("Allowed paths: ${allowedPaths.map { it.canonicalPath }}")
                return TikTokLaunchResult.Failed("Video path not in allowed directory")
            }

            // Generate content:// URI via FileProvider
            val videoUri = try {
                FileProvider.getUriForFile(
                    context,
                    FILE_PROVIDER_AUTHORITY,
                    videoFile
                )
            } catch (e: IllegalArgumentException) {
                Timber.tag(TAG).e("FileProvider error - path not in configured paths", e)
                return TikTokLaunchResult.Failed("FileProvider configuration error: ${e.message}")
            }

            // Determine which TikTok package to use
            val tiktokPackage = when {
                isTikTokInstalled(TIKTOK_PACKAGE) -> TIKTOK_PACKAGE
                isTikTokInstalled(TIKTOK_LITE_PACKAGE) -> TIKTOK_LITE_PACKAGE
                else -> return TikTokLaunchResult.NotInstalled
            }

            // Create share intent
            // IMPORTANT: FLAG_ACTIVITY_CLEAR_TOP ensures that if TikTok is already
            // running (e.g., on feed), it will restart with the share intent instead
            // of just bringing the existing activity to foreground
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, videoUri)
                setPackage(tiktokPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // Verify TikTok can handle the intent
            val canHandle = context.packageManager.queryIntentActivities(
                shareIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            ).isNotEmpty()

            if (!canHandle) {
                Timber.tag(TAG).w("TikTok cannot handle share intent, will use fallback")
                return TikTokLaunchResult.ShareNotSupported(tiktokPackage)
            }

            // Launch TikTok with video
            context.startActivity(shareIntent)

            Timber.tag(TAG).i("✓ Opened TikTok via share intent: $tiktokPackage")
            // Return URI for permission tracking in executePost (thread-safe)
            return TikTokLaunchResult.Success(
                TikTokLaunchMethod.SHARE_INTENT,
                tiktokPackage,
                grantedUri = videoUri
            )

        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to open TikTok with share intent", e)
            return TikTokLaunchResult.Failed("Share intent error: ${e.message}")
        }
    }

    /**
     * Open TikTok normally - legacy method (fallback)
     * AI will need to find the video in gallery
     */
    // NOTE: openTikTokNormal() and openTikTok() REMOVED
    // Share Intent is the only supported method - no gallery flow fallback

    /**
     * Wait for TikTok to appear in foreground after launch.
     *
     * For Share Intent: TikTok may take longer to start because it needs to:
     * 1. Launch the app
     * 2. Process the shared video
     * 3. Show the editor/picker screen
     *
     * @param timeoutMs Timeout in milliseconds (default 15 seconds)
     */
    private suspend fun waitForTikTok(timeoutMs: Long = 15000): Boolean {
        val startTime = System.currentTimeMillis()
        var checkCount = 0
        val checkIntervalMs = 200L  // Check every 200ms for faster detection

        Timber.tag(TAG).d("waitForTikTok: Starting wait (timeout=${timeoutMs}ms, interval=${checkIntervalMs}ms)")

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            checkCount++
            val service = TikTokAccessibilityService.getInstance()

            if (service == null) {
                Timber.tag(TAG).w("waitForTikTok: Accessibility service is null (check #$checkCount)")
                delay(checkIntervalMs)
                continue
            }

            val isForeground = service.isTikTokInForeground()

            if (isForeground) {
                val elapsed = System.currentTimeMillis() - startTime
                Timber.tag(TAG).i("⏱️ waitForTikTok: TikTok detected after ${elapsed}ms (check #$checkCount)")
                return true
            }

            // Log every 10th check to avoid log spam
            if (checkCount % 10 == 0) {
                val elapsed = System.currentTimeMillis() - startTime
                Timber.tag(TAG).d("waitForTikTok: Still waiting... (${elapsed}ms elapsed, check #$checkCount)")
            }

            delay(checkIntervalMs)
        }

        Timber.tag(TAG).e("waitForTikTok: TIMEOUT after ${timeoutMs}ms ($checkCount checks)")
        return false
    }

    /**
     * Verify that we've transitioned to the Feed screen after tapping publish.
     * Uses fast Qwen VLM detection via /api/v1/verify_feed endpoint.
     *
     * @return true if on Feed screen without popup, false otherwise
     */
    private suspend fun verifyFeedTransition(): Boolean {
        return try {
            val captureResult = screenshotManager.capture()
            if (captureResult is CaptureResult.Failed) {
                Timber.tag(TAG).e("Screenshot failed during feed verification")
                return false
            }

            val screenshot = (captureResult as CaptureResult.Success).base64

            // Validate screenshot is not empty
            if (screenshot.isBlank()) {
                Timber.tag(TAG).e("Screenshot is empty, cannot verify feed")
                return false
            }

            val request = VerifyFeedRequest(screenshot = screenshot)
            val response = apiService.verifyFeed(request)

            Timber.tag(TAG).d("Feed verify: is_feed=${response.isFeed}, has_popup=${response.hasPopup}")

            // Success only if on Feed without blocking popup
            response.isFeed && !response.hasPopup
        } catch (e: Exception) {
            Timber.tag(TAG).e("Feed verification failed", e)
            false
        }
    }

    /**
     * Lock screen after successful post to save battery.
     * Uses Accessibility Service's GLOBAL_ACTION_LOCK_SCREEN (Android 9+).
     * Includes post-publish browsing to simulate natural human behavior.
     */
    private suspend fun lockScreen() {
        try {
            // Wait for video upload to complete (cancellable, randomized 15-25s)
            val uploadWaitMs = Random.nextLong(15000, 25000)
            Timber.tag(TAG).i("Waiting ${uploadWaitMs}ms for upload to complete...")
            repeat((uploadWaitMs / 100).toInt()) {
                if (isCancelled) {
                    Timber.tag(TAG).w("Lock screen wait cancelled by user")
                    return
                }
                delay(100)
            }

            val service = TikTokAccessibilityService.getInstance()

            // Post-publish browsing: 35% chance to scroll feed like a real user
            // who checks that their video posted and browses a bit
            if (service != null && Random.nextFloat() < 0.35f) {
                val scrollCount = Random.nextInt(1, 4)  // 1-3 swipes
                Timber.tag(TAG).i("Post-publish browsing: scrolling feed $scrollCount times")

                // Use proportional coordinates based on actual screen size
                val displayMetrics = context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                repeat(scrollCount) { i ->
                    if (isCancelled) return

                    // Swipe up on feed (proportional coordinates + randomization)
                    val startX = Random.nextInt(screenWidth / 5, screenWidth / 2)
                    val startY = Random.nextInt((screenHeight * 0.72).toInt(), (screenHeight * 0.85).toInt())
                    val endX = startX + Random.nextInt(-30, 30)
                    val endY = Random.nextInt((screenHeight * 0.2).toInt(), (screenHeight * 0.35).toInt())
                    val duration = Random.nextLong(250, 450)

                    service.swipe(startX, startY, endX, endY, duration)

                    // Watch the video for 2-6 seconds (random, like a real person)
                    val watchTimeMs = Random.nextLong(2000, 6000)
                    repeat((watchTimeMs / 100).toInt()) {
                        if (isCancelled) return
                        delay(100)
                    }

                    Timber.tag(TAG).d("Post-publish browse ${i + 1}/$scrollCount done (watched ${watchTimeMs}ms)")
                }
            }

            if (service != null) {
                // Close TikTok app by pressing Home
                Timber.tag(TAG).i("Closing TikTok app before locking screen...")
                service.pressHome()
                delay(500)

                // Then lock screen
                val success = service.lockScreen()
                if (success) {
                    Timber.tag(TAG).i("✓ Screen locked via Accessibility after successful publish")
                } else {
                    Timber.tag(TAG).w("Accessibility lockScreen returned false")
                }
            } else {
                Timber.tag(TAG).w("Cannot lock screen: Accessibility service not available")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to lock screen: ${e.message}")
            // Not critical - posting was successful
        }
    }

    /**
     * Bring KotKit to foreground before launching TikTok.
     * Uses Full-Screen Intent via notification - the only way that works on MIUI
     * where direct background activity starts are blocked.
     */
    private suspend fun bringToForeground() {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Create high-priority channel for full-screen intent
            val channelId = "foreground_launch"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Posting",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Used to bring app to foreground during posting"
                    setSound(null, null)
                    enableVibration(false)
                }
                notificationManager.createNotificationChannel(channel)
            }

            // Create intent for MainActivity
            val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            }

            val fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                0,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Build notification with full-screen intent
            val notificationId = 10070
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("KotKit")
                .setContentText("Posting video...")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setAutoCancel(true)
                .build()

            // Show notification - this triggers the full-screen intent
            notificationManager.notify(notificationId, notification)
            Timber.tag(TAG).i("Triggered full-screen intent notification")

            delay(800)  // Wait for MainActivity to come to foreground

            // Cancel the notification (activity should be visible now)
            notificationManager.cancel(notificationId)
            Timber.tag(TAG).i("Brought KotKit to foreground via full-screen intent")

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to bring to foreground via full-screen intent, trying direct start")
            // Fallback to direct start (may work on non-MIUI)
            try {
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                context.startActivity(intent)
                delay(500)
            } catch (e2: Exception) {
                Timber.tag(TAG).e(e2, "Direct activity start also failed")
            }
        }
    }
}

sealed class PostResult {
    data class Success(
        val message: String?,
        val tiktokVideoId: String? = null, // TODO: Extract from TikTok after posting
        val tiktokPostUrl: String? = null
    ) : PostResult()
    data class Failed(val reason: String) : PostResult()
    data class NeedUserAction(val message: String) : PostResult()
    /** Temporary failure, should retry later (e.g., phone in pocket) */
    data class Retry(val reason: String) : PostResult()
}

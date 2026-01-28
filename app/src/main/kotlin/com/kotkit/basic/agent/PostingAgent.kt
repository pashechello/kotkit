package com.kotkit.basic.agent

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.kotkit.basic.executor.accessibility.portal.UITreeParser
import com.kotkit.basic.executor.screen.ProximitySensor
import com.kotkit.basic.executor.screen.ScreenUnlocker
import com.kotkit.basic.executor.screen.ScreenWaker
import com.kotkit.basic.executor.screen.UnlockResult
import com.kotkit.basic.executor.screenshot.CaptureResult
import com.kotkit.basic.executor.screenshot.ScreenshotManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
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
    private val proximitySensor: ProximitySensor,
    private val actionExecutor: ActionExecutor,
    private val uiTreeParser: UITreeParser
) {
    companion object {
        private const val TAG = "PostingAgent"
        private const val MAX_STEPS = 50
        private const val DEFAULT_WAIT = 500L
        private const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
        private const val TIKTOK_LITE_PACKAGE = "com.ss.android.ugc.trill"
        private const val FILE_PROVIDER_AUTHORITY = "com.kotkit.basic.fileprovider"

        // Static reference for emergency stop from FloatingLogoService
        @Volatile
        private var instance: PostingAgent? = null

        fun getInstance(): PostingAgent? = instance
    }

    init {
        instance = this
    }

    // Launch method tracking for backend AI
    enum class TikTokLaunchMethod {
        SHARE_INTENT,    // New method - video passed directly
        NORMAL_LAUNCH    // Legacy method - find video in gallery
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
            Timber.tag(TAG).i("⏱️ Screen unlock: ${System.currentTimeMillis() - unlockStartTime}ms")

            // Keep screen on during posting
            screenWaker.keepScreenOn()

            // Check for cancellation before opening TikTok
            if (isCancelled) {
                Timber.tag(TAG).w("Posting cancelled before opening TikTok")
                screenWaker.releaseWakeLock()
                return PostResult.Failed("Cancelled by user")
            }

            // 3. Open TikTok with automatic fallback
            val tiktokOpenStartTime = System.currentTimeMillis()
            _state.value = AgentState.OpeningTikTok

            // ALWAYS try Share Intent first (primary method)
            val finalLaunchResult: TikTokLaunchResult = run {
                val shareResult = openTikTokWithVideo(post.videoPath)
                Timber.tag(TAG).d("Share Intent attempt: $shareResult")

                when (shareResult) {
                    is TikTokLaunchResult.Success -> {
                        Timber.tag(TAG).i("TikTok opened successfully via ${shareResult.method}")
                        shareResult
                    }

                    is TikTokLaunchResult.ShareNotSupported -> {
                        Timber.tag(TAG).w("Share intent not supported for ${shareResult.packageName}, falling back to normal launch")
                        val fallbackResult = openTikTokNormal()

                        if (fallbackResult !is TikTokLaunchResult.Success) {
                            _state.value = AgentState.Failed("Both launch methods failed")
                            return PostResult.Failed("Cannot open TikTok: $fallbackResult")
                        }
                        fallbackResult
                    }

                    is TikTokLaunchResult.Failed -> {
                        Timber.tag(TAG).w("Share intent failed: ${shareResult.reason}, trying normal launch")
                        val fallbackResult = openTikTokNormal()

                        if (fallbackResult !is TikTokLaunchResult.Success) {
                            _state.value = AgentState.Failed("All launch methods failed")
                            return PostResult.Failed("Cannot open TikTok: $fallbackResult")
                        }
                        fallbackResult
                    }

                    is TikTokLaunchResult.NotInstalled -> {
                        _state.value = AgentState.Failed("TikTok not installed")
                        return PostResult.Failed("TikTok not installed")
                    }
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

            // Small delay to let the intent start processing (was 2000ms, now 500ms)
            // waitForTikTok will handle the rest
            val postLaunchDelayStart = System.currentTimeMillis()
            delay(500)
            Timber.tag(TAG).i("⏱️ Post-launch delay: ${System.currentTimeMillis() - postLaunchDelayStart}ms")

            // Wait for TikTok to be ready
            val waitForTikTokStart = System.currentTimeMillis()
            _state.value = AgentState.WaitingForTikTok
            if (!waitForTikTok()) {
                _state.value = AgentState.Failed("TikTok failed to open")
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

                    // Take screenshot
                    val screenshotStartTime = System.currentTimeMillis()
                    val captureResult = screenshotManager.capture()
                    if (captureResult is CaptureResult.Failed) {
                        Timber.tag(TAG).e("Screenshot failed: ${captureResult.reason}")
                        return PostResult.Failed("Screenshot failed: ${captureResult.reason}")
                    }
                    val screenshot = (captureResult as CaptureResult.Success).base64
                    Timber.tag(TAG).d("⏱️ Step $step screenshot: ${System.currentTimeMillis() - screenshotStartTime}ms")

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
                            task = if (launchMethod == TikTokLaunchMethod.SHARE_INTENT) {
                                "post_video_share"  // New flow - video already selected
                            } else {
                                "post_video"  // Legacy flow - need to find video in gallery
                            },
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

                    // Execute the action
                    _state.value = AgentState.ExecutingStep(step, response.action)
                    previousActions.add("${response.action}${response.reason?.let { " ($it)" } ?: ""}")

                    Timber.tag(TAG).d("Step $step: ${response.action} - ${response.reason}")

                    // Save screenshot BEFORE publish action for screen change detection
                    val screenshotBeforePublish: String? = if (response.isPublishAction) {
                        Timber.tag(TAG).d("Saving screenshot before publish for comparison")
                        screenshot
                    } else null

                    // Handle read_clipboard action (Post & Check link extraction)
                    if (response.action.equals("read_clipboard", ignoreCase = true)) {
                        Timber.tag(TAG).i("VLM requested clipboard read, extracting video URL...")
                        delay(300)  // Small delay to ensure clipboard is updated

                        val clipboardUrl = readClipboard()
                        if (clipboardUrl != null && isValidTikTokUrl(clipboardUrl)) {
                            val videoId = extractVideoId(clipboardUrl)
                            Timber.tag(TAG).i("Successfully extracted video URL: $clipboardUrl (ID: $videoId)")

                            _state.value = AgentState.Completed("Video published and link extracted")
                            screenWaker.releaseWakeLock()
                            lockScreen()

                            return PostResult.Success(
                                message = "Video published successfully",
                                tiktokVideoId = videoId,
                                tiktokPostUrl = clipboardUrl
                            )
                        } else {
                            Timber.tag(TAG).w("Invalid or empty clipboard: $clipboardUrl")
                            // Continue loop, VLM will retry
                        }
                    }

                    when (val result = actionExecutor.execute(response)) {
                        is ExecutionResult.Done -> {
                            _state.value = AgentState.Completed(result.message)
                            screenWaker.releaseWakeLock()
                            lockScreen()
                            return PostResult.Success(result.message)
                        }
                        is ExecutionResult.Error -> {
                            if (!result.recoverable) {
                                _state.value = AgentState.Failed(result.message ?: "Unknown error")
                                screenWaker.releaseWakeLock()
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
                            delay(1500)  // Wait for upload to complete (reduced from 2500ms)

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

                    step++
                    val waitAfterMs = response.waitAfter?.toLong() ?: DEFAULT_WAIT
                    delay(waitAfterMs)
                    Timber.tag(TAG).d("⏱️ Step ${step-1} TOTAL: ${System.currentTimeMillis() - stepStartTime}ms (waitAfter=${waitAfterMs}ms)")

                } catch (e: Exception) {
                    Timber.tag(TAG).e("Error at step $step", e)
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
    private fun openTikTokNormal(): TikTokLaunchResult {
        val tiktokPackage = when {
            isTikTokInstalled(TIKTOK_PACKAGE) -> TIKTOK_PACKAGE
            isTikTokInstalled(TIKTOK_LITE_PACKAGE) -> TIKTOK_LITE_PACKAGE
            else -> return TikTokLaunchResult.NotInstalled
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(tiktokPackage)
        if (launchIntent == null) {
            Timber.tag(TAG).e("Cannot get launch intent for $tiktokPackage")
            return TikTokLaunchResult.Failed("Cannot create launch intent")
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(launchIntent)
        Timber.tag(TAG).i("✓ Opened TikTok normally (fallback): $tiktokPackage")
        return TikTokLaunchResult.Success(TikTokLaunchMethod.NORMAL_LAUNCH, tiktokPackage)
    }

    /**
     * Legacy method - kept for backward compatibility
     * Redirects to openTikTokNormal()
     */
    private fun openTikTok(): Boolean {
        val result = openTikTokNormal()
        return result is TikTokLaunchResult.Success
    }

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

    // ========================================================================
    // Post & Check: Clipboard helpers for link extraction
    // ========================================================================

    private fun readClipboard(): String? {
        return try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                clipData.getItemAt(0)?.text?.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to read clipboard", e)
            null
        }
    }

    private fun isValidTikTokUrl(url: String): Boolean {
        val patterns = listOf(
            Regex("""^https?://(www\.)?tiktok\.com/@[\w.]+/video/\d+""", RegexOption.IGNORE_CASE),
            Regex("""^https?://vm\.tiktok\.com/[\w]+/?$""", RegexOption.IGNORE_CASE),
            Regex("""^https?://vt\.tiktok\.com/[\w]+/?$""", RegexOption.IGNORE_CASE)
        )
        return patterns.any { it.containsMatchIn(url) }
    }

    private fun extractVideoId(url: String): String? {
        val match = Regex("""/video/(\d+)""").find(url)
        return match?.groupValues?.getOrNull(1)
    }

    /**
     * Lock screen after successful post to save battery.
     * Uses Accessibility Service's GLOBAL_ACTION_LOCK_SCREEN (Android 9+).
     * Waits before locking to allow video upload to complete.
     */
    private suspend fun lockScreen() {
        try {
            // Wait for video upload to complete before locking
            Timber.tag(TAG).i("Waiting 20s before locking screen (allowing upload to complete)...")
            delay(20_000)

            val service = TikTokAccessibilityService.getInstance()
            if (service != null) {
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

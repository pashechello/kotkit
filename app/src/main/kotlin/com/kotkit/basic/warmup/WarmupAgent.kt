package com.kotkit.basic.warmup

import android.content.Context
import android.content.Intent
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.AnalyzeContext
import com.kotkit.basic.data.remote.api.models.AnalyzeRequest
import com.kotkit.basic.data.remote.api.models.VerifyFeedRequest
import com.kotkit.basic.executor.accessibility.ActionExecutor
import com.kotkit.basic.executor.accessibility.ExecutionResult
import com.kotkit.basic.executor.accessibility.TikTokAccessibilityService
import com.kotkit.basic.executor.humanizer.BasicHumanizer
import com.kotkit.basic.executor.screenshot.CaptureResult
import com.kotkit.basic.executor.screenshot.ScreenshotManager
import com.kotkit.basic.executor.screen.AudioMuter
import com.kotkit.basic.executor.screen.ScreenUnlocker
import com.kotkit.basic.executor.screen.ScreenWaker
import com.kotkit.basic.executor.screen.UnlockResult
import com.kotkit.basic.scheduler.DeviceStateChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Core warmup engine. Opens TikTok, scrolls feed, watches videos, likes some.
 *
 * VLM monitoring (like autobot):
 * - Every N videos: POST /api/v1/verify_feed (Qwen, cheap) — is_feed? has_popup?
 * - If popup detected: POST /api/v1/analyze (warmup_feed_recovery) — SYMBIOSIS dismissal
 * - If not on feed: POST /api/v1/analyze — VLM decides how to return
 */
@Singleton
class WarmupAgent @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screenUnlocker: ScreenUnlocker,
    private val screenWaker: ScreenWaker,
    private val audioMuter: AudioMuter,
    private val deviceStateChecker: DeviceStateChecker,
    private val screenshotManager: ScreenshotManager,
    private val actionExecutor: ActionExecutor,
    private val apiService: ApiService
) {
    companion object {
        private const val TAG = "WarmupAgent"

        private val TIKTOK_PACKAGES = listOf(
            "com.zhiliaoapp.musically",     // TikTok main
            "com.ss.android.ugc.trill",     // TikTok Lite
        )
    }

    private val _isCancelled = AtomicBoolean(false)
    val isCancelled: Boolean get() = _isCancelled.get()

    private val humanizer = BasicHumanizer()

    fun cancel() {
        _isCancelled.set(true)
        Timber.tag(TAG).i("Warmup cancel requested")
    }

    /**
     * Execute one warmup session.
     * Caller must ensure conditions are met (charging, screen off, etc.).
     */
    suspend fun execute(): WarmupResult {
        _isCancelled.set(false)
        val sessionId = UUID.randomUUID().toString().take(8)
        val startTime = System.currentTimeMillis()
        val durationMinutes = Random.nextInt(
            WarmupConfig.SESSION_MIN_MINUTES,
            WarmupConfig.SESSION_MAX_MINUTES + 1
        )
        val endTime = startTime + durationMinutes * 60 * 1000L

        var videosWatched = 0
        var likesGiven = 0
        var vlmCalls = 0
        var popupsDismissed = 0

        Timber.tag(TAG).i("[$sessionId] Starting warmup session: ${durationMinutes}min")

        try {
            // === SCREEN PREPARATION ===
            screenWaker.wake()
            if (!waitForScreenOn(2000)) {
                return WarmupResult.Failed("Screen did not turn on")
            }
            delay(500)

            when (val unlockResult = screenUnlocker.ensureUnlocked()) {
                is UnlockResult.Success,
                is UnlockResult.AlreadyUnlocked -> { /* Good */ }
                is UnlockResult.Failed -> {
                    Timber.tag(TAG).w("[$sessionId] Unlock failed: ${unlockResult.reason}")
                    return WarmupResult.Failed("Unlock failed: ${unlockResult.reason}")
                }
                is UnlockResult.NeedUserAction -> {
                    Timber.tag(TAG).w("[$sessionId] Unlock needs user: ${unlockResult.message}")
                    return WarmupResult.Failed("Unlock needs user action: ${unlockResult.message}")
                }
                is UnlockResult.NotSupported -> {
                    Timber.tag(TAG).w("[$sessionId] Unlock not supported: ${unlockResult.message}")
                    return WarmupResult.Failed("Unlock not supported: ${unlockResult.message}")
                }
            }

            audioMuter.muteAll()

            if (_isCancelled.get()) return cancelledResult(videosWatched, likesGiven, vlmCalls, popupsDismissed, startTime)

            // === LAUNCH TIKTOK ===
            val tiktokPkg = findTikTokPackage()
            if (tiktokPkg == null) {
                Timber.tag(TAG).w("[$sessionId] TikTok not installed")
                return WarmupResult.Failed("TikTok not installed")
            }

            launchTikTok(tiktokPkg)
            delay(WarmupConfig.TIKTOK_LAUNCH_WAIT_MS)

            if (_isCancelled.get()) return cancelledResult(videosWatched, likesGiven, vlmCalls, popupsDismissed, startTime)

            // Verify TikTok is in foreground
            val service = TikTokAccessibilityService.getInstance()
            if (service == null) {
                return WarmupResult.Failed("Accessibility service not available")
            }
            if (!service.isTikTokInForeground()) {
                Timber.tag(TAG).w("[$sessionId] TikTok not in foreground after launch")
                return WarmupResult.Failed("TikTok not in foreground")
            }

            // === INITIAL FEED VERIFICATION via VLM ===
            Timber.tag(TAG).i("[$sessionId] TikTok launched, verifying feed state via VLM")
            val initialCheck = checkFeedState(sessionId)
            vlmCalls++
            if (initialCheck is FeedCheckResult.NotRecoverable) {
                return WarmupResult.Failed("Initial feed check failed: ${initialCheck.reason}")
            }
            if (initialCheck is FeedCheckResult.PopupDismissed) {
                popupsDismissed++
            }

            Timber.tag(TAG).i("[$sessionId] Feed confirmed, starting main loop")

            // === MAIN WARMUP LOOP ===
            var recoveryAttempts = 0

            while (System.currentTimeMillis() < endTime && !_isCancelled.get()) {
                // Watch current video
                val watchTime = Random.nextLong(
                    WarmupConfig.WATCH_TIME_MIN_SEC,
                    WarmupConfig.WATCH_TIME_MAX_SEC + 1
                ) * 1000
                if (!cancellableDelay(watchTime)) break
                videosWatched++

                if (_isCancelled.get()) break

                // Maybe like (double-tap)
                if (Random.nextFloat() < WarmupConfig.LIKE_PROBABILITY) {
                    doubleTapToLike(service)
                    likesGiven++
                    Timber.tag(TAG).d("[$sessionId] Liked video #$videosWatched")
                }

                if (_isCancelled.get()) break

                // Maybe pause
                if (Random.nextFloat() < WarmupConfig.PAUSE_PROBABILITY) {
                    val pauseTime = Random.nextLong(
                        WarmupConfig.PAUSE_MIN_SEC,
                        WarmupConfig.PAUSE_MAX_SEC + 1
                    ) * 1000
                    if (!cancellableDelay(pauseTime)) break
                }

                if (_isCancelled.get()) break

                // Swipe up to next video
                swipeToNextVideo(service)
                delay(WarmupConfig.POST_SWIPE_SETTLE_MS)

                // === VLM MONITORING (every N videos) ===
                if (videosWatched % WarmupConfig.VLM_CHECK_INTERVAL_VIDEOS == 0) {
                    Timber.tag(TAG).d("[$sessionId] VLM feed check at video #$videosWatched")
                    val checkResult = checkFeedState(sessionId)
                    vlmCalls++

                    when (checkResult) {
                        is FeedCheckResult.FeedOk -> {
                            // All good, continue
                        }
                        is FeedCheckResult.PopupDismissed -> {
                            popupsDismissed++
                            Timber.tag(TAG).i("[$sessionId] Popup dismissed via VLM")
                        }
                        is FeedCheckResult.RecoveredToFeed -> {
                            recoveryAttempts++
                            Timber.tag(TAG).i("[$sessionId] Recovered to feed via VLM (attempt $recoveryAttempts)")
                            if (recoveryAttempts >= WarmupConfig.MAX_VLM_RECOVERY_ATTEMPTS) {
                                Timber.tag(TAG).w("[$sessionId] Too many recovery attempts, ending session")
                                break
                            }
                        }
                        is FeedCheckResult.NotRecoverable -> {
                            Timber.tag(TAG).w("[$sessionId] Feed not recoverable: ${checkResult.reason}")
                            break
                        }
                    }
                }

                // Periodic device checks (TikTok foreground + charging + wake lock refresh)
                if (videosWatched % WarmupConfig.FEED_CHECK_INTERVAL == 0) {
                    // Re-acquire wake lock before it expires (10 min timeout)
                    screenWaker.keepScreenOn()

                    val svc = TikTokAccessibilityService.getInstance()
                    if (svc == null || !svc.isTikTokInForeground()) {
                        Timber.tag(TAG).w("[$sessionId] TikTok no longer in foreground")
                        launchTikTok(tiktokPkg)
                        delay(WarmupConfig.TIKTOK_LAUNCH_WAIT_MS)
                        val svc2 = TikTokAccessibilityService.getInstance()
                        if (svc2 == null || !svc2.isTikTokInForeground()) {
                            Timber.tag(TAG).w("[$sessionId] TikTok still not in foreground after relaunch")
                            break
                        }
                    }

                    if (!deviceStateChecker.getCurrentStateSnapshot().isCharging) {
                        Timber.tag(TAG).i("[$sessionId] Phone unplugged, ending warmup")
                        break
                    }
                }
            }

            val stats = buildStats(videosWatched, likesGiven, vlmCalls, popupsDismissed, startTime)
            Timber.tag(TAG).i("[$sessionId] Warmup loop finished: $stats")

            return if (_isCancelled.get()) {
                WarmupResult.Cancelled(stats)
            } else {
                WarmupResult.Success(stats)
            }

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "[$sessionId] Warmup error")
            return WarmupResult.Failed(
                e.message ?: "Unknown error",
                buildStats(videosWatched, likesGiven, vlmCalls, popupsDismissed, startTime)
            )
        } finally {
            cleanup()
            Timber.tag(TAG).i("[$sessionId] Warmup cleanup complete")
        }
    }

    // ========================================================================
    // VLM MONITORING
    // ========================================================================

    /**
     * Check feed state via VLM. Uses lightweight verify_feed (Qwen) first,
     * then full analyze (Gemini + UI-TARS SYMBIOSIS) for recovery if needed.
     */
    private suspend fun checkFeedState(sessionId: String): FeedCheckResult {
        val screenshot = captureScreenshotBase64()
            ?: return FeedCheckResult.FeedOk // Can't capture — assume ok, will retry next check

        try {
            val feedState = apiService.verifyFeed(VerifyFeedRequest(screenshot = screenshot))
            Timber.tag(TAG).d("[$sessionId] verify_feed: is_feed=${feedState.isFeed}, has_popup=${feedState.hasPopup}, type=${feedState.popupType}")

            return when {
                feedState.isFeed && !feedState.hasPopup -> FeedCheckResult.FeedOk
                feedState.hasPopup -> dismissPopup(sessionId, screenshot)
                !feedState.isFeed -> recoverToFeed(sessionId, screenshot)
                else -> FeedCheckResult.FeedOk
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "[$sessionId] verify_feed failed, continuing")
            return FeedCheckResult.FeedOk // Network error — don't abort, try again next check
        }
    }

    /**
     * Dismiss popup via full /api/v1/analyze with SYMBIOSIS (Gemini + UI-TARS grounding).
     * Retries up to MAX_POPUP_DISMISSALS times.
     */
    private suspend fun dismissPopup(sessionId: String, initialScreenshot: String): FeedCheckResult {
        var currentScreenshot = initialScreenshot
        val previousActions = mutableListOf<String>()

        for (attempt in 1..WarmupConfig.MAX_POPUP_DISMISSALS) {
            if (_isCancelled.get()) break

            val recovered = executeVlmRecovery(sessionId, currentScreenshot, attempt, previousActions)
            if (!recovered) {
                return FeedCheckResult.NotRecoverable("VLM could not dismiss popup on attempt $attempt")
            }

            delay(WarmupConfig.FEED_RECOVERY_SETTLE_MS)

            // Re-check with Qwen
            val newScreenshot = captureScreenshotBase64()
                ?: return FeedCheckResult.PopupDismissed // Can't verify — assume success

            try {
                val recheck = apiService.verifyFeed(VerifyFeedRequest(screenshot = newScreenshot))
                if (recheck.isFeed && !recheck.hasPopup) {
                    Timber.tag(TAG).i("[$sessionId] Popup dismissed after $attempt attempts")
                    return FeedCheckResult.PopupDismissed
                }
                if (!recheck.hasPopup && !recheck.isFeed) {
                    // Popup gone but not on feed — need feed recovery
                    return recoverToFeed(sessionId, newScreenshot)
                }
                // Still has popup — retry with updated context
                currentScreenshot = newScreenshot
                Timber.tag(TAG).d("[$sessionId] Still has popup, retry $attempt/${WarmupConfig.MAX_POPUP_DISMISSALS}")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "[$sessionId] verify_feed recheck failed during popup dismiss")
                return FeedCheckResult.PopupDismissed // Can't verify — assume ok
            }
        }

        return FeedCheckResult.NotRecoverable("Max popup dismissals reached")
    }

    /**
     * Try to recover to the feed via full /api/v1/analyze.
     * VLM decides: back(), swipe(), tap(home), etc.
     */
    private suspend fun recoverToFeed(sessionId: String, initialScreenshot: String): FeedCheckResult {
        var currentScreenshot = initialScreenshot
        val previousActions = mutableListOf<String>()

        for (attempt in 1..WarmupConfig.MAX_VLM_RECOVERY_ATTEMPTS) {
            if (_isCancelled.get()) break

            val recovered = executeVlmRecovery(sessionId, currentScreenshot, attempt, previousActions)
            if (!recovered) {
                return FeedCheckResult.NotRecoverable("VLM recovery failed on attempt $attempt")
            }

            delay(WarmupConfig.FEED_RECOVERY_SETTLE_MS)

            val newScreenshot = captureScreenshotBase64()
            if (newScreenshot == null) {
                Timber.tag(TAG).w("[$sessionId] Cannot capture screenshot to verify recovery")
                return FeedCheckResult.NotRecoverable("Screenshot failed during recovery")
            }

            try {
                val recheck = apiService.verifyFeed(VerifyFeedRequest(screenshot = newScreenshot))
                if (recheck.isFeed && !recheck.hasPopup) {
                    Timber.tag(TAG).i("[$sessionId] Recovered to feed on attempt $attempt")
                    return FeedCheckResult.RecoveredToFeed
                }
                if (recheck.hasPopup) {
                    return dismissPopup(sessionId, newScreenshot)
                }
                // Still not on feed — retry
                currentScreenshot = newScreenshot
                Timber.tag(TAG).d("[$sessionId] Still not on feed, attempt $attempt/${WarmupConfig.MAX_VLM_RECOVERY_ATTEMPTS}")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "[$sessionId] verify_feed failed during recovery, retrying")
                currentScreenshot = newScreenshot // Keep trying with new screenshot
            }
        }

        return FeedCheckResult.NotRecoverable("Could not recover to feed after ${WarmupConfig.MAX_VLM_RECOVERY_ATTEMPTS} attempts")
    }

    /**
     * Execute a single VLM recovery action via /api/v1/analyze (warmup_feed_recovery).
     * Server handles SYMBIOSIS (Gemini + UI-TARS) for popup grounding automatically.
     *
     * @param step Attempt number (sent to VLM so it knows what was already tried)
     * @param previousActions Actions already tried (VLM uses this to avoid repeating)
     */
    private suspend fun executeVlmRecovery(
        sessionId: String,
        screenshotBase64: String,
        step: Int,
        previousActions: MutableList<String>
    ): Boolean {
        return try {
            val service = TikTokAccessibilityService.getInstance()
                ?: return false

            val uiTree = service.getUITree()
            val request = AnalyzeRequest(
                screenshot = screenshotBase64,
                uiTree = uiTree.toApiModel(),
                context = AnalyzeContext(
                    task = "warmup_feed_recovery",
                    sessionId = sessionId,
                    step = step,
                    videoFilename = "",
                    caption = "",
                    previousActions = previousActions.toList()
                )
            )

            val response = apiService.analyze(request)
            Timber.tag(TAG).i("VLM recovery step $step: ${response.action} (${response.reason})")

            // Track what VLM tried so next call has context
            previousActions.add("${response.action}(${response.reason?.take(30) ?: ""})")

            val result = actionExecutor.execute(response) { _isCancelled.get() }
            result is ExecutionResult.Success

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "VLM recovery call failed at step $step")
            TikTokAccessibilityService.getInstance()?.pressBack()
            delay(500)
            previousActions.add("back(fallback)")
            false
        }
    }

    // ========================================================================
    // ACTIONS
    // ========================================================================

    private fun findTikTokPackage(): String? {
        val pm = context.packageManager
        for (pkg in TIKTOK_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) { }
        }
        return null
    }

    private fun launchTikTok(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Timber.tag(TAG).d("Launched TikTok: $packageName")
        } else {
            Timber.tag(TAG).w("getLaunchIntentForPackage returned null for $packageName")
        }
    }

    private suspend fun doubleTapToLike(service: TikTokAccessibilityService) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        val x = (screenWidth * WarmupConfig.LIKE_ZONE_LEFT_PCT +
            Random.nextFloat() * screenWidth *
            (WarmupConfig.LIKE_ZONE_RIGHT_PCT - WarmupConfig.LIKE_ZONE_LEFT_PCT)).toInt()
        val y = (screenHeight * WarmupConfig.LIKE_ZONE_TOP_PCT +
            Random.nextFloat() * screenHeight *
            (WarmupConfig.LIKE_ZONE_BOTTOM_PCT - WarmupConfig.LIKE_ZONE_TOP_PCT)).toInt()

        val tap = humanizer.humanizeTap(x, y)

        service.tap(tap.x, tap.y, tap.durationMs)
        delay(Random.nextLong(100, 250))
        service.tap(tap.x, tap.y, tap.durationMs)
    }

    private suspend fun swipeToNextVideo(service: TikTokAccessibilityService) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        val baseDuration = Random.nextLong(
            WarmupConfig.SWIPE_DURATION_MIN_MS,
            WarmupConfig.SWIPE_DURATION_MAX_MS + 1
        )

        val swipe = humanizer.humanizeSwipe(
            startX = screenWidth / 2,
            startY = (screenHeight * 0.8f).toInt(),
            endX = screenWidth / 2,
            endY = (screenHeight * 0.2f).toInt(),
            baseDuration = baseDuration
        )

        service.swipe(swipe.startX, swipe.startY, swipe.endX, swipe.endY, swipe.durationMs)
    }

    // ========================================================================
    // UTILITIES
    // ========================================================================

    private suspend fun captureScreenshotBase64(): String? {
        return when (val result = screenshotManager.capture()) {
            is CaptureResult.Success -> result.base64
            is CaptureResult.Failed -> {
                Timber.tag(TAG).w("Screenshot failed: ${result.reason}")
                null
            }
        }
    }

    private suspend fun waitForScreenOn(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (pm.isInteractive) return true
            delay(100)
        }
        return pm.isInteractive
    }

    private suspend fun cancellableDelay(millis: Long): Boolean {
        var remaining = millis
        while (remaining > 0 && !_isCancelled.get()) {
            val chunk = minOf(remaining, WarmupConfig.CANCEL_CHECK_INTERVAL_MS)
            delay(chunk)
            remaining -= chunk
        }
        return !_isCancelled.get()
    }

    private fun cleanup() {
        try {
            TikTokAccessibilityService.getInstance()?.pressHome()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to press HOME")
        }

        try {
            audioMuter.restoreAll()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to restore audio")
        }

        try {
            screenWaker.releaseWakeLock()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to release wake lock")
        }

        try {
            TikTokAccessibilityService.getInstance()?.lockScreen()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to lock screen")
        }
    }

    private fun buildStats(
        videosWatched: Int,
        likesGiven: Int,
        vlmCalls: Int,
        popupsDismissed: Int,
        startTime: Long
    ) = WarmupStats(
        videosWatched = videosWatched,
        likesGiven = likesGiven,
        durationSeconds = ((System.currentTimeMillis() - startTime) / 1000).toInt(),
        vlmRecoveryCalls = vlmCalls,
        popupsDismissed = popupsDismissed
    )

    private fun cancelledResult(
        videosWatched: Int,
        likesGiven: Int,
        vlmCalls: Int,
        popupsDismissed: Int,
        startTime: Long
    ) = WarmupResult.Cancelled(buildStats(videosWatched, likesGiven, vlmCalls, popupsDismissed, startTime))
}

/**
 * Result of a VLM feed state check.
 */
private sealed class FeedCheckResult {
    /** Feed is fine, no issues */
    object FeedOk : FeedCheckResult()
    /** Popup was detected and dismissed */
    object PopupDismissed : FeedCheckResult()
    /** Was not on feed, but recovered via VLM */
    object RecoveredToFeed : FeedCheckResult()
    /** Could not recover — session should end */
    data class NotRecoverable(val reason: String) : FeedCheckResult()
}

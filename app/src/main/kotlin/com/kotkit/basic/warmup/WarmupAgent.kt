package com.kotkit.basic.warmup

import android.content.Context
import android.content.Intent
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.AnalyzeContext
import com.kotkit.basic.data.remote.api.models.AnalyzeRequest
import com.kotkit.basic.executor.accessibility.ActionExecutor
import com.kotkit.basic.executor.accessibility.TikTokAccessibilityService
import com.kotkit.basic.executor.humanizer.BasicHumanizer
import com.kotkit.basic.executor.screenshot.CaptureResult
import com.kotkit.basic.executor.screenshot.ScreenshotManager
import com.kotkit.basic.executor.screen.AudioMuter
import com.kotkit.basic.executor.screen.ScreenUnlocker
import com.kotkit.basic.executor.screen.ScreenWaker
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
 * All actions run on-device via AccessibilityService (no VLM).
 * VLM is only called for stuck recovery (popup/dialog blocking the feed).
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

    val isCancelled = AtomicBoolean(false)

    private val humanizer = BasicHumanizer()

    fun cancel() {
        isCancelled.set(true)
        Timber.tag(TAG).i("Warmup cancel requested")
    }

    /**
     * Execute one warmup session.
     * Caller must ensure conditions are met (charging, screen off, etc.).
     */
    suspend fun execute(): WarmupResult {
        isCancelled.set(false)
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

        Timber.tag(TAG).i("[$sessionId] Starting warmup session: ${durationMinutes}min")

        try {
            // === SCREEN PREPARATION ===
            screenWaker.wake()
            // Poll for screen on (MIUI needs 500-800ms)
            if (!waitForScreenOn(2000)) {
                return WarmupResult.Failed("Screen did not turn on")
            }
            delay(500) // Settle time

            when (val unlockResult = screenUnlocker.ensureUnlocked()) {
                is com.kotkit.basic.executor.screen.UnlockResult.Success,
                is com.kotkit.basic.executor.screen.UnlockResult.AlreadyUnlocked -> {
                    // Good to go
                }
                is com.kotkit.basic.executor.screen.UnlockResult.Failed -> {
                    Timber.tag(TAG).w("[$sessionId] Unlock failed: ${unlockResult.reason}")
                    return WarmupResult.Failed("Unlock failed: ${unlockResult.reason}")
                }
                is com.kotkit.basic.executor.screen.UnlockResult.NeedUserAction -> {
                    Timber.tag(TAG).w("[$sessionId] Unlock needs user: ${unlockResult.message}")
                    return WarmupResult.Failed("Unlock needs user action: ${unlockResult.message}")
                }
                is com.kotkit.basic.executor.screen.UnlockResult.NotSupported -> {
                    Timber.tag(TAG).w("[$sessionId] Unlock not supported: ${unlockResult.message}")
                    return WarmupResult.Failed("Unlock not supported: ${unlockResult.message}")
                }
            }

            audioMuter.muteAll()

            if (isCancelled.get()) return cancelledResult(videosWatched, likesGiven, vlmCalls, startTime)

            // === LAUNCH TIKTOK ===
            val tiktokPkg = findTikTokPackage()
            if (tiktokPkg == null) {
                Timber.tag(TAG).w("[$sessionId] TikTok not installed")
                return WarmupResult.Failed("TikTok not installed")
            }

            launchTikTok(tiktokPkg)
            delay(WarmupConfig.TIKTOK_LAUNCH_WAIT_MS)

            if (isCancelled.get()) return cancelledResult(videosWatched, likesGiven, vlmCalls, startTime)

            // Verify TikTok is in foreground
            val service = TikTokAccessibilityService.getInstance()
            if (service == null) {
                return WarmupResult.Failed("Accessibility service not available")
            }
            if (!service.isTikTokInForeground()) {
                Timber.tag(TAG).w("[$sessionId] TikTok not in foreground after launch")
                return WarmupResult.Failed("TikTok not in foreground")
            }

            Timber.tag(TAG).i("[$sessionId] TikTok launched, starting feed loop")

            // === MAIN WARMUP LOOP ===
            var stuckCount = 0

            while (System.currentTimeMillis() < endTime && !isCancelled.get()) {
                // Watch current video
                val watchTime = Random.nextLong(
                    WarmupConfig.WATCH_TIME_MIN_SEC,
                    WarmupConfig.WATCH_TIME_MAX_SEC + 1
                ) * 1000
                if (!cancellableDelay(watchTime)) break
                videosWatched++

                if (isCancelled.get()) break

                // Maybe like (double-tap)
                if (Random.nextFloat() < WarmupConfig.LIKE_PROBABILITY) {
                    doubleTapToLike(service)
                    likesGiven++
                    Timber.tag(TAG).d("[$sessionId] Liked video #$videosWatched")
                }

                if (isCancelled.get()) break

                // Maybe pause
                if (Random.nextFloat() < WarmupConfig.PAUSE_PROBABILITY) {
                    val pauseTime = Random.nextLong(
                        WarmupConfig.PAUSE_MIN_SEC,
                        WarmupConfig.PAUSE_MAX_SEC + 1
                    ) * 1000
                    if (!cancellableDelay(pauseTime)) break
                }

                if (isCancelled.get()) break

                // Take pre-swipe screenshot for stuck detection
                val preScreenshot = captureScreenshotBase64()

                // Swipe up to next video
                swipeToNextVideo(service)
                delay(WarmupConfig.POST_SWIPE_SETTLE_MS)

                // Take post-swipe screenshot
                val postScreenshot = captureScreenshotBase64()

                // Stuck detection: compare screenshots
                if (preScreenshot != null && postScreenshot != null) {
                    val changed = screenshotManager.screensChanged(preScreenshot, postScreenshot)
                    if (!changed) {
                        stuckCount++
                        Timber.tag(TAG).w("[$sessionId] Screen unchanged after swipe (stuckCount=$stuckCount)")

                        if (stuckCount >= WarmupConfig.STUCK_THRESHOLD) {
                            // VLM recovery
                            Timber.tag(TAG).i("[$sessionId] Attempting VLM recovery")
                            val recovered = attemptVlmRecovery(service, postScreenshot, sessionId)
                            vlmCalls++
                            if (recovered) {
                                stuckCount = 0
                                Timber.tag(TAG).i("[$sessionId] VLM recovery successful")
                            } else {
                                Timber.tag(TAG).w("[$sessionId] VLM recovery failed, ending session")
                                break
                            }
                        }
                    } else {
                        stuckCount = 0
                    }
                }

                // Periodic checks
                if (videosWatched % WarmupConfig.FEED_CHECK_INTERVAL == 0) {
                    // Check still in TikTok
                    val svc = TikTokAccessibilityService.getInstance()
                    if (svc == null || !svc.isTikTokInForeground()) {
                        Timber.tag(TAG).w("[$sessionId] TikTok no longer in foreground")
                        // Try to relaunch once
                        launchTikTok(tiktokPkg)
                        delay(WarmupConfig.TIKTOK_LAUNCH_WAIT_MS)
                        val svc2 = TikTokAccessibilityService.getInstance()
                        if (svc2 == null || !svc2.isTikTokInForeground()) {
                            Timber.tag(TAG).w("[$sessionId] TikTok still not in foreground after relaunch")
                            break
                        }
                    }

                    // Check still charging
                    if (!deviceStateChecker.getCurrentStateSnapshot().isCharging) {
                        Timber.tag(TAG).i("[$sessionId] Phone unplugged, ending warmup")
                        break
                    }
                }
            }

            val stats = buildStats(videosWatched, likesGiven, vlmCalls, startTime)
            Timber.tag(TAG).i("[$sessionId] Warmup loop finished: $stats")

            return if (isCancelled.get()) {
                WarmupResult.Cancelled(stats)
            } else {
                WarmupResult.Success(stats)
            }

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "[$sessionId] Warmup error")
            return WarmupResult.Failed(
                e.message ?: "Unknown error",
                buildStats(videosWatched, likesGiven, vlmCalls, startTime)
            )
        } finally {
            // === CLEANUP (always runs) ===
            cleanup()
            Timber.tag(TAG).i("[$sessionId] Warmup cleanup complete")
        }
    }

    private fun findTikTokPackage(): String? {
        val pm = context.packageManager
        for (pkg in TIKTOK_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {
                // Not installed
            }
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

    private suspend fun captureScreenshotBase64(): String? {
        return when (val result = screenshotManager.capture()) {
            is CaptureResult.Success -> result.base64
            is CaptureResult.Failed -> {
                Timber.tag(TAG).w("Screenshot failed: ${result.reason}")
                null
            }
        }
    }

    /**
     * Call the server VLM to figure out how to dismiss a popup/dialog that's blocking the feed.
     * Reuses the existing /api/v1/analyze endpoint with task="warmup_feed_recovery".
     */
    private suspend fun attemptVlmRecovery(
        service: TikTokAccessibilityService,
        screenshotBase64: String,
        sessionId: String
    ): Boolean {
        return try {
            val uiTree = service.getUITree()
            val request = AnalyzeRequest(
                screenshot = screenshotBase64,
                uiTree = uiTree.toApiModel(),
                context = AnalyzeContext(
                    task = "warmup_feed_recovery",
                    sessionId = sessionId,
                    step = 0,
                    videoFilename = "",
                    caption = "",
                    previousActions = listOf("swipe_up_stuck")
                )
            )

            val response = apiService.analyze(request)
            Timber.tag(TAG).i("VLM recovery action: ${response.action} (${response.reason})")

            // Execute the recovery action
            val result = actionExecutor.execute(response) { isCancelled.get() }

            result is com.kotkit.basic.executor.accessibility.ExecutionResult.Success

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "VLM recovery failed")
            // Fallback: try pressing back
            service.pressBack()
            delay(500)
            false // Don't assume recovery worked — end session if stuck persists
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
        while (remaining > 0 && !isCancelled.get()) {
            val chunk = minOf(remaining, WarmupConfig.CANCEL_CHECK_INTERVAL_MS)
            delay(chunk)
            remaining -= chunk
        }
        return !isCancelled.get()
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
        startTime: Long
    ) = WarmupStats(
        videosWatched = videosWatched,
        likesGiven = likesGiven,
        durationSeconds = ((System.currentTimeMillis() - startTime) / 1000).toInt(),
        vlmRecoveryCalls = vlmCalls
    )

    private fun cancelledResult(
        videosWatched: Int,
        likesGiven: Int,
        vlmCalls: Int,
        startTime: Long
    ) = WarmupResult.Cancelled(buildStats(videosWatched, likesGiven, vlmCalls, startTime))
}

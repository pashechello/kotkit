package com.kotkit.basic.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber
import com.kotkit.basic.data.local.db.entities.PostStatus
import com.kotkit.basic.data.repository.PostRepository
import com.kotkit.basic.network.NetworkWorkerService
import com.kotkit.basic.network.ServiceResurrector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        // goAsync() gives ~10 seconds before ANR, use 9 seconds for safety
        private const val TIMEOUT_MS = 9_000L
    }

    @Inject
    lateinit var postRepository: PostRepository

    @Inject
    lateinit var smartScheduler: SmartScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }

        Timber.tag(TAG).i("Device boot completed, rescheduling posts")

        val pendingResult = goAsync()

        // Restart Network Worker Mode if it was active before reboot.
        // BOOT_COMPLETED receivers have foreground launch exemption, so startForegroundService() is safe.
        // All AlarmManager alarms are cleared on reboot, so we must reschedule the resurrector too.
        if (NetworkWorkerService.shouldBeRunning(context)) {
            Timber.tag(TAG).i("Worker mode was active before reboot, restarting service + resurrector")
            try {
                NetworkWorkerService.start(context)
                ServiceResurrector.schedule(context)
                Timber.tag(TAG).i("Worker service + resurrector restarted successfully after boot")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to restart worker service after boot. " +
                    "OEM=${android.os.Build.MANUFACTURER}, SDK=${android.os.Build.VERSION.SDK_INT}")
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(TIMEOUT_MS) {
                    // Get all scheduled posts
                    val scheduledPosts = postRepository.getDuePosts()
                        .filter { it.status == PostStatus.SCHEDULED }

                    Timber.tag(TAG).i("Found ${scheduledPosts.size} scheduled posts to reschedule")

                    // Reschedule each post using SmartScheduler
                    scheduledPosts.forEach { post ->
                        smartScheduler.schedulePost(post)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Timber.tag(TAG).e(e, "Timeout while rescheduling posts after boot")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error rescheduling posts after boot")
            } finally {
                pendingResult.finish()
            }
        }
    }
}

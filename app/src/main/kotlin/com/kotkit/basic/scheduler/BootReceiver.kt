package com.kotkit.basic.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kotkit.basic.data.local.db.entities.PostStatus
import com.kotkit.basic.data.repository.PostRepository
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

        Log.i(TAG, "Device boot completed, rescheduling posts")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(TIMEOUT_MS) {
                    // Get all scheduled posts
                    val scheduledPosts = postRepository.getDuePosts()
                        .filter { it.status == PostStatus.SCHEDULED }

                    Log.i(TAG, "Found ${scheduledPosts.size} scheduled posts to reschedule")

                    // Reschedule each post using SmartScheduler
                    scheduledPosts.forEach { post ->
                        smartScheduler.schedulePost(post)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "Timeout while rescheduling posts after boot", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling posts after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

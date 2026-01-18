package com.autoposter.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autoposter.data.local.db.entities.PostStatus
import com.autoposter.data.repository.PostRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    @Inject
    lateinit var postRepository: PostRepository

    @Inject
    lateinit var postScheduler: PostScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.i(TAG, "Device boot completed, rescheduling posts")

        // Use a coroutine scope for async work
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get all scheduled posts
                val scheduledPosts = postRepository.getDuePosts()
                    .filter { it.status == PostStatus.SCHEDULED }

                Log.i(TAG, "Found ${scheduledPosts.size} scheduled posts to reschedule")

                // Reschedule each post
                scheduledPosts.forEach { post ->
                    postScheduler.schedulePost(post)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling posts after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

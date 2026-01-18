package com.autoposter.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
    }

    @Inject
    lateinit var workManager: WorkManager

    override fun onReceive(context: Context, intent: Intent) {
        val postId = intent.getLongExtra("post_id", -1)
        if (postId == -1L) {
            Log.e(TAG, "Invalid post ID in alarm")
            return
        }

        Log.i(TAG, "Alarm triggered for post: $postId")

        // Use goAsync() to extend BroadcastReceiver lifetime for async work
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Check if work is already scheduled/running (non-blocking)
                val workInfos = workManager.getWorkInfosByTag("post_$postId").get()
                val isAlreadyRunning = workInfos.any { !it.state.isFinished }

                if (isAlreadyRunning) {
                    Log.i(TAG, "Post $postId is already being processed")
                    return@launch
                }

                // Schedule the work
                val data = Data.Builder()
                    .putLong("post_id", postId)
                    .build()

                val request = OneTimeWorkRequestBuilder<PostWorker>()
                    .setInputData(data)
                    .addTag("post_$postId")
                    .build()

                workManager.enqueue(request)
                Log.i(TAG, "Scheduled work for post: $postId")

            } catch (e: Exception) {
                Log.e(TAG, "Error processing alarm for post: $postId", e)
            } finally {
                // Signal that we're done with the broadcast
                pendingResult.finish()
            }
        }
    }
}

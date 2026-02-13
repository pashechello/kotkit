package com.kotkit.basic.scheduler

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.kotkit.basic.agent.PostingAgent
import timber.log.Timber

/**
 * BroadcastReceiver to handle "Stop" button press from posting notification.
 * Cancels current posting task and all scheduled posting jobs.
 */
class StopPostingReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP_POSTING = "com.kotkit.basic.ACTION_STOP_POSTING"
        private const val TAG = "StopPostingReceiver"
        private const val FOREGROUND_NOTIFICATION_ID_BASE = 10000  // Same as PostWorker
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STOP_POSTING) return

        Timber.tag(TAG).w("Stop posting requested via notification")

        // 1. Cancel the current posting task in PostingAgent
        PostingAgent.getInstance()?.cancelCurrentTask()

        // 2. Cancel all posting WorkManager jobs
        WorkManager.getInstance(context).cancelAllWorkByTag(SmartScheduler.TAG_POSTING)

        // 3. Cancel notifications if postId is provided
        val postId = intent.getLongExtra("post_id", -1L)
        if (postId != -1L) {
            // Cancel SchedulerNotifications (warning, posting, result)
            SchedulerNotifications.cancelAllNotifications(context, postId)

            // Cancel foreground notification from PostWorker
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.cancel((FOREGROUND_NOTIFICATION_ID_BASE + postId).toInt())

            Timber.tag(TAG).i("Cancelled all notifications for post $postId")
        }

        Timber.tag(TAG).i("All posting jobs cancelled")
    }
}

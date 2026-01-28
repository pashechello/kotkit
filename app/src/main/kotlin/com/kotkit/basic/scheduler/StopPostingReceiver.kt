package com.kotkit.basic.scheduler

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
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STOP_POSTING) return

        Timber.tag(TAG).w("Stop posting requested via notification")

        // 1. Cancel the current posting task in PostingAgent
        PostingAgent.getInstance()?.cancelCurrentTask()

        // 2. Cancel all posting WorkManager jobs
        WorkManager.getInstance(context).cancelAllWorkByTag(SmartScheduler.TAG_POSTING)

        Timber.tag(TAG).i("All posting jobs cancelled")
    }
}

package com.autoposter.scheduler

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.autoposter.App
import com.autoposter.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground service to ensure posting continues even if app is backgrounded
 * This is a backup mechanism - WorkManager handles most cases
 */
@AndroidEntryPoint
class PostingForegroundService : Service() {

    companion object {
        private const val TAG = "PostingForegroundService"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val postId = intent?.getLongExtra("post_id", -1) ?: -1

        val notification = NotificationCompat.Builder(this, App.CHANNEL_POSTING)
            .setContentTitle("AutoPoster Active")
            .setContentText("Preparing to post your video...")
            .setSmallIcon(R.drawable.ic_upload)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }
}

package com.autoposter.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.autoposter.data.local.db.entities.PostEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PostScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager
) {
    fun schedulePost(post: PostEntity) {
        val delay = post.scheduledTime - System.currentTimeMillis()

        if (delay <= 0) {
            // Immediate posting
            enqueueImmediately(post)
            return
        }

        val data = Data.Builder()
            .putLong("post_id", post.id)
            .build()

        val request = OneTimeWorkRequestBuilder<PostWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag("post_${post.id}")
            .build()

        workManager.enqueue(request)

        // Backup with AlarmManager for exact timing
        scheduleAlarmBackup(post)
    }

    fun cancelPost(postId: Long) {
        workManager.cancelAllWorkByTag("post_$postId")
        cancelAlarmBackup(postId)
    }

    fun reschedulePost(post: PostEntity) {
        cancelPost(post.id)
        schedulePost(post)
    }

    private fun enqueueImmediately(post: PostEntity) {
        val data = Data.Builder()
            .putLong("post_id", post.id)
            .build()

        val request = OneTimeWorkRequestBuilder<PostWorker>()
            .setInputData(data)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag("post_${post.id}")
            .build()

        workManager.enqueue(request)
    }

    private fun scheduleAlarmBackup(post: PostEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("post_id", post.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            post.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            post.scheduledTime,
            pendingIntent
        )
    }

    private fun cancelAlarmBackup(postId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            postId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let { alarmManager.cancel(it) }
    }
}

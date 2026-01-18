package com.autoposter.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.autoposter.data.local.db.entities.PostEntity
import com.autoposter.data.local.preferences.EncryptedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SmartScheduler - Schedules posts with advance warning notifications.
 *
 * Flow:
 * 1. User schedules post for 14:00
 * 2. At 13:50 - Notification: "Публикация через 10 минут. Оставьте телефон в покое"
 * 3. At 13:59 - Notification: "Публикация через 1 минуту!"
 * 4. At 14:00 - Check if phone is free:
 *    - YES → Start publishing
 *    - NO → Wait 5 minutes and retry (up to 3 times)
 * 5. After publishing - Notification: "Готово! Видео опубликовано"
 *
 * Thread-safe: Uses @Volatile for config and safe request code calculation.
 */
@Singleton
class SmartScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    private val deviceStateChecker: DeviceStateChecker,
    private val preferences: EncryptedPreferences
) {
    companion object {
        private const val TAG = "SmartScheduler"

        // Intent actions
        const val ACTION_WARNING = "com.autoposter.ACTION_WARNING"
        const val ACTION_FINAL_WARNING = "com.autoposter.ACTION_FINAL_WARNING"
        const val ACTION_START_POSTING = "com.autoposter.ACTION_START_POSTING"
        const val ACTION_CANCEL_POST = "com.autoposter.ACTION_CANCEL_POST"
        const val ACTION_RETRY_POST = "com.autoposter.ACTION_RETRY_POST"

        // Extras
        const val EXTRA_POST_ID = "post_id"
        const val EXTRA_WARNING_MINUTES = "warning_minutes"
        const val EXTRA_RETRY_COUNT = "retry_count"

        // Request code offsets to avoid collisions
        private const val WARNING_REQUEST_OFFSET = 10_000
        private const val POSTING_REQUEST_OFFSET = 20_000
        private const val RETRY_REQUEST_OFFSET = 30_000

        // Max value for safe request code calculation
        private const val REQUEST_CODE_MODULO = 9_000
    }

    // Thread-safe config
    @Volatile
    private var config = ScheduleConfig()

    /**
     * Get context for use in other classes (e.g., SchedulerReceiver).
     */
    fun getContext(): Context = context

    /**
     * Check if exact alarms can be scheduled.
     * On Android 12+, requires SCHEDULE_EXACT_ALARM permission.
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /**
     * Schedule a post with advance warning notifications.
     */
    fun schedulePost(post: PostEntity) {
        val scheduledTime = post.scheduledTime
        val now = System.currentTimeMillis()

        if (scheduledTime <= now) {
            // Immediate posting - check device state first
            Log.i(TAG, "Post ${post.id}: Scheduling for immediate execution")
            schedulePostingAlarm(post.id, now + 5000) // 5 second delay
            return
        }

        Log.i(TAG, "Post ${post.id}: Scheduling for $scheduledTime (in ${(scheduledTime - now) / 60000} minutes)")

        // Schedule warning notifications
        val currentConfig = config // Read once for consistency
        for (warningMinutes in currentConfig.warningMinutesBefore) {
            val warningTime = scheduledTime - (warningMinutes * 60_000)
            if (warningTime > now) {
                scheduleWarningAlarm(post.id, warningTime, warningMinutes)
            }
        }

        // Schedule the actual posting
        schedulePostingAlarm(post.id, scheduledTime)
    }

    /**
     * Cancel all alarms for a post.
     */
    fun cancelPost(postId: Long) {
        Log.i(TAG, "Cancelling post: $postId")

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val currentConfig = config // Read once for consistency

        // Cancel warning alarms
        for (warningMinutes in currentConfig.warningMinutesBefore) {
            val intent = Intent(context, SchedulerReceiver::class.java).apply {
                action = ACTION_WARNING
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                calculateRequestCode(WARNING_REQUEST_OFFSET, postId, warningMinutes),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let { alarmManager.cancel(it) }
        }

        // Cancel posting alarm
        val postingIntent = Intent(context, SchedulerReceiver::class.java).apply {
            action = ACTION_START_POSTING
        }
        val postingPendingIntent = PendingIntent.getBroadcast(
            context,
            calculateRequestCode(POSTING_REQUEST_OFFSET, postId),
            postingIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        postingPendingIntent?.let { alarmManager.cancel(it) }

        // Cancel any retry alarms
        val retryIntent = Intent(context, SchedulerReceiver::class.java).apply {
            action = ACTION_RETRY_POST
        }
        for (retry in 0..currentConfig.maxBusyRetries) {
            val retryPendingIntent = PendingIntent.getBroadcast(
                context,
                calculateRequestCode(RETRY_REQUEST_OFFSET, postId, retry),
                retryIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            retryPendingIntent?.let { alarmManager.cancel(it) }
        }

        // Cancel WorkManager jobs
        workManager.cancelAllWorkByTag("post_$postId")
    }

    /**
     * Reschedule a post.
     */
    fun reschedulePost(post: PostEntity) {
        cancelPost(post.id)
        schedulePost(post)
    }

    /**
     * Handle warning alarm - show notification.
     */
    fun onWarningAlarm(postId: Long, warningMinutes: Int) {
        Log.i(TAG, "Warning alarm for post $postId: $warningMinutes minutes before")

        SchedulerNotifications.showWarningNotification(
            context,
            postId,
            warningMinutes
        )
    }

    /**
     * Handle posting alarm - check device state and start or wait.
     */
    fun onPostingAlarm(postId: Long, retryCount: Int = 0) {
        Log.i(TAG, "Posting alarm for post $postId (retry: $retryCount)")

        val currentConfig = config // Read once for consistency
        val checkResult = deviceStateChecker.canPublish(currentConfig.publishConditions)

        when (checkResult) {
            is PublishCheckResult.Ready -> {
                Log.i(TAG, "Device ready, starting post $postId")
                startPosting(postId)
            }
            is PublishCheckResult.NotReady -> {
                val reasons = checkResult.reasons.joinToString(", ")
                Log.w(TAG, "Device not ready for post $postId: $reasons")

                if (retryCount < currentConfig.maxBusyRetries) {
                    // Schedule retry
                    scheduleRetryAlarm(postId, retryCount + 1)
                    SchedulerNotifications.showDeviceBusyNotification(
                        context,
                        postId,
                        checkResult.reasons,
                        currentConfig.busyWaitMinutes
                    )
                } else {
                    // Max retries reached
                    Log.e(TAG, "Max retries reached for post $postId")
                    SchedulerNotifications.showFailedNotification(
                        context,
                        postId,
                        "Phone was busy, publishing postponed"
                    )
                }
            }
        }
    }

    /**
     * Start the actual posting process.
     */
    private fun startPosting(postId: Long) {
        SchedulerNotifications.showStartingNotification(context, postId)

        val data = Data.Builder()
            .putLong("post_id", postId)
            .build()

        val request = OneTimeWorkRequestBuilder<PostWorker>()
            .setInputData(data)
            .addTag("post_$postId")
            .build()

        workManager.enqueue(request)
    }

    private fun scheduleWarningAlarm(postId: Long, triggerTime: Long, warningMinutes: Int) {
        val intent = Intent(context, SchedulerReceiver::class.java).apply {
            action = ACTION_WARNING
            putExtra(EXTRA_POST_ID, postId)
            putExtra(EXTRA_WARNING_MINUTES, warningMinutes)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            calculateRequestCode(WARNING_REQUEST_OFFSET, postId, warningMinutes),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleExactAlarm(triggerTime, pendingIntent)
        Log.d(TAG, "Scheduled ${warningMinutes}min warning for post $postId at $triggerTime")
    }

    private fun schedulePostingAlarm(postId: Long, triggerTime: Long) {
        val intent = Intent(context, SchedulerReceiver::class.java).apply {
            action = ACTION_START_POSTING
            putExtra(EXTRA_POST_ID, postId)
            putExtra(EXTRA_RETRY_COUNT, 0)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            calculateRequestCode(POSTING_REQUEST_OFFSET, postId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleExactAlarm(triggerTime, pendingIntent)
        Log.d(TAG, "Scheduled posting for post $postId at $triggerTime")
    }

    private fun scheduleRetryAlarm(postId: Long, retryCount: Int) {
        val currentConfig = config
        val retryTime = System.currentTimeMillis() + (currentConfig.busyWaitMinutes * 60_000)

        val intent = Intent(context, SchedulerReceiver::class.java).apply {
            action = ACTION_RETRY_POST
            putExtra(EXTRA_POST_ID, postId)
            putExtra(EXTRA_RETRY_COUNT, retryCount)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            calculateRequestCode(RETRY_REQUEST_OFFSET, postId, retryCount),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleExactAlarm(retryTime, pendingIntent)
        Log.d(TAG, "Scheduled retry #$retryCount for post $postId at $retryTime")
    }

    /**
     * Schedule exact alarm with Android 12+ compatibility.
     * Falls back to inexact alarm if exact alarm permission not granted.
     */
    private fun scheduleExactAlarm(triggerTime: Long, pendingIntent: PendingIntent) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                // Fallback to inexact alarm
                Log.w(TAG, "Cannot schedule exact alarms - using inexact alarm as fallback")
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    /**
     * Calculate request code that won't overflow.
     * Uses hash-based approach to avoid integer overflow with large postId values.
     */
    private fun calculateRequestCode(offset: Int, postId: Long, suffix: Int = 0): Int {
        // Use modulo to keep values in safe range, and add offset and suffix
        val postIdPart = ((postId.hashCode() and 0x7FFFFFFF) % REQUEST_CODE_MODULO)
        return offset + postIdPart + suffix
    }

    /**
     * Update scheduler configuration.
     */
    fun updateConfig(newConfig: ScheduleConfig) {
        config = newConfig
    }

    /**
     * Get current configuration.
     */
    fun getConfig(): ScheduleConfig = config
}

package com.kotkit.basic.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Data
import timber.log.Timber
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.kotkit.basic.data.local.db.entities.PostEntity
import com.kotkit.basic.data.local.preferences.EncryptedPreferences
import com.kotkit.basic.sound.MeowSoundService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val preferences: EncryptedPreferences,
    private val meowSoundService: MeowSoundService
) {
    // Managed scope for sound playback - lives as long as the singleton
    private val soundScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "SmartScheduler"

        // Intent actions
        const val ACTION_WARNING = "com.kotkit.basic.ACTION_WARNING"
        const val ACTION_FINAL_WARNING = "com.kotkit.basic.ACTION_FINAL_WARNING"
        const val ACTION_START_POSTING = "com.kotkit.basic.ACTION_START_POSTING"
        const val ACTION_CANCEL_POST = "com.kotkit.basic.ACTION_CANCEL_POST"
        const val ACTION_RETRY_POST = "com.kotkit.basic.ACTION_RETRY_POST"
        const val ACTION_FORCE_PUBLISH = "com.kotkit.basic.ACTION_FORCE_PUBLISH"

        // WorkManager tag for all posting jobs (used for emergency stop)
        const val TAG_POSTING = "kotkit_posting"

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
            Timber.tag(TAG).i("Post ${post.id}: Scheduling for immediate execution")
            schedulePostingAlarm(post.id, now + 5000) // 5 second delay
            return
        }

        Timber.tag(TAG).i("Post ${post.id}: Scheduling for $scheduledTime (in ${(scheduledTime - now) / 60000} minutes)")

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
        Timber.tag(TAG).i("Cancelling post: $postId")

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
     * Handle warning alarm - show notification and play sound.
     */
    fun onWarningAlarm(postId: Long, warningMinutes: Int) {
        Timber.tag(TAG).i("Warning alarm for post $postId: $warningMinutes minutes before")

        // Play warning meow sound
        soundScope.launch {
            meowSoundService.playWarning()
        }

        SchedulerNotifications.showWarningNotification(
            context,
            postId,
            warningMinutes
        )
    }

    /**
     * Handle posting alarm - check device state and start or wait.
     * Shows notification only on first attempt, subsequent retries are silent.
     */
    fun onPostingAlarm(postId: Long, retryCount: Int = 0) {
        Timber.tag(TAG).i("Posting alarm for post $postId (retry: $retryCount)")

        val currentConfig = config // Read once for consistency
        val checkResult = deviceStateChecker.canPublish(currentConfig.publishConditions)

        when (checkResult) {
            is PublishCheckResult.Ready -> {
                Timber.tag(TAG).i("Device ready, starting post $postId")
                // Cancel busy notification if it was shown
                SchedulerNotifications.cancelAllNotifications(context, postId)
                startPosting(postId)
            }
            is PublishCheckResult.NotReady -> {
                val reasons = checkResult.reasons.joinToString(", ")
                Timber.tag(TAG).w("Device not ready for post $postId: $reasons")

                if (retryCount < currentConfig.maxBusyRetries) {
                    // Schedule silent retry
                    scheduleRetryAlarm(postId, retryCount + 1)

                    // Show notification only on first attempt
                    if (retryCount == 0) {
                        // Play meow sound
                        soundScope.launch {
                            meowSoundService.playUI()
                        }
                        SchedulerNotifications.showDeviceBusyNotification(context, postId)
                    } else {
                        Timber.tag(TAG).d("Silent retry #$retryCount for post $postId")
                    }
                } else {
                    // Max retries reached
                    Timber.tag(TAG).e("Max retries reached for post $postId")
                    SchedulerNotifications.showFailedNotification(
                        context,
                        postId,
                        "Телефон был занят слишком долго"
                    )
                }
            }
        }
    }

    /**
     * Force publish immediately, ignoring device state.
     * Called when user taps "Publish now" button on busy notification.
     */
    fun forcePublish(postId: Long) {
        Timber.tag(TAG).i("Force publishing post $postId (user requested)")

        // Cancel any pending retry alarms
        val currentConfig = config
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (retry in 0..currentConfig.maxBusyRetries) {
            val retryIntent = Intent(context, SchedulerReceiver::class.java).apply {
                action = ACTION_RETRY_POST
            }
            val retryPendingIntent = PendingIntent.getBroadcast(
                context,
                calculateRequestCode(RETRY_REQUEST_OFFSET, postId, retry),
                retryIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            retryPendingIntent?.let { alarmManager.cancel(it) }
        }

        // Cancel busy notification and start posting
        SchedulerNotifications.cancelAllNotifications(context, postId)
        startPosting(postId)
    }

    /**
     * Start the actual posting process.
     */
    private fun startPosting(postId: Long) {
        // Play starting meow sound
        soundScope.launch {
            meowSoundService.playStarting()
        }

        SchedulerNotifications.showStartingNotification(context, postId)

        val data = Data.Builder()
            .putLong("post_id", postId)
            .build()

        val request = OneTimeWorkRequestBuilder<PostWorker>()
            .setInputData(data)
            .addTag("post_$postId")
            .addTag(TAG_POSTING)  // Common tag for emergency stop
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
        Timber.tag(TAG).d("Scheduled ${warningMinutes}min warning for post $postId at $triggerTime")
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
        Timber.tag(TAG).d("Scheduled posting for post $postId at $triggerTime")
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
        Timber.tag(TAG).d("Scheduled retry #$retryCount for post $postId at $retryTime")
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
                Timber.tag(TAG).w("Cannot schedule exact alarms - using inexact alarm as fallback")
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

package com.kotkit.basic.network

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.kotkit.basic.ui.MainActivity
import timber.log.Timber

/**
 * AlarmManager-based watchdog that resurrects NetworkWorkerService if killed by OEM.
 *
 * Scheduling strategy:
 * - Aggressive OEMs (Xiaomi, Samsung, etc.) → always setAlarmClock()
 *   (MIUI/OneUI can't defer alarm clocks)
 * - Android 12+ (any OEM) → setAlarmClock()
 *   (needed for foreground service start exemption — without it,
 *   startForegroundService() from BroadcastReceiver throws
 *   ForegroundServiceStartNotAllowedException)
 * - Android 11 and below, non-aggressive OEMs → setExactAndAllowWhileIdle()
 *   (no alarm icon, battery-efficient, no foreground restriction on old Android)
 *
 * The check itself is extremely lightweight (~50ms CPU wake):
 * 1. Is the service supposed to be running? (SharedPreferences read)
 * 2. Is it actually running? (static boolean check)
 * 3. If dead → restart. If alive → do nothing.
 * 4. Reschedule next check.
 */
object ServiceResurrector {
    private const val TAG = "ServiceResurrector"
    private const val INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
    private const val REQUEST_CODE = 9001
    const val ACTION_RESURRECT = "com.kotkit.basic.ACTION_RESURRECT_SERVICE"

    private val AGGRESSIVE_OEMS = setOf(
        "xiaomi", "redmi", "poco",        // MIUI / HyperOS (5/5 badness)
        "samsung",                         // One UI (3/5 badness)
        "huawei", "honor",                // EMUI / MagicOS (4/5 badness)
        "oppo", "realme", "oneplus",      // ColorOS / OxygenOS (4/5 badness)
        "vivo", "iqoo",                   // FuntouchOS / OriginOS (3/5 badness)
        "meizu",                           // FlymeOS
        "asus"                             // ZenUI
    )

    fun schedule(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = createPendingIntent(context)
            val triggerTime = System.currentTimeMillis() + INTERVAL_MS
            val useAlarmClock = needsAlarmClock()

            if (useAlarmClock) {
                val showIntent = PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerTime, showIntent),
                    pendingIntent
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                )
            }

            Timber.tag(TAG).i("Resurrector scheduled: type=${if (useAlarmClock) "ALARM_CLOCK" else "EXACT"}, " +
                "interval=${INTERVAL_MS / 60000}min, OEM=${Build.MANUFACTURER}, SDK=${Build.VERSION.SDK_INT}")
        } catch (e: SecurityException) {
            // OEM revoked exact alarm permission — resurrector chain dies.
            // FallbackPollingWorker (WorkManager) is still active as backup.
            Timber.tag(TAG).e(e, "CRITICAL: Cannot schedule resurrector — exact alarm permission denied! " +
                "OEM=${Build.MANUFACTURER}, SDK=${Build.VERSION.SDK_INT}. FallbackPollingWorker is still active.")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to schedule resurrector: OEM=${Build.MANUFACTURER}")
        }
    }

    fun cancel(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(createPendingIntent(context))
            Timber.tag(TAG).i("Resurrector cancelled")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to cancel resurrector")
        }
    }

    /**
     * Whether to use setAlarmClock() instead of setExactAndAllowWhileIdle().
     *
     * Required when:
     * - Aggressive OEM that kills background processes (always)
     * - Android 12+ (API 31+) — startForegroundService() from BroadcastReceiver
     *   requires a foreground launch exemption, which only setAlarmClock() provides
     */
    private fun needsAlarmClock(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        return isAggressiveOem()
    }

    fun isAggressiveOem(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase().orEmpty()
        return AGGRESSIVE_OEMS.any { manufacturer.contains(it) }
    }

    private fun createPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ResurrectorReceiver::class.java).apply {
            action = ACTION_RESURRECT
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * Receives alarm and checks if NetworkWorkerService needs resurrection.
 *
 * This receiver fires every 15 minutes. If the service was killed by OEM
 * but worker mode is still active (SharedPreferences flag), it restarts the service.
 * Battery impact is negligible: ~50ms CPU wake per check.
 */
class ResurrectorReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ServiceResurrector"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val shouldBeRunning = NetworkWorkerService.shouldBeRunning(context)
        val isAlive = NetworkWorkerService.isServiceAlive
        Timber.tag(TAG).i("Resurrector fired: pid=${Process.myPid()}, shouldBeRunning=$shouldBeRunning, isServiceAlive=$isAlive")

        if (!shouldBeRunning) {
            Timber.tag(TAG).i("Resurrector terminating: shouldBeRunning=false, alarm chain will not be rescheduled")
            return
        }

        if (!isAlive) {
            val delay = RestartThrottler.recordStartAndGetDelay(context)
            if (delay > 0) {
                Timber.tag(TAG).w("SERVICE RESURRECTION THROTTLED: too many rapid restarts, " +
                    "delaying ${delay / 1000}s to prevent crash state. " +
                    "OEM=${Build.MANUFACTURER}, SDK=${Build.VERSION.SDK_INT}")
            } else {
                Timber.tag(TAG).w("SERVICE RESURRECTION: Service was killed by system, restarting now! " +
                    "OEM=${Build.MANUFACTURER}, SDK=${Build.VERSION.SDK_INT}")
                try {
                    NetworkWorkerService.start(context)
                    Timber.tag(TAG).i("SERVICE RESURRECTION: startForegroundService() called successfully")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "SERVICE RESURRECTION FAILED: Could not restart service. " +
                        "OEM=${Build.MANUFACTURER}, SDK=${Build.VERSION.SDK_INT}")
                }
            }
        } else {
            Timber.tag(TAG).d("Service alive, no resurrection needed")
        }

        // Chain-schedule next check
        ServiceResurrector.schedule(context)
    }
}

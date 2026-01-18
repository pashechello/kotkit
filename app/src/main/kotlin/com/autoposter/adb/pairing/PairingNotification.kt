package com.autoposter.adb.pairing

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.autoposter.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * PairingNotification shows a notification with RemoteInput for entering
 * the 6-digit Wireless Debugging pairing code.
 *
 * This allows users to enter the code without leaving the Settings screen,
 * making the pairing process much smoother.
 *
 * Flow:
 * 1. User opens Wireless Debugging settings and taps "Pair device with pairing code"
 * 2. App shows notification with "Enter 6-digit code" input field
 * 3. User types code in notification
 * 4. PairingReceiver receives code and triggers pairing
 * 5. Notification updates to show success/failure
 */
object PairingNotification {

    private const val TAG = "PairingNotification"

    const val CHANNEL_ID = "pairing_channel"
    const val NOTIFICATION_ID = 2001

    const val ACTION_PAIRING_CODE = "com.autoposter.ACTION_PAIRING_CODE"
    const val ACTION_CANCEL_PAIRING = "com.autoposter.ACTION_CANCEL_PAIRING"
    const val EXTRA_PAIRING_CODE = "pairing_code"

    /**
     * Create notification channel (required for Android 8+).
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wireless Debugging Pairing",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for ADB Wireless Debugging pairing"
                setShowBadge(true)
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Show notification prompting user to enter pairing code.
     */
    fun showPairingPrompt(context: Context, host: String, port: Int) {
        if (!canPostNotifications(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted")
            return
        }

        createChannel(context)

        // RemoteInput for the 6-digit code
        val remoteInput = RemoteInput.Builder(EXTRA_PAIRING_CODE)
            .setLabel("Enter 6-digit code")
            .build()

        // Intent for receiving the code
        val replyIntent = Intent(context, PairingReceiver::class.java).apply {
            action = ACTION_PAIRING_CODE
        }

        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // Reply action with RemoteInput
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_key,
            "Enter Code",
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()

        // Cancel action
        val cancelIntent = Intent(context, PairingReceiver::class.java).apply {
            action = ACTION_CANCEL_PAIRING
        }

        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID + 1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelAction = NotificationCompat.Action.Builder(
            R.drawable.ic_cancel,
            "Cancel",
            cancelPendingIntent
        ).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pair with Wireless Debugging")
            .setContentText("Enter the 6-digit code from Settings")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Open Settings → Developer Options → Wireless Debugging\n" +
                "Tap 'Pair device with pairing code'\n" +
                "Enter the code shown on screen"
            ))
            .addAction(replyAction)
            .addAction(cancelAction)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(false)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "Pairing prompt notification shown")
    }

    /**
     * Show pairing in progress notification.
     */
    fun showPairingProgress(context: Context) {
        if (!canPostNotifications(context)) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pairing...")
            .setContentText("Connecting to Wireless Debugging")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Show pairing success notification.
     */
    fun showPairingSuccess(context: Context) {
        if (!canPostNotifications(context)) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pairing Successful")
            .setContentText("Advanced Mode is now available")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "Pairing success notification shown")
    }

    /**
     * Show pairing error notification.
     */
    fun showPairingError(context: Context, message: String) {
        if (!canPostNotifications(context)) return

        // Retry action
        val retryIntent = Intent(context, PairingReceiver::class.java).apply {
            action = "com.autoposter.ACTION_RETRY_PAIRING"
        }

        val retryPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID + 2,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pairing Failed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .addAction(R.drawable.ic_schedule, "Retry", retryPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "Pairing error notification shown: $message")
    }

    /**
     * Dismiss pairing notification.
     */
    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

/**
 * BroadcastReceiver for handling pairing notification actions.
 */
@AndroidEntryPoint
class PairingReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PairingReceiver"
        private const val WORK_TIMEOUT_MS = 30_000L
    }

    @Inject
    lateinit var wirelessPairingManager: WirelessPairingManager

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received action: ${intent.action}")

        when (intent.action) {
            PairingNotification.ACTION_PAIRING_CODE -> {
                handlePairingCode(context, intent)
            }
            PairingNotification.ACTION_CANCEL_PAIRING -> {
                handleCancelPairing(context)
            }
            "com.autoposter.ACTION_RETRY_PAIRING" -> {
                handleRetryPairing(context)
            }
        }
    }

    private fun handlePairingCode(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val pairingCode = remoteInput?.getCharSequence(PairingNotification.EXTRA_PAIRING_CODE)?.toString()

        if (pairingCode.isNullOrBlank()) {
            Log.w(TAG, "Empty pairing code received")
            return
        }

        // Validate 6-digit code
        if (!pairingCode.matches(Regex("\\d{6}"))) {
            PairingNotification.showPairingError(context, "Invalid code. Please enter 6 digits.")
            return
        }

        Log.i(TAG, "Pairing code received: ${pairingCode.take(2)}****")

        // Show progress
        PairingNotification.showPairingProgress(context)

        // Use goAsync for extended work
        val pendingResult = goAsync()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                withTimeout(WORK_TIMEOUT_MS) {
                    val result = wirelessPairingManager.completeWithCode(pairingCode)

                    if (result.isSuccess) {
                        PairingNotification.showPairingSuccess(context)
                    } else {
                        val error = result.exceptionOrNull()?.message ?: "Unknown error"
                        PairingNotification.showPairingError(context, error)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pairing failed", e)
                PairingNotification.showPairingError(context, e.message ?: "Pairing failed")
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private fun handleCancelPairing(context: Context) {
        Log.i(TAG, "Pairing cancelled by user")
        wirelessPairingManager.cancelPairing()
        PairingNotification.dismiss(context)
    }

    private fun handleRetryPairing(context: Context) {
        Log.i(TAG, "Retrying pairing")
        PairingNotification.dismiss(context)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val result = wirelessPairingManager.startPairing()
                if (result.isSuccess) {
                    // Show prompt for code
                    val state = wirelessPairingManager.pairingState.value
                    if (state is WirelessPairingManager.PairingState.WaitingForCode) {
                        PairingNotification.showPairingPrompt(context, state.host, state.port)
                    }
                }
            } finally {
                scope.cancel()
            }
        }
    }
}

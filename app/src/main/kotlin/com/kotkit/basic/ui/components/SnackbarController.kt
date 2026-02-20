package com.kotkit.basic.ui.components

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber

/**
 * Notification type determines the visual style of the banner.
 */
enum class NotificationType {
    INFO,
    SUCCESS,
    ERROR,
    WARNING
}

/**
 * How long the notification stays visible before auto-dismissing.
 */
enum class NotificationDuration {
    SHORT,  // 3 seconds
    LONG    // 6 seconds
}

/**
 * Global notification controller. All screens post messages here;
 * MainActivity consumes them via AppNotificationBanner at the top of the screen.
 *
 * IMPORTANT: [messages] must be collected by exactly ONE collector (MainActivity).
 * Channel.receiveAsFlow() splits messages between multiple collectors.
 *
 * WARNING: [onAction] must NOT capture Activity or View references directly —
 * use applicationContext instead. The singleton outlives any Activity.
 */
object SnackbarController {

    data class Message(
        val text: String,
        val actionLabel: String? = null,
        val duration: NotificationDuration = NotificationDuration.SHORT,
        val type: NotificationType = NotificationType.INFO,
        val onAction: (() -> Unit)? = null
    )

    private val channel = Channel<Message>(Channel.BUFFERED)
    val messages = channel.receiveAsFlow()

    fun show(
        message: String,
        actionLabel: String? = null,
        duration: NotificationDuration = NotificationDuration.SHORT,
        type: NotificationType = NotificationType.INFO,
        onAction: (() -> Unit)? = null
    ) {
        val result = channel.trySend(Message(message, actionLabel, duration, type, onAction))
        if (result.isFailure) {
            Timber.w("SnackbarController buffer full, dropped: $message")
        }
    }

    fun showError(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        show(message, actionLabel, NotificationDuration.LONG, NotificationType.ERROR, onAction)
    }

    fun showSuccess(message: String) {
        show(message, type = NotificationType.SUCCESS)
    }
}

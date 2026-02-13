package com.kotkit.basic.network

import android.util.Log
import java.util.Collections

/**
 * Singleton coordinator to prevent concurrent acceptance of the same task
 * from multiple sources (FCM handler, crash recovery, etc.).
 *
 * The backend handles idempotent task acceptance via CAS (compare-and-swap),
 * so duplicate calls would be safe but wasteful. This coordinator prevents
 * redundant API calls within the same app process.
 */
object TaskAcceptanceCoordinator {
    private const val TAG = "TaskAcceptCoord"

    // Thread-safe set tracking tasks currently being accepted
    private val acceptingTasks: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /**
     * Try to mark a task as being accepted.
     *
     * @param taskId The task ID to accept
     * @return true if this caller should proceed with acceptance, false if another caller is already handling it
     */
    fun tryStartAccepting(taskId: String): Boolean {
        val added = acceptingTasks.add(taskId)
        if (!added) {
            Log.d(TAG, "Task $taskId is already being accepted by another caller")
        }
        return added
    }

    /**
     * Mark a task acceptance as complete (success or failure).
     * Must be called in a finally block after tryStartAccepting returns true.
     *
     * @param taskId The task ID that finished acceptance
     */
    fun finishAccepting(taskId: String) {
        acceptingTasks.remove(taskId)
        Log.d(TAG, "Task $taskId acceptance finished")
    }

    /**
     * Check if a task is currently being accepted.
     */
    fun isAccepting(taskId: String): Boolean = acceptingTasks.contains(taskId)
}

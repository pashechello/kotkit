package com.kotkit.basic.network

import android.content.Context
import timber.log.Timber
import androidx.work.WorkManager
import com.kotkit.basic.data.repository.NetworkTaskRepository
import com.kotkit.basic.data.repository.WorkerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central manager for worker mode state.
 *
 * Coordinates between UI, service, workers, and repositories.
 * Single source of truth for worker mode status.
 */
@Singleton
class WorkerStateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workerRepository: WorkerRepository,
    private val networkTaskRepository: NetworkTaskRepository,
    private val workManager: WorkManager
) {
    companion object {
        private const val TAG = "WorkerStateManager"
    }

    private val _isActivating = MutableStateFlow(false)
    val isActivating: StateFlow<Boolean> = _isActivating.asStateFlow()

    /**
     * Observe worker active state from repository.
     */
    fun isWorkerActiveFlow(): Flow<Boolean> =
        workerRepository.isWorkerActiveFlow().map { it ?: false }

    /**
     * Check if worker mode is currently active.
     */
    suspend fun isWorkerActive(): Boolean =
        workerRepository.isWorkerActive()

    /**
     * Start worker mode.
     *
     * 1. Notify backend
     * 2. Start foreground service
     * 3. Schedule heartbeat worker
     */
    suspend fun startWorkerMode(): Result<Unit> {
        Timber.tag(TAG).i("Starting worker mode")
        _isActivating.value = true

        return try {
            // 1. Notify backend
            val result = workerRepository.toggleWorkerMode(true)
            if (result.isFailure) {
                throw result.exceptionOrNull() ?: Exception("Failed to toggle worker mode")
            }

            // 2. Start foreground service
            NetworkWorkerService.start(context)

            Timber.tag(TAG).i("Worker mode started successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start worker mode")
            Result.failure(e)
        } finally {
            _isActivating.value = false
        }
    }

    /**
     * Stop worker mode.
     *
     * 1. Notify backend
     * 2. Stop foreground service
     * 3. Cancel heartbeat worker
     */
    suspend fun stopWorkerMode(): Result<Unit> {
        Timber.tag(TAG).i("Stopping worker mode")
        _isActivating.value = true

        return try {
            // 1. Notify backend (best effort)
            try {
                workerRepository.toggleWorkerMode(false)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to notify backend about worker stop")
            }

            // 2. Stop foreground service
            NetworkWorkerService.stop(context)

            Timber.tag(TAG).i("Worker mode stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to stop worker mode")
            Result.failure(e)
        } finally {
            _isActivating.value = false
        }
    }

    /**
     * Toggle worker mode.
     */
    suspend fun toggleWorkerMode(): Result<Unit> {
        return if (isWorkerActive()) {
            stopWorkerMode()
        } else {
            startWorkerMode()
        }
    }

    /**
     * Claim a task and start execution.
     */
    suspend fun claimTask(taskId: String): Result<Unit> {
        Timber.tag(TAG).i("Claiming task: $taskId")

        return try {
            // Claim task on server
            val result = networkTaskRepository.claimTask(taskId)
            if (result.isFailure) {
                throw result.exceptionOrNull() ?: Exception("Failed to claim task")
            }

            // Ensure worker mode is active
            if (!isWorkerActive()) {
                Timber.tag(TAG).w("Worker mode not active, starting it")
                startWorkerMode()
            }

            Timber.tag(TAG).i("Task claimed successfully: $taskId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to claim task: $taskId")
            Result.failure(e)
        }
    }

    /**
     * Get active tasks count.
     */
    fun getActiveTasksCountFlow(): Flow<Int> =
        networkTaskRepository.getActiveCountFlow()

    /**
     * Sync any pending operations.
     */
    suspend fun syncPending(): Int {
        return try {
            networkTaskRepository.syncPendingTasks()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to sync pending tasks")
            0
        }
    }
}

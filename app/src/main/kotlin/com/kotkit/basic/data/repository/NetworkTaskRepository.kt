package com.kotkit.basic.data.repository

import android.util.Log
import com.kotkit.basic.data.local.db.NetworkTaskDao
import com.kotkit.basic.data.local.db.entities.NetworkTaskEntity
import com.kotkit.basic.data.local.db.entities.NetworkTaskStatus
import com.kotkit.basic.data.local.db.entities.SyncStatus
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.CompleteTaskRequest
import com.kotkit.basic.data.remote.api.models.FailTaskRequest
import com.kotkit.basic.data.remote.api.models.TaskProgressRequest
import com.kotkit.basic.data.remote.api.models.TaskResponse
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkTaskRepository @Inject constructor(
    private val networkTaskDao: NetworkTaskDao,
    private val apiService: ApiService
) {
    companion object {
        private const val TAG = "NetworkTaskRepository"
    }

    // ========================================================================
    // Flow Queries (reactive UI updates)
    // ========================================================================

    fun getActiveTasksFlow(): Flow<List<NetworkTaskEntity>> =
        networkTaskDao.getActiveTasksFlow()

    fun getCompletedTasksFlow(): Flow<List<NetworkTaskEntity>> =
        networkTaskDao.getCompletedTasksFlow()

    fun getActiveCountFlow(): Flow<Int> =
        networkTaskDao.getActiveCountFlow()

    // ========================================================================
    // Task Operations
    // ========================================================================

    suspend fun getActiveTasks(): List<NetworkTaskEntity> =
        networkTaskDao.getActiveTasks()

    suspend fun getTaskById(id: String): NetworkTaskEntity? =
        networkTaskDao.getById(id)

    suspend fun getNextScheduledTask(): NetworkTaskEntity? =
        networkTaskDao.getNextScheduledTask(System.currentTimeMillis())

    suspend fun getPendingSyncTasks(): List<NetworkTaskEntity> =
        networkTaskDao.getPendingSyncTasks()

    suspend fun countCompletedToday(): Int {
        val startOfDay = getStartOfDay()
        return networkTaskDao.countCompletedToday(startOfDay)
    }

    // ========================================================================
    // API + Local: Fetch Available Tasks
    // ========================================================================

    suspend fun fetchAvailableTasks(limit: Int = 10, categoryId: String? = null): Result<List<TaskResponse>> {
        return try {
            val response = apiService.getAvailableTasks(limit, categoryId)
            Log.i(TAG, "Fetched ${response.tasks.size} available tasks")
            Result.success(response.tasks)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch available tasks", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // API + Local: Claim Task
    // ========================================================================

    suspend fun claimTask(taskId: String): Result<NetworkTaskEntity> {
        return try {
            val response = apiService.claimTask(taskId)
            val entity = response.toEntity()
            networkTaskDao.insert(entity)
            Log.i(TAG, "Claimed task $taskId, scheduled for ${entity.scheduledFor}")
            Result.success(entity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to claim task $taskId", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // API + Local: Heartbeat
    // ========================================================================

    suspend fun sendHeartbeat(taskId: String): Result<Unit> {
        return try {
            val response = apiService.sendHeartbeat(taskId)
            networkTaskDao.updateHeartbeat(taskId, response.lastHeartbeat)
            Log.d(TAG, "Heartbeat sent for task $taskId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send heartbeat for $taskId", e)
            // Mark for retry
            networkTaskDao.updateSyncStatus(taskId, SyncStatus.PENDING_HEARTBEAT)
            Result.failure(e)
        }
    }

    // ========================================================================
    // API + Local: Get Video URL
    // ========================================================================

    suspend fun getVideoUrl(taskId: String): Result<VideoDownloadInfo> {
        return try {
            val response = apiService.getVideoUrl(taskId)
            Log.i(TAG, "Got video URL for task $taskId, size=${response.videoSizeBytes}")
            Result.success(VideoDownloadInfo(
                url = response.videoUrl,
                hash = response.videoHash,
                sizeBytes = response.videoSizeBytes,
                expiresAt = response.expiresAt,
                supportsResume = response.supportResume
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get video URL for $taskId", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // API + Local: Update Progress
    // ========================================================================

    suspend fun updateProgress(
        taskId: String,
        status: String,
        progressPercent: Int? = null,
        message: String? = null
    ): Result<Unit> {
        return try {
            apiService.updateTaskProgress(
                taskId,
                TaskProgressRequest(status, progressPercent, message)
            )
            networkTaskDao.updateStatus(taskId, status)
            Log.d(TAG, "Updated progress: $taskId -> $status ($progressPercent%)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update progress for $taskId", e)
            // Still update local status for offline support
            networkTaskDao.updateStatus(taskId, status)
            Result.failure(e)
        }
    }

    // ========================================================================
    // Local: Update Download Progress
    // ========================================================================

    suspend fun updateDownloadProgress(taskId: String, localPath: String?, progress: Int) {
        networkTaskDao.updateDownloadProgress(taskId, localPath, progress)
    }

    // ========================================================================
    // API + Local: Complete Task
    // ========================================================================

    suspend fun completeTask(
        taskId: String,
        tiktokVideoId: String,
        tiktokPostUrl: String?,
        proofScreenshotPath: String?,
        proofScreenshotB64: String?
    ): Result<Unit> {
        // First, save locally with pending sync
        networkTaskDao.markCompleted(
            id = taskId,
            videoId = tiktokVideoId,
            postUrl = tiktokPostUrl,
            screenshotPath = proofScreenshotPath,
            syncStatus = SyncStatus.PENDING_COMPLETION
        )

        return try {
            apiService.completeTask(
                taskId,
                CompleteTaskRequest(
                    tiktokVideoId = tiktokVideoId,
                    tiktokPostUrl = tiktokPostUrl,
                    proofScreenshotB64 = proofScreenshotB64
                )
            )
            networkTaskDao.updateSyncStatus(taskId, SyncStatus.SYNCED)
            Log.i(TAG, "Task $taskId completed and synced")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync completion for $taskId (will retry)", e)
            // Keep pending sync status for later retry
            Result.failure(e)
        }
    }

    // ========================================================================
    // API + Local: Fail Task
    // ========================================================================

    suspend fun failTask(
        taskId: String,
        errorMessage: String,
        errorType: String?,
        screenshotB64: String?
    ): Result<Unit> {
        // First, save locally
        networkTaskDao.updateStatus(
            id = taskId,
            status = NetworkTaskStatus.FAILED,
            error = errorMessage,
            errorType = errorType,
            syncStatus = SyncStatus.PENDING_FAILURE
        )

        return try {
            apiService.failTask(
                taskId,
                FailTaskRequest(
                    errorMessage = errorMessage,
                    errorType = errorType,
                    screenshotB64 = screenshotB64
                )
            )
            networkTaskDao.updateSyncStatus(taskId, SyncStatus.SYNCED)
            Log.i(TAG, "Task $taskId failed and synced: $errorMessage")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync failure for $taskId (will retry)", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // Sync: Retry Pending Operations
    // ========================================================================

    suspend fun syncPendingTasks(): Int {
        val pending = networkTaskDao.getPendingSyncTasks()
        var synced = 0

        for (task in pending) {
            try {
                when (task.syncStatus) {
                    SyncStatus.PENDING_COMPLETION -> {
                        apiService.completeTask(
                            task.id,
                            CompleteTaskRequest(
                                tiktokVideoId = task.tiktokVideoId ?: "",
                                tiktokPostUrl = task.tiktokPostUrl,
                                proofScreenshotB64 = null // Already uploaded or lost
                            )
                        )
                        networkTaskDao.updateSyncStatus(task.id, SyncStatus.SYNCED)
                        synced++
                    }
                    SyncStatus.PENDING_FAILURE -> {
                        apiService.failTask(
                            task.id,
                            FailTaskRequest(
                                errorMessage = task.errorMessage ?: "Unknown error",
                                errorType = task.errorType,
                                screenshotB64 = null
                            )
                        )
                        networkTaskDao.updateSyncStatus(task.id, SyncStatus.SYNCED)
                        synced++
                    }
                    SyncStatus.PENDING_HEARTBEAT -> {
                        apiService.sendHeartbeat(task.id)
                        networkTaskDao.updateSyncStatus(task.id, SyncStatus.SYNCED)
                        synced++
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync task ${task.id}: ${e.message}")
            }
        }

        if (synced > 0) {
            Log.i(TAG, "Synced $synced pending tasks")
        }
        return synced
    }

    // ========================================================================
    // Cleanup
    // ========================================================================

    suspend fun deleteVideoFile(taskId: String) {
        val task = networkTaskDao.getById(taskId) ?: return
        task.videoLocalPath?.let { path ->
            try {
                val file = File(path)
                if (file.exists() && file.delete()) {
                    Log.i(TAG, "Deleted video file for task $taskId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete video file: $path", e)
            }
            Unit
        }
        networkTaskDao.updateDownloadProgress(taskId, null, 0)
    }

    suspend fun cleanupOldTasks(daysOld: Int = 7) {
        val cutoff = System.currentTimeMillis() - (daysOld * 24 * 60 * 60 * 1000L)
        networkTaskDao.deleteOldCompletedTasks(cutoff)
        Log.i(TAG, "Cleaned up tasks older than $daysOld days")
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun getStartOfDay(): Long {
        return java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun TaskResponse.toEntity(): NetworkTaskEntity {
        return NetworkTaskEntity(
            id = id,
            campaignId = campaignId,
            videoS3Key = videoS3Key,
            videoHash = videoHash,
            videoSizeBytes = videoSizeBytes,
            caption = caption,
            status = status,
            priceUsd = priceUsd,
            assignedAt = assignedAt,
            scheduledFor = scheduledFor,
            lastHeartbeat = lastHeartbeat,
            startedAt = startedAt,
            completedAt = completedAt,
            tiktokVideoId = tiktokVideoId,
            tiktokPostUrl = tiktokPostUrl,
            errorMessage = errorMessage,
            errorType = errorType,
            retryCount = retryCount,
            syncStatus = SyncStatus.SYNCED
        )
    }
}

data class VideoDownloadInfo(
    val url: String,
    val hash: String,
    val sizeBytes: Long,
    val expiresAt: Long,
    val supportsResume: Boolean
)

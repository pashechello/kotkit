package com.kotkit.basic.data.repository

import timber.log.Timber
import com.kotkit.basic.data.local.db.NetworkTaskDao
import com.kotkit.basic.data.local.db.entities.NetworkTaskEntity
import com.kotkit.basic.data.local.db.entities.NetworkTaskStatus
import com.kotkit.basic.data.local.db.entities.SyncStatus
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.CompleteTaskRequest
import com.kotkit.basic.data.remote.api.models.CompletedTasksResponse
import com.kotkit.basic.data.remote.api.models.FailTaskRequest
import com.kotkit.basic.data.remote.api.models.TaskProgressRequest
import com.kotkit.basic.data.remote.api.models.TaskResponse
import com.kotkit.basic.data.remote.api.models.UrlSubmissionRequest
import com.kotkit.basic.data.remote.api.models.UrlSubmissionResponse
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

    suspend fun getLastCompletedAt(): Long? =
        networkTaskDao.getLastCompletedAt()

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
            Timber.tag(TAG).i("Fetched ${response.tasks.size} available tasks")
            Result.success(response.tasks)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to fetch available tasks")
            Result.failure(e)
        }
    }

    // ========================================================================
    // API + Local: Claim Task (legacy pull-based)
    // ========================================================================

    suspend fun claimTask(taskId: String): Result<NetworkTaskEntity> {
        return try {
            val response = apiService.claimTask(taskId)
            val entity = response.toEntity()
            networkTaskDao.insert(entity)
            Timber.tag(TAG).i("Claimed task $taskId, scheduled for ${entity.scheduledFor}")
            Result.success(entity)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to claim task $taskId")
            Result.failure(e)
        }
    }

    // ========================================================================
    // API + Local: Accept Reserved Task (push-based)
    // ========================================================================

    /**
     * Accept a task that was reserved for this worker by the backend.
     * This is part of the push-based queue system.
     *
     * @param taskId Task ID from FCM notification
     * @return Result with the accepted task entity
     */
    suspend fun acceptTask(taskId: String): Result<NetworkTaskEntity> {
        return try {
            val response = apiService.acceptTask(taskId)
            val entity = response.toEntity()
            networkTaskDao.insert(entity)
            Timber.tag(TAG).i("Accepted reserved task $taskId, scheduled for ${entity.scheduledFor}")
            Result.success(entity)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to accept task $taskId")
            Result.failure(e)
        }
    }

    /**
     * Get tasks reserved for this worker (for crash recovery on startup).
     */
    suspend fun getReservedTasks(): Result<List<TaskResponse>> {
        return try {
            val response = apiService.getReservedTasks()
            Timber.tag(TAG).i("Got ${response.tasks.size} reserved tasks")
            Result.success(response.tasks)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get reserved tasks")
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
            Timber.tag(TAG).d("Heartbeat sent for task $taskId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to send heartbeat for $taskId")
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
            Timber.tag(TAG).i("Got video URL for task $taskId, size=${response.videoSizeBytes}")
            Result.success(VideoDownloadInfo(
                url = response.videoUrl,
                hash = response.videoHash,
                sizeBytes = response.videoSizeBytes,
                expiresAt = response.expiresAt,
                supportsResume = response.supportResume
            ))
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get video URL for $taskId")
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
            Timber.tag(TAG).d("Updated progress: $taskId -> $status ($progressPercent%)")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to update progress for $taskId")
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
        tiktokVideoId: String?,
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
            Timber.tag(TAG).i("Task $taskId completed and synced")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to sync completion for $taskId (will retry)")
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
            Timber.tag(TAG).i("Task $taskId failed and synced: $errorMessage")
            Result.success(Unit)
        } catch (e: Exception) {
            // If server returns 403/404, the task was reassigned — no point retrying.
            if (e is retrofit2.HttpException && e.code() in listOf(403, 404)) {
                Timber.tag(TAG).w("Task $taskId rejected by server (${e.code()}) on fail, removing ghost task")
                removeStaleTask(taskId)
                Result.success(Unit)
            } else {
                Timber.tag(TAG).e(e, "Failed to sync failure for $taskId (will retry)")
                Result.failure(e)
            }
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
                // If server returns 403/404, the task was reassigned or doesn't exist.
                // Stop retrying — remove from local DB to prevent infinite 403 loop.
                if (e is retrofit2.HttpException && e.code() in listOf(403, 404)) {
                    Timber.tag(TAG).w("Task ${task.id} rejected by server (${e.code()}) during sync, removing ghost task")
                    removeStaleTask(task.id)
                } else {
                    Timber.tag(TAG).w("Failed to sync task ${task.id}: ${e.message}")
                }
            }
        }

        if (synced > 0) {
            Timber.tag(TAG).i("Synced $synced pending tasks")
        }
        return synced
    }

    // ========================================================================
    // Stale Task Cleanup
    // ========================================================================

    /**
     * Remove a task that server no longer recognizes (400/403/404 on heartbeat).
     * Cancels any pending WorkManager jobs and deletes from local DB.
     */
    suspend fun removeStaleTask(taskId: String) {
        try {
            networkTaskDao.deleteById(taskId)
            Timber.tag(TAG).i("Removed stale task $taskId from local DB")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to remove stale task $taskId")
        }
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
                    Timber.tag(TAG).i("Deleted video file for task $taskId")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to delete video file: $path")
            }
            Unit
        }
        networkTaskDao.updateDownloadProgress(taskId, null, 0)
    }

    suspend fun cleanupOldTasks(daysOld: Int = 7) {
        val cutoff = System.currentTimeMillis() - (daysOld * 24 * 60 * 60 * 1000L)
        networkTaskDao.deleteOldCompletedTasks(cutoff)
        Timber.tag(TAG).i("Cleaned up tasks older than $daysOld days")
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
            priceRub = priceRub,
            assignedAt = assignedAt?.toMillisIfSeconds(),
            scheduledFor = scheduledFor?.toMillisIfSeconds(),
            lastHeartbeat = null,
            startedAt = startedAt?.toMillisIfSeconds(),
            completedAt = completedAt?.toMillisIfSeconds(),
            tiktokVideoId = tiktokVideoId,
            tiktokPostUrl = tiktokPostUrl,
            errorMessage = errorMessage,
            retryCount = retryCount,
            videoThumbnailUrl = videoThumbnailUrl,
            syncStatus = SyncStatus.SYNCED
        )
    }

    // ========================================================================
    // Verification URL Submission
    // ========================================================================

    suspend fun getCompletedTasksWithVerification(
        limit: Int = 20,
        offset: Int = 0
    ): Result<CompletedTasksResponse> {
        return try {
            val response = apiService.getCompletedTasksWithVerification(limit, offset)
            Result.success(response)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to fetch completed tasks with verification")
            Result.failure(e)
        }
    }

    suspend fun submitVerificationUrl(
        verificationId: String,
        tiktokVideoUrl: String
    ): Result<UrlSubmissionResponse> {
        return try {
            val response = apiService.submitVerificationUrl(
                verificationId,
                UrlSubmissionRequest(tiktokVideoUrl)
            )
            Result.success(response)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to submit verification URL")
            Result.failure(e)
        }
    }

    /**
     * Convert UNIX timestamp from seconds to milliseconds if needed.
     * Server sends timestamps in seconds; Android/Room uses milliseconds.
     * Timestamps in seconds are < 10B until year ~2286.
     */
    private fun Long.toMillisIfSeconds(): Long =
        if (this < 10_000_000_000L) this * 1000 else this
}

data class VideoDownloadInfo(
    val url: String,
    val hash: String,
    val sizeBytes: Long,
    val expiresAt: Long,
    val supportsResume: Boolean
)

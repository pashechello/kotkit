package com.kotkit.basic.data.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kotkit.basic.data.local.db.entities.NetworkTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkTaskDao {

    // ========================================================================
    // Queries - Flow (reactive)
    // ========================================================================

    @Query("SELECT * FROM network_tasks ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<NetworkTaskEntity>>

    @Query("SELECT * FROM network_tasks WHERE status = :status ORDER BY scheduledFor ASC")
    fun getByStatusFlow(status: String): Flow<List<NetworkTaskEntity>>

    @Query("""
        SELECT * FROM network_tasks
        WHERE status IN ('assigned', 'downloading', 'posting', 'verifying')
        ORDER BY scheduledFor ASC
    """)
    fun getActiveTasksFlow(): Flow<List<NetworkTaskEntity>>

    @Query("""
        SELECT * FROM network_tasks
        WHERE status IN ('completed', 'failed', 'expired')
        ORDER BY completedAt DESC
    """)
    fun getCompletedTasksFlow(): Flow<List<NetworkTaskEntity>>

    @Query("SELECT COUNT(*) FROM network_tasks WHERE status IN ('assigned', 'downloading', 'posting', 'verifying')")
    fun getActiveCountFlow(): Flow<Int>

    // ========================================================================
    // Queries - Suspend (one-shot)
    // ========================================================================

    @Query("SELECT * FROM network_tasks WHERE id = :id")
    suspend fun getById(id: String): NetworkTaskEntity?

    @Query("SELECT * FROM network_tasks WHERE status = :status ORDER BY scheduledFor ASC")
    suspend fun getByStatus(status: String): List<NetworkTaskEntity>

    @Query("""
        SELECT * FROM network_tasks
        WHERE status IN ('assigned', 'downloading', 'posting', 'verifying')
        ORDER BY scheduledFor ASC
    """)
    suspend fun getActiveTasks(): List<NetworkTaskEntity>

    @Query("""
        SELECT * FROM network_tasks
        WHERE status = 'assigned'
        AND (scheduledFor IS NULL OR scheduledFor <= :currentTime)
        ORDER BY scheduledFor ASC
        LIMIT 1
    """)
    suspend fun getNextScheduledTask(currentTime: Long): NetworkTaskEntity?

    @Query("SELECT * FROM network_tasks WHERE syncStatus != 'synced' ORDER BY updatedAt ASC")
    suspend fun getPendingSyncTasks(): List<NetworkTaskEntity>

    @Query("SELECT COUNT(*) FROM network_tasks WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("""
        SELECT COUNT(*) FROM network_tasks
        WHERE status = 'completed'
        AND completedAt >= :startOfDay
    """)
    suspend fun countCompletedToday(startOfDay: Long): Int

    @Query("SELECT MAX(completedAt) FROM network_tasks WHERE status = 'completed'")
    suspend fun getLastCompletedAt(): Long?

    // ========================================================================
    // Insert / Update / Delete
    // ========================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: NetworkTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<NetworkTaskEntity>)

    @Update
    suspend fun update(task: NetworkTaskEntity)

    @Query("""
        UPDATE network_tasks
        SET status = :status,
            errorMessage = :error,
            errorType = :errorType,
            syncStatus = :syncStatus,
            updatedAt = :time
        WHERE id = :id
    """)
    suspend fun updateStatus(
        id: String,
        status: String,
        error: String? = null,
        errorType: String? = null,
        syncStatus: String = "synced",
        time: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE network_tasks
        SET lastHeartbeat = :timestamp,
            updatedAt = :timestamp
        WHERE id = :id
    """)
    suspend fun updateHeartbeat(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("""
        UPDATE network_tasks
        SET videoLocalPath = :path,
            downloadProgress = :progress,
            updatedAt = :time
        WHERE id = :id
    """)
    suspend fun updateDownloadProgress(
        id: String,
        path: String?,
        progress: Int,
        time: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE network_tasks
        SET tiktokVideoId = :videoId,
            tiktokPostUrl = :postUrl,
            proofScreenshotPath = :screenshotPath,
            status = 'completed',
            completedAt = :completedAt,
            syncStatus = :syncStatus,
            updatedAt = :time
        WHERE id = :id
    """)
    suspend fun markCompleted(
        id: String,
        videoId: String,
        postUrl: String?,
        screenshotPath: String?,
        completedAt: Long = System.currentTimeMillis(),
        syncStatus: String = "pending_completion",
        time: Long = System.currentTimeMillis()
    )

    @Query("UPDATE network_tasks SET syncStatus = :syncStatus WHERE id = :id")
    suspend fun updateSyncStatus(id: String, syncStatus: String)

    @Delete
    suspend fun delete(task: NetworkTaskEntity)

    @Query("DELETE FROM network_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM network_tasks WHERE status IN ('completed', 'failed', 'expired') AND completedAt < :before")
    suspend fun deleteOldCompletedTasks(before: Long)

    // ========================================================================
    // Cleanup
    // ========================================================================

    @Query("DELETE FROM network_tasks")
    suspend fun deleteAll()
}

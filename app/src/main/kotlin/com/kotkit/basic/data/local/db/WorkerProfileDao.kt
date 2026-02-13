package com.kotkit.basic.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kotkit.basic.data.local.db.entities.WorkerProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkerProfileDao {

    // ========================================================================
    // Queries
    // ========================================================================

    @Query("SELECT * FROM worker_profile LIMIT 1")
    fun getProfileFlow(): Flow<WorkerProfileEntity?>

    @Query("SELECT * FROM worker_profile LIMIT 1")
    suspend fun getProfile(): WorkerProfileEntity?

    @Query("SELECT isActive FROM worker_profile LIMIT 1")
    fun isActiveFlow(): Flow<Boolean?>

    @Query("SELECT isActive FROM worker_profile LIMIT 1")
    suspend fun isActive(): Boolean?

    // ========================================================================
    // Insert / Update
    // ========================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: WorkerProfileEntity)

    @Update
    suspend fun update(profile: WorkerProfileEntity)

    @Query("""
        UPDATE worker_profile
        SET isActive = :isActive,
            updatedAt = :time
        WHERE id = :id
    """)
    suspend fun updateActiveStatus(id: String, isActive: Boolean, time: Long = System.currentTimeMillis())

    @Query("""
        UPDATE worker_profile
        SET totalEarnedRub = :totalEarned,
            pendingBalanceRub = :pending,
            availableBalanceRub = :available,
            updatedAt = :time
        WHERE id = :id
    """)
    suspend fun updateBalance(
        id: String,
        totalEarned: Float,
        pending: Float,
        available: Float,
        time: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE worker_profile
        SET totalTasks = :total,
            completedTasks = :completed,
            failedTasks = :failed,
            successRate = :successRate,
            updatedAt = :time
        WHERE id = :id
    """)
    suspend fun updateStats(
        id: String,
        total: Int,
        completed: Int,
        failed: Int,
        successRate: Float,
        time: Long = System.currentTimeMillis()
    )

    @Query("UPDATE worker_profile SET lastSyncedAt = :time WHERE id = :id")
    suspend fun updateLastSynced(id: String, time: Long = System.currentTimeMillis())

    // ========================================================================
    // Delete
    // ========================================================================

    @Query("DELETE FROM worker_profile")
    suspend fun deleteAll()
}

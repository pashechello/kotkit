package com.kotkit.basic.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kotkit.basic.data.local.db.entities.WorkerEarningEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkerEarningDao {

    // ========================================================================
    // Queries
    // ========================================================================

    @Query("SELECT * FROM worker_earnings ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<WorkerEarningEntity>>

    @Query("SELECT * FROM worker_earnings WHERE status = :status ORDER BY createdAt DESC")
    fun getByStatusFlow(status: String): Flow<List<WorkerEarningEntity>>

    @Query("SELECT * FROM worker_earnings ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getAll(limit: Int, offset: Int): List<WorkerEarningEntity>

    @Query("SELECT * FROM worker_earnings WHERE id = :id")
    suspend fun getById(id: String): WorkerEarningEntity?

    @Query("SELECT SUM(amountRub) FROM worker_earnings WHERE status = :status")
    suspend fun sumByStatus(status: String): Float?

    @Query("""
        SELECT SUM(amountRub) FROM worker_earnings
        WHERE createdAt >= :startOfDay
    """)
    suspend fun sumTodayEarnings(startOfDay: Long): Float?

    @Query("SELECT COUNT(*) FROM worker_earnings")
    suspend fun count(): Int

    // ========================================================================
    // Insert / Update
    // ========================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(earning: WorkerEarningEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(earnings: List<WorkerEarningEntity>)

    @Query("UPDATE worker_earnings SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    // ========================================================================
    // Delete
    // ========================================================================

    @Query("DELETE FROM worker_earnings WHERE createdAt < :before")
    suspend fun deleteOldEarnings(before: Long)

    @Query("DELETE FROM worker_earnings")
    suspend fun deleteAll()
}

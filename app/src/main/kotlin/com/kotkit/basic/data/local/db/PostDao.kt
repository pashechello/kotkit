package com.kotkit.basic.data.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.kotkit.basic.data.local.db.entities.PostEntity
import com.kotkit.basic.data.local.db.entities.PostStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface PostDao {

    @Query("SELECT * FROM posts ORDER BY scheduledTime ASC")
    fun getAllFlow(): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE status = :status ORDER BY scheduledTime ASC")
    fun getByStatusFlow(status: PostStatus): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE status IN (:statuses) ORDER BY scheduledTime ASC")
    fun getByStatusesFlow(statuses: List<PostStatus>): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE id = :id")
    suspend fun getById(id: Long): PostEntity?

    @Query("SELECT * FROM posts WHERE status = :status ORDER BY scheduledTime ASC")
    suspend fun getByStatus(status: PostStatus): List<PostEntity>

    @Query("SELECT * FROM posts WHERE scheduledTime <= :time AND status = :status")
    suspend fun getDuePosts(time: Long, status: PostStatus = PostStatus.SCHEDULED): List<PostEntity>

    @Insert
    suspend fun insert(post: PostEntity): Long

    @Update
    suspend fun update(post: PostEntity)

    @Query("UPDATE posts SET status = :status, errorMessage = :error, updatedAt = :time WHERE id = :id")
    suspend fun updateStatus(
        id: Long,
        status: PostStatus,
        error: String? = null,
        time: Long = System.currentTimeMillis()
    )

    @Delete
    suspend fun delete(post: PostEntity)

    @Query("DELETE FROM posts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM posts WHERE status = :status")
    suspend fun countByStatus(status: PostStatus): Int

    @Query("SELECT COUNT(*) FROM posts")
    fun countAllFlow(): Flow<Int>

    /**
     * Recover posts stuck in POSTING status after app crash.
     * Posts that have been POSTING since before [cutoffTime] are marked as FAILED.
     */
    @Query("UPDATE posts SET status = 'FAILED', errorMessage = 'Приложение было закрыто во время публикации', updatedAt = :now WHERE status = 'POSTING' AND updatedAt < :cutoffTime")
    suspend fun recoverStuckPostingPosts(cutoffTime: Long, now: Long = System.currentTimeMillis())
}

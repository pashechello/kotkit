package com.kotkit.basic.data.repository

import android.util.Log
import com.kotkit.basic.data.local.db.PostDao
import com.kotkit.basic.data.local.db.entities.PostEntity
import com.kotkit.basic.data.local.db.entities.PostStatus
import com.kotkit.basic.executor.screenshot.ThumbnailGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PostRepository @Inject constructor(
    private val postDao: PostDao,
    private val thumbnailGenerator: ThumbnailGenerator
) {
    fun getAllPosts(): Flow<List<PostEntity>> = postDao.getAllFlow()

    fun getScheduledPosts(): Flow<List<PostEntity>> =
        postDao.getByStatusFlow(PostStatus.SCHEDULED)

    fun getQueuePosts(): Flow<List<PostEntity>> =
        postDao.getByStatusesFlow(listOf(PostStatus.SCHEDULED, PostStatus.POSTING))

    fun getCompletedPosts(): Flow<List<PostEntity>> =
        postDao.getByStatusFlow(PostStatus.COMPLETED)

    fun getFailedPosts(): Flow<List<PostEntity>> =
        postDao.getByStatusesFlow(listOf(PostStatus.FAILED, PostStatus.NEEDS_ACTION))

    suspend fun getById(id: Long): PostEntity? = postDao.getById(id)

    suspend fun getDuePosts(): List<PostEntity> =
        postDao.getDuePosts(System.currentTimeMillis())

    suspend fun createPost(
        videoPath: String,
        caption: String,
        scheduledTime: Long
    ): Long = withContext(Dispatchers.IO) {
        // Generate thumbnail before creating post
        val thumbnailPath = thumbnailGenerator.generateThumbnail(videoPath)
        if (thumbnailPath != null) {
            Log.i("PostRepository", "Generated thumbnail: $thumbnailPath")
        } else {
            Log.w("PostRepository", "Failed to generate thumbnail for: $videoPath")
        }

        val post = PostEntity(
            videoPath = videoPath,
            thumbnailPath = thumbnailPath,
            caption = caption,
            scheduledTime = scheduledTime,
            status = PostStatus.SCHEDULED
        )
        postDao.insert(post)
    }

    suspend fun updateStatus(
        id: Long,
        status: PostStatus,
        errorMessage: String? = null
    ) {
        postDao.updateStatus(id, status, errorMessage)
    }

    suspend fun cancelPost(id: Long) {
        postDao.updateStatus(id, PostStatus.CANCELLED)
    }

    suspend fun deletePost(id: Long) {
        // Get post to delete video and thumbnail files first
        val post = postDao.getById(id)
        if (post != null) {
            deleteVideoFile(post.videoPath)
            thumbnailGenerator.deleteThumbnail(post.thumbnailPath)
        }
        postDao.deleteById(id)
    }

    /**
     * Delete video file from internal storage.
     * Called after successful posting or when manually deleting a post.
     */
    suspend fun deleteVideoFile(videoPath: String) {
        try {
            val videoFile = File(videoPath)
            if (videoFile.exists()) {
                val deleted = videoFile.delete()
                if (deleted) {
                    Log.i("PostRepository", "✓ Deleted video file: ${videoFile.name}")
                } else {
                    Log.w("PostRepository", "Failed to delete video file: $videoPath")
                }
            } else {
                Log.d("PostRepository", "Video file already deleted: $videoPath")
            }
        } catch (e: Exception) {
            Log.e("PostRepository", "Error deleting video file: $videoPath", e)
            // Don't throw - deletion failure shouldn't block other operations
        }
    }

    suspend fun reschedulePost(id: Long, newTime: Long) {
        val post = postDao.getById(id) ?: return
        postDao.update(post.copy(
            scheduledTime = newTime,
            status = PostStatus.SCHEDULED,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun getScheduledCount(): Int = postDao.countByStatus(PostStatus.SCHEDULED)

    suspend fun getCompletedCount(): Int = postDao.countByStatus(PostStatus.COMPLETED)

    suspend fun getFailedCount(): Int = postDao.countByStatus(PostStatus.FAILED)

    fun getTotalCount(): Flow<Int> = postDao.countAllFlow()
}

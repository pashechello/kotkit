package com.autoposter.data.repository

import com.autoposter.data.local.db.PostDao
import com.autoposter.data.local.db.entities.PostEntity
import com.autoposter.data.local.db.entities.PostStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PostRepository @Inject constructor(
    private val postDao: PostDao
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
    ): Long {
        val post = PostEntity(
            videoPath = videoPath,
            caption = caption,
            scheduledTime = scheduledTime,
            status = PostStatus.SCHEDULED
        )
        return postDao.insert(post)
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
        postDao.deleteById(id)
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

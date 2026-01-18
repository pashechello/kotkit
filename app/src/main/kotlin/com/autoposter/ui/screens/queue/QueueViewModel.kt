package com.autoposter.ui.screens.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autoposter.data.local.db.entities.PostEntity
import com.autoposter.data.repository.PostRepository
import com.autoposter.scheduler.PostScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val postRepository: PostRepository,
    private val postScheduler: PostScheduler
) : ViewModel() {

    val queuePosts: StateFlow<List<PostEntity>> = postRepository.getQueuePosts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun cancelPost(postId: Long) {
        viewModelScope.launch {
            postRepository.cancelPost(postId)
            postScheduler.cancelPost(postId)
        }
    }

    fun deletePost(postId: Long) {
        viewModelScope.launch {
            postScheduler.cancelPost(postId)
            postRepository.deletePost(postId)
        }
    }

    fun reschedulePost(postId: Long, newTime: Long) {
        viewModelScope.launch {
            postRepository.reschedulePost(postId, newTime)
            val post = postRepository.getById(postId)
            if (post != null) {
                postScheduler.reschedulePost(post)
            }
        }
    }
}

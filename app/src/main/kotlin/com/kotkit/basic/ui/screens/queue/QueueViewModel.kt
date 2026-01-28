package com.kotkit.basic.ui.screens.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotkit.basic.data.local.db.entities.PostEntity
import com.kotkit.basic.data.repository.PostRepository
import com.kotkit.basic.scheduler.SmartScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val postRepository: PostRepository,
    private val smartScheduler: SmartScheduler
) : ViewModel() {

    val queuePosts: StateFlow<List<PostEntity>> = postRepository.getQueuePosts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun cancelPost(postId: Long) {
        viewModelScope.launch {
            postRepository.cancelPost(postId)
            smartScheduler.cancelPost(postId)
        }
    }

    fun deletePost(postId: Long) {
        viewModelScope.launch {
            smartScheduler.cancelPost(postId)
            postRepository.deletePost(postId)
        }
    }

    fun reschedulePost(postId: Long, newTime: Long) {
        viewModelScope.launch {
            postRepository.reschedulePost(postId, newTime)
            val post = postRepository.getById(postId)
            if (post != null) {
                smartScheduler.reschedulePost(post)
            }
        }
    }
}

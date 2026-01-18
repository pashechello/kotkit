package com.autoposter.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autoposter.data.local.db.entities.PostEntity
import com.autoposter.data.local.db.entities.PostStatus
import com.autoposter.data.repository.PostRepository
import com.autoposter.data.repository.SettingsRepository
import com.autoposter.executor.accessibility.TikTokAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val postRepository: PostRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            combine(
                postRepository.getQueuePosts(),
                postRepository.getCompletedPosts(),
                postRepository.getTotalCount()
            ) { queue, completed, total ->
                Triple(queue, completed, total)
            }.collect { (queue, completed, total) ->
                _uiState.update { state ->
                    state.copy(
                        scheduledPosts = queue.filter { it.status == PostStatus.SCHEDULED },
                        recentPosts = completed.take(5),
                        scheduledCount = queue.count { it.status == PostStatus.SCHEDULED },
                        completedCount = completed.size,
                        totalCount = total,
                        isAccessibilityEnabled = TikTokAccessibilityService.isServiceEnabled(),
                        hasUnlockCredentials = settingsRepository.hasUnlockCredentials(),
                        isLoading = false
                    )
                }
            }
        }
    }

    fun refreshAccessibilityStatus() {
        _uiState.update { state ->
            state.copy(isAccessibilityEnabled = TikTokAccessibilityService.isServiceEnabled())
        }
    }

    fun deletePost(postId: Long) {
        viewModelScope.launch {
            postRepository.deletePost(postId)
        }
    }
}

data class HomeUiState(
    val scheduledPosts: List<PostEntity> = emptyList(),
    val recentPosts: List<PostEntity> = emptyList(),
    val scheduledCount: Int = 0,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val isAccessibilityEnabled: Boolean = false,
    val hasUnlockCredentials: Boolean = false,
    val isLoading: Boolean = true
)

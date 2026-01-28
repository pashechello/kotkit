package com.kotkit.basic.ui.screens.home

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotkit.basic.data.local.db.entities.PostEntity
import com.kotkit.basic.data.local.db.entities.PostStatus
import com.kotkit.basic.data.repository.PostRepository
import com.kotkit.basic.data.repository.SettingsRepository
import com.kotkit.basic.executor.accessibility.TikTokAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val application: Application,
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
                postRepository.getFailedPosts(),
                postRepository.getCompletedPosts(),
                postRepository.getTotalCount()
            ) { queue, failed, completed, total ->
                HomeDataBundle(queue, failed, completed, total)
            }.collect { (queue, failed, completed, total) ->
                _uiState.update { state ->
                    state.copy(
                        scheduledPosts = queue.filter { it.status == PostStatus.SCHEDULED },
                        failedPosts = failed,
                        recentPosts = completed.take(5),
                        scheduledCount = queue.count { it.status == PostStatus.SCHEDULED },
                        failedCount = failed.size,
                        completedCount = completed.size,
                        totalCount = total,
                        isAccessibilityEnabled = TikTokAccessibilityService.isServiceEnabled(application),
                        hasUnlockCredentials = settingsRepository.hasUnlockCredentials(),
                        isLoading = false
                    )
                }
            }
        }
    }

    private data class HomeDataBundle(
        val queue: List<PostEntity>,
        val failed: List<PostEntity>,
        val completed: List<PostEntity>,
        val total: Int
    )

    fun refreshAccessibilityStatus() {
        _uiState.update { state ->
            state.copy(isAccessibilityEnabled = TikTokAccessibilityService.isServiceEnabled(application))
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }

            // Small delay for visual feedback
            kotlinx.coroutines.delay(400)

            // Update all non-reactive states
            _uiState.update { state ->
                state.copy(
                    isAccessibilityEnabled = TikTokAccessibilityService.isServiceEnabled(application),
                    hasUnlockCredentials = settingsRepository.hasUnlockCredentials(),
                    isRefreshing = false
                )
            }
        }
    }

    fun deletePost(postId: Long) {
        viewModelScope.launch {
            postRepository.deletePost(postId)
        }
    }

    fun retryPostNow(postId: Long) {
        viewModelScope.launch {
            // Reschedule to 1 minute from now
            val newTime = System.currentTimeMillis() + 60 * 1000
            postRepository.reschedulePost(postId, newTime)
        }
    }
}

data class HomeUiState(
    val scheduledPosts: List<PostEntity> = emptyList(),
    val failedPosts: List<PostEntity> = emptyList(),
    val recentPosts: List<PostEntity> = emptyList(),
    val scheduledCount: Int = 0,
    val failedCount: Int = 0,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val isAccessibilityEnabled: Boolean = false,
    val hasUnlockCredentials: Boolean = false,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false
)

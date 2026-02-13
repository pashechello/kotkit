package com.kotkit.basic.ui.screens.home

import android.app.Application
import android.app.NotificationManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.kotkit.basic.agent.PostingAgent
import com.kotkit.basic.data.local.db.entities.PostEntity
import com.kotkit.basic.data.local.db.entities.PostStatus
import com.kotkit.basic.data.repository.PostRepository
import com.kotkit.basic.data.repository.SettingsRepository
import com.kotkit.basic.executor.accessibility.TikTokAccessibilityService
import com.kotkit.basic.scheduler.SchedulerNotifications
import com.kotkit.basic.scheduler.SmartScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val application: Application,
    private val postRepository: PostRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Track post being cancelled for optimistic UI update
    private val _cancellingPostId = MutableStateFlow<Long?>(null)
    val cancellingPostId: StateFlow<Long?> = _cancellingPostId.asStateFlow()

    init {
        recoverStuckPosts()
        loadData()
    }

    /**
     * Recover posts stuck in POSTING status (e.g., after app crash).
     * If a post has been POSTING for more than 1 hour, mark it as FAILED.
     */
    private fun recoverStuckPosts() {
        viewModelScope.launch {
            val oneHourAgo = System.currentTimeMillis() - 60 * 60 * 1000
            postRepository.recoverStuckPostingPosts(oneHourAgo)
        }
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
                        // Include both SCHEDULED and POSTING posts (POSTING first, then sorted by time)
                        scheduledPosts = queue.filter {
                            it.status == PostStatus.SCHEDULED || it.status == PostStatus.POSTING
                        }.sortedWith(
                            compareByDescending<PostEntity> { it.status == PostStatus.POSTING }
                                .thenBy { it.scheduledTime }
                        ),
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

    /**
     * Stop currently posting post.
     * Same logic as StopPostingReceiver but callable from UI.
     */
    fun stopPosting(postId: Long) {
        // Optimistic UI update - show "Cancelling..." immediately
        _cancellingPostId.value = postId

        viewModelScope.launch {
            Timber.tag("HomeViewModel").w("Stop posting requested from UI for post $postId")

            // 1. Cancel the current posting task in PostingAgent
            PostingAgent.getInstance()?.cancelCurrentTask()

            // 2. Cancel all posting WorkManager jobs
            WorkManager.getInstance(application).cancelAllWorkByTag(SmartScheduler.TAG_POSTING)

            // 3. Cancel notifications
            SchedulerNotifications.cancelAllNotifications(application, postId)

            // Cancel foreground notification from PostWorker (ID: 10000 + postId)
            val notificationManager = application.getSystemService(NotificationManager::class.java)
            notificationManager.cancel((10000 + postId).toInt())

            // 4. Update post status to cancelled
            postRepository.updateStatus(postId, PostStatus.CANCELLED, "Остановлено пользователем")

            // Reset optimistic UI state
            _cancellingPostId.value = null

            Timber.tag("HomeViewModel").i("Posting stopped for post $postId")
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

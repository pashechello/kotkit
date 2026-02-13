package com.kotkit.basic.ui.screens.worker

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import timber.log.Timber
import androidx.lifecycle.viewModelScope
import com.kotkit.basic.data.local.db.entities.NetworkTaskEntity
import com.kotkit.basic.data.local.db.entities.WorkerProfileEntity
import com.kotkit.basic.data.remote.api.isAuthError
import com.kotkit.basic.data.remote.api.models.TaskResponse
import com.kotkit.basic.data.repository.NetworkTaskRepository
import com.kotkit.basic.data.repository.SettingsRepository
import com.kotkit.basic.data.repository.WorkerRepository
import com.kotkit.basic.network.NetworkWorkerService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.util.TimeZone
import javax.inject.Inject

@HiltViewModel
class WorkerDashboardViewModel @Inject constructor(
    private val application: Application,
    private val workerRepository: WorkerRepository,
    private val networkTaskRepository: NetworkTaskRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "WorkerDashboardVM"
    }

    private val _uiState = MutableStateFlow(WorkerDashboardUiState())
    val uiState: StateFlow<WorkerDashboardUiState> = _uiState.asStateFlow()

    // Job for refresh operation - can be cancelled when toggling
    private var refreshJob: Job? = null

    /**
     * Auto-register as worker if not registered (404 response).
     * Returns true if registration was successful, false otherwise.
     */
    private suspend fun autoRegisterIfNeeded(exception: Throwable?): Boolean {
        if (exception is HttpException && exception.code() == 404) {
            Log.i(TAG, "Worker not registered, auto-registering...")
            val result = workerRepository.registerWorker(
                tiktokUsername = null,
                categoryIds = null,
                countryCode = null,
                timezone = TimeZone.getDefault().id
            )
            return result.isSuccess
        }
        return false
    }

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            combine(
                workerRepository.getProfileFlow(),
                networkTaskRepository.getActiveTasksFlow(),
                networkTaskRepository.getActiveCountFlow()
            ) { profile, activeTasks, activeCount ->
                Triple(profile, activeTasks, activeCount)
            }.collect { (profile, activeTasks, activeCount) ->
                // Only update profile data (balance, stats, tasks).
                // Button state (isWorkerModeActive) is NEVER set from profile —
                // it starts as false and only changes via explicit user toggle.
                _uiState.update { state ->
                    state.copy(
                        profile = profile,
                        activeTasks = activeTasks,
                        activeTasksCount = activeCount,
                        isLoading = false
                    )
                }
            }
        }

        // Fetch fresh data from API
        refreshData()
    }

    fun refreshData() {
        // Cancel any existing refresh to prevent race conditions
        refreshJob?.cancel()

        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }

            // Fetch profile - auto-register if not registered
            val profileResult = workerRepository.fetchAndSyncProfile()
            if (profileResult.isFailure) {
                autoRegisterIfNeeded(profileResult.exceptionOrNull())
            }

            // Fetch balance and stats (will work after registration)
            workerRepository.fetchBalance()
            workerRepository.fetchStats()

            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun requestToggleWorkerMode() {
        val targetState = !_uiState.value.isWorkerModeActive
        if (targetState && _uiState.value.profile?.tiktokUsername.isNullOrBlank()) {
            // Need TikTok username before enabling Worker Mode
            _uiState.update { it.copy(showTiktokUsernameDialog = true) }
            return
        }
        performToggle()
    }

    fun dismissTiktokUsernameDialog() {
        _uiState.update { it.copy(showTiktokUsernameDialog = false, isUsernameEditMode = false) }
    }

    fun openUsernameEditor() {
        _uiState.update { it.copy(showTiktokUsernameDialog = true, isUsernameEditMode = true) }
    }

    fun saveTiktokUsername(username: String) {
        val isEditMode = _uiState.value.isUsernameEditMode
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingUsername = true) }
            val result = workerRepository.updateTiktokUsername(username)
            if (result.isSuccess) {
                _uiState.update { it.copy(
                    showTiktokUsernameDialog = false,
                    isSavingUsername = false,
                    isUsernameEditMode = false,
                    profile = result.getOrNull() ?: it.profile
                ) }
                if (!isEditMode) {
                    // First-time setup — proceed with toggle ON
                    performToggle()
                }
            } else {
                val exception = result.exceptionOrNull()
                _uiState.update { it.copy(
                    error = if (exception.isAuthError()) null else "Failed to save username: ${exception?.message}",
                    isSavingUsername = false
                ) }
            }
        }
    }

    private fun performToggle() {
        // Cancel any pending refresh to prevent race condition
        refreshJob?.cancel()

        val currentlyActive = _uiState.value.isWorkerModeActive
        val targetState = !currentlyActive

        Timber.tag(TAG).i("TOGGLE: requested current=$currentlyActive -> target=$targetState")

        viewModelScope.launch {
            // Optimistic UI update - instant feedback to user
            _uiState.update { it.copy(isWorkerModeActive = targetState, isToggling = true) }
            Timber.tag(TAG).i("TOGGLE: optimistic update done, UI=$targetState, isToggling=true")

            var result = workerRepository.toggleWorkerMode(targetState)

            // If not registered as worker (404), auto-register first then retry
            if (result.isFailure && autoRegisterIfNeeded(result.exceptionOrNull())) {
                result = workerRepository.toggleWorkerMode(targetState)
            }

            if (result.isSuccess) {
                val newProfile = result.getOrNull()
                val newIsActive = newProfile?.isActive == true
                Timber.tag(TAG).i("TOGGLE: API success, response=$newIsActive, setting isToggling=false")

                _uiState.update { state ->
                    Timber.tag(TAG).i("TOGGLE: final update, UI=${state.isWorkerModeActive} -> $newIsActive")
                    state.copy(
                        isWorkerModeActive = newIsActive,
                        profile = newProfile ?: state.profile,
                        isToggling = false
                    )
                }

                if (newIsActive) {
                    NetworkWorkerService.start(application)
                } else {
                    NetworkWorkerService.stop(application)
                }
            } else {
                val error = result.exceptionOrNull()
                Timber.tag(TAG).e(error, "TOGGLE: failed")
                // Rollback optimistic update on failure
                _uiState.update { it.copy(
                    isWorkerModeActive = currentlyActive,
                    error = if (error.isAuthError()) null else "Failed to toggle: ${error?.message}",
                    isToggling = false
                ) }
            }
        }
    }

    fun claimTask(taskId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isClaimingTask = true) }

            val result = networkTaskRepository.claimTask(taskId)
            if (result.isSuccess) {
                // Refresh available tasks
                fetchAvailableTasks()
            } else {
                val exception = result.exceptionOrNull()
                _uiState.update {
                    it.copy(error = if (exception.isAuthError()) null else (exception?.message ?: "Failed to claim task"))
                }
            }

            _uiState.update { it.copy(isClaimingTask = false) }
        }
    }

    fun fetchAvailableTasks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTasks = true) }

            val result = networkTaskRepository.fetchAvailableTasks()
            if (result.isSuccess) {
                _uiState.update { it.copy(availableTasks = result.getOrDefault(emptyList())) }
            }

            _uiState.update { it.copy(isLoadingTasks = false) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class WorkerDashboardUiState(
    // Profile
    val profile: WorkerProfileEntity? = null,
    val isWorkerModeActive: Boolean = false,

    // Tasks
    val activeTasks: List<NetworkTaskEntity> = emptyList(),
    val activeTasksCount: Int = 0,
    val availableTasks: List<TaskResponse> = emptyList(),

    // Loading states
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isToggling: Boolean = false,
    val isClaimingTask: Boolean = false,
    val isLoadingTasks: Boolean = false,

    // TikTok username dialog
    val showTiktokUsernameDialog: Boolean = false,
    val isSavingUsername: Boolean = false,
    val isUsernameEditMode: Boolean = false,

    // Error
    val error: String? = null
) {
    // Derived properties
    val totalEarned: Float get() = profile?.totalEarnedRub ?: 0f
    val pendingBalance: Float get() = profile?.pendingBalanceRub ?: 0f
    val availableBalance: Float get() = profile?.availableBalanceRub ?: 0f
    val successRate: Float get() = profile?.successRate ?: 0f
    val completedTasks: Int get() = profile?.completedTasks ?: 0
    val rating: Float get() = profile?.rating ?: 0f
}

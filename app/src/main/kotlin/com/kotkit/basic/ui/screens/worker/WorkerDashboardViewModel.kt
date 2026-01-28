package com.kotkit.basic.ui.screens.worker

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotkit.basic.data.local.db.entities.NetworkTaskEntity
import com.kotkit.basic.data.local.db.entities.WorkerProfileEntity
import com.kotkit.basic.data.remote.api.models.TaskResponse
import com.kotkit.basic.data.repository.NetworkTaskRepository
import com.kotkit.basic.data.repository.SettingsRepository
import com.kotkit.basic.data.repository.WorkerRepository
import com.kotkit.basic.network.NetworkWorkerService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
                _uiState.update { state ->
                    state.copy(
                        profile = profile,
                        activeTasks = activeTasks,
                        activeTasksCount = activeCount,
                        isWorkerModeActive = profile?.isActive == true,
                        isLoading = false
                    )
                }
            }
        }

        // Fetch fresh data from API
        refreshData()
    }

    fun refreshData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }

            // Fetch profile and balance
            workerRepository.fetchAndSyncProfile()
            workerRepository.fetchBalance()
            workerRepository.fetchStats()

            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun toggleWorkerMode() {
        val currentlyActive = _uiState.value.isWorkerModeActive

        viewModelScope.launch {
            _uiState.update { it.copy(isToggling = true) }

            val result = workerRepository.toggleWorkerMode(!currentlyActive)
            if (result.isSuccess) {
                val newProfile = result.getOrNull()
                if (newProfile?.isActive == true) {
                    // Start worker service
                    NetworkWorkerService.start(application)
                } else {
                    // Stop worker service
                    NetworkWorkerService.stop(application)
                }
            }

            _uiState.update { it.copy(isToggling = false) }
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
                _uiState.update {
                    it.copy(error = result.exceptionOrNull()?.message ?: "Failed to claim task")
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

    // Error
    val error: String? = null
) {
    // Derived properties
    val totalEarned: Float get() = profile?.totalEarnedUsd ?: 0f
    val pendingBalance: Float get() = profile?.pendingBalanceUsd ?: 0f
    val availableBalance: Float get() = profile?.availableBalanceUsd ?: 0f
    val successRate: Float get() = profile?.successRate ?: 0f
    val completedTasks: Int get() = profile?.completedTasks ?: 0
    val rating: Float get() = profile?.rating ?: 0f
}

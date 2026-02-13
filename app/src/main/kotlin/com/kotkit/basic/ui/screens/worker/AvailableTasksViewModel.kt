package com.kotkit.basic.ui.screens.worker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotkit.basic.data.remote.api.models.TaskResponse
import com.kotkit.basic.data.repository.NetworkTaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import com.kotkit.basic.data.remote.api.userErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AvailableTasksViewModel @Inject constructor(
    private val networkTaskRepository: NetworkTaskRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AvailableTasksUiState())
    val uiState: StateFlow<AvailableTasksUiState> = _uiState.asStateFlow()

    init {
        loadTasks()
    }

    private fun loadTasks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val result = networkTaskRepository.fetchAvailableTasks(limit = 20)
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        tasks = result.getOrDefault(emptyList()),
                        isLoading = false
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        error = result.userErrorMessage("Не удалось загрузить задачи"),
                        isLoading = false
                    )
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }

            val result = networkTaskRepository.fetchAvailableTasks(limit = 20)
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        tasks = result.getOrDefault(emptyList()),
                        isRefreshing = false
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        error = result.userErrorMessage(),
                        isRefreshing = false
                    )
                }
            }
        }
    }

    fun claimTask(taskId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(claimingTaskId = taskId) }

            val result = networkTaskRepository.claimTask(taskId)
            if (result.isSuccess) {
                // Remove from list
                _uiState.update { state ->
                    state.copy(
                        tasks = state.tasks.filter { it.id != taskId },
                        claimingTaskId = null,
                        claimSuccess = true
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        error = result.userErrorMessage("Не удалось взять задачу"),
                        claimingTaskId = null
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearClaimSuccess() {
        _uiState.update { it.copy(claimSuccess = false) }
    }
}

data class AvailableTasksUiState(
    val tasks: List<TaskResponse> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val claimingTaskId: String? = null,
    val claimSuccess: Boolean = false,
    val error: String? = null
)

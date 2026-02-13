package com.kotkit.basic.ui.screens.worker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotkit.basic.data.remote.api.models.CompletedTaskItem
import com.kotkit.basic.data.remote.api.userErrorMessage
import com.kotkit.basic.data.repository.NetworkTaskRepository
import com.kotkit.basic.data.repository.WorkerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CompletedTasksUiState(
    val tasks: List<CompletedTaskItem> = emptyList(),
    val total: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null,
    val submittingVerificationId: String? = null,
    val submitSuccess: Boolean = false,
    val submitRewardRub: Float? = null,
    val showUrlDialog: Boolean = false,
    val dialogVerificationId: String? = null,
    val dialogCampaignName: String? = null,
)

@HiltViewModel
class CompletedTasksViewModel @Inject constructor(
    private val networkTaskRepository: NetworkTaskRepository,
    private val workerRepository: WorkerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CompletedTasksUiState())
    val uiState: StateFlow<CompletedTasksUiState> = _uiState.asStateFlow()

    init {
        loadTasks()
    }

    fun loadTasks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val result = networkTaskRepository.getCompletedTasksWithVerification(limit = 50)
            if (result.isSuccess) {
                val response = result.getOrNull()
                _uiState.update {
                    it.copy(
                        tasks = response?.tasks ?: emptyList(),
                        total = response?.total ?: 0,
                        isLoading = false,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        error = result.userErrorMessage("Не удалось загрузить задачи"),
                        isLoading = false,
                    )
                }
            }
        }
    }

    fun showUrlDialog(verificationId: String, campaignName: String) {
        _uiState.update {
            it.copy(
                showUrlDialog = true,
                dialogVerificationId = verificationId,
                dialogCampaignName = campaignName,
            )
        }
    }

    fun dismissUrlDialog() {
        _uiState.update {
            it.copy(
                showUrlDialog = false,
                dialogVerificationId = null,
                dialogCampaignName = null,
            )
        }
    }

    fun submitUrl(verificationId: String, url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(submittingVerificationId = verificationId) }

            val result = networkTaskRepository.submitVerificationUrl(verificationId, url)
            if (result.isSuccess) {
                val response = result.getOrNull()
                _uiState.update { state ->
                    state.copy(
                        tasks = state.tasks.map { task ->
                            if (task.verificationId == verificationId) {
                                task.copy(
                                    tiktokVideoUrl = response?.tiktokVideoUrl ?: url,
                                    needsUrlSubmission = false,
                                )
                            } else task
                        },
                        submittingVerificationId = null,
                        showUrlDialog = false,
                        dialogVerificationId = null,
                        dialogCampaignName = null,
                        submitSuccess = true,
                        submitRewardRub = response?.rewardAmountRub,
                    )
                }
                // Refresh balance so WorkerDashboard sees +2 RUB via Room Flow
                workerRepository.fetchBalance()
            } else {
                _uiState.update {
                    it.copy(
                        error = result.userErrorMessage("Не удалось отправить ссылку"),
                        submittingVerificationId = null,
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSubmitSuccess() {
        _uiState.update { it.copy(submitSuccess = false, submitRewardRub = null) }
    }
}

package com.kotkit.basic.ui.screens.worker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotkit.basic.data.local.db.entities.WorkerEarningEntity
import com.kotkit.basic.data.repository.WorkerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EarningsViewModel @Inject constructor(
    private val workerRepository: WorkerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(EarningsUiState())
    val uiState: StateFlow<EarningsUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Fetch balance
            val balanceResult = workerRepository.fetchBalance()
            if (balanceResult.isSuccess) {
                val balance = balanceResult.getOrNull()
                _uiState.update {
                    it.copy(
                        availableBalance = balance?.availableBalance ?: 0f,
                        pendingBalance = balance?.pendingBalance ?: 0f,
                        totalEarned = balance?.totalEarned ?: 0f
                    )
                }
            }

            // Fetch stats
            val statsResult = workerRepository.fetchStats()
            if (statsResult.isSuccess) {
                val stats = statsResult.getOrNull()
                _uiState.update {
                    it.copy(
                        thisMonthEarned = stats?.monthEarned ?: 0f,
                        todayEarned = stats?.todayEarned ?: 0f
                    )
                }
            }

            // Fetch earnings history
            val earningsResult = workerRepository.fetchEarnings(limit = 50)
            if (earningsResult.isSuccess) {
                _uiState.update {
                    it.copy(earnings = earningsResult.getOrDefault(emptyList()))
                }
            }

            _uiState.update { it.copy(isLoading = false) }
        }

        // Subscribe to earnings flow
        viewModelScope.launch {
            workerRepository.getEarningsFlow().collect { earnings ->
                _uiState.update { it.copy(earnings = earnings) }
            }
        }
    }

    fun refresh() {
        loadData()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class EarningsUiState(
    val availableBalance: Float = 0f,
    val pendingBalance: Float = 0f,
    val totalEarned: Float = 0f,
    val thisMonthEarned: Float = 0f,
    val todayEarned: Float = 0f,
    val earnings: List<WorkerEarningEntity> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

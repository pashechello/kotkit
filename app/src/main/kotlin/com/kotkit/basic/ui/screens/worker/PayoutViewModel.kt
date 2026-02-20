package com.kotkit.basic.ui.screens.worker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotkit.basic.data.remote.api.models.PayoutMethod
import com.kotkit.basic.data.remote.api.models.PayoutResponse
import com.kotkit.basic.data.remote.api.models.PayoutStatus
import com.kotkit.basic.data.repository.WorkerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PayoutViewModel @Inject constructor(
    private val workerRepository: WorkerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PayoutUiState())
    val uiState: StateFlow<PayoutUiState> = _uiState.asStateFlow()

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
                        minPayoutAmount = balance?.minPayoutAmount ?: 10f
                    )
                }
            }

            // Fetch payout history
            val historyResult = workerRepository.getPayoutHistory()
            if (historyResult.isSuccess) {
                val payouts = historyResult.getOrNull()?.payouts ?: emptyList()
                _uiState.update {
                    it.copy(
                        payoutHistory = payouts,
                        activePayout = payouts.firstOrNull { p ->
                            p.status == PayoutStatus.PENDING || p.status == PayoutStatus.PROCESSING
                        }
                    )
                }
            }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun updateAmount(amount: String) {
        _uiState.update { it.copy(amount = amount) }
    }

    fun updateMethod(method: String) {
        _uiState.update { it.copy(method = method) }
    }

    fun updateCurrency(currency: String) {
        _uiState.update { it.copy(currency = currency) }
    }

    fun updateWalletAddress(address: String) {
        _uiState.update { it.copy(walletAddress = address) }
    }

    fun submitPayout() {
        val state = _uiState.value
        val amountNum = state.amount.toFloatOrNull() ?: return

        if (amountNum < state.minPayoutAmount || amountNum > state.availableBalance) return
        if (state.walletAddress.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }

            val currencyToSend = if (state.method == PayoutMethod.CRYPTO) state.currency else "RUB"
            val result = workerRepository.requestPayout(
                amountRub = amountNum,
                method = state.method,
                currency = currencyToSend,
                walletAddress = state.walletAddress.trim()
            )

            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        payoutSubmitted = true,
                        amount = "",
                        walletAddress = ""
                    )
                }
                loadData() // Refresh balance and history
            } else {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        error = result.exceptionOrNull()?.message ?: "Ошибка при создании заявки"
                    )
                }
            }
        }
    }

    fun cancelPayout(payoutId: String) {
        viewModelScope.launch {
            val result = workerRepository.cancelPayout(payoutId)
            if (result.isSuccess) {
                loadData()
            } else {
                _uiState.update {
                    it.copy(error = result.exceptionOrNull()?.message ?: "Ошибка отмены")
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSubmitted() {
        _uiState.update { it.copy(payoutSubmitted = false) }
    }

    fun refresh() {
        loadData()
    }
}

data class PayoutUiState(
    val availableBalance: Float = 0f,
    val minPayoutAmount: Float = 10f,
    val amount: String = "",
    val method: String = PayoutMethod.CRYPTO,
    val currency: String = "USDT",
    val walletAddress: String = "",
    val activePayout: PayoutResponse? = null,
    val payoutHistory: List<PayoutResponse> = emptyList(),
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val payoutSubmitted: Boolean = false,
    val error: String? = null
)

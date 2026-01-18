package com.autoposter.ui.screens.settings

import androidx.lifecycle.ViewModel
import com.autoposter.data.repository.AuthRepository
import com.autoposter.data.repository.SettingsRepository
import com.autoposter.executor.accessibility.TikTokAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshState()
    }

    fun refreshState() {
        _uiState.update {
            SettingsUiState(
                isAccessibilityEnabled = TikTokAccessibilityService.isServiceEnabled(),
                hasStoredPin = settingsRepository.hasStoredPin(),
                hasStoredPassword = settingsRepository.hasStoredPassword(),
                isLoggedIn = authRepository.isLoggedIn()
            )
        }
    }

    fun savePin(pin: String): Boolean {
        if (pin.length < 4) return false
        settingsRepository.savePin(pin)
        refreshState()
        return true
    }

    fun savePassword(password: String): Boolean {
        if (password.isEmpty()) return false
        settingsRepository.savePassword(password)
        refreshState()
        return true
    }

    fun clearUnlockCredentials() {
        settingsRepository.clearUnlockCredentials()
        refreshState()
    }

    fun logout() {
        authRepository.logout()
        refreshState()
    }
}

data class SettingsUiState(
    val isAccessibilityEnabled: Boolean = false,
    val hasStoredPin: Boolean = false,
    val hasStoredPassword: Boolean = false,
    val isLoggedIn: Boolean = false
)

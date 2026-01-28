package com.kotkit.basic.ui.screens.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotkit.basic.data.repository.AuthRepository
import com.kotkit.basic.data.repository.SettingsRepository
import com.kotkit.basic.executor.accessibility.TikTokAccessibilityService
import com.kotkit.basic.executor.screen.ScreenUnlocker
import com.kotkit.basic.executor.screen.UnlockResult
import com.kotkit.basic.permission.ExactAlarmPermissionManager
import com.kotkit.basic.permission.NotificationPermissionHelper
import com.kotkit.basic.status.OverlayPermissionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val application: Application,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val exactAlarmPermissionManager: ExactAlarmPermissionManager,
    private val screenUnlocker: ScreenUnlocker
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Test unlock state
    private val _testUnlockCountdown = MutableStateFlow<Int?>(null)
    val testUnlockCountdown: StateFlow<Int?> = _testUnlockCountdown.asStateFlow()

    private val _testUnlockResult = MutableStateFlow<String?>(null)
    val testUnlockResult: StateFlow<String?> = _testUnlockResult.asStateFlow()

    private var testUnlockJob: Job? = null

    init {
        refreshState()
    }

    fun refreshState() {
        exactAlarmPermissionManager.refreshPermissionState()
        _uiState.update {
            SettingsUiState(
                isAccessibilityEnabled = TikTokAccessibilityService.isServiceEnabled(application),
                hasStoredPin = settingsRepository.hasStoredPin(),
                hasStoredPassword = settingsRepository.hasStoredPassword(),
                isLoggedIn = authRepository.isLoggedIn(),
                currentLanguage = settingsRepository.appLanguage,
                canScheduleExactAlarms = exactAlarmPermissionManager.canScheduleExactAlarms(),
                hasOverlayPermission = OverlayPermissionHelper.hasOverlayPermission(application),
                hasNotificationPermission = NotificationPermissionHelper.hasNotificationPermission(application)
            )
        }
    }

    fun openExactAlarmSettings() {
        exactAlarmPermissionManager.openExactAlarmSettings()
    }

    fun openOverlaySettings() {
        OverlayPermissionHelper.openOverlaySettings(application)
    }

    fun openNotificationSettings() {
        NotificationPermissionHelper.openNotificationSettings(application)
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

    fun setLanguage(language: String) {
        settingsRepository.appLanguage = language
        refreshState()
    }

    /**
     * Start test unlock with 15-second countdown.
     * User should lock the phone during countdown.
     */
    fun startTestUnlock() {
        Log.w(TAG, "startTestUnlock: called")
        // Cancel any existing test
        testUnlockJob?.cancel()
        _testUnlockResult.value = null

        testUnlockJob = viewModelScope.launch {
            Log.w(TAG, "startTestUnlock: starting 15 second countdown")
            // 15 second countdown
            for (i in 15 downTo 1) {
                _testUnlockCountdown.value = i
                delay(1000)
            }
            _testUnlockCountdown.value = 0
            Log.w(TAG, "startTestUnlock: countdown finished, calling ensureUnlocked()")

            // Attempt unlock
            val result = screenUnlocker.ensureUnlocked()
            Log.w(TAG, "startTestUnlock: ensureUnlocked returned: $result")
            _testUnlockResult.value = when (result) {
                is UnlockResult.Success -> "Разблокировка успешна!"
                is UnlockResult.AlreadyUnlocked -> "Экран уже разблокирован"
                is UnlockResult.Failed -> "Ошибка: ${result.reason}"
                is UnlockResult.NeedUserAction -> result.message
                is UnlockResult.NotSupported -> "Не поддерживается: ${result.message}"
            }
            Log.w(TAG, "startTestUnlock: done, result=$_testUnlockResult")

            // Clear countdown
            _testUnlockCountdown.value = null
        }
    }

    /**
     * Cancel ongoing test unlock.
     */
    fun cancelTestUnlock() {
        testUnlockJob?.cancel()
        testUnlockJob = null
        _testUnlockCountdown.value = null
        _testUnlockResult.value = null
    }

    /**
     * Clear test unlock result.
     */
    fun clearTestUnlockResult() {
        _testUnlockResult.value = null
    }
}

data class SettingsUiState(
    val isAccessibilityEnabled: Boolean = false,
    val hasStoredPin: Boolean = false,
    val hasStoredPassword: Boolean = false,
    val isLoggedIn: Boolean = false,
    val currentLanguage: String = "ru",
    val canScheduleExactAlarms: Boolean = true,
    val hasOverlayPermission: Boolean = false,
    val hasNotificationPermission: Boolean = true  // true for Android <13
)

package com.kotkit.basic.ui.screens.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotkit.basic.data.local.preferences.AudiencePersonaPreferencesManager
import com.kotkit.basic.data.repository.AuthRepository
import com.kotkit.basic.data.repository.SettingsRepository
import com.kotkit.basic.data.repository.WorkerRepository
import com.kotkit.basic.executor.accessibility.TikTokAccessibilityService
import com.kotkit.basic.executor.screen.ScreenUnlocker
import com.kotkit.basic.executor.screen.UnlockResult
import com.kotkit.basic.permission.AutostartHelper
import com.kotkit.basic.permission.BatteryOptimizationHelper
import com.kotkit.basic.permission.ExactAlarmPermissionManager
import com.kotkit.basic.permission.NotificationPermissionHelper
import com.kotkit.basic.scheduler.AudiencePersona
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val application: Application,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val workerRepository: WorkerRepository,
    private val exactAlarmPermissionManager: ExactAlarmPermissionManager,
    private val batteryOptimizationHelper: BatteryOptimizationHelper,
    private val autostartHelper: AutostartHelper,
    private val screenUnlocker: ScreenUnlocker,
    private val audiencePersonaPreferencesManager: AudiencePersonaPreferencesManager
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

        // Observe persona changes
        viewModelScope.launch {
            audiencePersonaPreferencesManager.personaFlow.collect { persona ->
                _uiState.update { it.copy(selectedPersona = persona) }
            }
        }

        // Sync persona from server on init
        viewModelScope.launch {
            audiencePersonaPreferencesManager.syncFromServer()
        }

        // Observe worker profile for TikTok username
        viewModelScope.launch {
            workerRepository.getProfileFlow().collect { profile ->
                _uiState.update { it.copy(tiktokUsername = profile?.tiktokUsername) }
            }
        }
    }

    fun refreshState() {
        viewModelScope.launch {
            // Show loading state while checking permissions
            _uiState.update { it.copy(isLoading = true) }

            try {
                // Execute permission checks in background thread to avoid blocking UI
                exactAlarmPermissionManager.refreshPermissionState()

                // Heavy operations on Default dispatcher
                val isAccessibilityEnabled = withContext(Dispatchers.Default) {
                    TikTokAccessibilityService.isServiceEnabled(application)
                }
                val isBatteryOptDisabled = withContext(Dispatchers.Default) {
                    batteryOptimizationHelper.isBatteryOptimizationDisabled()
                }
                val hasNotificationPermission = withContext(Dispatchers.Default) {
                    NotificationPermissionHelper.hasNotificationPermission(application)
                }

                // Light operations can stay on main thread
                val hasStoredPin = settingsRepository.hasStoredPin()
                val hasStoredPassword = settingsRepository.hasStoredPassword()
                val isLoggedIn = authRepository.isLoggedIn()
                val currentLanguage = settingsRepository.appLanguage
                val canScheduleExactAlarms = exactAlarmPermissionManager.canScheduleExactAlarms()
                val isAutostartRequired = autostartHelper.isAutostartRequired()
                val isAutostartConfirmed = autostartHelper.isAutostartConfirmed()
                val manufacturerName = autostartHelper.getManufacturerName()

                // Get user email if logged in (try API first, fallback to cache)
                val userEmail = if (isLoggedIn) {
                    authRepository.getProfile().getOrNull()?.email
                        ?: authRepository.getCachedEmail()
                } else null

                // Update state on main thread
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        hasStoredPin = hasStoredPin,
                        hasStoredPassword = hasStoredPassword,
                        isLoggedIn = isLoggedIn,
                        userEmail = userEmail,
                        currentLanguage = currentLanguage,
                        canScheduleExactAlarms = canScheduleExactAlarms,
                        hasNotificationPermission = hasNotificationPermission,
                        isBatteryOptimizationDisabled = isBatteryOptDisabled,
                        isAutostartRequired = isAutostartRequired,
                        isAutostartConfirmed = isAutostartConfirmed,
                        manufacturerName = manufacturerName
                    )
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to refresh settings state")
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun openExactAlarmSettings() {
        exactAlarmPermissionManager.openExactAlarmSettings()
    }

    fun openNotificationSettings() {
        NotificationPermissionHelper.openNotificationSettings(application)
    }

    fun openBatteryOptimizationSettings() {
        batteryOptimizationHelper.openBatteryOptimizationSettings()
    }

    fun openAutostartSettings() {
        val opened = autostartHelper.openAutostartSettings()
        if (opened) {
            // Show confirmation dialog when user returns from settings
            _uiState.update { it.copy(showAutostartConfirmDialog = true) }
        } else {
            // Settings couldn't be opened — show manual instructions
            _uiState.update { it.copy(showAutostartManualDialog = true) }
        }
    }

    fun confirmAutostartEnabled() {
        autostartHelper.setAutostartConfirmed(true)
        _uiState.update { it.copy(
            showAutostartConfirmDialog = false,
            isAutostartConfirmed = true
        ) }
    }

    fun dismissAutostartDialog() {
        _uiState.update { it.copy(showAutostartConfirmDialog = false) }
    }

    fun dismissAutostartManualDialog() {
        _uiState.update { it.copy(showAutostartManualDialog = false) }
    }

    fun getAutostartInstructions(): String {
        return autostartHelper.getInstructionsForManufacturer()
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
     * Set audience persona - saves locally and syncs to server.
     */
    fun setPersona(persona: AudiencePersona) {
        viewModelScope.launch {
            audiencePersonaPreferencesManager.setPersona(persona)
        }
    }

    fun showTiktokDialog() {
        _uiState.update { it.copy(showTiktokUsernameDialog = true) }
    }

    fun dismissTiktokDialog() {
        _uiState.update { it.copy(showTiktokUsernameDialog = false) }
    }

    fun updateTiktokUsername(username: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingTiktokUsername = true) }
            val result = workerRepository.updateTiktokUsername(username)
            if (result.isSuccess) {
                _uiState.update { it.copy(
                    tiktokUsername = username,
                    isSavingTiktokUsername = false,
                    showTiktokUsernameDialog = false
                ) }
            } else {
                Timber.tag(TAG).e(result.exceptionOrNull(), "Failed to update TikTok username")
                _uiState.update { it.copy(isSavingTiktokUsername = false) }
            }
        }
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
    val isLoading: Boolean = true,  // Show loading state until permissions are checked
    val isAccessibilityEnabled: Boolean = false,
    val hasStoredPin: Boolean = false,
    val hasStoredPassword: Boolean = false,
    val isLoggedIn: Boolean = false,
    val userEmail: String? = null,  // User's email to display when logged in
    val currentLanguage: String = "ru",
    val canScheduleExactAlarms: Boolean = true,
    val hasNotificationPermission: Boolean = true,  // true for Android <13
    val isBatteryOptimizationDisabled: Boolean = false,  // true if app is in battery whitelist
    val isAutostartRequired: Boolean = false,  // true if OEM requires autostart permission (Xiaomi/Samsung/etc)
    val isAutostartConfirmed: Boolean = false,  // true if user confirmed they enabled autostart
    val manufacturerName: String = "Unknown",  // Human-readable manufacturer name
    val selectedPersona: AudiencePersona = AudiencePersona.DEFAULT,
    val showAutostartConfirmDialog: Boolean = false,  // true to show confirmation dialog
    val showAutostartManualDialog: Boolean = false,  // true to show manual instructions when settings can't be opened
    val tiktokUsername: String? = null,
    val isSavingTiktokUsername: Boolean = false,
    val showTiktokUsernameDialog: Boolean = false
)

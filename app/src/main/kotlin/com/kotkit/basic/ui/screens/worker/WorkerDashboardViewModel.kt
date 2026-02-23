package com.kotkit.basic.ui.screens.worker

import android.app.Application
import android.content.Intent
import android.provider.Settings
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
import com.kotkit.basic.executor.screenshot.MediaProjectionConsentActivity
import com.kotkit.basic.executor.screenshot.MediaProjectionScreenshot
import com.kotkit.basic.network.NetworkWorkerService
import com.kotkit.basic.proxy.VpnConsentActivity
import com.kotkit.basic.permission.AutostartHelper
import com.kotkit.basic.permission.BatteryOptimizationHelper
import com.kotkit.basic.permission.DeviceProtectionChecker
import android.os.Build
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.util.TimeZone
import javax.inject.Inject

@HiltViewModel
class WorkerDashboardViewModel @Inject constructor(
    private val application: Application,
    private val workerRepository: WorkerRepository,
    private val networkTaskRepository: NetworkTaskRepository,
    private val settingsRepository: SettingsRepository,
    private val deviceProtectionChecker: DeviceProtectionChecker,
    private val batteryOptimizationHelper: BatteryOptimizationHelper,
    private val autostartHelper: AutostartHelper
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
            Timber.tag(TAG).i("Worker not registered, auto-registering...")
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

        // Check device protections
        refreshProtectionStatus()
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

    /** Refresh device protection status (called on init + ON_RESUME). */
    fun refreshProtectionStatus() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.Default) {
                deviceProtectionChecker.check()
            }
            _uiState.update { it.copy(protectionStatus = status) }
        }
    }

    fun requestToggleWorkerMode() {
        val targetState = !_uiState.value.isWorkerModeActive

        if (targetState) {
            // Turning ON — check TikTok username first
            if (_uiState.value.profile?.tiktokUsername.isNullOrBlank()) {
                _uiState.update { it.copy(showTiktokUsernameDialog = true) }
                return
            }

            // Check device protections (off main thread — Settings.Secure is Binder IPC)
            viewModelScope.launch {
                val status = withContext(Dispatchers.Default) {
                    deviceProtectionChecker.check()
                }
                _uiState.update { it.copy(protectionStatus = status) }

                if (!status.allCriticalOk) {
                    _uiState.update { it.copy(showSetupDialog = true) }
                } else {
                    performToggle()
                }
            }
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
                    // First-time setup — now check protections before toggle
                    requestToggleWorkerMode()
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

    // --- Setup dialog methods ---

    fun dismissSetupDialog() {
        _uiState.update { it.copy(showSetupDialog = false) }
    }

    /** Open setup dialog directly (e.g. from warning card). */
    fun showSetupDialog() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.Default) {
                deviceProtectionChecker.check()
            }
            _uiState.update { it.copy(protectionStatus = status, showSetupDialog = true) }
        }
    }

    /** Re-check protections and proceed if all good. */
    fun retryToggleAfterSetup() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.Default) {
                deviceProtectionChecker.check()
            }
            _uiState.update { it.copy(protectionStatus = status) }
            if (status.allCriticalOk) {
                _uiState.update { it.copy(showSetupDialog = false) }
                performToggle()
            }
        }
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        application.startActivity(intent)
    }

    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, application.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        application.startActivity(intent)
    }

    fun openBatteryOptimizationSettings() {
        batteryOptimizationHelper.openBatteryOptimizationSettings()
    }

    fun openAutostartSettings() {
        val opened = autostartHelper.openAutostartSettings()
        if (opened) {
            _uiState.update { it.copy(showAutostartConfirmDialog = true) }
        } else {
            _uiState.update { it.copy(showAutostartManualDialog = true) }
        }
    }

    fun confirmAutostartEnabled() {
        autostartHelper.setAutostartConfirmed(true)
        _uiState.update { it.copy(showAutostartConfirmDialog = false) }
        refreshProtectionStatus()
    }

    fun dismissAutostartConfirmDialog() {
        _uiState.update { it.copy(showAutostartConfirmDialog = false) }
    }

    fun dismissAutostartManualDialog() {
        _uiState.update { it.copy(showAutostartManualDialog = false) }
    }

    fun getAutostartChecklist(): List<String> = autostartHelper.getConfirmationChecklist()
    fun getAutostartInstructions(): String = autostartHelper.getInstructionsForManufacturer()
    fun getManufacturerName(): String = autostartHelper.getManufacturerName()

    // --- Existing toggle logic ---

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
                    startWorkerWithVpnConsent()
                } else {
                    MediaProjectionScreenshot.release()
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

    /**
     * Step 1 of 2: Request VPN consent (one-time system dialog).
     * If already consented, proceeds directly to screenshot consent.
     */
    private fun startWorkerWithVpnConsent() {
        VpnConsentActivity.start(application) { granted ->
            if (granted) {
                Timber.tag(TAG).i("VPN consent granted, proceeding to screenshot consent")
                startWorkerWithScreenshotConsent()
            } else {
                Timber.tag(TAG).w("VPN consent denied")
                rollbackWorkerToggle("VPN permission required for Worker Mode")
            }
        }
    }

    /**
     * Step 2 of 2: On API 29, request MediaProjection consent before starting the worker service.
     * On API 30+, start directly (screenshots use AccessibilityService.takeScreenshot()).
     * If consent is denied or initialization fails, rollback the toggle.
     */
    private fun startWorkerWithScreenshotConsent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Timber.tag(TAG).i("API ${Build.VERSION.SDK_INT}: requesting MediaProjection consent")
            MediaProjectionConsentActivity.start(application) { granted ->
                if (granted) {
                    // Token is stored in MediaProjectionTokenHolder.
                    // Do NOT call getMediaProjection() here — on API 29, it requires
                    // an active foreground service with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
                    // The service will initialize MediaProjection after startForeground().
                    Timber.tag(TAG).i("MediaProjection consent granted, starting worker service")
                    NetworkWorkerService.start(application)
                } else {
                    Timber.tag(TAG).w("MediaProjection consent denied")
                    rollbackWorkerToggle("Screen recording permission required for Worker Mode")
                }
            }
        } else {
            NetworkWorkerService.start(application)
        }
    }

    /** Rollback worker toggle to OFF and show error. */
    private fun rollbackWorkerToggle(errorMessage: String) {
        viewModelScope.launch {
            // Deactivate on server
            workerRepository.toggleWorkerMode(false)
            _uiState.update { it.copy(
                isWorkerModeActive = false,
                isToggling = false,
                error = errorMessage
            ) }
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

    // Device protection
    val protectionStatus: DeviceProtectionChecker.ProtectionStatus? = null,
    val showSetupDialog: Boolean = false,
    val showAutostartConfirmDialog: Boolean = false,
    val showAutostartManualDialog: Boolean = false,

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
    val hasProtectionIssues: Boolean get() = protectionStatus?.allCriticalOk == false
}

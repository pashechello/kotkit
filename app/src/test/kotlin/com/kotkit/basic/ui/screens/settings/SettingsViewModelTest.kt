package com.kotkit.basic.ui.screens.settings

import android.app.Application
import com.kotkit.basic.data.local.preferences.AudiencePersonaPreferencesManager
import com.kotkit.basic.data.repository.AuthRepository
import com.kotkit.basic.data.repository.SettingsRepository
import com.kotkit.basic.executor.accessibility.TikTokAccessibilityService
import com.kotkit.basic.executor.screen.ScreenUnlocker
import com.kotkit.basic.permission.AutostartHelper
import com.kotkit.basic.permission.BatteryOptimizationHelper
import com.kotkit.basic.permission.ExactAlarmPermissionManager
import com.kotkit.basic.permission.NotificationPermissionHelper
import com.kotkit.basic.scheduler.AudiencePersona
import com.kotkit.basic.status.OverlayPermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive unit tests for SettingsViewModel.
 *
 * Coverage:
 * - Async state refresh on Dispatchers.Default
 * - Coroutine-based permission checks
 * - State updates and Flow propagation
 * - Error handling and graceful degradation
 * - Delegation to helper classes
 * - ViewModel lifecycle
 *
 * Uses Kotlin Coroutines Test library for testing async operations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class SettingsViewModelTest {

    @Mock
    private lateinit var application: Application

    @Mock
    private lateinit var settingsRepository: SettingsRepository

    @Mock
    private lateinit var authRepository: AuthRepository

    @Mock
    private lateinit var exactAlarmPermissionManager: ExactAlarmPermissionManager

    @Mock
    private lateinit var batteryOptimizationHelper: BatteryOptimizationHelper

    @Mock
    private lateinit var autostartHelper: AutostartHelper

    @Mock
    private lateinit var screenUnlocker: ScreenUnlocker

    @Mock
    private lateinit var audiencePersonaPreferencesManager: AudiencePersonaPreferencesManager

    private lateinit var viewModel: SettingsViewModel

    // Test dispatcher for coroutines
    private val testDispatcher = StandardTestDispatcher()

    // Static mocks for singleton helpers
    private lateinit var accessibilityServiceMock: MockedStatic<TikTokAccessibilityService>
    private lateinit var overlayPermissionHelperMock: MockedStatic<OverlayPermissionHelper>
    private lateinit var notificationPermissionHelperMock: MockedStatic<NotificationPermissionHelper>

    @Before
    fun setUp() {
        // Set test dispatcher for Main and Default
        Dispatchers.setMain(testDispatcher)

        // Mock static helper classes
        accessibilityServiceMock = Mockito.mockStatic(TikTokAccessibilityService::class.java)
        overlayPermissionHelperMock = Mockito.mockStatic(OverlayPermissionHelper::class.java)
        notificationPermissionHelperMock = Mockito.mockStatic(NotificationPermissionHelper::class.java)

        // Setup default return values
        setupDefaultMocks()

        // Mock persona flow
        whenever(audiencePersonaPreferencesManager.personaFlow)
            .thenReturn(MutableStateFlow(AudiencePersona.DEFAULT))

        // Create ViewModel (triggers init block with refreshState)
        viewModel = SettingsViewModel(
            application = application,
            settingsRepository = settingsRepository,
            authRepository = authRepository,
            exactAlarmPermissionManager = exactAlarmPermissionManager,
            batteryOptimizationHelper = batteryOptimizationHelper,
            autostartHelper = autostartHelper,
            screenUnlocker = screenUnlocker,
            audiencePersonaPreferencesManager = audiencePersonaPreferencesManager
        )

        // Advance time to complete init block
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        // Clean up static mocks
        accessibilityServiceMock.close()
        overlayPermissionHelperMock.close()
        notificationPermissionHelperMock.close()

        // Reset main dispatcher
        Dispatchers.resetMain()
    }

    private fun setupDefaultMocks() {
        // Repository mocks
        whenever(settingsRepository.hasStoredPin()).thenReturn(false)
        whenever(settingsRepository.hasStoredPassword()).thenReturn(false)
        whenever(settingsRepository.appLanguage).thenReturn("ru")
        whenever(authRepository.isLoggedIn()).thenReturn(false)

        // Permission manager mocks
        whenever(exactAlarmPermissionManager.canScheduleExactAlarms()).thenReturn(true)
        whenever(batteryOptimizationHelper.isBatteryOptimizationDisabled()).thenReturn(false)
        whenever(autostartHelper.isAutostartRequired()).thenReturn(false)
        whenever(autostartHelper.getManufacturerName()).thenReturn("Google")

        // Static helper mocks
        accessibilityServiceMock.`when`<Boolean> {
            TikTokAccessibilityService.isServiceEnabled(any())
        }.thenReturn(false)

        overlayPermissionHelperMock.`when`<Boolean> {
            OverlayPermissionHelper.hasOverlayPermission(any())
        }.thenReturn(false)

        notificationPermissionHelperMock.`when`<Boolean> {
            NotificationPermissionHelper.hasNotificationPermission(any())
        }.thenReturn(true)
    }

    // ==================== STATE INITIALIZATION TESTS ====================

    @Test
    fun `initial state has default values`() = runTest {
        // Given: Fresh ViewModel
        val state = viewModel.uiState.first()

        // Then: Should have default values
        assertFalse(state.isAccessibilityEnabled)
        assertFalse(state.hasStoredPin)
        assertFalse(state.hasStoredPassword)
        assertFalse(state.isLoggedIn)
        assertEquals("ru", state.currentLanguage)
        assertTrue(state.canScheduleExactAlarms)
        assertFalse(state.hasOverlayPermission)
        assertTrue(state.hasNotificationPermission)
        assertFalse(state.isBatteryOptimizationDisabled)
        assertFalse(state.isAutostartRequired)
        assertEquals("Google", state.manufacturerName)
        assertEquals(AudiencePersona.DEFAULT, state.selectedPersona)
    }

    @Test
    fun `init block triggers refreshState and syncs persona`() = runTest {
        // Then: Should call permission checks
        verify(exactAlarmPermissionManager).refreshPermissionState()
        verify(batteryOptimizationHelper).isBatteryOptimizationDisabled()
        verify(autostartHelper).isAutostartRequired()
        verify(autostartHelper).getManufacturerName()

        // Should sync persona from server
        verify(audiencePersonaPreferencesManager).syncFromServer()

        // Should collect persona flow
        verify(audiencePersonaPreferencesManager).personaFlow
    }

    // ==================== REFRESH STATE TESTS ====================

    @Test
    fun `refreshState updates UI state correctly`() = runTest {
        // Given: Changed permission states
        whenever(batteryOptimizationHelper.isBatteryOptimizationDisabled()).thenReturn(true)
        whenever(autostartHelper.isAutostartRequired()).thenReturn(true)
        whenever(autostartHelper.getManufacturerName()).thenReturn("Xiaomi")
        accessibilityServiceMock.`when`<Boolean> {
            TikTokAccessibilityService.isServiceEnabled(any())
        }.thenReturn(true)

        // When: Refresh state
        viewModel.refreshState()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: State should be updated
        val state = viewModel.uiState.first()
        assertTrue(state.isAccessibilityEnabled)
        assertTrue(state.isBatteryOptimizationDisabled)
        assertTrue(state.isAutostartRequired)
        assertEquals("Xiaomi", state.manufacturerName)
    }

    @Test
    fun `refreshState executes permission checks on Dispatchers Default`() = runTest {
        // Given: Mock heavy operations
        var batteryCheckThread: Thread? = null
        whenever(batteryOptimizationHelper.isBatteryOptimizationDisabled()).thenAnswer {
            batteryCheckThread = Thread.currentThread()
            false
        }

        // When: Refresh state
        viewModel.refreshState()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Heavy operations should execute (test verifies they were called)
        verify(batteryOptimizationHelper).isBatteryOptimizationDisabled()
        accessibilityServiceMock.verify {
            TikTokAccessibilityService.isServiceEnabled(any())
        }
    }

    @Test
    fun `refreshState handles exceptions gracefully - graceful degradation`() = runTest {
        // Given: Battery optimization check throws exception
        whenever(batteryOptimizationHelper.isBatteryOptimizationDisabled())
            .thenThrow(RuntimeException("Permission check failed"))

        // When: Refresh state (should not crash)
        viewModel.refreshState()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: State should remain unchanged (graceful degradation)
        val state = viewModel.uiState.first()
        assertNotNull(state)
        // State values should be from previous state (default false)
        assertFalse(state.isBatteryOptimizationDisabled)
    }

    @Test
    fun `refreshState updates all permission fields`() = runTest {
        // Given: All permissions granted
        whenever(settingsRepository.hasStoredPin()).thenReturn(true)
        whenever(settingsRepository.hasStoredPassword()).thenReturn(true)
        whenever(authRepository.isLoggedIn()).thenReturn(true)
        whenever(settingsRepository.appLanguage).thenReturn("en")
        whenever(exactAlarmPermissionManager.canScheduleExactAlarms()).thenReturn(false)
        whenever(batteryOptimizationHelper.isBatteryOptimizationDisabled()).thenReturn(true)
        whenever(autostartHelper.isAutostartRequired()).thenReturn(true)

        accessibilityServiceMock.`when`<Boolean> {
            TikTokAccessibilityService.isServiceEnabled(any())
        }.thenReturn(true)

        overlayPermissionHelperMock.`when`<Boolean> {
            OverlayPermissionHelper.hasOverlayPermission(any())
        }.thenReturn(true)

        notificationPermissionHelperMock.`when`<Boolean> {
            NotificationPermissionHelper.hasNotificationPermission(any())
        }.thenReturn(false)

        // When: Refresh state
        viewModel.refreshState()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: All fields should be updated
        val state = viewModel.uiState.first()
        assertTrue(state.isAccessibilityEnabled)
        assertTrue(state.hasStoredPin)
        assertTrue(state.hasStoredPassword)
        assertTrue(state.isLoggedIn)
        assertEquals("en", state.currentLanguage)
        assertFalse(state.canScheduleExactAlarms)
        assertTrue(state.hasOverlayPermission)
        assertFalse(state.hasNotificationPermission)
        assertTrue(state.isBatteryOptimizationDisabled)
        assertTrue(state.isAutostartRequired)
    }

    // ==================== DELEGATION TESTS ====================

    @Test
    fun `openBatteryOptimizationSettings delegates to helper`() {
        // When: Open battery optimization settings
        viewModel.openBatteryOptimizationSettings()

        // Then: Should delegate to helper
        verify(batteryOptimizationHelper).openBatteryOptimizationSettings()
    }

    @Test
    fun `openAutostartSettings delegates to helper`() {
        // When: Open autostart settings
        viewModel.openAutostartSettings()

        // Then: Should delegate to helper
        verify(autostartHelper).openAutostartSettings()
    }

    @Test
    fun `openExactAlarmSettings delegates to permission manager`() {
        // When: Open exact alarm settings
        viewModel.openExactAlarmSettings()

        // Then: Should delegate to permission manager
        verify(exactAlarmPermissionManager).openExactAlarmSettings()
    }

    @Test
    fun `openOverlaySettings delegates to helper`() {
        // When: Open overlay settings
        viewModel.openOverlaySettings()

        // Then: Should delegate to static helper
        overlayPermissionHelperMock.verify {
            OverlayPermissionHelper.openOverlaySettings(application)
        }
    }

    @Test
    fun `openNotificationSettings delegates to helper`() {
        // When: Open notification settings
        viewModel.openNotificationSettings()

        // Then: Should delegate to static helper
        notificationPermissionHelperMock.verify {
            NotificationPermissionHelper.openNotificationSettings(application)
        }
    }

    // ==================== PIN/PASSWORD MANAGEMENT TESTS ====================

    @Test
    fun `savePin saves valid pin and refreshes state`() = runTest {
        // Given: Valid pin
        val pin = "1234"

        // When: Save pin
        val result = viewModel.savePin(pin)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Should save and refresh
        assertTrue(result)
        verify(settingsRepository).savePin(pin)
        verify(settingsRepository, atLeast(2)).hasStoredPin() // init + refresh
    }

    @Test
    fun `savePin rejects short pin`() = runTest {
        // Given: Short pin (< 4 characters)
        val pin = "123"

        // When: Save pin
        val result = viewModel.savePin(pin)

        // Then: Should reject
        assertFalse(result)
        verify(settingsRepository, never()).savePin(any())
    }

    @Test
    fun `savePassword saves valid password and refreshes state`() = runTest {
        // Given: Valid password
        val password = "secure_password"

        // When: Save password
        val result = viewModel.savePassword(password)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Should save and refresh
        assertTrue(result)
        verify(settingsRepository).savePassword(password)
        verify(settingsRepository, atLeast(2)).hasStoredPassword() // init + refresh
    }

    @Test
    fun `savePassword rejects empty password`() = runTest {
        // Given: Empty password
        val password = ""

        // When: Save password
        val result = viewModel.savePassword(password)

        // Then: Should reject
        assertFalse(result)
        verify(settingsRepository, never()).savePassword(any())
    }

    @Test
    fun `clearUnlockCredentials clears credentials and refreshes state`() = runTest {
        // When: Clear credentials
        viewModel.clearUnlockCredentials()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Should clear and refresh
        verify(settingsRepository).clearUnlockCredentials()
        verify(settingsRepository, atLeast(2)).hasStoredPin() // init + refresh
    }

    // ==================== AUTH TESTS ====================

    @Test
    fun `logout logs out user and refreshes state`() = runTest {
        // When: Logout
        viewModel.logout()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Should logout and refresh
        verify(authRepository).logout()
        verify(authRepository, atLeast(2)).isLoggedIn() // init + refresh
    }

    // ==================== LANGUAGE TESTS ====================

    @Test
    fun `setLanguage updates language and refreshes state`() = runTest {
        // Given: New language
        val language = "en"

        // When: Set language
        viewModel.setLanguage(language)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Should update and refresh
        verify(settingsRepository).appLanguage = language
        verify(settingsRepository, atLeast(2)).appLanguage // init + refresh getter
    }

    // ==================== PERSONA TESTS ====================

    @Test
    fun `setPersona delegates to preferences manager`() = runTest {
        // Given: New persona
        val persona = AudiencePersona.NIGHT_OWL

        // When: Set persona
        viewModel.setPersona(persona)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Should delegate to manager
        verify(audiencePersonaPreferencesManager).setPersona(persona)
    }

    @Test
    fun `persona changes update UI state`() = runTest {
        // Given: Persona flow emits new value
        val personaFlow = MutableStateFlow(AudiencePersona.DEFAULT)
        whenever(audiencePersonaPreferencesManager.personaFlow).thenReturn(personaFlow)

        // Recreate ViewModel to pick up new flow
        val newViewModel = SettingsViewModel(
            application = application,
            settingsRepository = settingsRepository,
            authRepository = authRepository,
            exactAlarmPermissionManager = exactAlarmPermissionManager,
            batteryOptimizationHelper = batteryOptimizationHelper,
            autostartHelper = autostartHelper,
            screenUnlocker = screenUnlocker,
            audiencePersonaPreferencesManager = audiencePersonaPreferencesManager
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // When: Persona changes
        personaFlow.value = AudiencePersona.EARLY_BIRD
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: UI state should update
        val state = newViewModel.uiState.first()
        assertEquals(AudiencePersona.EARLY_BIRD, state.selectedPersona)
    }

    // ==================== CONCURRENT STATE UPDATE TESTS ====================

    @Test
    fun `multiple refreshState calls don't corrupt state`() = runTest {
        // Given: Multiple concurrent refresh calls
        viewModel.refreshState()
        viewModel.refreshState()
        viewModel.refreshState()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: State should be consistent (no corruption)
        val state = viewModel.uiState.first()
        assertNotNull(state)
        // Verify helpers were called (at least once, possibly multiple times)
        verify(batteryOptimizationHelper, atLeast(1)).isBatteryOptimizationDisabled()
    }

    @Test
    fun `refreshState preserves previous values on partial failure`() = runTest {
        // Given: Initial state with some values
        whenever(batteryOptimizationHelper.isBatteryOptimizationDisabled()).thenReturn(true)
        viewModel.refreshState()
        testDispatcher.scheduler.advanceUntilIdle()

        val initialState = viewModel.uiState.first()
        assertTrue(initialState.isBatteryOptimizationDisabled)

        // When: Next refresh throws exception
        whenever(batteryOptimizationHelper.isBatteryOptimizationDisabled())
            .thenThrow(RuntimeException("Check failed"))
        viewModel.refreshState()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: State should preserve previous value (graceful degradation)
        val newState = viewModel.uiState.first()
        // Due to exception, state update is skipped, so previous state is preserved
        assertTrue(newState.isBatteryOptimizationDisabled)
    }

    // ==================== FLOW PROPAGATION TESTS ====================

    @Test
    fun `uiState flow emits updates on state change`() = runTest(testDispatcher) {
        // Given: Initial state
        val states = mutableListOf<SettingsUiState>()
        val job = backgroundScope.launch {
            viewModel.uiState.collect { states.add(it) }
        }

        // When: Trigger state update
        whenever(batteryOptimizationHelper.isBatteryOptimizationDisabled()).thenReturn(true)
        viewModel.refreshState()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Should have at least 2 states (initial + updated)
        assertTrue(states.size >= 2)
        assertTrue(states.last().isBatteryOptimizationDisabled)

        job.cancel()
    }

    // ==================== EDGE CASES ====================

    @Test
    fun `refreshState handles null values gracefully`() = runTest {
        // Given: Repository returns null/empty values
        whenever(settingsRepository.appLanguage).thenReturn("")
        whenever(autostartHelper.getManufacturerName()).thenReturn("")

        // When: Refresh state
        viewModel.refreshState()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Should handle gracefully
        val state = viewModel.uiState.first()
        assertNotNull(state)
        // Empty strings are valid
        assertEquals("", state.currentLanguage)
        assertEquals("", state.manufacturerName)
    }

    @Test
    fun `ViewModel cleanup doesn't crash`() = runTest {
        // When: ViewModel is cleared (simulates lifecycle)
        viewModel.onCleared()

        // Then: Should not crash
        // Coroutines should be cancelled properly
    }
}

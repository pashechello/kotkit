package com.kotkit.basic.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import timber.log.Timber
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeviceStateChecker monitors device state and determines if phone is available for publishing.
 *
 * Checks:
 * - Screen state (on/off)
 * - Charging state
 * - Proximity sensor (phone in pocket)
 * - User activity/idle time
 * - Battery level
 * - Network connectivity
 *
 * Thread-safe: Uses atomic variables for state that can be accessed from multiple threads.
 */
@Singleton
class DeviceStateChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DeviceStateChecker"
    }

    private val powerManager: PowerManager? = context.getSystemService()
    private val batteryManager: BatteryManager? = context.getSystemService()
    private val sensorManager: SensorManager? = context.getSystemService()
    private val connectivityManager: ConnectivityManager? = context.getSystemService()

    private val proximitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    // Thread-safe state variables
    private val lastUserActivityTime = AtomicLong(System.currentTimeMillis())
    private val isProximityNear = AtomicBoolean(false)
    private val isMonitoring = AtomicBoolean(false)

    private val _deviceState = MutableStateFlow(getCurrentStateSnapshot())
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    // BroadcastReceivers
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Timber.tag(TAG).d("Screen turned ON")
                    onUserActivity()
                    updateState()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Timber.tag(TAG).d("Screen turned OFF")
                    updateState()
                }
                Intent.ACTION_USER_PRESENT -> {
                    Timber.tag(TAG).d("User unlocked device")
                    onUserActivity()
                    updateState()
                }
            }
        }
    }

    private val chargingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    Timber.tag(TAG).d("Power connected")
                    updateState()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    Timber.tag(TAG).d("Power disconnected")
                    updateState()
                }
            }
        }
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val maxRange = proximitySensor?.maximumRange ?: 5f
            val near = event.values[0] < maxRange
            isProximityNear.set(near)
            Timber.tag(TAG).d("Proximity: ${if (near) "NEAR" else "FAR"}")
            updateState()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Start monitoring device state.
     * Thread-safe: uses compareAndSet to prevent double registration.
     */
    fun startMonitoring() {
        if (!isMonitoring.compareAndSet(false, true)) {
            Timber.tag(TAG).d("Already monitoring")
            return
        }

        Timber.tag(TAG).i("Starting device state monitoring")

        // Register screen state receiver with RECEIVER_NOT_EXPORTED for Android 14+
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                screenStateReceiver,
                screenFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(screenStateReceiver, screenFilter)
        }

        // Register charging state receiver
        val chargingFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                chargingStateReceiver,
                chargingFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(chargingStateReceiver, chargingFilter)
        }

        // Register proximity sensor
        proximitySensor?.let { sensor ->
            sensorManager?.registerListener(
                proximityListener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        } ?: Timber.tag(TAG).w("Proximity sensor not available")

        updateState()
    }

    /**
     * Stop monitoring device state.
     * Thread-safe: uses compareAndSet to prevent double unregistration.
     */
    fun stopMonitoring() {
        if (!isMonitoring.compareAndSet(true, false)) {
            Timber.tag(TAG).d("Not monitoring")
            return
        }

        Timber.tag(TAG).i("Stopping device state monitoring")

        try {
            context.unregisterReceiver(screenStateReceiver)
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).w("Screen state receiver not registered")
        }

        try {
            context.unregisterReceiver(chargingStateReceiver)
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).w("Charging state receiver not registered")
        }

        sensorManager?.unregisterListener(proximityListener)
    }

    /**
     * Get current device state snapshot.
     * Thread-safe: reads atomic values.
     */
    fun getCurrentStateSnapshot(): DeviceState {
        return DeviceState(
            isScreenOff = !isScreenOn(),
            isCharging = isCharging(),
            isInPocket = isProximityNear.get(),
            idleMinutes = getIdleMinutes(),
            batteryLevel = getBatteryLevel(),
            isWifiConnected = isWifiConnected()
        )
    }

    /**
     * Check if device meets publishing conditions.
     */
    fun canPublish(conditions: PublishConditions): PublishCheckResult {
        val state = getCurrentStateSnapshot()
        val issues = mutableListOf<String>()

        if (conditions.requireScreenOff && !state.isScreenOff) {
            issues.add("Screen is on")
        }

        if (conditions.requireCharging && !state.isCharging) {
            issues.add("Not charging")
        }

        if (conditions.requireInPocket && !state.isInPocket) {
            issues.add("Not in pocket")
        }

        if (conditions.idleTimeoutMinutes > 0 && state.idleMinutes < conditions.idleTimeoutMinutes) {
            issues.add("User was active ${state.idleMinutes} min ago (need ${conditions.idleTimeoutMinutes} min)")
        }

        return if (issues.isEmpty()) {
            PublishCheckResult.Ready
        } else {
            PublishCheckResult.NotReady(issues)
        }
    }

    /**
     * Call when user activity is detected.
     * Thread-safe: uses AtomicLong.
     */
    fun onUserActivity() {
        lastUserActivityTime.set(System.currentTimeMillis())
        Timber.tag(TAG).d("User activity detected")
    }

    private fun isScreenOn(): Boolean {
        return powerManager?.isInteractive ?: run {
            Timber.tag(TAG).w("PowerManager unavailable, assuming screen is on")
            true
        }
    }

    private fun isCharging(): Boolean {
        return batteryManager?.isCharging ?: run {
            Timber.tag(TAG).w("BatteryManager unavailable, assuming not charging")
            false
        }
    }

    private fun getBatteryLevel(): Int {
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: run {
            Timber.tag(TAG).w("BatteryManager unavailable, assuming 50% battery")
            50
        }
    }

    private fun getIdleMinutes(): Int {
        return ((System.currentTimeMillis() - lastUserActivityTime.get()) / 60_000).toInt()
    }

    private fun isWifiConnected(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun updateState() {
        _deviceState.value = getCurrentStateSnapshot()
    }
}

/**
 * Result of checking if device is ready to publish
 */
sealed class PublishCheckResult {
    /**
     * Device is ready to publish
     */
    object Ready : PublishCheckResult()

    /**
     * Device is not ready with list of reasons
     */
    data class NotReady(val reasons: List<String>) : PublishCheckResult()
}

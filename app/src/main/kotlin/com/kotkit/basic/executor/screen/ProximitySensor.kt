package com.kotkit.basic.executor.screen

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * ProximitySensor - Detects if phone is in pocket/bag before posting.
 *
 * If something is blocking the proximity sensor (pocket, bag, face),
 * we should reschedule the post to avoid accidental touches.
 */
@Singleton
class ProximitySensor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ProximitySensor"
        private const val CHECK_TIMEOUT_MS = 1000L // 1 second to get reading
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    /**
     * Check if proximity sensor is available on this device.
     */
    fun isAvailable(): Boolean = proximitySensor != null

    /**
     * Quick check if something is currently close to the sensor (phone in pocket/bag).
     *
     * @return CheckResult indicating the state
     */
    suspend fun quickCheck(): CheckResult {
        val sensor = proximitySensor
        if (sensor == null) {
            Log.w(TAG, "Proximity sensor not available")
            return CheckResult.SensorUnavailable
        }

        val result = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
            suspendCancellableCoroutine<CheckResult> { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sensorManager.unregisterListener(this)

                        // Proximity sensor returns distance in cm
                        // If < maxRange, something is close (blocked)
                        val distance = event.values[0]
                        val maxRange = sensor.maximumRange
                        val isBlocked = distance < maxRange

                        Log.d(TAG, "Proximity: ${distance}cm (max: ${maxRange}cm) → ${if (isBlocked) "BLOCKED" else "CLEAR"}")

                        if (continuation.isActive) {
                            continuation.resume(
                                if (isBlocked) CheckResult.Blocked else CheckResult.Clear
                            )
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }

                sensorManager.registerListener(
                    listener,
                    sensor,
                    SensorManager.SENSOR_DELAY_FASTEST // Fast reading
                )

                continuation.invokeOnCancellation {
                    sensorManager.unregisterListener(listener)
                }
            }
        }

        return result ?: run {
            Log.w(TAG, "Proximity check timeout")
            CheckResult.SensorUnavailable
        }
    }

    sealed class CheckResult {
        /** Proximity sensor is clear - safe to proceed */
        object Clear : CheckResult()

        /** Something is blocking the sensor (pocket, bag, face) - should reschedule */
        object Blocked : CheckResult()

        /** Sensor not available on device - proceed with caution */
        object SensorUnavailable : CheckResult()
    }
}

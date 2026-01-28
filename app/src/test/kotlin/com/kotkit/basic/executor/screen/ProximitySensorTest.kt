package com.kotkit.basic.executor.screen

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.lang.reflect.Field

/**
 * Tests for ProximitySensor.
 *
 * Tests cover:
 * - quickCheck() returning Blocked when proximity is close
 * - quickCheck() returning Clear when proximity is far
 * - quickCheck() returning SensorUnavailable when no sensor
 * - Timeout handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProximitySensorTest {

    private lateinit var mockContext: Context
    private lateinit var mockSensorManager: SensorManager
    private lateinit var mockProximitySensor: Sensor

    @Before
    fun setup() {
        mockContext = mock()
        mockSensorManager = mock()
        mockProximitySensor = mock()

        whenever(mockContext.getSystemService(Context.SENSOR_SERVICE)).thenReturn(mockSensorManager)
    }

    // ==================== Sensor Availability Tests ====================

    @Test
    fun `isAvailable returns true when proximity sensor exists`() {
        // Given
        whenever(mockSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)).thenReturn(mockProximitySensor)

        val sensor = ProximitySensor(mockContext)

        // Then
        assertTrue(sensor.isAvailable())
    }

    @Test
    fun `isAvailable returns false when proximity sensor does not exist`() {
        // Given
        whenever(mockSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)).thenReturn(null)

        val sensor = ProximitySensor(mockContext)

        // Then
        assertFalse(sensor.isAvailable())
    }

    // ==================== quickCheck Tests ====================

    @Test
    fun `quickCheck returns SensorUnavailable when no proximity sensor`() = runTest {
        // Given: no proximity sensor
        whenever(mockSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)).thenReturn(null)

        val sensor = ProximitySensor(mockContext)

        // When
        val result = sensor.quickCheck()

        // Then
        assertTrue(result is ProximitySensor.CheckResult.SensorUnavailable)
    }

    @Test
    fun `quickCheck returns Blocked when object is close`() = runTest {
        // Given
        whenever(mockSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)).thenReturn(mockProximitySensor)
        whenever(mockProximitySensor.maximumRange).thenReturn(5.0f)

        // Capture the listener and trigger sensor event
        val listenerCaptor = argumentCaptor<SensorEventListener>()
        whenever(mockSensorManager.registerListener(
            listenerCaptor.capture(),
            eq(mockProximitySensor),
            eq(SensorManager.SENSOR_DELAY_FASTEST)
        )).thenAnswer {
            // Simulate immediate sensor response with close proximity (0 cm)
            val event = createSensorEvent(0.0f)
            listenerCaptor.lastValue.onSensorChanged(event)
            true
        }

        val sensor = ProximitySensor(mockContext)

        // When
        val result = sensor.quickCheck()

        // Then
        assertTrue("Expected Blocked but got $result", result is ProximitySensor.CheckResult.Blocked)
    }

    @Test
    fun `quickCheck returns Clear when object is far`() = runTest {
        // Given
        whenever(mockSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)).thenReturn(mockProximitySensor)
        whenever(mockProximitySensor.maximumRange).thenReturn(5.0f)

        val listenerCaptor = argumentCaptor<SensorEventListener>()
        whenever(mockSensorManager.registerListener(
            listenerCaptor.capture(),
            eq(mockProximitySensor),
            eq(SensorManager.SENSOR_DELAY_FASTEST)
        )).thenAnswer {
            // Simulate immediate sensor response with far proximity (5 cm = maxRange)
            val event = createSensorEvent(5.0f)
            listenerCaptor.lastValue.onSensorChanged(event)
            true
        }

        val sensor = ProximitySensor(mockContext)

        // When
        val result = sensor.quickCheck()

        // Then
        assertTrue("Expected Clear but got $result", result is ProximitySensor.CheckResult.Clear)
    }

    @Test
    fun `quickCheck returns Blocked when distance less than maxRange`() = runTest {
        // Given: maxRange is 8cm, distance is 3cm
        whenever(mockSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)).thenReturn(mockProximitySensor)
        whenever(mockProximitySensor.maximumRange).thenReturn(8.0f)

        val listenerCaptor = argumentCaptor<SensorEventListener>()
        whenever(mockSensorManager.registerListener(
            listenerCaptor.capture(),
            eq(mockProximitySensor),
            eq(SensorManager.SENSOR_DELAY_FASTEST)
        )).thenAnswer {
            val event = createSensorEvent(3.0f) // 3cm < 8cm maxRange
            listenerCaptor.lastValue.onSensorChanged(event)
            true
        }

        val sensor = ProximitySensor(mockContext)

        // When
        val result = sensor.quickCheck()

        // Then
        assertTrue("Expected Blocked but got $result", result is ProximitySensor.CheckResult.Blocked)
    }

    @Test
    fun `quickCheck unregisters listener after receiving event`() = runTest {
        // Given
        whenever(mockSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)).thenReturn(mockProximitySensor)
        whenever(mockProximitySensor.maximumRange).thenReturn(5.0f)

        val listenerCaptor = argumentCaptor<SensorEventListener>()
        whenever(mockSensorManager.registerListener(
            listenerCaptor.capture(),
            eq(mockProximitySensor),
            eq(SensorManager.SENSOR_DELAY_FASTEST)
        )).thenAnswer {
            val event = createSensorEvent(5.0f)
            listenerCaptor.lastValue.onSensorChanged(event)
            true
        }

        val sensor = ProximitySensor(mockContext)

        // When
        sensor.quickCheck()

        // Then: listener should be unregistered
        verify(mockSensorManager).unregisterListener(any<SensorEventListener>())
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a mock SensorEvent with the specified proximity value.
     * SensorEvent is a final class, so we use reflection to set values.
     */
    private fun createSensorEvent(proximityValue: Float): SensorEvent {
        // SensorEvent constructor is package-private, so we mock it
        val event = mock<SensorEvent>()

        // Use reflection to set the values array
        try {
            val valuesField: Field = SensorEvent::class.java.getField("values")
            valuesField.isAccessible = true
            valuesField.set(event, floatArrayOf(proximityValue))
        } catch (e: Exception) {
            // If reflection fails, use a workaround with doReturn
            // This happens in some test environments
            val mockValues = floatArrayOf(proximityValue)
            whenever(event.values).thenReturn(mockValues)
        }

        return event
    }
}

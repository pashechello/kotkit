package com.autoposter.executor.advanced

import com.autoposter.privileged.InputInjector
import com.autoposter.privileged.ServerProtocol
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * AdvancedHumanizer - Generates fully humanized input events for sendevent injection.
 *
 * Unlike BasicHumanizer (Accessibility API), this can control:
 * - Touch pressure curve (sinusoidal, realistic attack/decay)
 * - Touch size (correlates with pressure)
 * - Micro-movements during touch (finger tremor)
 * - Tracking ID management
 * - Precise timing between events
 *
 * This produces touches that are virtually indistinguishable from real human input.
 */
class AdvancedHumanizer(
    private val config: AdvancedHumanizerConfig = AdvancedHumanizerConfig()
) {

    private val random = Random(System.currentTimeMillis())
    private var trackingIdCounter = Random.nextInt(1000, 10000)

    /**
     * Generate fully humanized tap events.
     *
     * @param targetX Target X coordinate
     * @param targetY Target Y coordinate
     * @param elementWidth Element width for adaptive jitter
     * @param elementHeight Element height for adaptive jitter
     * @param maxPressure Max pressure value for device
     * @param maxTouchMajor Max touch major value for device
     * @return List of input events to inject
     */
    fun generateHumanizedTap(
        targetX: Int,
        targetY: Int,
        elementWidth: Int,
        elementHeight: Int,
        maxPressure: Int = 255,
        maxTouchMajor: Int = 255
    ): List<ServerProtocol.InputEvent> {
        val events = mutableListOf<ServerProtocol.InputEvent>()

        // 1. Calculate jittered coordinates
        val (finalX, finalY) = calculateJitteredCoordinates(
            targetX, targetY, elementWidth, elementHeight
        )

        // 2. Generate tap duration
        val durationMs = generateLogNormalDuration()
        val stepCount = (durationMs / config.eventIntervalMs).toInt().coerceIn(5, 30)

        // 3. Generate pressure curve
        val pressureCurve = generatePressureCurve(stepCount, maxPressure)

        // 4. Get tracking ID
        val trackingId = nextTrackingId()

        // 5. Generate micro-movement offsets
        val microMovements = generateMicroMovements(stepCount)

        // 6. Build events
        for (i in 0 until stepCount) {
            val pressure = pressureCurve[i]
            val touchMajor = calculateTouchMajor(pressure, maxPressure, maxTouchMajor)
            val (offsetX, offsetY) = microMovements[i]

            val x = finalX + offsetX
            val y = finalY + offsetY

            // First event: BTN_TOUCH down, tracking ID
            if (i == 0) {
                events.add(ServerProtocol.InputEvent(
                    InputInjector.EV_ABS,
                    InputInjector.ABS_MT_TRACKING_ID,
                    trackingId
                ))
                events.add(ServerProtocol.InputEvent(
                    InputInjector.EV_KEY,
                    InputInjector.BTN_TOUCH,
                    1
                ))
            }

            // Position
            events.add(ServerProtocol.InputEvent(
                InputInjector.EV_ABS,
                InputInjector.ABS_MT_POSITION_X,
                x
            ))
            events.add(ServerProtocol.InputEvent(
                InputInjector.EV_ABS,
                InputInjector.ABS_MT_POSITION_Y,
                y
            ))

            // Pressure
            events.add(ServerProtocol.InputEvent(
                InputInjector.EV_ABS,
                InputInjector.ABS_MT_PRESSURE,
                pressure
            ))

            // Touch size
            events.add(ServerProtocol.InputEvent(
                InputInjector.EV_ABS,
                InputInjector.ABS_MT_TOUCH_MAJOR,
                touchMajor
            ))

            // Sync
            events.add(ServerProtocol.InputEvent(
                InputInjector.EV_SYN,
                InputInjector.SYN_REPORT,
                0
            ))

            // Last event: release
            if (i == stepCount - 1) {
                events.add(ServerProtocol.InputEvent(
                    InputInjector.EV_ABS,
                    InputInjector.ABS_MT_TRACKING_ID,
                    -1
                ))
                events.add(ServerProtocol.InputEvent(
                    InputInjector.EV_KEY,
                    InputInjector.BTN_TOUCH,
                    0
                ))
                events.add(ServerProtocol.InputEvent(
                    InputInjector.EV_SYN,
                    InputInjector.SYN_REPORT,
                    0
                ))
            }
        }

        return events
    }

    /**
     * Generate humanized swipe events.
     */
    fun generateHumanizedSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
        maxPressure: Int = 255,
        maxTouchMajor: Int = 255
    ): List<ServerProtocol.InputEvent> {
        val events = mutableListOf<ServerProtocol.InputEvent>()

        // Calculate number of steps based on duration
        val stepCount = (durationMs / config.eventIntervalMs).toInt().coerceIn(10, 100)

        // Generate pressure curve (constant-ish with slight variation)
        val basePressure = (maxPressure * config.swipePressureFactor).toInt()

        // Get tracking ID
        val trackingId = nextTrackingId()

        // Jitter start and end points
        val jitteredStartX = startX + (nextGaussian() * 5).toInt()
        val jitteredStartY = startY + (nextGaussian() * 5).toInt()
        val jitteredEndX = endX + (nextGaussian() * 10).toInt()
        val jitteredEndY = endY + (nextGaussian() * 10).toInt()

        for (i in 0 until stepCount) {
            val t = i.toFloat() / (stepCount - 1)

            // Interpolate position with slight curve (not perfectly linear)
            val curve = easeInOutCubic(t)
            val x = (jitteredStartX + (jitteredEndX - jitteredStartX) * curve).toInt()
            val y = (jitteredStartY + (jitteredEndY - jitteredStartY) * curve).toInt()

            // Add micro-movement
            val microX = (nextGaussian() * 2).toInt()
            val microY = (nextGaussian() * 2).toInt()

            // Pressure varies slightly during swipe
            val pressureVariation = (nextGaussian() * 10).toInt()
            val pressure = (basePressure + pressureVariation).coerceIn(50, maxPressure)
            val touchMajor = calculateTouchMajor(pressure, maxPressure, maxTouchMajor)

            // First event
            if (i == 0) {
                events.add(ServerProtocol.InputEvent(
                    InputInjector.EV_ABS,
                    InputInjector.ABS_MT_TRACKING_ID,
                    trackingId
                ))
                events.add(ServerProtocol.InputEvent(
                    InputInjector.EV_KEY,
                    InputInjector.BTN_TOUCH,
                    1
                ))
            }

            // Position with micro-movement
            events.add(ServerProtocol.InputEvent(
                InputInjector.EV_ABS,
                InputInjector.ABS_MT_POSITION_X,
                x + microX
            ))
            events.add(ServerProtocol.InputEvent(
                InputInjector.EV_ABS,
                InputInjector.ABS_MT_POSITION_Y,
                y + microY
            ))
            events.add(ServerProtocol.InputEvent(
                InputInjector.EV_ABS,
                InputInjector.ABS_MT_PRESSURE,
                pressure
            ))
            events.add(ServerProtocol.InputEvent(
                InputInjector.EV_ABS,
                InputInjector.ABS_MT_TOUCH_MAJOR,
                touchMajor
            ))
            events.add(ServerProtocol.InputEvent(
                InputInjector.EV_SYN,
                InputInjector.SYN_REPORT,
                0
            ))

            // Last event: release
            if (i == stepCount - 1) {
                events.add(ServerProtocol.InputEvent(
                    InputInjector.EV_ABS,
                    InputInjector.ABS_MT_TRACKING_ID,
                    -1
                ))
                events.add(ServerProtocol.InputEvent(
                    InputInjector.EV_KEY,
                    InputInjector.BTN_TOUCH,
                    0
                ))
                events.add(ServerProtocol.InputEvent(
                    InputInjector.EV_SYN,
                    InputInjector.SYN_REPORT,
                    0
                ))
            }
        }

        return events
    }

    /**
     * Generate pre-action delay (reaction time).
     */
    fun generatePreActionDelay(): Long {
        val mu = ln(config.preDelayMode.toDouble())
        val sigma = 0.4
        val sample = exp(mu + sigma * nextGaussian())
        return sample.toLong().coerceIn(config.preDelayMin, config.preDelayMax)
    }

    /**
     * Generate post-action delay.
     */
    fun generatePostActionDelay(): Long {
        val mu = ln(config.postDelayMode.toDouble())
        val sigma = 0.3
        val sample = exp(mu + sigma * nextGaussian())
        return sample.toLong().coerceIn(config.postDelayMin, config.postDelayMax)
    }

    /**
     * Calculate jittered coordinates with adaptive sigma.
     */
    private fun calculateJitteredCoordinates(
        targetX: Int,
        targetY: Int,
        elementWidth: Int,
        elementHeight: Int
    ): Pair<Int, Int> {
        // Sigma proportional to element size
        val sigmaX = elementWidth / config.jitterSigmaFactor
        val sigmaY = elementHeight / config.jitterSigmaFactor

        // Gaussian jitter
        val jitterX = (nextGaussian() * sigmaX).toInt()
        val jitterY = (nextGaussian() * sigmaY).toInt()

        // Right-handed bias
        val biasX = (nextGaussian() * config.biasX).toInt()
        val biasY = (nextGaussian() * config.biasY).toInt()

        // Clamp to element bounds
        val x = (targetX + jitterX + biasX).coerceIn(
            targetX - elementWidth / 2,
            targetX + elementWidth / 2
        )
        val y = (targetY + jitterY + biasY).coerceIn(
            targetY - elementHeight / 2,
            targetY + elementHeight / 2
        )

        return Pair(x, y)
    }

    /**
     * Generate sinusoidal pressure curve.
     * Simulates natural finger pressure: soft attack, peak, soft release.
     */
    private fun generatePressureCurve(stepCount: Int, maxPressure: Int): List<Int> {
        val curve = mutableListOf<Int>()
        val peakPressure = (maxPressure * config.peakPressureFactor).toInt()
        val minPressure = (maxPressure * config.minPressureFactor).toInt()

        for (i in 0 until stepCount) {
            val t = i.toFloat() / (stepCount - 1)

            // Sinusoidal curve: 0 -> peak -> 0
            val basePressure = minPressure +
                    (peakPressure - minPressure) * sin(PI * t).toFloat()

            // Add slight noise
            val noise = (nextGaussian() * 5).toInt()

            val pressure = (basePressure.toInt() + noise).coerceIn(minPressure, peakPressure)
            curve.add(pressure)
        }

        return curve
    }

    /**
     * Calculate touch major (contact area) based on pressure.
     * More pressure = larger contact area.
     */
    private fun calculateTouchMajor(pressure: Int, maxPressure: Int, maxTouchMajor: Int): Int {
        val normalized = pressure.toFloat() / maxPressure
        val minTouch = (maxTouchMajor * config.minTouchMajorFactor).toInt()
        val maxTouch = (maxTouchMajor * config.maxTouchMajorFactor).toInt()

        val base = minTouch + ((maxTouch - minTouch) * normalized).toInt()
        val noise = (nextGaussian() * 3).toInt()

        return (base + noise).coerceIn(minTouch, maxTouch)
    }

    /**
     * Generate micro-movements (finger tremor).
     */
    private fun generateMicroMovements(stepCount: Int): List<Pair<Int, Int>> {
        return (0 until stepCount).map {
            val x = (nextGaussian() * config.microMovementRadius).toInt()
            val y = (nextGaussian() * config.microMovementRadius).toInt()
            Pair(x, y)
        }
    }

    /**
     * Ease-in-out cubic for natural swipe motion.
     */
    private fun easeInOutCubic(t: Float): Float {
        return if (t < 0.5f) {
            4 * t * t * t
        } else {
            1 - (-2 * t + 2).let { it * it * it } / 2
        }
    }

    /**
     * Get next tracking ID (wraps around).
     */
    private fun nextTrackingId(): Int {
        trackingIdCounter++
        if (trackingIdCounter > 65535) {
            trackingIdCounter = Random.nextInt(1000, 10000)
        }
        return trackingIdCounter
    }

    /**
     * Generate log-normal distributed tap duration.
     */
    private fun generateLogNormalDuration(): Long {
        val mu = ln(config.durationMode.toDouble())
        val sigma = 0.3
        val sample = exp(mu + sigma * nextGaussian())
        return sample.toLong().coerceIn(config.durationMin, config.durationMax)
    }

    /**
     * Generate standard normal random number (Box-Muller).
     */
    private fun nextGaussian(): Double {
        val u1 = random.nextDouble()
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }
}

/**
 * Configuration for AdvancedHumanizer.
 */
data class AdvancedHumanizerConfig(
    // Coordinate jitter
    val jitterSigmaFactor: Double = 6.0,
    val biasX: Double = 2.0,
    val biasY: Double = 2.0,

    // Tap duration (log-normal)
    val durationMin: Long = 70,
    val durationMax: Long = 150,
    val durationMode: Long = 100,

    // Event timing
    val eventIntervalMs: Long = 8, // ~125 Hz typical touch rate

    // Pressure curve
    val minPressureFactor: Float = 0.3f,
    val peakPressureFactor: Float = 0.8f,
    val swipePressureFactor: Float = 0.5f,

    // Touch major (contact area)
    val minTouchMajorFactor: Float = 0.1f,
    val maxTouchMajorFactor: Float = 0.4f,

    // Micro-movements
    val microMovementRadius: Double = 2.0,

    // Delays
    val preDelayMin: Long = 150,
    val preDelayMax: Long = 600,
    val preDelayMode: Long = 300,
    val postDelayMin: Long = 200,
    val postDelayMax: Long = 800,
    val postDelayMode: Long = 400
)

package com.autoposter.executor.humanizer

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

/**
 * BasicHumanizer - Adds human-like variations to touch inputs.
 *
 * For Basic Mode (Accessibility API), we can only control:
 * - Tap coordinates (with jitter)
 * - Tap duration
 * - Delays between actions
 *
 * This is simpler than Advanced Mode (sendevent) which can also control
 * pressure, touch size, and micro-movements.
 */
class BasicHumanizer(
    private val config: BasicHumanizerConfig = BasicHumanizerConfig()
) {

    private val random = Random(System.currentTimeMillis())

    /**
     * Humanize tap coordinates with adaptive Gaussian jitter.
     *
     * @param targetX Target X coordinate (center of element)
     * @param targetY Target Y coordinate (center of element)
     * @param elementWidth Width of the element (from backend)
     * @param elementHeight Height of the element (from backend)
     * @return HumanizedTap with jittered coordinates and duration
     */
    fun humanizeTap(
        targetX: Int,
        targetY: Int,
        elementWidth: Int? = null,
        elementHeight: Int? = null
    ): HumanizedTap {
        // Use provided element size or defaults
        val width = elementWidth ?: config.defaultElementWidth
        val height = elementHeight ?: config.defaultElementHeight

        // Calculate adaptive sigma based on element size
        // Smaller elements = smaller sigma = more precise taps
        val sigmaX = width / config.jitterSigmaFactor
        val sigmaY = height / config.jitterSigmaFactor

        // Apply Gaussian jitter
        val jitterX = (nextGaussian() * sigmaX).toInt()
        val jitterY = (nextGaussian() * sigmaY).toInt()

        // Add slight right-down bias (typical for right-handed users)
        val biasX = (nextGaussian() * config.biasX).toInt()
        val biasY = (nextGaussian() * config.biasY).toInt()

        // Calculate final coordinates
        val finalX = targetX + jitterX + biasX
        val finalY = targetY + jitterY + biasY

        // Ensure coordinates stay within element bounds (approximately)
        val clampedX = finalX.coerceIn(
            targetX - width / 2,
            targetX + width / 2
        )
        val clampedY = finalY.coerceIn(
            targetY - height / 2,
            targetY + height / 2
        )

        // Generate log-normal tap duration
        val duration = generateLogNormalDuration()

        return HumanizedTap(
            x = clampedX,
            y = clampedY,
            durationMs = duration
        )
    }

    /**
     * Generate human-like delay before action.
     *
     * Uses log-normal distribution to simulate human reaction time.
     */
    fun generatePreActionDelay(): Long {
        val mu = ln(config.delayMode.toDouble())
        val sigma = 0.4
        val sample = exp(mu + sigma * nextGaussian())
        return sample.toLong().coerceIn(config.delayMin, config.delayMax)
    }

    /**
     * Generate delay after action (before next action).
     */
    fun generatePostActionDelay(): Long {
        val mu = ln(config.postDelayMode.toDouble())
        val sigma = 0.3
        val sample = exp(mu + sigma * nextGaussian())
        return sample.toLong().coerceIn(config.postDelayMin, config.postDelayMax)
    }

    /**
     * Generate humanized swipe parameters.
     */
    fun humanizeSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        baseDuration: Long = 300
    ): HumanizedSwipe {
        // Add slight variation to start point
        val jitteredStartX = startX + (nextGaussian() * 5).toInt()
        val jitteredStartY = startY + (nextGaussian() * 5).toInt()

        // Add variation to end point (more variance than start)
        val jitteredEndX = endX + (nextGaussian() * 10).toInt()
        val jitteredEndY = endY + (nextGaussian() * 10).toInt()

        // Vary duration ±20%
        val durationVariation = 0.8 + random.nextDouble() * 0.4
        val duration = (baseDuration * durationVariation).toLong()

        return HumanizedSwipe(
            startX = jitteredStartX,
            startY = jitteredStartY,
            endX = jitteredEndX,
            endY = jitteredEndY,
            durationMs = duration
        )
    }

    /**
     * Generate log-normal tap duration.
     *
     * Human tap durations follow log-normal distribution with:
     * - Mode (most common): ~100ms
     * - Min: ~70ms
     * - Max: ~150ms
     */
    private fun generateLogNormalDuration(): Long {
        val mu = ln(config.durationMode.toDouble())
        val sigma = 0.3
        val sample = exp(mu + sigma * nextGaussian())
        return sample.toLong().coerceIn(config.durationMin, config.durationMax)
    }

    /**
     * Generate standard normal distributed random number.
     * Uses Box-Muller transform.
     */
    private fun nextGaussian(): Double {
        val u1 = random.nextDouble()
        val u2 = random.nextDouble()
        return kotlin.math.sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}

/**
 * Configuration for BasicHumanizer.
 */
data class BasicHumanizerConfig(
    // Jitter configuration
    val jitterSigmaFactor: Double = 6.0,  // sigma = elementSize / factor
    val biasX: Double = 2.0,               // Right bias for right-handed users
    val biasY: Double = 2.0,               // Down bias

    // Tap duration (log-normal distribution)
    val durationMin: Long = 70,
    val durationMax: Long = 150,
    val durationMode: Long = 100,

    // Pre-action delay
    val delayMin: Long = 150,
    val delayMax: Long = 600,
    val delayMode: Long = 300,

    // Post-action delay
    val postDelayMin: Long = 200,
    val postDelayMax: Long = 800,
    val postDelayMode: Long = 400,

    // Default element size if not provided
    val defaultElementWidth: Int = 100,
    val defaultElementHeight: Int = 50
)

/**
 * Result of humanized tap calculation.
 */
data class HumanizedTap(
    val x: Int,
    val y: Int,
    val durationMs: Long
)

/**
 * Result of humanized swipe calculation.
 */
data class HumanizedSwipe(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long
)

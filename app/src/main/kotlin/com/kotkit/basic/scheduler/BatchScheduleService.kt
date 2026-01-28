package com.kotkit.basic.scheduler

import com.kotkit.basic.security.DeviceFingerprint
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * A single scheduled slot for a video in the batch
 */
data class ScheduledSlot(
    val videoIndex: Int,
    val dateTime: LocalDateTime
) {
    /**
     * Convert to Unix timestamp in milliseconds
     */
    fun toEpochMillis(): Long {
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

/**
 * Result of batch schedule generation
 */
data class BatchScheduleResult(
    val slots: List<ScheduledSlot>,
    val totalDays: Int,
    val videosPerDay: Int,
    val persona: AudiencePersona
)

/**
 * Service for generating batch schedules with smart time distribution.
 *
 * Features:
 * - Uses device fingerprint as seed for consistent randomization per device
 * - Distributes videos across peak hours based on audience persona
 * - Adds ±20 minute randomization to make posting times look natural
 * - Supports custom hours override
 */
@Singleton
class BatchScheduleService @Inject constructor(
    private val deviceFingerprint: DeviceFingerprint
) {
    companion object {
        /** Default randomization range in minutes */
        const val DEFAULT_RANDOMIZATION_MINUTES = 20

        /** Minimum videos per day */
        const val MIN_VIDEOS_PER_DAY = 1

        /** Maximum videos per day */
        const val MAX_VIDEOS_PER_DAY = 10
    }

    /**
     * Generate a batch schedule for multiple videos.
     *
     * @param videoCount Number of videos to schedule
     * @param persona Target audience persona (determines peak hours)
     * @param startDate First day to start scheduling
     * @param videosPerDay How many videos to post per day
     * @param customHours Optional custom hours to use instead of persona's peak hours
     * @param randomizationMinutes How much to randomize times (±minutes)
     *
     * @return BatchScheduleResult with all scheduled slots
     */
    fun generateSchedule(
        videoCount: Int,
        persona: AudiencePersona,
        startDate: LocalDate,
        videosPerDay: Int = 3,
        customHours: List<Int>? = null,
        randomizationMinutes: Int = DEFAULT_RANDOMIZATION_MINUTES
    ): BatchScheduleResult {
        require(videoCount > 0) { "Video count must be positive" }
        require(videosPerDay in MIN_VIDEOS_PER_DAY..MAX_VIDEOS_PER_DAY) {
            "Videos per day must be between $MIN_VIDEOS_PER_DAY and $MAX_VIDEOS_PER_DAY"
        }

        // Use device ID as seed for consistent randomization per device
        val seed = deviceFingerprint.getDeviceId().hashCode().toLong()
        val random = Random(seed)

        // Use custom hours if provided, otherwise use persona's peak hours
        val availableHours = customHours?.takeIf { it.isNotEmpty() } ?: persona.peakHours

        val slots = mutableListOf<ScheduledSlot>()
        var currentDate = startDate
        var videoIndex = 0
        var dayCounter = 0

        while (videoIndex < videoCount) {
            dayCounter++

            // Select hours for this day
            // Shuffle based on seed + day counter for variety across days
            val dayRandom = Random(seed + dayCounter)
            val todayHours = availableHours
                .shuffled(dayRandom)
                .take(videosPerDay.coerceAtMost(availableHours.size))
                .sorted()

            for (hour in todayHours) {
                if (videoIndex >= videoCount) break

                // Add randomization ±N minutes
                val minuteOffset = if (randomizationMinutes > 0) {
                    random.nextInt(-randomizationMinutes, randomizationMinutes + 1)
                } else {
                    0
                }

                // Create time with randomization, clamping to valid range
                val baseTime = LocalTime.of(hour, 0)
                val adjustedTime = baseTime.plusMinutes(minuteOffset.toLong())

                // Handle edge cases (before midnight, after midnight)
                val finalDateTime = if (adjustedTime.isBefore(LocalTime.of(0, 30))) {
                    // If randomization pushed time to very early morning, use original
                    LocalDateTime.of(currentDate, baseTime)
                } else {
                    LocalDateTime.of(currentDate, adjustedTime)
                }

                slots.add(
                    ScheduledSlot(
                        videoIndex = videoIndex,
                        dateTime = finalDateTime
                    )
                )
                videoIndex++
            }

            currentDate = currentDate.plusDays(1)
        }

        return BatchScheduleResult(
            slots = slots,
            totalDays = dayCounter,
            videosPerDay = videosPerDay,
            persona = persona
        )
    }

    /**
     * Preview schedule without committing - useful for UI preview
     */
    fun previewSchedule(
        videoCount: Int,
        persona: AudiencePersona,
        startDate: LocalDate,
        videosPerDay: Int = 3,
        customHours: List<Int>? = null
    ): BatchScheduleResult {
        return generateSchedule(
            videoCount = videoCount,
            persona = persona,
            startDate = startDate,
            videosPerDay = videosPerDay,
            customHours = customHours
        )
    }

    /**
     * Get recommended videos per day based on video count.
     * Aims for a balanced schedule over reasonable time period.
     */
    fun getRecommendedVideosPerDay(videoCount: Int): Int {
        return when {
            videoCount <= 3 -> 1
            videoCount <= 10 -> 2
            videoCount <= 21 -> 3
            videoCount <= 40 -> 4
            else -> 5
        }
    }
}

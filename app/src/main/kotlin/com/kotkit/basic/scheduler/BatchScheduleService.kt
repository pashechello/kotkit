package com.kotkit.basic.scheduler

import com.kotkit.basic.security.DeviceFingerprint
import timber.log.Timber
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
    val persona: AudiencePersona,
    val quality: ScheduleQuality = ScheduleQuality.OPTIMAL,
    val effectiveIntervalMinutes: Int = 120
)

/**
 * Quality indicator for generated schedule
 */
enum class ScheduleQuality(val priority: Int) {
    /** All peak hours, standard 2h intervals */
    OPTIMAL(1),
    /** Peak + adjacent hours, standard intervals */
    GOOD(2),
    /** Reduced intervals (1.5h - 1h) */
    COMPRESSED(3),
    /** Minimum intervals, even distribution */
    TIGHT(4)
}

/**
 * Result of adaptive capacity calculation.
 * Used to determine schedule quality for a given number of videos per day.
 */
data class AdaptiveCapacityResult(
    /** Maximum videos that can fit with adaptive intervals */
    val maxVideos: Int,
    /** Effective interval used (may be reduced from default 120min) */
    val effectiveIntervalMinutes: Int,
    /** Quality of the schedule at this capacity */
    val quality: ScheduleQuality,
    /** Whether hours needed to be expanded beyond peak hours */
    val hoursExpanded: Boolean,
    /** Warning message if schedule quality is degraded */
    val warningMessage: String? = null
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

        /** Maximum videos per day (adaptive algorithm handles intervals) */
        const val MAX_VIDEOS_PER_DAY = 15

        /** Minimum interval between posts in minutes (2 hours) */
        const val DEFAULT_MIN_INTERVAL_MINUTES = 120

        /** Interval tiers for adaptive scheduling (tried in order) */
        val INTERVAL_TIERS = listOf(120, 90, 60, 45, 30)

        /** Latest hour to schedule posts */
        const val LATEST_POSTING_HOUR = 23

        /** Earliest hour to schedule posts */
        const val EARLIEST_POSTING_HOUR = 6
    }

    /**
     * Calculate minimum distance between two hours, accounting for day wraparound.
     * Example: hour 23 and hour 1 are 2 hours apart, not 22.
     */
    private fun hourDistance(hour1: Int, hour2: Int): Int {
        val forward = (hour2 - hour1 + 24) % 24
        val backward = (hour1 - hour2 + 24) % 24
        return kotlin.math.min(forward, backward)
    }

    /**
     * Select hours with guaranteed minimum interval between them.
     * Uses greedy algorithm to maximize variety while respecting interval constraints.
     */
    private fun selectHoursWithInterval(
        availableHours: List<Int>,
        videosPerDay: Int,
        minIntervalMinutes: Int,
        random: Random
    ): List<Int> {
        // Shuffle for variety
        val shuffled = availableHours.shuffled(random)
        val selected = mutableListOf<Int>()
        val minIntervalHours = (minIntervalMinutes + 59) / 60  // Ceiling division

        for (hour in shuffled) {
            if (selected.isEmpty()) {
                // First hour always added
                selected.add(hour)
            } else {
                // Check if this hour satisfies minimum interval with ALL selected hours
                // Uses wraparound-aware distance calculation (23 and 1 are 2h apart, not 22h)
                val safeToAdd = selected.all { selectedHour ->
                    hourDistance(hour, selectedHour) >= minIntervalHours
                }

                if (safeToAdd) {
                    selected.add(hour)
                }
            }

            // Stop when we have enough
            if (selected.size >= videosPerDay) break
        }

        // Log warning if couldn't select enough hours
        if (selected.size < videosPerDay) {
            Timber.tag("BatchScheduleService").w(
                "Could only select ${selected.size} hours out of $videosPerDay requested due to interval constraints")
        }

        // Sort for chronological posting (AFTER selection, not before!)
        return selected.sorted()
    }

    /**
     * Calculate safe randomization offset that won't violate minimum interval.
     */
    private fun calculateSafeRandomizationOffset(
        hour: Int,
        adjacentHours: List<Int>,
        minIntervalMinutes: Int,
        maxRandomizationMinutes: Int
    ): Int {
        val sortedAdjacent = adjacentHours.sorted()
        val hourIndex = sortedAdjacent.indexOf(hour)

        // Find previous and next hours
        val prevHour = if (hourIndex > 0) sortedAdjacent[hourIndex - 1] else null
        val nextHour = if (hourIndex < sortedAdjacent.size - 1) sortedAdjacent[hourIndex + 1] else null

        // Calculate maximum forward offset using wraparound-aware distance
        val maxForward = if (nextHour != null) {
            val gapMinutes = hourDistance(hour, nextHour) * 60
            val safeGap = ((gapMinutes - minIntervalMinutes) / 2).coerceAtLeast(0)
            kotlin.math.min(maxRandomizationMinutes, safeGap)
        } else {
            maxRandomizationMinutes
        }

        // Calculate maximum backward offset using wraparound-aware distance
        val maxBackward = if (prevHour != null) {
            val gapMinutes = hourDistance(prevHour, hour) * 60
            val safeGap = ((gapMinutes - minIntervalMinutes) / 2).coerceAtLeast(0)
            kotlin.math.min(maxRandomizationMinutes, safeGap)
        } else {
            maxRandomizationMinutes
        }

        return kotlin.math.min(maxForward, maxBackward).coerceAtLeast(0)
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
        randomizationMinutes: Int = DEFAULT_RANDOMIZATION_MINUTES,
        minIntervalMinutes: Int = DEFAULT_MIN_INTERVAL_MINUTES,
        seed: Long? = null
    ): BatchScheduleResult {
        require(videoCount > 0) { "Video count must be positive" }
        require(videosPerDay in MIN_VIDEOS_PER_DAY..MAX_VIDEOS_PER_DAY) {
            "Videos per day must be between $MIN_VIDEOS_PER_DAY and $MAX_VIDEOS_PER_DAY"
        }

        // Use provided seed for fresh randomization, or fallback to device ID for consistency
        val effectiveSeed = seed ?: deviceFingerprint.getDeviceId().hashCode().toLong()
        val random = Random(effectiveSeed)

        // Use custom hours if provided, otherwise use persona's peak hours
        val availableHours = customHours?.takeIf { it.isNotEmpty() } ?: persona.peakHours

        val slots = mutableListOf<ScheduledSlot>()
        var currentDate = startDate
        var videoIndex = 0
        var dayCounter = 0

        while (videoIndex < videoCount) {
            dayCounter++

            // Select hours for this day with interval constraints
            // Uses greedy algorithm to ensure minimum intervals while preserving variety
            val dayRandom = Random(effectiveSeed + dayCounter)
            val todayHours = selectHoursWithInterval(
                availableHours = availableHours,
                videosPerDay = videosPerDay,
                minIntervalMinutes = minIntervalMinutes,
                random = dayRandom
            )

            for (hour in todayHours) {
                if (videoIndex >= videoCount) break

                // Calculate safe randomization offset that won't violate intervals
                val safeOffset = calculateSafeRandomizationOffset(
                    hour = hour,
                    adjacentHours = todayHours,
                    minIntervalMinutes = minIntervalMinutes,
                    maxRandomizationMinutes = randomizationMinutes
                )

                // Add randomization within safe bounds
                val minuteOffset = if (safeOffset > 0) {
                    random.nextInt(-safeOffset, safeOffset + 1)
                } else {
                    0
                }

                // Create time with safe randomization
                // Use LocalDateTime.plusMinutes() to properly handle day boundary crossing
                val baseDateTime = LocalDateTime.of(currentDate, LocalTime.of(hour, 0))
                val finalDateTime = baseDateTime.plusMinutes(minuteOffset.toLong())

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
        customHours: List<Int>? = null,
        minIntervalMinutes: Int = DEFAULT_MIN_INTERVAL_MINUTES,
        seed: Long? = null
    ): BatchScheduleResult {
        return generateSchedule(
            videoCount = videoCount,
            persona = persona,
            startDate = startDate,
            videosPerDay = videosPerDay,
            customHours = customHours,
            minIntervalMinutes = minIntervalMinutes,
            seed = seed
        )
    }

    /**
     * Generate ADAPTIVE schedule that tries to fit all posts in the requested day.
     *
     * Algorithm:
     * 1. For TODAY: Calculate remaining time window from startDateTime
     * 2. Try to fit using peak hours with standard 2h interval
     * 3. If not enough: expand to adjacent hours
     * 4. If still not enough: reduce interval progressively (90min → 60min → 45min → 30min)
     * 5. Last resort: distribute evenly across remaining window
     */
    fun generateAdaptiveSchedule(
        videoCount: Int,
        persona: AudiencePersona,
        startDateTime: LocalDateTime,
        videosPerDay: Int = 3,
        customHours: List<Int>? = null,
        randomizationMinutes: Int = DEFAULT_RANDOMIZATION_MINUTES,
        seed: Long? = null
    ): BatchScheduleResult {
        require(videoCount > 0) { "Video count must be positive" }
        require(videosPerDay in MIN_VIDEOS_PER_DAY..MAX_VIDEOS_PER_DAY)

        // Use provided seed for fresh randomization, or fallback to device ID
        val effectiveSeed = seed ?: deviceFingerprint.getDeviceId().hashCode().toLong()
        val random = Random(effectiveSeed)

        val allSlots = mutableListOf<ScheduledSlot>()
        var currentDate = startDateTime.toLocalDate()
        var videoIndex = 0
        var dayCounter = 0
        var worstQuality = ScheduleQuality.OPTIMAL
        var minEffectiveInterval = DEFAULT_MIN_INTERVAL_MINUTES

        val baseHours = customHours?.takeIf { it.isNotEmpty() } ?: persona.peakHours

        while (videoIndex < videoCount) {
            dayCounter++
            val videosNeededToday = minOf(videosPerDay, videoCount - videoIndex)

            // Determine start hour constraint for this day
            val isStartDay = (currentDate == startDateTime.toLocalDate())
            val startHour = if (isStartDay) {
                // Add 1 hour buffer from current time
                (startDateTime.hour + 1).coerceIn(EARLIEST_POSTING_HOUR, LATEST_POSTING_HOUR)
            } else {
                EARLIEST_POSTING_HOUR
            }

            // Generate slots for this day using adaptive algorithm
            val (daySlots, dayQuality, dayInterval) = generateAdaptiveDaySlots(
                date = currentDate,
                startHour = startHour,
                endHour = LATEST_POSTING_HOUR,
                videosNeeded = videosNeededToday,
                peakHours = baseHours,
                videoIndexStart = videoIndex,
                random = Random(effectiveSeed + dayCounter),
                randomizationMinutes = randomizationMinutes
            )

            allSlots.addAll(daySlots)
            videoIndex += daySlots.size

            // Track worst quality across all days
            if (dayQuality.priority > worstQuality.priority) {
                worstQuality = dayQuality
            }
            if (dayInterval < minEffectiveInterval) {
                minEffectiveInterval = dayInterval
            }

            currentDate = currentDate.plusDays(1)

            // Safety: prevent infinite loops
            if (dayCounter > 365) break
        }

        return BatchScheduleResult(
            slots = allSlots,
            totalDays = dayCounter,
            videosPerDay = videosPerDay,
            persona = persona,
            quality = worstQuality,
            effectiveIntervalMinutes = minEffectiveInterval
        )
    }

    /**
     * Generate adaptive slots for a single day.
     * Tries progressively relaxed constraints until all videos fit.
     *
     * @return Triple of (slots, quality, effectiveIntervalMinutes)
     */
    private fun generateAdaptiveDaySlots(
        date: LocalDate,
        startHour: Int,
        endHour: Int,
        videosNeeded: Int,
        peakHours: List<Int>,
        videoIndexStart: Int,
        random: Random,
        randomizationMinutes: Int
    ): Triple<List<ScheduledSlot>, ScheduleQuality, Int> {

        // Step 1: Filter peak hours within the available window
        val availablePeakHours = peakHours.filter { it in startHour..endHour }.sorted()

        // Step 2: Calculate adjacent hours (±1-2 hours from peak)
        val adjacentHours = peakHours
            .flatMap { peak -> listOf(peak - 2, peak - 1, peak + 1, peak + 2) }
            .filter { it in startHour..endHour && it !in peakHours }
            .distinct()
            .sorted()

        // Step 3: Combined hours (peak + adjacent)
        val combinedHours = (availablePeakHours + adjacentHours).distinct().sorted()

        // Step 4: All hours in window (for even distribution)
        val allHoursInWindow = (startHour..endHour).toList()

        // Try fitting with progressively relaxed constraints
        for (intervalMinutes in INTERVAL_TIERS) {
            val intervalHours = (intervalMinutes + 59) / 60

            // Strategy A: Peak hours only
            val peakSlots = selectHoursWithMinInterval(availablePeakHours, videosNeeded, intervalHours)
            if (peakSlots.size >= videosNeeded) {
                val quality = if (intervalMinutes == DEFAULT_MIN_INTERVAL_MINUTES)
                    ScheduleQuality.OPTIMAL else ScheduleQuality.COMPRESSED
                return Triple(
                    createSlotsFromHours(peakSlots.take(videosNeeded), date, videoIndexStart, random, randomizationMinutes, intervalMinutes),
                    quality,
                    intervalMinutes
                )
            }

            // Strategy B: Peak + Adjacent hours
            val combinedSlots = selectHoursWithMinInterval(combinedHours, videosNeeded, intervalHours)
            if (combinedSlots.size >= videosNeeded) {
                val quality = if (intervalMinutes >= 90) ScheduleQuality.GOOD else ScheduleQuality.COMPRESSED
                return Triple(
                    createSlotsFromHours(combinedSlots.take(videosNeeded), date, videoIndexStart, random, randomizationMinutes, intervalMinutes),
                    quality,
                    intervalMinutes
                )
            }
        }

        // Strategy C: Even distribution (last resort)
        val evenlyDistributed = distributeEvenlyInWindow(startHour, endHour, videosNeeded)
        val minInterval = if (evenlyDistributed.size > 1) {
            ((endHour - startHour) * 60) / (evenlyDistributed.size - 1)
        } else 60

        return Triple(
            createSlotsFromHours(evenlyDistributed, date, videoIndexStart, random, randomizationMinutes / 2, minInterval),
            ScheduleQuality.TIGHT,
            minInterval.coerceAtLeast(30)
        )
    }

    /**
     * Select hours with minimum interval constraint using greedy algorithm.
     */
    private fun selectHoursWithMinInterval(
        hours: List<Int>,
        needed: Int,
        intervalHours: Int
    ): List<Int> {
        if (hours.isEmpty()) return emptyList()

        val selected = mutableListOf<Int>()
        for (hour in hours.sorted()) {
            if (selected.isEmpty()) {
                selected.add(hour)
            } else if (hour - selected.last() >= intervalHours) {
                selected.add(hour)
            }
            if (selected.size >= needed) break
        }
        return selected
    }

    /**
     * Distribute N slots evenly across a time window.
     */
    private fun distributeEvenlyInWindow(
        startHour: Int,
        endHour: Int,
        count: Int
    ): List<Int> {
        val windowSize = endHour - startHour + 1
        if (count <= 0 || windowSize <= 0) return emptyList()
        if (count == 1) return listOf((startHour + endHour) / 2)

        // Distribute evenly
        val step = windowSize.toFloat() / count
        return (0 until count).map { i ->
            (startHour + (i * step + step / 2).toInt()).coerceIn(startHour, endHour)
        }.distinct()
    }

    /**
     * Create ScheduledSlot objects from selected hours.
     */
    private fun createSlotsFromHours(
        hours: List<Int>,
        date: LocalDate,
        videoIndexStart: Int,
        random: Random,
        randomizationMinutes: Int,
        effectiveInterval: Int
    ): List<ScheduledSlot> {
        return hours.mapIndexed { index, hour ->
            // Calculate safe randomization
            val safeOffset = calculateSafeRandomizationOffset(
                hour = hour,
                adjacentHours = hours,
                minIntervalMinutes = effectiveInterval,
                maxRandomizationMinutes = randomizationMinutes
            )

            val minuteOffset = if (safeOffset > 0) {
                random.nextInt(-safeOffset, safeOffset + 1)
            } else {
                0
            }

            val baseDateTime = LocalDateTime.of(date, LocalTime.of(hour, 0))
            val finalDateTime = baseDateTime.plusMinutes(minuteOffset.toLong())

            ScheduledSlot(
                videoIndex = videoIndexStart + index,
                dateTime = finalDateTime
            )
        }
    }

    /**
     * Calculate maximum videos that can fit in given hours with interval constraints.
     * Uses greedy algorithm to find the best combination.
     *
     * @param availableHours List of hours (0-23) available for scheduling
     * @param minIntervalMinutes Minimum gap between posts
     * @return Maximum number of videos that can be scheduled per day
     */
    fun calculateMaxCapacity(
        availableHours: List<Int>,
        minIntervalMinutes: Int = DEFAULT_MIN_INTERVAL_MINUTES
    ): Int {
        if (availableHours.isEmpty()) return 0
        if (availableHours.size == 1) return 1

        val minIntervalHours = (minIntervalMinutes + 59) / 60
        val sortedHours = availableHours.sorted()

        // Use greedy algorithm: start from earliest hour, take next valid hour
        var count = 1
        var lastHour = sortedHours.first()

        for (hour in sortedHours.drop(1)) {
            if (hour - lastHour >= minIntervalHours) {
                count++
                lastHour = hour
            }
        }

        return count
    }

    /**
     * Get the effective maximum videos per day for a persona.
     */
    fun getMaxVideosPerDay(
        persona: AudiencePersona,
        customHours: List<Int>? = null,
        minIntervalMinutes: Int = DEFAULT_MIN_INTERVAL_MINUTES
    ): Int {
        val hours = customHours?.takeIf { it.isNotEmpty() } ?: persona.peakHours
        return calculateMaxCapacity(hours, minIntervalMinutes)
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

    /**
     * Calculate maximum videos per day using ADAPTIVE algorithm.
     *
     * Unlike calculateMaxCapacity() which uses fixed 2h interval,
     * this method tries progressively shorter intervals (120 -> 90 -> 60 -> 45 -> 30)
     * and optionally expands to adjacent hours to fit more videos.
     *
     * @param peakHours Base peak hours from persona
     * @param hardCap Absolute maximum videos per day (default MAX_VIDEOS_PER_DAY)
     * @return AdaptiveCapacityResult with max capacity and quality info
     */
    fun calculateAdaptiveCapacity(
        peakHours: List<Int>,
        hardCap: Int = MAX_VIDEOS_PER_DAY
    ): AdaptiveCapacityResult {
        if (peakHours.isEmpty()) {
            return AdaptiveCapacityResult(
                maxVideos = 0,
                effectiveIntervalMinutes = DEFAULT_MIN_INTERVAL_MINUTES,
                quality = ScheduleQuality.OPTIMAL,
                hoursExpanded = false
            )
        }

        // Calculate adjacent hours (peak ± 1-2 hours)
        val adjacentHours = peakHours
            .flatMap { peak -> listOf(peak - 2, peak - 1, peak + 1, peak + 2) }
            .filter { it in EARLIEST_POSTING_HOUR..LATEST_POSTING_HOUR && it !in peakHours }
            .distinct()
            .sorted()

        val combinedHours = (peakHours + adjacentHours).distinct().sorted()

        // Try each interval tier to find maximum capacity
        var bestCapacity = 0
        var bestInterval = DEFAULT_MIN_INTERVAL_MINUTES
        var bestQuality = ScheduleQuality.OPTIMAL
        var expanded = false

        for (intervalMinutes in INTERVAL_TIERS) {
            val intervalHours = (intervalMinutes + 59) / 60

            // Try peak hours only
            val peakCapacity = selectHoursWithMinInterval(
                peakHours.sorted(), Int.MAX_VALUE, intervalHours
            ).size

            if (peakCapacity > bestCapacity) {
                bestCapacity = peakCapacity
                bestInterval = intervalMinutes
                bestQuality = when {
                    intervalMinutes == DEFAULT_MIN_INTERVAL_MINUTES -> ScheduleQuality.OPTIMAL
                    intervalMinutes >= 90 -> ScheduleQuality.GOOD
                    intervalMinutes >= 60 -> ScheduleQuality.COMPRESSED
                    else -> ScheduleQuality.TIGHT
                }
                expanded = false
            }

            // Try combined hours (peak + adjacent)
            val combinedCapacity = selectHoursWithMinInterval(
                combinedHours, Int.MAX_VALUE, intervalHours
            ).size

            if (combinedCapacity > bestCapacity) {
                bestCapacity = combinedCapacity
                bestInterval = intervalMinutes
                bestQuality = when {
                    intervalMinutes >= 90 -> ScheduleQuality.GOOD
                    intervalMinutes >= 60 -> ScheduleQuality.COMPRESSED
                    else -> ScheduleQuality.TIGHT
                }
                expanded = true
            }

            // Stop if we've reached the hard cap
            if (bestCapacity >= hardCap) {
                bestCapacity = hardCap
                break
            }
        }

        return AdaptiveCapacityResult(
            maxVideos = minOf(bestCapacity, hardCap),
            effectiveIntervalMinutes = bestInterval,
            quality = bestQuality,
            hoursExpanded = expanded,
            warningMessage = if (bestQuality == ScheduleQuality.TIGHT) {
                "Very tight schedule"
            } else null
        )
    }

    /**
     * Check what schedule quality would result from posting N videos per day.
     * Used by UI to show warnings when user selects high video count.
     *
     * @param peakHours Base peak hours from persona
     * @param videosPerDay Desired number of videos per day
     * @return AdaptiveCapacityResult with quality info for this count
     */
    fun checkCapacityForCount(
        peakHours: List<Int>,
        videosPerDay: Int
    ): AdaptiveCapacityResult {
        if (peakHours.isEmpty()) {
            return AdaptiveCapacityResult(
                maxVideos = 0,
                effectiveIntervalMinutes = DEFAULT_MIN_INTERVAL_MINUTES,
                quality = ScheduleQuality.OPTIMAL,
                hoursExpanded = false,
                warningMessage = "No posting hours available"
            )
        }
        if (videosPerDay <= 0) {
            return AdaptiveCapacityResult(
                maxVideos = 0,
                effectiveIntervalMinutes = DEFAULT_MIN_INTERVAL_MINUTES,
                quality = ScheduleQuality.OPTIMAL,
                hoursExpanded = false
            )
        }

        val adjacentHours = peakHours
            .flatMap { peak -> listOf(peak - 2, peak - 1, peak + 1, peak + 2) }
            .filter { it in EARLIEST_POSTING_HOUR..LATEST_POSTING_HOUR && it !in peakHours }
            .distinct()

        val combinedHours = (peakHours + adjacentHours).distinct().sorted()

        for (intervalMinutes in INTERVAL_TIERS) {
            val intervalHours = (intervalMinutes + 59) / 60

            // Try peak hours only
            val peakCapacity = selectHoursWithMinInterval(peakHours.sorted(), videosPerDay, intervalHours).size
            if (peakCapacity >= videosPerDay) {
                return AdaptiveCapacityResult(
                    maxVideos = videosPerDay,
                    effectiveIntervalMinutes = intervalMinutes,
                    quality = when {
                        intervalMinutes == DEFAULT_MIN_INTERVAL_MINUTES -> ScheduleQuality.OPTIMAL
                        intervalMinutes >= 90 -> ScheduleQuality.GOOD
                        else -> ScheduleQuality.COMPRESSED
                    },
                    hoursExpanded = false
                )
            }

            // Try combined hours
            val combinedCapacity = selectHoursWithMinInterval(combinedHours, videosPerDay, intervalHours).size
            if (combinedCapacity >= videosPerDay) {
                return AdaptiveCapacityResult(
                    maxVideos = videosPerDay,
                    effectiveIntervalMinutes = intervalMinutes,
                    quality = when {
                        intervalMinutes >= 90 -> ScheduleQuality.GOOD
                        intervalMinutes >= 60 -> ScheduleQuality.COMPRESSED
                        else -> ScheduleQuality.TIGHT
                    },
                    hoursExpanded = true,
                    warningMessage = if (intervalMinutes < 60) "Extended hours, tight schedule" else null
                )
            }
        }

        // Cannot fit with normal constraints - return TIGHT with warning
        return AdaptiveCapacityResult(
            maxVideos = videosPerDay,
            effectiveIntervalMinutes = INTERVAL_TIERS.last(),
            quality = ScheduleQuality.TIGHT,
            hoursExpanded = true,
            warningMessage = "Very tight schedule - consider fewer videos"
        )
    }
}

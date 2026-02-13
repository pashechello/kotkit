package com.kotkit.basic.scheduler

import androidx.annotation.StringRes
import com.kotkit.basic.R

/**
 * Audience persona types for smart schedule generation.
 * Each persona has different peak activity hours based on typical daily routines.
 *
 * The scheduler will randomly select hours from peakHours and add
 * randomization (±20 minutes) to make posting times look natural.
 */
enum class AudiencePersona(
    @StringRes val displayNameRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val emojiRes: Int,
    val peakHours: List<Int>  // Hours in 24h format when this audience is most active
) {
    /**
     * Student audience - active before classes, lunch break, evening after studies, late night
     */
    STUDENT(
        displayNameRes = R.string.persona_student_name,
        descriptionRes = R.string.persona_student_desc,
        emojiRes = R.string.persona_student_emoji,
        peakHours = listOf(7, 8, 12, 13, 18, 19, 22, 23)
    ),

    /**
     * Working professional - morning commute, lunch break, evening after work
     */
    WORKER(
        displayNameRes = R.string.persona_worker_name,
        descriptionRes = R.string.persona_worker_desc,
        emojiRes = R.string.persona_worker_emoji,
        peakHours = listOf(7, 8, 12, 13, 19, 20, 21)
    ),

    /**
     * Night owl - late riser, afternoon activity, evening/night peak
     */
    NIGHT_OWL(
        displayNameRes = R.string.persona_night_owl_name,
        descriptionRes = R.string.persona_night_owl_desc,
        emojiRes = R.string.persona_night_owl_emoji,
        peakHours = listOf(11, 12, 15, 16, 21, 22, 23)
    ),

    /**
     * Parent/homemaker - after morning routine, nap time, evening
     */
    PARENT(
        displayNameRes = R.string.persona_parent_name,
        descriptionRes = R.string.persona_parent_desc,
        emojiRes = R.string.persona_parent_emoji,
        peakHours = listOf(9, 10, 13, 14, 21, 22)
    ),

    /**
     * General audience - standard peak hours that work for most people
     */
    GENERAL(
        displayNameRes = R.string.persona_general_name,
        descriptionRes = R.string.persona_general_desc,
        emojiRes = R.string.persona_general_emoji,
        peakHours = listOf(9, 12, 14, 18, 20)
    );

    companion object {
        val DEFAULT = GENERAL
    }
}

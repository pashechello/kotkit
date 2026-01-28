package com.kotkit.basic.sound

import com.kotkit.basic.R

/**
 * Types of meow sounds for KotKit notifications.
 * Each sound corresponds to a specific event in the app.
 */
enum class SoundType(
    val rawResId: Int,
    val vibrationPattern: LongArray
) {
    /**
     * Warning sound - played before scheduled posting (e.g., 5 min before).
     * Soft, attention-grabbing meow.
     */
    MEOW_WARNING(
        rawResId = R.raw.meow_warning,
        vibrationPattern = longArrayOf(0, 200, 100, 200)
    ),

    /**
     * Starting sound - played when posting begins.
     * Short, businesslike meow.
     */
    MEOW_STARTING(
        rawResId = R.raw.meow_starting,
        vibrationPattern = longArrayOf(0, 150)
    ),

    /**
     * Success sound - played when posting completes successfully.
     * Happy purring sound.
     */
    MEOW_SUCCESS(
        rawResId = R.raw.meow_success,
        vibrationPattern = longArrayOf(0, 100, 50, 100, 50, 200)
    ),

    /**
     * Error sound - played when posting fails or is interrupted.
     * Sad, prolonged meow.
     */
    MEOW_ERROR(
        rawResId = R.raw.meow_error,
        vibrationPattern = longArrayOf(0, 400, 200, 400)
    ),

    /**
     * UI sound - played for positive UI interactions (button clicks, scheduling confirmed).
     * Short, cheerful meow.
     */
    MEOW_UI(
        rawResId = R.raw.meow_ui,
        vibrationPattern = longArrayOf(0, 50)
    )
}

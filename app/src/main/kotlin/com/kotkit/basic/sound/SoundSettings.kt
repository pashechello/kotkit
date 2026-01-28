package com.kotkit.basic.sound

/**
 * Sound notification settings for KotKit.
 * Controls which sounds are enabled and their volume.
 */
data class SoundSettings(
    /** Master switch for all sounds */
    val soundsEnabled: Boolean = true,

    /** Individual sound toggles */
    val warningEnabled: Boolean = true,
    val startingEnabled: Boolean = true,
    val successEnabled: Boolean = true,
    val errorEnabled: Boolean = true,
    val uiSoundsEnabled: Boolean = true,

    /** Volume level 0-100 */
    val volume: Int = 80,

    /** Vibration with sounds */
    val vibrationEnabled: Boolean = true
) {
    /**
     * Check if a specific sound type is enabled.
     */
    fun isEnabled(soundType: SoundType): Boolean {
        if (!soundsEnabled) return false

        return when (soundType) {
            SoundType.MEOW_WARNING -> warningEnabled
            SoundType.MEOW_STARTING -> startingEnabled
            SoundType.MEOW_SUCCESS -> successEnabled
            SoundType.MEOW_ERROR -> errorEnabled
            SoundType.MEOW_UI -> uiSoundsEnabled
        }
    }

    /**
     * Get volume as float (0.0 - 1.0) for MediaPlayer.
     */
    fun getVolumeFloat(): Float = volume / 100f
}

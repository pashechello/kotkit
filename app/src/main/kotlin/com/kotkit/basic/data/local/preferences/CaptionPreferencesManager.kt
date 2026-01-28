package com.kotkit.basic.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for caption generation preferences.
 * Uses SharedPreferences for non-sensitive user tone settings.
 */
@Singleton
class CaptionPreferencesManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _tonePromptFlow = MutableStateFlow(getTonePrompt())
    val tonePromptFlow: Flow<String> = _tonePromptFlow.asStateFlow()

    private val _isEnabledFlow = MutableStateFlow(isEnabled())
    val isEnabledFlow: Flow<Boolean> = _isEnabledFlow.asStateFlow()

    /**
     * Get user's tone prompt for caption generation.
     * Example: "Use emojis, casual tone, add #fyp #viral"
     */
    fun getTonePrompt(): String {
        return prefs.getString(KEY_TONE_PROMPT, "") ?: ""
    }

    /**
     * Save user's tone prompt.
     */
    fun setTonePrompt(prompt: String) {
        prefs.edit().putString(KEY_TONE_PROMPT, prompt).apply()
        _tonePromptFlow.value = prompt
    }

    /**
     * Check if custom tone is enabled.
     */
    fun isEnabled(): Boolean {
        return prefs.getBoolean(KEY_ENABLED, true)
    }

    /**
     * Enable or disable custom tone.
     */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _isEnabledFlow.value = enabled
    }

    /**
     * Get the effective tone prompt (returns null if disabled).
     */
    fun getEffectiveTonePrompt(): String? {
        return if (isEnabled()) {
            val prompt = getTonePrompt()
            if (prompt.isNotBlank()) prompt else null
        } else {
            null
        }
    }

    /**
     * Clear all caption preferences.
     */
    fun clear() {
        prefs.edit().clear().apply()
        _tonePromptFlow.value = ""
        _isEnabledFlow.value = true
    }

    companion object {
        private const val PREFS_NAME = "caption_preferences"
        private const val KEY_TONE_PROMPT = "tone_prompt"
        private const val KEY_ENABLED = "enabled"
    }
}

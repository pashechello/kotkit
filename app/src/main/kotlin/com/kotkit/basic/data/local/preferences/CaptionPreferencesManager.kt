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
 * Language for caption generation.
 * Hashtags are always in English, but caption text can be in different languages.
 */
enum class CaptionLanguage(val code: String, val displayName: String, val promptInstruction: String) {
    ENGLISH("en", "EN", "Write the caption text in English."),
    RUSSIAN("ru", "RU", "Write the caption text in Russian (Cyrillic). Hashtags should still be in English.");

    companion object {
        fun fromCode(code: String): CaptionLanguage =
            entries.find { it.code == code } ?: ENGLISH
    }
}

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

    private val _languageFlow = MutableStateFlow(getLanguage())
    val languageFlow: Flow<CaptionLanguage> = _languageFlow.asStateFlow()

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
     * Get caption language preference.
     */
    fun getLanguage(): CaptionLanguage {
        val code = prefs.getString(KEY_LANGUAGE, CaptionLanguage.ENGLISH.code) ?: CaptionLanguage.ENGLISH.code
        return CaptionLanguage.fromCode(code)
    }

    /**
     * Set caption language preference.
     */
    fun setLanguage(language: CaptionLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
        _languageFlow.value = language
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
     * Get effective tone prompt with language instruction included.
     */
    fun getEffectiveTonePromptWithLanguage(): String {
        val parts = mutableListOf<String>()

        // Add language instruction
        parts.add(getLanguage().promptInstruction)

        // Add user tone if enabled
        getEffectiveTonePrompt()?.let { parts.add(it) }

        return parts.joinToString(" ")
    }

    /**
     * Clear all caption preferences.
     */
    fun clear() {
        prefs.edit().clear().apply()
        _tonePromptFlow.value = ""
        _isEnabledFlow.value = true
        _languageFlow.value = CaptionLanguage.ENGLISH
    }

    companion object {
        private const val PREFS_NAME = "caption_preferences"
        private const val KEY_TONE_PROMPT = "tone_prompt"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LANGUAGE = "language"
    }
}

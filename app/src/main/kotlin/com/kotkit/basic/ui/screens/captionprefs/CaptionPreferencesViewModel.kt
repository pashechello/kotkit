package com.kotkit.basic.ui.screens.captionprefs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotkit.basic.data.local.preferences.CaptionPreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CaptionPreferencesViewModel @Inject constructor(
    private val captionPreferencesManager: CaptionPreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(CaptionPreferencesUiState())
    val uiState: StateFlow<CaptionPreferencesUiState> = _uiState.asStateFlow()

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    tonePrompt = captionPreferencesManager.getTonePrompt(),
                    isEnabled = captionPreferencesManager.isEnabled()
                )
            }
        }
    }

    fun setTonePrompt(prompt: String) {
        if (prompt.length <= MAX_TONE_LENGTH) {
            _uiState.update { it.copy(tonePrompt = prompt) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isEnabled = enabled) }
    }

    fun savePreferences() {
        viewModelScope.launch {
            captionPreferencesManager.setTonePrompt(_uiState.value.tonePrompt)
            captionPreferencesManager.setEnabled(_uiState.value.isEnabled)

            _uiState.update { it.copy(showSavedMessage = true) }
        }
    }

    fun clearSavedMessage() {
        _uiState.update { it.copy(showSavedMessage = false) }
    }

    companion object {
        const val MAX_TONE_LENGTH = 500
    }
}

data class CaptionPreferencesUiState(
    val tonePrompt: String = "",
    val isEnabled: Boolean = true,
    val showSavedMessage: Boolean = false
)

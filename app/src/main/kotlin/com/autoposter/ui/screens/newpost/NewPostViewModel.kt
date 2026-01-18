package com.autoposter.ui.screens.newpost

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autoposter.data.repository.PostRepository
import com.autoposter.scheduler.PostScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class NewPostViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val postRepository: PostRepository,
    private val postScheduler: PostScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewPostUiState())
    val uiState: StateFlow<NewPostUiState> = _uiState.asStateFlow()

    /**
     * Handle video URI from content picker.
     * Copies the video to app's internal storage to ensure access during scheduled posting.
     */
    fun setVideoUri(uri: Uri?) {
        if (uri == null) {
            _uiState.update { it.copy(videoUri = null, videoPath = null) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                val localPath = copyVideoToInternalStorage(uri)
                _uiState.update {
                    it.copy(
                        videoUri = uri,
                        videoPath = localPath,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        videoUri = null,
                        videoPath = null,
                        isLoading = false,
                        error = "Failed to load video: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Copy video from content:// URI to app's internal storage.
     * This ensures the video is accessible during scheduled posting,
     * even if the original file is moved/deleted.
     */
    private suspend fun copyVideoToInternalStorage(uri: Uri): String = withContext(Dispatchers.IO) {
        val videosDir = File(context.filesDir, "videos").apply {
            if (!exists()) mkdirs()
        }

        // Get original filename or generate one
        val fileName = getFileName(uri) ?: "video_${UUID.randomUUID()}.mp4"
        val destFile = File(videosDir, fileName)

        // Copy the file
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Cannot open video file")

        destFile.absolutePath
    }

    /**
     * Get filename from content URI
     */
    private fun getFileName(uri: Uri): String? {
        var name: String? = null

        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        name = cursor.getString(nameIndex)
                    }
                }
            }
        }

        if (name == null) {
            name = uri.path?.substringAfterLast('/')
        }

        return name
    }

    fun setCaption(caption: String) {
        _uiState.update { it.copy(caption = caption) }
    }

    fun setScheduledTime(time: Long) {
        _uiState.update { it.copy(scheduledTime = time) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun createPost(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val state = _uiState.value

        if (state.videoPath.isNullOrBlank()) {
            onError("Please select a video")
            return
        }

        if (state.scheduledTime <= System.currentTimeMillis()) {
            onError("Please select a future time")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                val postId = postRepository.createPost(
                    videoPath = state.videoPath,
                    caption = state.caption,
                    scheduledTime = state.scheduledTime
                )

                val post = postRepository.getById(postId)
                if (post != null) {
                    postScheduler.schedulePost(post)
                }

                _uiState.update { it.copy(isLoading = false) }
                onSuccess()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                onError(e.message ?: "Failed to create post")
            }
        }
    }
}

data class NewPostUiState(
    val videoUri: Uri? = null,
    val videoPath: String? = null,
    val caption: String = "",
    val scheduledTime: Long = System.currentTimeMillis() + 60 * 60 * 1000, // Default: 1 hour from now
    val isLoading: Boolean = false,
    val error: String? = null
)

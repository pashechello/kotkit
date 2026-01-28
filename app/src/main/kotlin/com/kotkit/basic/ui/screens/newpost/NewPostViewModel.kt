package com.kotkit.basic.ui.screens.newpost

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotkit.basic.data.local.preferences.CaptionPreferencesManager
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.GenerateCaptionRequest
import com.kotkit.basic.data.repository.PostRepository
import com.kotkit.basic.scheduler.AudiencePersona
import com.kotkit.basic.scheduler.BatchScheduleResult
import com.kotkit.basic.scheduler.BatchScheduleService
import com.kotkit.basic.scheduler.ScheduledSlot
import com.kotkit.basic.scheduler.SmartScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

/**
 * Video item with URI and optional generated caption
 */
data class VideoItem(
    val uri: Uri,
    val fileName: String,
    val localPath: String? = null,
    val caption: String = "",
    val isGeneratingCaption: Boolean = false
)

/**
 * Preview item combining video with scheduled time
 */
data class SchedulePreviewItem(
    val video: VideoItem,
    val slot: ScheduledSlot,
    val formattedTime: String
)

/**
 * UI State for NewPostScreen - supports both single and batch modes
 */
data class NewPostUiState(
    // Video selection - supports single or multiple videos
    val videos: List<VideoItem> = emptyList(),

    // Single video mode (videos.size == 1)
    val caption: String = "",
    val scheduledTime: Long = System.currentTimeMillis(),

    // Batch mode (videos.size > 1)
    val selectedPersona: AudiencePersona = AudiencePersona.DEFAULT,
    val videosPerDay: Int = 3,
    val startDate: LocalDate = LocalDate.now().plusDays(1),
    val useCustomHours: Boolean = false,
    val customHours: List<Int> = listOf(9, 14, 19),
    val schedulePreview: BatchScheduleResult? = null,
    val previewItems: List<SchedulePreviewItem> = emptyList(),

    // Loading states
    val isLoading: Boolean = false,
    val isCopyingVideos: Boolean = false,
    val isGeneratingCaption: Boolean = false,
    val isGeneratingCaptions: Boolean = false,
    val isScheduling: Boolean = false,
    val copyProgress: Int = 0,
    val scheduleProgress: Int = 0,
    val captionProgress: Int = 0,

    // Messages
    val error: String? = null,
    val successMessage: String? = null
) {
    /** Whether in batch mode (multiple videos selected) */
    val isBatchMode: Boolean get() = videos.size > 1

    /** Single video path for backward compatibility */
    val videoPath: String? get() = videos.firstOrNull()?.localPath

    /** Single video URI for backward compatibility */
    val videoUri: Uri? get() = videos.firstOrNull()?.uri
}

@HiltViewModel
class NewPostViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val postRepository: PostRepository,
    private val smartScheduler: SmartScheduler,
    private val apiService: ApiService,
    private val captionPreferencesManager: CaptionPreferencesManager,
    private val batchScheduleService: BatchScheduleService
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewPostUiState())
    val uiState: StateFlow<NewPostUiState> = _uiState.asStateFlow()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMM")

    // Job tracking for cancellation
    private var copyJob: Job? = null
    private var captionJob: Job? = null
    private var scheduleJob: Job? = null

    // Track if videos were successfully scheduled (don't clean up on exit)
    private var videosScheduled = false

    // ==================== Single Video Mode ====================

    /**
     * Handle single video URI from content picker.
     * Copies the video to app's internal storage to ensure access during scheduled posting.
     */
    fun setVideoUri(uri: Uri?) {
        if (uri == null) {
            _uiState.update { it.copy(videos = emptyList()) }
            return
        }
        setVideoUris(listOf(uri))
    }

    // ==================== Batch Mode ====================

    /**
     * Handle multiple video URIs from picker
     */
    fun setVideoUris(uris: List<Uri>) {
        if (uris.isEmpty()) {
            _uiState.update { it.copy(videos = emptyList()) }
            return
        }

        // Cancel any existing copy operation
        copyJob?.cancel()
        copyJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isCopyingVideos = true, copyProgress = 0) }

            try {
                val videos = mutableListOf<VideoItem>()
                uris.forEachIndexed { index, uri ->
                    val fileName = getFileName(uri) ?: "video_${index + 1}.mp4"
                    val localPath = copyVideoToInternalStorage(uri, fileName)
                    videos.add(
                        VideoItem(
                            uri = uri,
                            fileName = fileName,
                            localPath = localPath
                        )
                    )
                    _uiState.update {
                        it.copy(copyProgress = ((index + 1) * 100) / uris.size)
                    }
                }

                _uiState.update {
                    it.copy(
                        videos = videos,
                        isLoading = false,
                        isCopyingVideos = false,
                        videosPerDay = if (videos.size > 1) {
                            batchScheduleService.getRecommendedVideosPerDay(videos.size)
                        } else it.videosPerDay
                    )
                }

                // Auto-generate preview for batch mode
                if (videos.size > 1) {
                    updatePreview()
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        videos = emptyList(),
                        isLoading = false,
                        isCopyingVideos = false,
                        error = "Failed to load videos: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Remove video at index
     */
    fun removeVideo(index: Int) {
        val videos = _uiState.value.videos.toMutableList()
        if (index in videos.indices) {
            // Delete local file if exists
            videos[index].localPath?.let { path ->
                try {
                    File(path).delete()
                } catch (e: Exception) {
                    android.util.Log.w("NewPostVM", "Failed to delete video file: ${e.message}")
                }
            }
            videos.removeAt(index)
            _uiState.update { it.copy(videos = videos) }
            if (videos.size > 1) {
                updatePreview()
            }
        }
    }

    /**
     * Set audience persona for batch scheduling
     */
    fun setPersona(persona: AudiencePersona) {
        _uiState.update { it.copy(selectedPersona = persona) }
        updatePreview()
    }

    /**
     * Set start date for batch scheduling
     */
    fun setStartDate(date: LocalDate) {
        _uiState.update { it.copy(startDate = date) }
        updatePreview()
    }

    /**
     * Set videos per day for batch scheduling
     * Max is limited by total selected videos count
     */
    fun setVideosPerDay(count: Int) {
        val maxAllowed = _uiState.value.videos.size.coerceAtLeast(1)
        val clamped = count.coerceIn(
            BatchScheduleService.MIN_VIDEOS_PER_DAY,
            maxAllowed.coerceAtMost(BatchScheduleService.MAX_VIDEOS_PER_DAY)
        )
        _uiState.update { it.copy(videosPerDay = clamped) }
        updatePreview()
    }

    /**
     * Toggle custom hours mode
     */
    fun setUseCustomHours(use: Boolean) {
        _uiState.update { it.copy(useCustomHours = use) }
        updatePreview()
    }

    /**
     * Set custom hours with validation
     */
    fun setCustomHours(hours: List<Int>) {
        // Prevent empty custom hours when in custom mode
        val state = _uiState.value
        if (hours.isEmpty() && state.useCustomHours) {
            // Keep at least the last selected hour
            return
        }
        _uiState.update { it.copy(customHours = hours.sorted()) }
        if (state.useCustomHours) {
            updatePreview()
        }
    }

    /**
     * Update schedule preview based on current settings
     */
    private fun updatePreview() {
        val state = _uiState.value
        if (state.videos.size <= 1) {
            _uiState.update { it.copy(schedulePreview = null, previewItems = emptyList()) }
            return
        }

        val customHours = if (state.useCustomHours && state.customHours.isNotEmpty()) {
            state.customHours
        } else {
            null
        }

        val result = batchScheduleService.previewSchedule(
            videoCount = state.videos.size,
            persona = state.selectedPersona,
            startDate = state.startDate,
            videosPerDay = state.videosPerDay,
            customHours = customHours
        )

        val previewItems = result.slots.mapIndexed { index, slot ->
            val video = state.videos.getOrElse(index) { state.videos.first() }
            val dateStr = slot.dateTime.toLocalDate().format(dateFormatter)
            val timeStr = slot.dateTime.toLocalTime().format(timeFormatter)

            SchedulePreviewItem(
                video = video,
                slot = slot,
                formattedTime = "$dateStr, $timeStr"
            )
        }

        _uiState.update {
            it.copy(
                schedulePreview = result,
                previewItems = previewItems
            )
        }
    }

    // ==================== Caption Generation ====================

    fun setCaption(caption: String) {
        _uiState.update { it.copy(caption = caption) }
    }

    /**
     * Update caption for specific video in batch mode
     */
    fun updateVideoCaption(index: Int, caption: String) {
        val videos = _uiState.value.videos.toMutableList()
        if (index in videos.indices) {
            videos[index] = videos[index].copy(caption = caption)
            _uiState.update { it.copy(videos = videos) }
            updatePreview()
        }
    }

    /**
     * Generate caption for single video using AI
     */
    fun generateCaption() {
        captionJob?.cancel()
        captionJob = viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingCaption = true, error = null) }

            try {
                val tonePrompt = captionPreferencesManager.getEffectiveTonePrompt()
                val videoPath = _uiState.value.videoPath
                val trackName = if (videoPath != null) {
                    File(videoPath).nameWithoutExtension
                } else {
                    "TikTok Video"
                }

                val request = GenerateCaptionRequest(
                    trackName = trackName,
                    videoDescription = "",
                    tonePrompt = tonePrompt
                )

                val response = apiService.generateCaption(request)

                if (response.success) {
                    _uiState.update {
                        it.copy(
                            caption = response.caption,
                            isGeneratingCaption = false
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isGeneratingCaption = false,
                            error = "Failed to generate caption: ${response.error}"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isGeneratingCaption = false,
                        error = "Failed to generate caption: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Generate captions for all videos in batch mode using AI with parallelism
     */
    fun generateCaptions() {
        captionJob?.cancel()
        captionJob = viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingCaptions = true, captionProgress = 0) }

            try {
                val tonePrompt = captionPreferencesManager.getEffectiveTonePrompt()
                val currentVideos = _uiState.value.videos
                val results = Array(currentVideos.size) { currentVideos[it] }

                // Use semaphore for limited parallelism (3 concurrent requests)
                val semaphore = Semaphore(3)
                var completedCount = 0

                val deferreds = currentVideos.mapIndexed { index, video ->
                    async {
                        semaphore.withPermit {
                            try {
                                // Update individual video loading state
                                results[index] = video.copy(isGeneratingCaption = true)
                                _uiState.update { it.copy(videos = results.toList()) }

                                val trackName = File(video.fileName).nameWithoutExtension
                                val request = GenerateCaptionRequest(
                                    trackName = trackName,
                                    videoDescription = "",
                                    tonePrompt = tonePrompt
                                )

                                val response = apiService.generateCaption(request)

                                results[index] = if (response.success) {
                                    video.copy(
                                        caption = response.caption,
                                        isGeneratingCaption = false
                                    )
                                } else {
                                    video.copy(isGeneratingCaption = false)
                                }
                            } catch (e: Exception) {
                                results[index] = video.copy(isGeneratingCaption = false)
                            }

                            completedCount++
                            _uiState.update {
                                it.copy(
                                    videos = results.toList(),
                                    captionProgress = (completedCount * 100) / currentVideos.size
                                )
                            }
                        }
                    }
                }

                deferreds.awaitAll()

                // Atomically update videos AND previewItems together to avoid race condition
                val finalVideos = results.toList()
                val currentState = _uiState.value

                val customHours = if (currentState.useCustomHours && currentState.customHours.isNotEmpty()) {
                    currentState.customHours
                } else {
                    null
                }

                val scheduleResult = batchScheduleService.previewSchedule(
                    videoCount = finalVideos.size,
                    persona = currentState.selectedPersona,
                    startDate = currentState.startDate,
                    videosPerDay = currentState.videosPerDay,
                    customHours = customHours
                )

                val newPreviewItems = scheduleResult.slots.mapIndexed { index, slot ->
                    val video = finalVideos.getOrElse(index) { finalVideos.first() }
                    val dateStr = slot.dateTime.toLocalDate().format(dateFormatter)
                    val timeStr = slot.dateTime.toLocalTime().format(timeFormatter)
                    SchedulePreviewItem(video = video, slot = slot, formattedTime = "$dateStr, $timeStr")
                }

                _uiState.update {
                    it.copy(
                        isGeneratingCaptions = false,
                        videos = finalVideos,
                        schedulePreview = scheduleResult,
                        previewItems = newPreviewItems
                    )
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isGeneratingCaptions = false,
                        error = "Failed to generate captions: ${e.message}"
                    )
                }
            }
        }
    }

    // ==================== Scheduling ====================

    fun setScheduledTime(time: Long) {
        _uiState.update { it.copy(scheduledTime = time) }
    }

    /**
     * Update scheduled time for a specific slot in batch mode
     */
    fun updateSlotTime(videoIndex: Int, newDateTime: java.time.LocalDateTime) {
        val state = _uiState.value
        val currentPreview = state.schedulePreview ?: return

        // Create updated slots list
        val updatedSlots = currentPreview.slots.map { slot ->
            if (slot.videoIndex == videoIndex) {
                ScheduledSlot(videoIndex = slot.videoIndex, dateTime = newDateTime)
            } else {
                slot
            }
        }

        // Recreate preview items with updated times
        val previewItems = updatedSlots.mapIndexed { index, slot ->
            val video = state.videos.getOrElse(index) { state.videos.first() }
            val dateStr = slot.dateTime.toLocalDate().format(dateFormatter)
            val timeStr = slot.dateTime.toLocalTime().format(timeFormatter)

            SchedulePreviewItem(
                video = video,
                slot = slot,
                formattedTime = "$dateStr, $timeStr"
            )
        }

        _uiState.update {
            it.copy(
                schedulePreview = currentPreview.copy(slots = updatedSlots),
                previewItems = previewItems
            )
        }
    }

    /**
     * Create a single scheduled post
     */
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

        val videoPath = state.videoPath ?: return

        scheduleJob?.cancel()
        scheduleJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                val postId = postRepository.createPost(
                    videoPath = videoPath,
                    caption = state.caption,
                    scheduledTime = state.scheduledTime
                )

                val post = postRepository.getById(postId)
                if (post != null) {
                    smartScheduler.schedulePost(post)
                }

                videosScheduled = true
                _uiState.update { it.copy(isLoading = false) }
                onSuccess()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                onError(e.message ?: "Failed to create post")
            }
        }
    }

    /**
     * Post immediately - for testing purposes
     */
    fun postNow(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val state = _uiState.value
        android.util.Log.d("NewPostVM", "postNow called, videoPath=${state.videoPath}")

        if (state.videoPath.isNullOrBlank()) {
            android.util.Log.e("NewPostVM", "No video selected")
            onError("Please select a video")
            return
        }

        val videoPath = state.videoPath ?: return

        scheduleJob?.cancel()
        scheduleJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                android.util.Log.d("NewPostVM", "Creating post...")
                val postId = postRepository.createPost(
                    videoPath = videoPath,
                    caption = state.caption,
                    scheduledTime = System.currentTimeMillis() + 5000
                )
                android.util.Log.d("NewPostVM", "Post created with ID: $postId")

                val post = postRepository.getById(postId)
                if (post != null) {
                    android.util.Log.d("NewPostVM", "Force publishing post: $post")
                    smartScheduler.forcePublish(postId)
                    android.util.Log.d("NewPostVM", "Post force published!")
                } else {
                    android.util.Log.e("NewPostVM", "Post not found after creation!")
                }

                videosScheduled = true
                _uiState.update { it.copy(isLoading = false) }
                onSuccess()
            } catch (e: Exception) {
                android.util.Log.e("NewPostVM", "Error: ${e.message}", e)
                _uiState.update { it.copy(isLoading = false) }
                onError(e.message ?: "Failed to create post")
            }
        }
    }

    /**
     * Schedule all videos in batch mode
     */
    fun scheduleAll(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val state = _uiState.value

        if (state.videos.isEmpty()) {
            onError("Please select videos first")
            return
        }

        if (state.schedulePreview == null) {
            onError("Schedule preview not generated")
            return
        }

        scheduleJob?.cancel()
        scheduleJob = viewModelScope.launch {
            _uiState.update { it.copy(isScheduling = true, scheduleProgress = 0) }

            try {
                val slots = state.schedulePreview.slots
                var successCount = 0
                var skippedCount = 0

                for ((index, slot) in slots.withIndex()) {
                    // Safe bounds check
                    val video = state.videos.getOrNull(slot.videoIndex)
                    if (video == null) {
                        android.util.Log.e("NewPostVM", "Video index ${slot.videoIndex} out of bounds, skipping")
                        skippedCount++
                        continue
                    }

                    // Safe null check for localPath
                    val localPath = video.localPath
                    if (localPath == null) {
                        android.util.Log.e("NewPostVM", "Video ${video.fileName} has no local path, skipping")
                        skippedCount++
                        continue
                    }

                    val postId = postRepository.createPost(
                        videoPath = localPath,
                        caption = video.caption,
                        scheduledTime = slot.toEpochMillis()
                    )

                    val post = postRepository.getById(postId)
                    if (post != null) {
                        smartScheduler.schedulePost(post)
                        successCount++
                    }

                    _uiState.update {
                        it.copy(scheduleProgress = ((index + 1) * 100) / slots.size)
                    }
                }

                videosScheduled = true

                val message = if (skippedCount > 0) {
                    "Scheduled $successCount videos ($skippedCount skipped)"
                } else {
                    "Successfully scheduled $successCount videos!"
                }

                _uiState.update {
                    it.copy(
                        isScheduling = false,
                        successMessage = message
                    )
                }
                onSuccess()

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isScheduling = false,
                        error = "Failed to schedule: ${e.message}"
                    )
                }
                onError(e.message ?: "Failed to schedule videos")
            }
        }
    }

    // ==================== Utilities ====================

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSuccess() {
        _uiState.update { it.copy(successMessage = null) }
    }

    /**
     * Clean up resources when ViewModel is cleared
     */
    override fun onCleared() {
        super.onCleared()

        // Cancel all running jobs
        copyJob?.cancel()
        captionJob?.cancel()
        scheduleJob?.cancel()

        // Clean up uncommitted video files only if not scheduled
        if (!videosScheduled) {
            _uiState.value.videos.forEach { video ->
                video.localPath?.let { path ->
                    try {
                        val file = File(path)
                        if (file.exists()) {
                            file.delete()
                            android.util.Log.i("NewPostVM", "Cleaned up uncommitted video: ${file.name}")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("NewPostVM", "Failed to clean up video: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Copy video from content:// URI to app's internal storage.
     */
    private suspend fun copyVideoToInternalStorage(uri: Uri, fileName: String? = null): String = withContext(Dispatchers.IO) {
        val videosDir = File(context.filesDir, "videos").apply {
            if (!exists()) mkdirs()
        }

        val videoSize = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize
            } ?: throw IllegalStateException("Cannot read video size")
        } catch (e: Exception) {
            throw IllegalStateException("Cannot read video metadata: ${e.message}", e)
        }

        val availableSpace = videosDir.usableSpace
        val requiredSpace = (videoSize * 1.2).toLong()

        if (availableSpace < requiredSpace) {
            val availableMB = availableSpace / 1_000_000
            val requiredMB = requiredSpace / 1_000_000
            throw IllegalStateException("Not enough storage. Need ${requiredMB}MB, have ${availableMB}MB available")
        }

        val finalFileName = fileName ?: getFileName(uri) ?: "video_${UUID.randomUUID()}.mp4"
        val destFile = File(videosDir, finalFileName)

        // If file exists, add UUID to avoid conflicts
        val actualDestFile = if (destFile.exists()) {
            File(videosDir, "${UUID.randomUUID()}_$finalFileName")
        } else {
            destFile
        }

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(actualDestFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Cannot open video file")

            val copiedSize = actualDestFile.length()
            if (copiedSize != videoSize) {
                throw IllegalStateException("Video copy incomplete: expected $videoSize bytes, got $copiedSize bytes")
            }

            android.util.Log.i("NewPostVM", "Copied video: ${actualDestFile.name}, size: ${copiedSize / 1_000_000}MB")
            actualDestFile.absolutePath

        } catch (e: Exception) {
            if (actualDestFile.exists()) {
                actualDestFile.delete()
                android.util.Log.w("NewPostVM", "Cleaned up partial file after copy failure")
            }
            throw e
        }
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
}

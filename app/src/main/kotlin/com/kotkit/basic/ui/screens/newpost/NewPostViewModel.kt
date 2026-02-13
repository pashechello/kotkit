package com.kotkit.basic.ui.screens.newpost

import android.content.Context
import android.net.Uri
import com.kotkit.basic.R
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotkit.basic.auth.AuthStateManager
import com.kotkit.basic.data.local.preferences.AudiencePersonaPreferencesManager
import com.kotkit.basic.data.local.preferences.CaptionLanguage
import com.kotkit.basic.data.local.preferences.CaptionPreferencesManager
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.GenerateCaptionRequest
import com.kotkit.basic.data.repository.PostRepository
import com.kotkit.basic.scheduler.AudiencePersona
import com.kotkit.basic.scheduler.BatchScheduleResult
import com.kotkit.basic.scheduler.BatchScheduleService
import com.kotkit.basic.scheduler.ScheduledSlot
import com.kotkit.basic.scheduler.ScheduleQuality
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    val maxVideosPerDayForPersona: Int = BatchScheduleService.MAX_VIDEOS_PER_DAY,  // Adaptive max capacity
    val startDate: LocalDate = LocalDate.now(),
    // Schedule quality feedback (for showing warnings when user selects high video count)
    val scheduleQuality: ScheduleQuality = ScheduleQuality.OPTIMAL,
    val scheduleWarning: String? = null,
    val effectiveIntervalMinutes: Int = 120,
    val useCustomHours: Boolean = false,
    val customHours: List<Int> = listOf(9, 14, 19),
    val schedulePreview: BatchScheduleResult? = null,
    val previewItems: List<SchedulePreviewItem> = emptyList(),
    val batchPrompt: String = "",
    val selectedLanguage: CaptionLanguage = CaptionLanguage.ENGLISH,

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
    private val batchScheduleService: BatchScheduleService,
    private val authStateManager: AuthStateManager,
    private val audiencePersonaPreferencesManager: AudiencePersonaPreferencesManager
) : ViewModel() {

    companion object {
        private const val TAG = "NewPostVM"
    }

    private val _uiState = MutableStateFlow(NewPostUiState())
    val uiState: StateFlow<NewPostUiState> = _uiState.asStateFlow()

    init {
        // Load initial persona and language from settings
        viewModelScope.launch {
            val savedPersona = audiencePersonaPreferencesManager.personaFlow.first()
            val adaptiveCapacity = batchScheduleService.calculateAdaptiveCapacity(savedPersona.peakHours)
            val savedLanguage = captionPreferencesManager.getLanguage()
            _uiState.update {
                it.copy(
                    selectedPersona = savedPersona,
                    maxVideosPerDayForPersona = adaptiveCapacity.maxVideos,
                    selectedLanguage = savedLanguage
                )
            }
        }
    }

    /** Whether user is currently authenticated */
    val isAuthenticated: Boolean
        get() = authStateManager.isAuthenticated

    /**
     * Check if user is authenticated, show error and log if not.
     * @return true if authenticated, false otherwise
     */
    private fun requireAuth(operation: String): Boolean {
        if (!authStateManager.isAuthenticated) {
            _uiState.update { it.copy(error = context.getString(R.string.error_auth_required_for_schedule)) }
            Timber.tag(TAG).w("$operation: User not authenticated")
            return false
        }
        return true
    }

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

                // Create empty preview for batch mode (without times)
                if (videos.size > 1) {
                    createEmptyPreview()
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
                createEmptyPreview()
            } else {
                // Single video mode - clear preview
                _uiState.update { it.copy(previewItems = emptyList(), schedulePreview = null) }
            }
        }
    }

    /**
     * Set audience persona for batch scheduling.
     * Uses adaptive capacity calculation - doesn't limit user choice.
     */
    fun setPersona(persona: AudiencePersona) {
        val state = _uiState.value
        val hours = if (state.useCustomHours && state.customHours.isNotEmpty()) {
            state.customHours
        } else {
            persona.peakHours
        }
        val adaptiveCapacity = batchScheduleService.calculateAdaptiveCapacity(hours)
        // Check quality for current videosPerDay selection
        val qualityResult = batchScheduleService.checkCapacityForCount(hours, state.videosPerDay)

        _uiState.update {
            it.copy(
                selectedPersona = persona,
                maxVideosPerDayForPersona = adaptiveCapacity.maxVideos,
                // Don't clamp videosPerDay - let user choose, show warning if needed
                scheduleQuality = qualityResult.quality,
                scheduleWarning = qualityResult.warningMessage,
                effectiveIntervalMinutes = qualityResult.effectiveIntervalMinutes
            )
        }
    }

    /**
     * Set start date for batch scheduling
     */
    fun setStartDate(date: LocalDate) {
        _uiState.update { it.copy(startDate = date) }
    }

    /**
     * Set videos per day for batch scheduling.
     * Max is limited by total videos count and hard cap (not by persona hours).
     * Algorithm adapts intervals to fit the requested count.
     */
    fun setVideosPerDay(count: Int) {
        val state = _uiState.value
        // Only limit by actual video count and hard cap - not by persona
        val maxAllowed = minOf(
            state.videos.size.coerceAtLeast(1),
            BatchScheduleService.MAX_VIDEOS_PER_DAY  // Hard cap of 15
        )
        val clamped = count.coerceIn(BatchScheduleService.MIN_VIDEOS_PER_DAY, maxAllowed)

        // Check quality for this selection
        val hours = if (state.useCustomHours && state.customHours.isNotEmpty()) {
            state.customHours
        } else {
            state.selectedPersona.peakHours
        }
        val qualityResult = batchScheduleService.checkCapacityForCount(hours, clamped)

        _uiState.update {
            it.copy(
                videosPerDay = clamped,
                scheduleQuality = qualityResult.quality,
                scheduleWarning = qualityResult.warningMessage,
                effectiveIntervalMinutes = qualityResult.effectiveIntervalMinutes
            )
        }
    }

    /**
     * Toggle custom hours mode.
     * Recalculates adaptive capacity based on new hours source.
     */
    fun setUseCustomHours(use: Boolean) {
        val state = _uiState.value
        val hours = if (use && state.customHours.isNotEmpty()) {
            state.customHours
        } else {
            state.selectedPersona.peakHours
        }
        val adaptiveCapacity = batchScheduleService.calculateAdaptiveCapacity(hours)
        val qualityResult = batchScheduleService.checkCapacityForCount(hours, state.videosPerDay)

        _uiState.update {
            it.copy(
                useCustomHours = use,
                maxVideosPerDayForPersona = adaptiveCapacity.maxVideos,
                // Don't clamp videosPerDay - show warning instead
                scheduleQuality = qualityResult.quality,
                scheduleWarning = qualityResult.warningMessage,
                effectiveIntervalMinutes = qualityResult.effectiveIntervalMinutes
            )
        }
    }

    /**
     * Set custom hours with validation.
     * Recalculates adaptive capacity if in custom hours mode.
     */
    fun setCustomHours(hours: List<Int>) {
        val state = _uiState.value
        // Prevent empty custom hours when in custom mode
        if (hours.isEmpty() && state.useCustomHours) {
            return
        }

        val sortedHours = hours.sorted()
        val effectiveHours = if (state.useCustomHours) sortedHours else state.selectedPersona.peakHours
        val adaptiveCapacity = batchScheduleService.calculateAdaptiveCapacity(effectiveHours)
        val qualityResult = batchScheduleService.checkCapacityForCount(effectiveHours, state.videosPerDay)

        _uiState.update {
            it.copy(
                customHours = sortedHours,
                maxVideosPerDayForPersona = adaptiveCapacity.maxVideos,
                // Don't clamp videosPerDay - show warning instead
                scheduleQuality = qualityResult.quality,
                scheduleWarning = qualityResult.warningMessage,
                effectiveIntervalMinutes = qualityResult.effectiveIntervalMinutes
            )
        }
    }

    /**
     * Create preview items without scheduled times (--:-- placeholders).
     * Used when videos are selected but scheduling hasn't been generated yet.
     */
    private fun createEmptyPreview() {
        val state = _uiState.value
        val emptyPreviewItems = state.videos.mapIndexed { index, video ->
            SchedulePreviewItem(
                video = video,
                slot = ScheduledSlot(
                    videoIndex = index,
                    dateTime = java.time.LocalDateTime.MIN  // Placeholder for "not scheduled"
                ),
                formattedTime = "--:--"  // Empty time placeholder
            )
        }

        _uiState.update {
            it.copy(
                previewItems = emptyPreviewItems,
                schedulePreview = null  // Schedule not generated yet
            )
        }
    }

    /**
     * Update schedule preview using ADAPTIVE scheduling algorithm.
     * Automatically adjusts intervals and expands hours to fit all videos in the requested day.
     */
    fun updatePreview() {
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

        // Create start DateTime: if start date is today, use current time + 1h buffer
        // Otherwise, use 6:00 AM of that day
        val now = java.time.LocalDateTime.now()
        val startDateTime = if (state.startDate == java.time.LocalDate.now()) {
            now.plusHours(1)
        } else {
            java.time.LocalDateTime.of(state.startDate, java.time.LocalTime.of(6, 0))
        }

        // Use adaptive scheduling that adjusts intervals and hours to fit videos
        // Timestamp-based seed for FRESH randomization each time "Reschedule" is pressed
        val previewSeed = System.currentTimeMillis()

        val result = batchScheduleService.generateAdaptiveSchedule(
            videoCount = state.videos.size,
            persona = state.selectedPersona,
            startDateTime = startDateTime,
            videosPerDay = state.videosPerDay,
            customHours = customHours,
            seed = previewSeed
        )

        val previewItems = result.slots.map { slot ->
            // Use slot.videoIndex for consistency with generateCaptions()
            val video = state.videos.getOrNull(slot.videoIndex) ?: run {
                Timber.tag(TAG).w("Invalid videoIndex ${slot.videoIndex}, falling back to first video")
                state.videos.first()
            }
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
                previewItems = previewItems,
                error = null
            )
        }
    }

    // ==================== Caption Generation ====================

    fun setCaption(caption: String) {
        _uiState.update { it.copy(caption = caption) }
    }

    /**
     * Set batch prompt for AI caption generation
     */
    fun setBatchPrompt(prompt: String) {
        _uiState.update { it.copy(batchPrompt = prompt) }
    }

    /**
     * Set caption language preference (RU/EN).
     * Saves to preferences and updates UI state.
     */
    fun setLanguage(language: CaptionLanguage) {
        captionPreferencesManager.setLanguage(language)
        _uiState.update { it.copy(selectedLanguage = language) }
    }

    /**
     * Update caption for specific video in batch mode.
     * Updates both videos list AND previewItems to keep them in sync.
     */
    fun updateVideoCaption(index: Int, caption: String) {
        val state = _uiState.value
        val videos = state.videos.toMutableList()
        if (index in videos.indices) {
            val updatedVideo = videos[index].copy(caption = caption)
            videos[index] = updatedVideo

            // Also update previewItems to keep them in sync
            val updatedPreviewItems = state.previewItems.map { previewItem ->
                if (previewItem.slot.videoIndex == index) {
                    previewItem.copy(video = updatedVideo)
                } else {
                    previewItem
                }
            }

            _uiState.update {
                it.copy(
                    videos = videos,
                    previewItems = updatedPreviewItems
                )
            }
        }
    }

    /**
     * Generate caption for single video using AI
     */
    fun generateCaption() {
        if (!requireAuth("generateCaption")) return

        captionJob?.cancel()
        captionJob = viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingCaption = true, error = null) }

            try {
                // Get tone prompt with language instruction included
                val tonePrompt = captionPreferencesManager.getEffectiveTonePromptWithLanguage()

                // Note: Don't send trackName (filename) to avoid it appearing in generated caption
                // Filenames like "video_123.mp4" or "вайп трека 10000" don't help with caption quality
                val request = GenerateCaptionRequest(
                    trackName = "",  // Empty - don't include filename in caption
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
        if (!requireAuth("generateCaptions")) return

        captionJob?.cancel()
        captionJob = viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingCaptions = true, captionProgress = 0, error = null) }

            try {
                // Get tone prompt with language instruction included
                val tonePrompt = captionPreferencesManager.getEffectiveTonePromptWithLanguage()
                val batchPrompt = _uiState.value.batchPrompt
                val currentVideos = _uiState.value.videos
                // Use ConcurrentHashMap for thread-safe parallel writes
                val results = ConcurrentHashMap<Int, VideoItem>()
                currentVideos.forEachIndexed { index, video -> results[index] = video }

                // Use semaphore for limited parallelism (3 concurrent requests)
                val semaphore = Semaphore(3)
                val completedCount = AtomicInteger(0)
                val failedCount = AtomicInteger(0)
                val lastError = AtomicReference<String?>(null)

                Timber.tag(TAG).i("Starting batch caption generation for ${currentVideos.size} videos, prompt: '${batchPrompt.take(50)}'")

                val totalVideos = currentVideos.size
                val deferreds = currentVideos.mapIndexed { index, video ->
                    async {
                        semaphore.withPermit {
                            try {
                                // Set loading state in results map (thread-safe)
                                results[index] = video.copy(isGeneratingCaption = true)

                                // Add video number to make each caption unique
                                // Format: "Video 3 of 9. [user prompt]" or just "Video 3 of 9" if no prompt
                                val uniqueDescription = if (batchPrompt.isNotBlank()) {
                                    "Video ${index + 1} of $totalVideos. $batchPrompt"
                                } else {
                                    "Video ${index + 1} of $totalVideos"
                                }

                                val request = GenerateCaptionRequest(
                                    trackName = "",
                                    videoDescription = uniqueDescription,
                                    tonePrompt = "$tonePrompt Generate a UNIQUE caption different from other videos in this batch."
                                )

                                Timber.tag(TAG).d("[$index] Requesting caption for video: ${video.fileName}")
                                val response = apiService.generateCaption(request)

                                results[index] = if (response.success && response.caption.isNotBlank()) {
                                    Timber.tag(TAG).i("[$index] Caption OK: ${response.caption.take(50)}...")
                                    video.copy(
                                        caption = response.caption,
                                        isGeneratingCaption = false
                                    )
                                } else {
                                    val errorMsg = response.error ?: "Empty caption returned"
                                    Timber.tag(TAG).e("[$index] API FAILED: $errorMsg")
                                    failedCount.incrementAndGet()
                                    lastError.set(errorMsg)
                                    video.copy(isGeneratingCaption = false)
                                }
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "[$index] EXCEPTION: ${e.message}")
                                failedCount.incrementAndGet()
                                lastError.set(e.message ?: "Network error")
                                results[index] = video.copy(isGeneratingCaption = false)
                            }

                            // Only update progress, not videos (to avoid race condition)
                            val completed = completedCount.incrementAndGet()
                            _uiState.update {
                                it.copy(
                                    captionProgress = (completed * 100) / currentVideos.size
                                )
                            }
                        }
                    }
                }

                deferreds.awaitAll()

                // Atomically update videos AND previewItems together to avoid race condition
                // Convert ConcurrentHashMap to ordered list by index (fallback to original if missing)
                val finalVideos = (0 until currentVideos.size).map { i ->
                    results[i] ?: run {
                        Timber.tag(TAG).e("Missing result for index $i, using original video")
                        currentVideos[i]
                    }
                }
                val currentState = _uiState.value

                // IMPORTANT: Reuse existing previewItems if available, just update video data
                // This preserves user-edited times and avoids regenerating schedule with different algorithm
                val newPreviewItems = if (currentState.previewItems.isNotEmpty()) {
                    // Update existing preview items with new video data (captions)
                    currentState.previewItems.map { previewItem ->
                        val updatedVideo = finalVideos.getOrNull(previewItem.slot.videoIndex)
                            ?: previewItem.video
                        previewItem.copy(video = updatedVideo)
                    }
                } else {
                    // No existing preview - generate new schedule using SAME algorithm as updatePreview()
                    val customHours = if (currentState.useCustomHours && currentState.customHours.isNotEmpty()) {
                        currentState.customHours
                    } else {
                        null
                    }

                    // Use same startDateTime logic as updatePreview()
                    val now = java.time.LocalDateTime.now()
                    val startDateTime = if (currentState.startDate == java.time.LocalDate.now()) {
                        now.plusHours(1)
                    } else {
                        java.time.LocalDateTime.of(currentState.startDate, java.time.LocalTime.of(6, 0))
                    }

                    // Timestamp-based seed for fresh randomization
                    val captionSeed = System.currentTimeMillis()

                    val scheduleResult = batchScheduleService.generateAdaptiveSchedule(
                        videoCount = finalVideos.size,
                        persona = currentState.selectedPersona,
                        startDateTime = startDateTime,
                        videosPerDay = currentState.videosPerDay,
                        customHours = customHours,
                        seed = captionSeed
                    )

                    scheduleResult.slots.map { slot ->
                        val video = finalVideos.getOrNull(slot.videoIndex) ?: run {
                            Timber.tag(TAG).w("Invalid videoIndex ${slot.videoIndex}, falling back to first video")
                            finalVideos.first()
                        }
                        val dateStr = slot.dateTime.toLocalDate().format(dateFormatter)
                        val timeStr = slot.dateTime.toLocalTime().format(timeFormatter)
                        SchedulePreviewItem(video = video, slot = slot, formattedTime = "$dateStr, $timeStr")
                    }
                }

                val successCount = finalVideos.count { it.caption.isNotEmpty() }
                val failed = failedCount.get()
                Timber.tag(TAG).i("Batch complete: $successCount success, $failed failed")

                // Show error to user if any captions failed
                val errorMessage = if (failed > 0) {
                    "Failed to generate $failed caption(s): ${lastError.get()}"
                } else {
                    null
                }

                _uiState.update {
                    it.copy(
                        isGeneratingCaptions = false,
                        videos = finalVideos,
                        // Keep existing schedulePreview - don't regenerate
                        previewItems = newPreviewItems,
                        error = errorMessage
                    )
                }

            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Batch generation CRASHED: ${e.message}")
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

        // Validate videoIndex
        if (videoIndex !in state.videos.indices) {
            Timber.tag(TAG).e("Invalid videoIndex: $videoIndex, videos.size=${state.videos.size}")
            return
        }

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
            val video = state.videos.getOrElse(slot.videoIndex) { state.videos.first() }
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
        if (!requireAuth("createPost")) return

        val state = _uiState.value

        if (state.videoPath.isNullOrBlank()) {
            onError(context.getString(R.string.error_select_video))
            return
        }

        if (state.scheduledTime <= System.currentTimeMillis()) {
            onError(context.getString(R.string.error_select_future_time))
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
                onError(e.message ?: context.getString(R.string.error_failed_create_post))
            }
        }
    }

    /**
     * Post immediately - for testing purposes
     */
    fun postNow(onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (!requireAuth("postNow")) return

        val state = _uiState.value
        Timber.tag(TAG).d("postNow called, videoPath=${state.videoPath}")

        if (state.videoPath.isNullOrBlank()) {
            Timber.tag(TAG).e("No video selected")
            onError(context.getString(R.string.error_select_video))
            return
        }

        val videoPath = state.videoPath ?: return

        scheduleJob?.cancel()
        scheduleJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                Timber.tag(TAG).d("Creating post...")
                val postId = postRepository.createPost(
                    videoPath = videoPath,
                    caption = state.caption,
                    scheduledTime = System.currentTimeMillis() + 5000
                )
                Timber.tag(TAG).d("Post created with ID: $postId")

                val post = postRepository.getById(postId)
                if (post != null) {
                    Timber.tag(TAG).d("Force publishing post: $post")
                    smartScheduler.forcePublish(postId)
                    Timber.tag(TAG).d("Post force published!")
                } else {
                    Timber.tag(TAG).e("Post not found after creation!")
                }

                videosScheduled = true
                _uiState.update { it.copy(isLoading = false) }
                onSuccess()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error: ${e.message}")
                _uiState.update { it.copy(isLoading = false) }
                onError(e.message ?: context.getString(R.string.error_failed_create_post))
            }
        }
    }

    /**
     * Schedule all videos in batch mode
     */
    fun scheduleAll(onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (!requireAuth("scheduleAll")) return

        val state = _uiState.value

        if (state.videos.isEmpty()) {
            onError(context.getString(R.string.error_select_videos_first))
            return
        }

        if (state.schedulePreview == null) {
            onError(context.getString(R.string.error_schedule_not_generated))
            return
        }

        // Validate: all slots must be in the future
        val now = java.time.LocalDateTime.now()
        val pastSlots = state.schedulePreview.slots.filter {
            !it.dateTime.isAfter(now)
        }

        if (pastSlots.isNotEmpty()) {
            onError(context.getString(R.string.error_slots_in_past, pastSlots.size))
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
                        Timber.tag(TAG).e("Video index ${slot.videoIndex} out of bounds, skipping")
                        skippedCount++
                        continue
                    }

                    // Safe null check for localPath
                    val localPath = video.localPath
                    if (localPath == null) {
                        Timber.tag(TAG).e("Video ${video.fileName} has no local path, skipping")
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
                onError(e.message ?: context.getString(R.string.error_failed_schedule_videos))
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

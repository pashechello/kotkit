package com.kotkit.basic.ui.screens.newpost

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotkit.basic.R
import com.kotkit.basic.data.local.preferences.CaptionLanguage
import com.kotkit.basic.scheduler.AudiencePersona
import com.kotkit.basic.scheduler.BatchScheduleService
import com.kotkit.basic.scheduler.ScheduleQuality
import android.content.Intent
import android.net.Uri
import com.kotkit.basic.ui.components.BounceOverscrollContainer
import com.kotkit.basic.ui.components.GlassCard
import com.kotkit.basic.ui.components.GradientButton
import com.kotkit.basic.ui.components.LoginPromptBottomSheet
import com.kotkit.basic.ui.components.LoginPromptReason
import com.kotkit.basic.ui.components.VideoThumbnail
import com.kotkit.basic.ui.theme.*
import com.commandiron.wheel_picker_compose.WheelDatePicker
import com.commandiron.wheel_picker_compose.WheelTimePicker
import com.kotkit.basic.ui.components.WheelTextPicker
import com.commandiron.wheel_picker_compose.core.WheelPickerDefaults
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewPostScreen(
    onNavigateBack: () -> Unit,
    viewModel: NewPostViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showSnackbar by remember { mutableStateOf<String?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }

    // Auth state
    val isAuthenticated = viewModel.isAuthenticated
    var showLoginPrompt by remember { mutableStateOf(false) }
    var loginPromptReason by remember { mutableStateOf(LoginPromptReason.GENERAL) }

    // Photo Picker for multi-select video selection (works on all Android versions)
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.setVideoUris(uris)
        }
    }

    fun launchVideoPicker() {
        videoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        )
    }

    // Handle messages
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            showSnackbar = it
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            showSnackbar = it
            viewModel.clearSuccess()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBase)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top Bar
            GlassTopBar(
                title = stringResource(R.string.new_post_title),
                onBackClick = onNavigateBack,
                actionContent = { }
            )

            // Content - adaptive based on mode
            if (uiState.isBatchMode) {
                // Batch Mode UI
                BatchModeContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    showAdvanced = showAdvanced,
                    onShowAdvancedChange = { showAdvanced = it },
                    onPickVideos = { launchVideoPicker() },
                    onNavigateBack = onNavigateBack,
                    onShowSnackbar = { showSnackbar = it },
                    isAuthenticated = isAuthenticated,
                    onLoginRequired = { reason ->
                        loginPromptReason = reason
                        showLoginPrompt = true
                    }
                )
            } else {
                // Single Mode UI
                SingleModeContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    onPickVideo = { launchVideoPicker() },
                    onNavigateBack = onNavigateBack,
                    onShowSnackbar = { showSnackbar = it },
                    isAuthenticated = isAuthenticated,
                    onLoginRequired = { reason ->
                        loginPromptReason = reason
                        showLoginPrompt = true
                    }
                )
            }
        }

        // Progress overlay for batch scheduling
        if (uiState.isScheduling) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                GlassCard {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = { uiState.scheduleProgress / 100f },
                            color = BrandCyan,
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "${uiState.scheduleProgress}%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.batch_schedule_scheduling),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        // Snackbar (top position for better visibility)
        showSnackbar?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp)
            ) {
                GlassSnackbar(
                    message = message,
                    onDismiss = { showSnackbar = null }
                )
            }
        }
    }

    // Login prompt bottom sheet
    if (showLoginPrompt) {
        LoginPromptBottomSheet(
            onDismiss = { showLoginPrompt = false },
            onLoginClick = {
                showLoginPrompt = false
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://kotkit.pro/auth/app"))
                context.startActivity(intent)
            },
            reason = loginPromptReason
        )
    }
}

// ==================== Single Mode Content ====================

@Composable
private fun SingleModeContent(
    uiState: NewPostUiState,
    viewModel: NewPostViewModel,
    onPickVideo: () -> Unit,
    onNavigateBack: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    isAuthenticated: Boolean,
    onLoginRequired: (LoginPromptReason) -> Unit
) {
    BounceOverscrollContainer(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
        ) {
            // Video Picker Section
            item {
                VideoPickerCard(
                    videoPath = uiState.videoPath,
                    videoCount = uiState.videos.size,
                    onPickVideo = onPickVideo
                )
            }

        // Caption Section
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Description,
                                contentDescription = null,
                                tint = BrandPink,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.new_post_caption).uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                        }

                        // AI Generate button
                        GlassAIButton(
                            onClick = {
                                if (!isAuthenticated) {
                                    onLoginRequired(LoginPromptReason.AI_CAPTION)
                                } else {
                                    viewModel.generateCaption()
                                }
                            },
                            isLoading = uiState.isGeneratingCaption,
                            enabled = !uiState.isLoading && !uiState.isGeneratingCaption && uiState.videoPath != null
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Language toggle (RU/EN)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.caption_language_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceGlassLight)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        ) {
                            CaptionLanguage.entries.forEach { language ->
                                val isSelected = uiState.selectedLanguage == language
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (isSelected) BrandPink.copy(alpha = 0.2f)
                                            else Color.Transparent
                                        )
                                        .clickable { viewModel.setLanguage(language) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = language.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) BrandPink else TextMuted
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Glass text field
                    GlassTextField(
                        value = uiState.caption,
                        onValueChange = viewModel::setCaption,
                        placeholder = stringResource(R.string.new_post_caption_placeholder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    )
                }
            }
        }

        // Schedule Section
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = GradientPurple,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.new_post_schedule_for).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    GlassSchedulePicker(
                        scheduledTime = uiState.scheduledTime,
                        onTimeSelected = viewModel::setScheduledTime
                    )
                }
            }
        }

        // Main Schedule button - PINK
        item {
            GradientButton(
                onClick = {
                    if (!isAuthenticated) {
                        onLoginRequired(LoginPromptReason.SCHEDULE_POST)
                    } else {
                        viewModel.createPost(
                            onSuccess = onNavigateBack,
                            onError = { onShowSnackbar(it) }
                        )
                    }
                },
                enabled = !uiState.isLoading && uiState.videoPath != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_schedule),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Test button - simple text
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceGlassLight)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .clickable(
                        enabled = !uiState.isLoading && uiState.videoPath != null,
                        onClick = {
                            if (!isAuthenticated) {
                                onLoginRequired(LoginPromptReason.SCHEDULE_POST)
                            } else {
                                viewModel.postNow(
                                    onSuccess = onNavigateBack,
                                    onError = { onShowSnackbar(it) }
                                )
                            }
                        }
                    )
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.new_post_post_now),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (!uiState.isLoading && uiState.videoPath != null) TextSecondary else TextMuted
                )
            }
        }
        }
    }
}

// ==================== Batch Mode Content ====================

@Composable
private fun BatchModeContent(
    uiState: NewPostUiState,
    viewModel: NewPostViewModel,
    showAdvanced: Boolean,
    onShowAdvancedChange: (Boolean) -> Unit,
    onPickVideos: () -> Unit,
    onNavigateBack: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    isAuthenticated: Boolean,
    onLoginRequired: (LoginPromptReason) -> Unit
) {
    BounceOverscrollContainer(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
        // ═══════════════════════════════════════════════════════════
        // SECTION 1: VIDEO SELECTION
        // ═══════════════════════════════════════════════════════════
        item {
            BatchVideoSelectionSection(
                videos = uiState.videos,
                onAddVideos = onPickVideos,
                onRemoveVideo = { viewModel.removeVideo(it) }
            )
        }

        // ═══════════════════════════════════════════════════════════
        // SECTION 2: AI CAPTION GENERATION (first!)
        // ═══════════════════════════════════════════════════════════
        item {
            BatchAICaptionSection(
                isGenerating = uiState.isGeneratingCaptions,
                progress = uiState.captionProgress,
                videosCount = uiState.videos.size,
                batchPrompt = uiState.batchPrompt,
                onBatchPromptChange = { viewModel.setBatchPrompt(it) },
                selectedLanguage = uiState.selectedLanguage,
                onLanguageChange = { viewModel.setLanguage(it) },
                onGenerate = {
                    if (!isAuthenticated) {
                        onLoginRequired(LoginPromptReason.AI_CAPTION)
                    } else {
                        viewModel.generateCaptions()
                    }
                }
            )
        }

        // ═══════════════════════════════════════════════════════════
        // SECTION 3: SCHEDULE SETTINGS
        // ═══════════════════════════════════════════════════════════
        item {
            BatchScheduleSection(
                startDate = uiState.startDate,
                videosPerDay = uiState.videosPerDay,
                totalVideos = uiState.videos.size,
                selectedPersona = uiState.selectedPersona,
                scheduleQuality = uiState.scheduleQuality,
                effectiveIntervalMinutes = uiState.effectiveIntervalMinutes,
                onDateChange = { viewModel.setStartDate(it) },
                onVideosPerDayChange = { viewModel.setVideosPerDay(it) },
                onPersonaChange = { viewModel.setPersona(it) }
            )
        }

        // ═══════════════════════════════════════════════════════════
        // SECTION 4: SCHEDULE GENERATION BUTTON
        // ═══════════════════════════════════════════════════════════
        if (uiState.videos.size > 1) {
            item {
                Spacer(modifier = Modifier.height(8.dp))

                if (uiState.schedulePreview == null) {
                    // Show "Распланировать" button when times are not generated
                    GradientButton(
                        onClick = { viewModel.updatePreview() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.batch_schedule_generate))
                    }
                } else {
                    // Show "Перепланировать" button when times are already generated
                    OutlinedButton(
                        onClick = { viewModel.updatePreview() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = BrandPink.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = BrandPink
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.batch_schedule_regenerate))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // ═══════════════════════════════════════════════════════════
        // SECTION 5: POST PREVIEW
        // ═══════════════════════════════════════════════════════════
        if (uiState.previewItems.isNotEmpty()) {
            item {
                BatchSectionHeader(
                    title = stringResource(R.string.batch_schedule_post_preview),
                    subtitle = uiState.schedulePreview?.let {
                        stringResource(R.string.batch_schedule_days_count, it.totalDays)
                    }
                )
            }

            itemsIndexed(
                items = uiState.previewItems,
                key = { _, item -> item.slot.videoIndex }  // Use stable videoIndex as key
            ) { index, previewItem ->
                PostPreviewCard(
                    previewItem = previewItem,
                    index = index,
                    onCaptionChange = { newCaption ->
                        viewModel.updateVideoCaption(previewItem.slot.videoIndex, newCaption)
                    },
                    onTimeChange = { newDateTime ->
                        viewModel.updateSlotTime(previewItem.slot.videoIndex, newDateTime)
                    },
                    onRemove = { viewModel.removeVideo(previewItem.slot.videoIndex) }
                )
            }
        }

        // ═══════════════════════════════════════════════════════════
        // SECTION 6: ADVANCED SETTINGS (collapsible)
        // ═══════════════════════════════════════════════════════════
        item {
            BatchAdvancedSection(
                showAdvanced = showAdvanced,
                onShowAdvancedChange = onShowAdvancedChange,
                useCustomHours = uiState.useCustomHours,
                customHours = uiState.customHours,
                onUseCustomHoursChange = { viewModel.setUseCustomHours(it) },
                onCustomHoursChange = { viewModel.setCustomHours(it) }
            )
        }

        // ═══════════════════════════════════════════════════════════
        // SECTION 7: SCHEDULE ALL BUTTON
        // ═══════════════════════════════════════════════════════════
        item {
            Spacer(modifier = Modifier.height(4.dp))

            GradientButton(
                onClick = {
                    if (!isAuthenticated) {
                        onLoginRequired(LoginPromptReason.SCHEDULE_POST)
                    } else {
                        viewModel.scheduleAll(
                            onSuccess = onNavigateBack,
                            onError = { onShowSnackbar(it) }
                        )
                    }
                },
                enabled = uiState.videos.isNotEmpty() && !uiState.isScheduling,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (uiState.isScheduling) {
                        stringResource(R.string.batch_schedule_scheduling)
                    } else {
                        stringResource(R.string.batch_schedule_all_posts, uiState.videos.size)
                    },
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// BATCH MODE SECTION COMPONENTS
// ═══════════════════════════════════════════════════════════

@Composable
private fun BatchSectionHeader(
    title: String,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = TextSecondary,
            letterSpacing = 1.sp
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun BatchVideoSelectionSection(
    videos: List<VideoItem>,
    onAddVideos: () -> Unit,
    onRemoveVideo: (Int) -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val hasVideos = videos.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        BrandCyan.copy(alpha = 0.08f),
                        BrandPink.copy(alpha = 0.08f)
                    )
                )
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    colors = listOf(
                        BrandCyan.copy(alpha = 0.25f),
                        BrandPink.copy(alpha = 0.25f)
                    )
                ),
                shape
            )
            .padding(20.dp)
    ) {
        // Header with count
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(BrandCyan.copy(alpha = 0.15f))
                        .border(1.dp, BrandCyan.copy(alpha = 0.3f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.VideoLibrary,
                        contentDescription = null,
                        tint = BrandCyan,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.batch_schedule_videos),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (hasVideos) {
                            stringResource(R.string.batch_schedule_videos_selected, videos.size)
                        } else {
                            stringResource(R.string.batch_schedule_no_videos)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }

            // Add button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrandCyan.copy(alpha = 0.2f))
                    .border(1.dp, BrandCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .clickable(onClick = onAddVideos)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = BrandCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.batch_schedule_add),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = BrandCyan
                    )
                }
            }
        }

        // Video thumbnails (if any)
        if (hasVideos) {
            Spacer(modifier = Modifier.height(16.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(videos) { index, video ->
                    Box(
                        modifier = Modifier
                            .width(70.dp)
                            .height(100.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfaceGlassMedium)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                        ) {
                            if (video.localPath != null) {
                                VideoThumbnail(
                                    videoPath = video.localPath,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Outlined.VideoFile,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                        // Remove button
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Error)
                                .clickable { onRemoveVideo(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.remove),
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchScheduleSection(
    startDate: LocalDate,
    videosPerDay: Int,
    totalVideos: Int,
    selectedPersona: AudiencePersona,
    scheduleQuality: ScheduleQuality,
    effectiveIntervalMinutes: Int,
    onDateChange: (LocalDate) -> Unit,
    onVideosPerDayChange: (Int) -> Unit,
    onPersonaChange: (AudiencePersona) -> Unit
) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    // Max is limited only by total videos and hard cap - algorithm adapts intervals
    val maxVideosPerDay = minOf(totalVideos, BatchScheduleService.MAX_VIDEOS_PER_DAY)
    val currentYear = LocalDate.now().year

    // State for selected day and month
    var selectedDay by remember(startDate) { mutableIntStateOf(startDate.dayOfMonth) }
    var selectedMonth by remember(startDate) { mutableIntStateOf(startDate.monthValue) }

    // Generate month names
    val monthNames = remember(locale) {
        Month.entries.map { it.getDisplayName(JavaTextStyle.SHORT_STANDALONE, locale) }
    }

    // Calculate days in selected month
    val daysInMonth = remember(selectedMonth) {
        YearMonth.of(currentYear, selectedMonth).lengthOfMonth()
    }
    val daysList = remember(daysInMonth) { (1..daysInMonth).map { it.toString() } }

    // Consume scroll after wheel picker handles it
    val consumeRemainingScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                return available
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return available
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BatchSectionHeader(title = stringResource(R.string.batch_schedule_settings))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Start Date with custom day/month pickers - fully CYAN
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .nestedScroll(consumeRemainingScroll)
                        .clip(RoundedCornerShape(14.dp))
                        .background(BrandCyan.copy(alpha = 0.1f))
                        .border(1.dp, BrandCyan.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Day picker
                    WheelTextPicker(
                        texts = daysList,
                        startIndex = (selectedDay - 1).coerceIn(0, daysList.size - 1),
                        rowCount = 3,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        color = TextPrimary,
                        selectorProperties = WheelPickerDefaults.selectorProperties(
                            enabled = true,
                            shape = RoundedCornerShape(10.dp),
                            color = BrandCyan.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, BrandCyan.copy(alpha = 0.3f))
                        ),
                        modifier = Modifier.weight(0.35f)
                    ) { index ->
                        selectedDay = index + 1
                        val validDay = selectedDay.coerceAtMost(daysInMonth)
                        val newDate = LocalDate.of(currentYear, selectedMonth, validDay)

                        // Block past dates - cannot select dates before today
                        val today = LocalDate.now()
                        if (newDate < today) {
                            return@WheelTextPicker
                        }

                        onDateChange(newDate)
                    }

                    // Month picker
                    WheelTextPicker(
                        texts = monthNames,
                        startIndex = selectedMonth - 1,
                        rowCount = 3,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        color = TextPrimary,
                        selectorProperties = WheelPickerDefaults.selectorProperties(
                            enabled = true,
                            shape = RoundedCornerShape(10.dp),
                            color = BrandCyan.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, BrandCyan.copy(alpha = 0.3f))
                        ),
                        modifier = Modifier.weight(0.65f)
                    ) { index ->
                        selectedMonth = index + 1
                        val newDaysInMonth = YearMonth.of(currentYear, selectedMonth).lengthOfMonth()
                        val validDay = selectedDay.coerceAtMost(newDaysInMonth)
                        val newDate = LocalDate.of(currentYear, selectedMonth, validDay)

                        // Block past dates - cannot select dates before today
                        val today = LocalDate.now()
                        if (newDate < today) {
                            return@WheelTextPicker
                        }

                        onDateChange(newDate)
                    }
                }

                // Videos Per Day
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceGlassMedium)
                        .border(1.dp, BorderDefault, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Success.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Speed,
                                contentDescription = null,
                                tint = Success,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.batch_schedule_per_day),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextTertiary
                            )
                            Text(
                                text = stringResource(R.string.batch_schedule_videos_count, videosPerDay),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    }

                    // +/- buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (videosPerDay > 1) SurfaceGlassHeavy else SurfaceGlassLight)
                                .border(1.dp, BorderDefault, CircleShape)
                                .clickable(enabled = videosPerDay > 1) { onVideosPerDayChange(videosPerDay - 1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = stringResource(R.string.decrease),
                                tint = if (videosPerDay > 1) TextPrimary else TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (videosPerDay < maxVideosPerDay) SurfaceGlassHeavy else SurfaceGlassLight)
                                .border(1.dp, BorderDefault, CircleShape)
                                .clickable(enabled = videosPerDay < maxVideosPerDay) { onVideosPerDayChange(videosPerDay + 1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.increase),
                                tint = if (videosPerDay < maxVideosPerDay) TextPrimary else TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Duration info (after user selected date AND videos per day)
                if (totalVideos > 0 && videosPerDay > 0) {
                    val totalDays = (totalVideos + videosPerDay - 1) / videosPerDay
                    Text(
                        text = pluralStringResource(R.plurals.batch_schedule_duration_days, totalDays, totalDays),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }

                // Schedule quality warning (when intervals are compressed)
                if (scheduleQuality != ScheduleQuality.OPTIMAL) {
                    val warningColor = when (scheduleQuality) {
                        ScheduleQuality.TIGHT -> Error
                        else -> Warning
                    }
                    // Format interval: 120 -> "2h", 90 -> "90min", 60 -> "1h", 45 -> "45min", 30 -> "30min"
                    val intervalText = if (effectiveIntervalMinutes % 60 == 0) {
                        "${effectiveIntervalMinutes / 60}h"
                    } else {
                        "${effectiveIntervalMinutes}min"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(warningColor.copy(alpha = 0.1f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = warningColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = when (scheduleQuality) {
                                ScheduleQuality.GOOD -> stringResource(R.string.schedule_quality_good, intervalText)
                                ScheduleQuality.COMPRESSED -> stringResource(R.string.schedule_quality_compressed, intervalText)
                                ScheduleQuality.TIGHT -> stringResource(R.string.schedule_quality_tight)
                                else -> ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = warningColor
                        )
                    }
                }

                // Audience Persona - Wheel Picker
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.batch_schedule_audience),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary
                    )

                    // Wheel picker for persona selection (casino-style)
                    // Filter out WORKER as it's similar to STUDENT
                    val personas = AudiencePersona.entries.filter { it != AudiencePersona.WORKER }
                    val personaTexts = personas.map { persona ->
                        stringResource(persona.displayNameRes)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .nestedScroll(consumeRemainingScroll)
                            .clip(RoundedCornerShape(14.dp))
                            .background(BrandPink.copy(alpha = 0.1f))
                            .border(1.dp, BrandPink.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        WheelTextPicker(
                            texts = personaTexts,
                            startIndex = personas.indexOf(selectedPersona).coerceAtLeast(0),
                            rowCount = 3,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = TextPrimary,
                            selectorProperties = WheelPickerDefaults.selectorProperties(
                                enabled = true,
                                shape = RoundedCornerShape(10.dp),
                                color = BrandPink.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, BrandPink.copy(alpha = 0.3f))
                            ),
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) { index ->
                            onPersonaChange(personas[index])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchAICaptionSection(
    isGenerating: Boolean,
    progress: Int,
    videosCount: Int,
    batchPrompt: String,
    onBatchPromptChange: (String) -> Unit,
    selectedLanguage: CaptionLanguage,
    onLanguageChange: (CaptionLanguage) -> Unit,
    onGenerate: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BatchSectionHeader(title = stringResource(R.string.batch_schedule_ai_captions))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // AI Icon with glow
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    GradientPurple.copy(alpha = 0.2f),
                                    BrandPink.copy(alpha = 0.2f)
                                )
                            )
                        )
                        .border(
                            1.dp,
                            Brush.linearGradient(
                                colors = listOf(
                                    GradientPurple.copy(alpha = 0.4f),
                                    BrandPink.copy(alpha = 0.4f)
                                )
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = GradientPurple,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Description
                Text(
                    text = stringResource(R.string.batch_schedule_ai_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary,
                    textAlign = TextAlign.Center
                )

                // Prompt input field
                GlassTextField(
                    value = batchPrompt,
                    onValueChange = onBatchPromptChange,
                    placeholder = stringResource(R.string.batch_prompt_placeholder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                )

                // Language toggle (RU/EN)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.caption_language_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    // Segmented button for language selection
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceGlassLight)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                    ) {
                        CaptionLanguage.entries.forEach { language ->
                            val isSelected = selectedLanguage == language
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) GradientPurple.copy(alpha = 0.2f)
                                        else Color.Transparent
                                    )
                                    .clickable { onLanguageChange(language) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = language.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) GradientPurple else TextMuted
                                )
                            }
                        }
                    }
                }

                // Progress or Generate button
                if (isGenerating) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = GradientPurple,
                            trackColor = SurfaceGlassLight
                        )
                        Text(
                            text = stringResource(R.string.batch_schedule_generating_progress, progress),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = GradientPurple
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(GradientPurple, BrandPink)
                                )
                            )
                            .clickable(enabled = videosCount > 0, onClick = onGenerate)
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.batch_schedule_generate_all, videosCount),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchAdvancedSection(
    showAdvanced: Boolean,
    onShowAdvancedChange: (Boolean) -> Unit,
    useCustomHours: Boolean,
    customHours: List<Int>,
    onUseCustomHoursChange: (Boolean) -> Unit,
    onCustomHoursChange: (List<Int>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Collapsible header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceGlassLight)
                .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                .clickable { onShowAdvancedChange(!showAdvanced) }
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(TextSecondary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.batch_schedule_advanced),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
            }
            Icon(
                if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = TextMuted
            )
        }

        // Expanded content
        AnimatedVisibility(
            visible = showAdvanced,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.batch_schedule_custom_hours),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                text = stringResource(R.string.batch_schedule_custom_hours_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                        }
                        Switch(
                            checked = useCustomHours,
                            onCheckedChange = onUseCustomHoursChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = BrandCyan,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = SurfaceElevated2
                            )
                        )
                    }

                    if (useCustomHours) {
                        // Hour chips in wrapping rows
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            (6..23).chunked(6).forEach { hourRow ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    hourRow.forEach { hour ->
                                        val isSelected = hour in customHours
                                        HourChip(
                                            hour = hour,
                                            isSelected = isSelected,
                                            onClick = {
                                                val newHours = if (isSelected) {
                                                    customHours - hour
                                                } else {
                                                    customHours + hour
                                                }
                                                onCustomHoursChange(newHours)
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PostPreviewCard(
    previewItem: SchedulePreviewItem,
    index: Int,
    onCaptionChange: (String) -> Unit,
    onTimeChange: (LocalDateTime) -> Unit,
    onRemove: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    var showTimePicker by remember { mutableStateOf(false) }

    // Current date/time from slot
    val currentDateTime = previewItem.slot.dateTime

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurfaceElevated1)
            .border(1.dp, BorderSubtle, shape)
    ) {
        // Header: Time (clickable) + Remove button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceGlassLight)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Clickable time section
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (showTimePicker) BrandCyan.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { showTimePicker = !showTimePicker }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                // Post number badge
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(BrandCyan.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = BrandCyan
                    )
                }

                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = BrandCyan,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = previewItem.formattedTime,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = BrandCyan
                )
                Icon(
                    if (showTimePicker) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = BrandCyan,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Remove button
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Error.copy(alpha = 0.1f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = Error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Expandable Time Picker with local state (apply only on OK)
        AnimatedVisibility(
            visible = showTimePicker,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val context = LocalContext.current
            val locale = context.resources.configuration.locales[0]
            val currentYear = LocalDate.now().year

            // LOCAL state for editing - only applied on OK button
            var editingDay by remember(currentDateTime) { mutableIntStateOf(currentDateTime.dayOfMonth) }
            var editingMonth by remember(currentDateTime) { mutableIntStateOf(currentDateTime.monthValue) }
            var editingTime by remember(currentDateTime) { mutableStateOf(currentDateTime.toLocalTime()) }

            // Generate month names
            val monthNames = remember(locale) {
                Month.entries.map { it.getDisplayName(JavaTextStyle.SHORT_STANDALONE, locale) }
            }

            // Calculate days in selected month
            val daysInMonth = remember(editingMonth) {
                YearMonth.of(currentYear, editingMonth).lengthOfMonth()
            }
            val daysList = remember(daysInMonth) { (1..daysInMonth).map { it.toString() } }

            // Consume scroll after wheel picker handles it
            val consumeRemainingScroll = remember {
                object : NestedScrollConnection {
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        return available
                    }
                    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                        return available
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(consumeRemainingScroll)
                    .background(SurfaceGlassMedium)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Date picker (day + month) - fully CYAN
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BrandCyan.copy(alpha = 0.1f))
                        .border(1.dp, BrandCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Day picker - updates LOCAL state only
                    WheelTextPicker(
                        texts = daysList,
                        startIndex = (editingDay - 1).coerceIn(0, daysList.size - 1),
                        rowCount = 3,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        color = TextPrimary,
                        selectorProperties = WheelPickerDefaults.selectorProperties(
                            enabled = true,
                            shape = RoundedCornerShape(8.dp),
                            color = BrandCyan.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, BrandCyan.copy(alpha = 0.3f))
                        ),
                        modifier = Modifier.weight(0.35f)
                    ) { index ->
                        editingDay = (index + 1).coerceAtMost(daysInMonth)
                    }

                    // Month picker - updates LOCAL state only
                    WheelTextPicker(
                        texts = monthNames,
                        startIndex = editingMonth - 1,
                        rowCount = 3,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        color = TextPrimary,
                        selectorProperties = WheelPickerDefaults.selectorProperties(
                            enabled = true,
                            shape = RoundedCornerShape(8.dp),
                            color = BrandCyan.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, BrandCyan.copy(alpha = 0.3f))
                        ),
                        modifier = Modifier.weight(0.65f)
                    ) { index ->
                        editingMonth = index + 1
                        // Adjust day if needed for new month
                        val newDaysInMonth = YearMonth.of(currentYear, editingMonth).lengthOfMonth()
                        editingDay = editingDay.coerceAtMost(newDaysInMonth)
                    }
                }

                // Time wheel picker - fully PINK, updates LOCAL state only
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BrandPink.copy(alpha = 0.1f))
                        .border(1.dp, BrandPink.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(vertical = 6.dp)
                ) {
                    WheelTimePicker(
                        startTime = editingTime,
                        rowCount = 3,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        textColor = TextPrimary,
                        selectorProperties = WheelPickerDefaults.selectorProperties(
                            enabled = true,
                            shape = RoundedCornerShape(8.dp),
                            color = BrandPink.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, BrandPink.copy(alpha = 0.3f))
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) { selectedTime ->
                        editingTime = selectedTime
                    }
                }

                // Action buttons: Cancel and OK
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Cancel button - just closes the picker
                    OutlinedButton(
                        onClick = { showTimePicker = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextSecondary
                        ),
                        border = BorderStroke(1.dp, BorderDefault)
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }

                    // OK button - applies changes and closes
                    Button(
                        onClick = {
                            val validDay = editingDay.coerceAtMost(daysInMonth)
                            val newDate = LocalDate.of(currentYear, editingMonth, validDay)
                            val newDateTime = LocalDateTime.of(newDate, editingTime)
                            onTimeChange(newDateTime)
                            showTimePicker = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BrandCyan,
                            contentColor = Color.White
                        )
                    ) {
                        Text(stringResource(R.string.dialog_ok))
                    }
                }
            }
        }

        // Content: Thumbnail + Caption
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Video Thumbnail
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceGlassMedium)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
            ) {
                if (previewItem.video.localPath != null) {
                    VideoThumbnail(
                        videoPath = previewItem.video.localPath,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.VideoFile,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // Caption section
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(120.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // File name
                Text(
                    text = previewItem.video.fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Caption input
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceGlassLight)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                ) {
                    BasicTextField(
                        value = previewItem.video.caption,
                        onValueChange = onCaptionChange,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = TextPrimary
                        ),
                        decorationBox = { innerTextField ->
                            Box {
                                if (previewItem.video.caption.isEmpty()) {
                                    Text(
                                        text = "Add caption...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }

                // Loading indicator for caption generation
                if (previewItem.video.isGeneratingCaption) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = GradientPurple,
                            strokeWidth = 1.5.dp
                        )
                        Text(
                            text = "Generating...",
                            style = MaterialTheme.typography.labelSmall,
                            color = GradientPurple
                        )
                    }
                }
            }
        }
    }
}

// ==================== Common Components ====================

@Composable
private fun GlassTopBar(
    title: String,
    onBackClick: () -> Unit,
    actionContent: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SurfaceElevated1,
                        SurfaceBase
                    )
                )
            )
            .padding(top = 48.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Back button
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(SurfaceGlassLight)
                        .border(1.dp, BorderSubtle, CircleShape)
                        .clickable(onClick = onBackClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            actionContent()
        }
    }
}

@Composable
private fun VideoPickerCard(
    videoPath: String?,
    videoCount: Int,
    onPickVideo: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)

    val glassBackground = Brush.linearGradient(
        colors = listOf(
            BrandCyan.copy(alpha = 0.08f),
            BrandPink.copy(alpha = 0.08f)
        )
    )

    val glassBorder = Brush.linearGradient(
        colors = listOf(
            BrandCyan.copy(alpha = 0.25f),
            BrandPink.copy(alpha = 0.25f)
        )
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassBackground)
            .border(1.dp, glassBorder, shape)
            .clickable(onClick = onPickVideo)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (videoPath != null && videoCount == 1) {
            // Video selected - show thumbnail
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = 2.dp,
                        brush = Brush.linearGradient(listOf(BrandCyan, BrandPink)),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                VideoThumbnail(
                    videoPath = videoPath,
                    modifier = Modifier
                        .width(160.dp)
                        .height(280.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Change video hint
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    tint = BrandCyan,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.new_post_change_video),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BrandCyan
                )
            }
        } else {
            // No video - show placeholder
            Spacer(modifier = Modifier.height(16.dp))

            // Icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(BrandCyan.copy(alpha = 0.15f))
                    .border(1.dp, BrandCyan.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.VideoLibrary,
                    contentDescription = null,
                    tint = BrandCyan,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.new_post_select_video),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.new_post_tap_to_select),
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GlassAIButton(
    onClick: () -> Unit,
    isLoading: Boolean,
    enabled: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ai_shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = if (enabled) listOf(
                        GradientPurple.copy(alpha = 0.2f),
                        BrandPink.copy(alpha = 0.2f)
                    ) else listOf(
                        SurfaceGlassLight,
                        SurfaceGlassLight
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = if (enabled) listOf(
                        GradientPurple.copy(alpha = shimmerAlpha * 0.5f),
                        BrandPink.copy(alpha = shimmerAlpha * 0.5f)
                    ) else listOf(BorderSubtle, BorderSubtle)
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = GradientPurple,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = if (enabled) GradientPurple else TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = stringResource(R.string.generate),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) GradientPurple else TextMuted
        )
    }
}

@Composable
private fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceGlassLight)
            .border(1.dp, BorderDefault, RoundedCornerShape(16.dp))
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = TextPrimary
            ),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun GlassSchedulePicker(
    scheduledTime: Long,
    onTimeSelected: (Long) -> Unit
) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]

    // Convert timestamp to LocalDateTime for wheel pickers
    val dateTime = remember(scheduledTime) {
        LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(scheduledTime),
            ZoneId.systemDefault()
        )
    }

    val shape = RoundedCornerShape(16.dp)

    // State for selected day and month
    var selectedDay by remember(dateTime) { mutableIntStateOf(dateTime.dayOfMonth) }
    var selectedMonth by remember(dateTime) { mutableIntStateOf(dateTime.monthValue) }

    // Generate month names
    val monthNames = remember(locale) {
        Month.entries.map { it.getDisplayName(JavaTextStyle.SHORT_STANDALONE, locale) }
    }

    // Calculate days in selected month (use current year)
    val currentYear = LocalDate.now().year
    val daysInMonth = remember(selectedMonth) {
        YearMonth.of(currentYear, selectedMonth).lengthOfMonth()
    }
    val daysList = remember(daysInMonth) { (1..daysInMonth).map { it.toString() } }

    // Consume scroll after wheel picker handles it - prevents parent LazyColumn from scrolling
    val consumeRemainingScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                return available
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return available
            }
        }
    }

    // Date (left) and Time (right) in one row
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .nestedScroll(consumeRemainingScroll)
            .clip(shape)
            .background(SurfaceGlassMedium)
            .border(1.dp, BorderDefault, shape)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Date picker (day + month) - fully CYAN
        Row(
            modifier = Modifier
                .weight(1.2f)
                .clip(RoundedCornerShape(12.dp))
                .background(BrandCyan.copy(alpha = 0.1f))
                .border(1.dp, BrandCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Day picker
            WheelTextPicker(
                texts = daysList,
                startIndex = (selectedDay - 1).coerceIn(0, daysList.size - 1),
                rowCount = 3,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                ),
                color = TextPrimary,
                selectorProperties = WheelPickerDefaults.selectorProperties(
                    enabled = true,
                    shape = RoundedCornerShape(8.dp),
                    color = BrandCyan.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, BrandCyan.copy(alpha = 0.3f))
                ),
                modifier = Modifier.weight(0.4f)
            ) { index ->
                selectedDay = index + 1
                val validDay = selectedDay.coerceAtMost(daysInMonth)
                val newDate = LocalDate.of(currentYear, selectedMonth, validDay)
                val newDateTime = LocalDateTime.of(newDate, dateTime.toLocalTime())
                val newMillis = newDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                onTimeSelected(newMillis)
            }

            // Month picker
            WheelTextPicker(
                texts = monthNames,
                startIndex = selectedMonth - 1,
                rowCount = 3,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                ),
                color = TextPrimary,
                selectorProperties = WheelPickerDefaults.selectorProperties(
                    enabled = true,
                    shape = RoundedCornerShape(8.dp),
                    color = BrandCyan.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, BrandCyan.copy(alpha = 0.3f))
                ),
                modifier = Modifier.weight(0.6f)
            ) { index ->
                selectedMonth = index + 1
                val newDaysInMonth = YearMonth.of(currentYear, selectedMonth).lengthOfMonth()
                val validDay = selectedDay.coerceAtMost(newDaysInMonth)
                val newDate = LocalDate.of(currentYear, selectedMonth, validDay)
                val newDateTime = LocalDateTime.of(newDate, dateTime.toLocalTime())
                val newMillis = newDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                onTimeSelected(newMillis)
            }
        }

        // Time picker - fully PINK
        Box(
            modifier = Modifier
                .weight(0.8f)
                .clip(RoundedCornerShape(12.dp))
                .background(BrandPink.copy(alpha = 0.1f))
                .border(1.dp, BrandPink.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(vertical = 6.dp)
        ) {
            WheelTimePicker(
                startTime = dateTime.toLocalTime(),
                rowCount = 3,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                ),
                textColor = TextPrimary,
                selectorProperties = WheelPickerDefaults.selectorProperties(
                    enabled = true,
                    shape = RoundedCornerShape(8.dp),
                    color = BrandPink.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, BrandPink.copy(alpha = 0.3f))
                ),
                modifier = Modifier.fillMaxWidth()
            ) { selectedTime ->
                val validDay = selectedDay.coerceAtMost(daysInMonth)
                val newDate = LocalDate.of(currentYear, selectedMonth, validDay)
                val newDateTime = LocalDateTime.of(newDate, selectedTime)
                val newMillis = newDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                onTimeSelected(newMillis)
            }
        }
    }
}

@Composable
private fun GlassActionCard(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val alpha = if (enabled) 1f else 0.5f

    val glassBackground = Brush.linearGradient(
        colors = listOf(
            accentColor.copy(alpha = 0.15f * alpha),
            BrandPink.copy(alpha = 0.1f * alpha)
        )
    )

    val glassBorder = Brush.linearGradient(
        colors = listOf(
            accentColor.copy(alpha = 0.4f * alpha),
            accentColor.copy(alpha = 0.2f * alpha)
        )
    )

    Column(
        modifier = modifier
            .clip(shape)
            .background(glassBackground)
            .border(1.dp, glassBorder, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.2f * alpha))
                .border(1.dp, accentColor.copy(alpha = 0.3f * alpha), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = accentColor.copy(alpha = alpha),
                modifier = Modifier.size(22.dp)
            )
        }

        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary.copy(alpha = alpha)
        )
    }
}

@Composable
private fun GlassSnackbar(
    message: String,
    onDismiss: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)

    // Glassmorphism container
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 24.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.3f),
                spotColor = Color.Black.copy(alpha = 0.3f)
            )
            .clip(shape)
            .graphicsLayer {
                // Apply blur effect for glass look (API 31+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    renderEffect = android.graphics.RenderEffect
                        .createBlurEffect(25f, 25f, android.graphics.Shader.TileMode.CLAMP)
                        .asComposeRenderEffect()
                }
            }
    ) {
        // Glass background with gradient
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.08f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.3f),
                            Color.White.copy(alpha = 0.1f)
                        )
                    ),
                    shape = shape
                )
        )

        // Content
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Error icon with glow
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    // Glow effect
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = Error.copy(alpha = 0.4f),
                        modifier = Modifier
                            .size(28.dp)
                            .graphicsLayer {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                    renderEffect = android.graphics.RenderEffect
                                        .createBlurEffect(8f, 8f, android.graphics.Shader.TileMode.CLAMP)
                                        .asComposeRenderEffect()
                                }
                            }
                    )
                    // Main icon
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = Error,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.dismiss),
                    tint = TextSecondary
                )
            }
        }
    }
}

// ==================== Batch Mode Components ====================

@Composable
private fun HourChip(
    hour: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) BrandCyan.copy(alpha = 0.2f) else SurfaceGlassLight
    val borderColor = if (isSelected) BrandCyan else BorderSubtle
    val textColor = if (isSelected) BrandCyan else TextSecondary

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = String.format("%02d:00", hour),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = textColor
        )
    }
}

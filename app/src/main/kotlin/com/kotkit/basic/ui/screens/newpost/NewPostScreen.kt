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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotkit.basic.R
import com.kotkit.basic.scheduler.AudiencePersona
import com.kotkit.basic.ui.components.BounceOverscrollContainer
import com.kotkit.basic.ui.components.GlassCard
import com.kotkit.basic.ui.components.GradientButton
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
                    onShowSnackbar = { showSnackbar = it }
                )
            } else {
                // Single Mode UI
                SingleModeContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    onPickVideo = { launchVideoPicker() },
                    onNavigateBack = onNavigateBack,
                    onShowSnackbar = { showSnackbar = it }
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

        // Snackbar
        showSnackbar?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                GlassSnackbar(
                    message = message,
                    onDismiss = { showSnackbar = null }
                )
            }
        }
    }
}

// ==================== Single Mode Content ====================

@Composable
private fun SingleModeContent(
    uiState: NewPostUiState,
    viewModel: NewPostViewModel,
    onPickVideo: () -> Unit,
    onNavigateBack: () -> Unit,
    onShowSnackbar: (String) -> Unit
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
                            onClick = { viewModel.generateCaption() },
                            isLoading = uiState.isGeneratingCaption,
                            enabled = !uiState.isLoading && !uiState.isGeneratingCaption && uiState.videoPath != null
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

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
                    viewModel.createPost(
                        onSuccess = onNavigateBack,
                        onError = { onShowSnackbar(it) }
                    )
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
                            viewModel.postNow(
                                onSuccess = onNavigateBack,
                                onError = { onShowSnackbar(it) }
                            )
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
    onShowSnackbar: (String) -> Unit
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
        // SECTION 2: SCHEDULE SETTINGS
        // ═══════════════════════════════════════════════════════════
        item {
            BatchScheduleSection(
                startDate = uiState.startDate,
                videosPerDay = uiState.videosPerDay,
                totalVideos = uiState.videos.size,
                selectedPersona = uiState.selectedPersona,
                onDateChange = { viewModel.setStartDate(it) },
                onVideosPerDayChange = { viewModel.setVideosPerDay(it) },
                onPersonaChange = { viewModel.setPersona(it) }
            )
        }

        // ═══════════════════════════════════════════════════════════
        // SECTION 3: AI CAPTION GENERATION
        // ═══════════════════════════════════════════════════════════
        item {
            BatchAICaptionSection(
                isGenerating = uiState.isGeneratingCaptions,
                progress = uiState.captionProgress,
                videosCount = uiState.videos.size,
                onGenerate = { viewModel.generateCaptions() }
            )
        }

        // ═══════════════════════════════════════════════════════════
        // SECTION 4: ADVANCED SETTINGS (collapsible)
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
                key = { index, item -> "${item.video.uri}_$index" }
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
        // SECTION 6: SCHEDULE ALL BUTTON
        // ═══════════════════════════════════════════════════════════
        item {
            Spacer(modifier = Modifier.height(4.dp))

            GradientButton(
                onClick = {
                    viewModel.scheduleAll(
                        onSuccess = onNavigateBack,
                        onError = { onShowSnackbar(it) }
                    )
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
    onDateChange: (LocalDate) -> Unit,
    onVideosPerDayChange: (Int) -> Unit,
    onPersonaChange: (AudiencePersona) -> Unit
) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val maxVideosPerDay = totalVideos.coerceAtMost(10)
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
                        onDateChange(LocalDate.of(currentYear, selectedMonth, validDay))
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
                        onDateChange(LocalDate.of(currentYear, selectedMonth, validDay))
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

                // Audience Persona
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.batch_schedule_audience),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary
                    )

                    // Persona chips as a wrapping flow
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AudiencePersona.entries.take(3).forEach { persona ->
                                BatchPersonaChip(
                                    persona = persona,
                                    isSelected = selectedPersona == persona,
                                    onClick = { onPersonaChange(persona) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AudiencePersona.entries.drop(3).forEach { persona ->
                                BatchPersonaChip(
                                    persona = persona,
                                    isSelected = selectedPersona == persona,
                                    onClick = { onPersonaChange(persona) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // Fill remaining space if odd number
                            if (AudiencePersona.entries.size % 3 != 0) {
                                repeat(3 - (AudiencePersona.entries.size % 3)) {
                                    Spacer(modifier = Modifier.weight(1f))
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
private fun BatchPersonaChip(
    persona: AudiencePersona,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) BrandPink.copy(alpha = 0.2f) else SurfaceGlassLight
    val borderColor = if (isSelected) BrandPink.copy(alpha = 0.5f) else BorderSubtle
    val textColor = if (isSelected) BrandPink else TextSecondary

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(persona.displayNameRes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = textColor
        )
    }
}

@Composable
private fun BatchAICaptionSection(
    isGenerating: Boolean,
    progress: Int,
    videosCount: Int,
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

        // Expandable Time Picker
        AnimatedVisibility(
            visible = showTimePicker,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val context = LocalContext.current
            val locale = context.resources.configuration.locales[0]
            val currentYear = LocalDate.now().year

            // State for selected day and month
            var selectedDay by remember(currentDateTime) { mutableIntStateOf(currentDateTime.dayOfMonth) }
            var selectedMonth by remember(currentDateTime) { mutableIntStateOf(currentDateTime.monthValue) }

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
                    // Day picker
                    WheelTextPicker(
                        texts = daysList,
                        startIndex = (selectedDay - 1).coerceIn(0, daysList.size - 1),
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
                        selectedDay = index + 1
                        val validDay = selectedDay.coerceAtMost(daysInMonth)
                        val newDate = LocalDate.of(currentYear, selectedMonth, validDay)
                        onTimeChange(LocalDateTime.of(newDate, currentDateTime.toLocalTime()))
                    }

                    // Month picker
                    WheelTextPicker(
                        texts = monthNames,
                        startIndex = selectedMonth - 1,
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
                        selectedMonth = index + 1
                        val newDaysInMonth = YearMonth.of(currentYear, selectedMonth).lengthOfMonth()
                        val validDay = selectedDay.coerceAtMost(newDaysInMonth)
                        val newDate = LocalDate.of(currentYear, selectedMonth, validDay)
                        onTimeChange(LocalDateTime.of(newDate, currentDateTime.toLocalTime()))
                    }
                }

                // Time wheel picker - fully PINK
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BrandPink.copy(alpha = 0.1f))
                        .border(1.dp, BrandPink.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(vertical = 6.dp)
                ) {
                    WheelTimePicker(
                        startTime = currentDateTime.toLocalTime(),
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
                        val validDay = selectedDay.coerceAtMost(daysInMonth)
                        val newDate = LocalDate.of(currentYear, selectedMonth, validDay)
                        onTimeChange(LocalDateTime.of(newDate, selectedTime))
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Error.copy(alpha = 0.2f)
            )
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceElevated2)
            .border(1.dp, Error.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = Error,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.dismiss),
                tint = TextTertiary
            )
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

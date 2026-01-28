package com.kotkit.basic.ui.screens.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.airbnb.lottie.compose.*
import com.kotkit.basic.R
import com.kotkit.basic.ui.components.*
import com.kotkit.basic.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToNewPost: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToWorkerDashboard: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAccessibilityStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Box вместо Scaffold - контент скроллится под навбаром для glassmorphism эффекта
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBase)
    ) {
        AnimatedContent(
            targetState = uiState.isLoading,
            transitionSpec = {
                fadeIn(animationSpec = tween(400)) togetherWith
                    fadeOut(animationSpec = tween(400))
            },
            label = "loading_transition"
        ) { isLoading ->
            if (isLoading) {
                BounceOverscrollContainer(
                    modifier = Modifier.fillMaxSize(),
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() }
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // Контент начинается под навбаром и скроллится под ним
                        contentPadding = PaddingValues(top = 156.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
                    ) {
                        item { ShimmerHomeContent() }
                    }
                }
            } else {
                BounceOverscrollContainer(
                    modifier = Modifier.fillMaxSize(),
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() }
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // Контент скроллится под прозрачным навбаром
                        contentPadding = PaddingValues(top = 156.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                    // Status cards
                    item {
                        StatusSection(
                            isAccessibilityEnabled = uiState.isAccessibilityEnabled,
                            hasUnlockCredentials = uiState.hasUnlockCredentials,
                            onSettingsClick = onNavigateToSettings,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    // Worker Mode Card
                    item {
                        WorkerModeCard(
                            onNavigateToWorkerDashboard = onNavigateToWorkerDashboard,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    // Stats
                    item {
                        Text(
                            text = "Моя статистика",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    item {
                        StatsSection(
                            scheduledCount = uiState.scheduledCount,
                            failedCount = uiState.failedCount,
                            completedCount = uiState.completedCount,
                            onQueueClick = onNavigateToQueue,
                            onHistoryClick = onNavigateToHistory,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    // Upcoming posts
                    if (uiState.scheduledPosts.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = stringResource(R.string.home_upcoming_posts),
                                onSeeAllClick = onNavigateToQueue,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        itemsIndexed(
                            items = uiState.scheduledPosts.take(3),
                            key = { _, post -> "scheduled_${post.id}" }
                        ) { index, post ->
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(
                                    animationSpec = tween(400, delayMillis = index * 80)
                                ) + slideInVertically(
                                    animationSpec = tween(400, delayMillis = index * 80),
                                    initialOffsetY = { it / 3 }
                                )
                            ) {
                                PostCard(
                                    post = post,
                                    onClick = {},
                                    onDelete = { viewModel.deletePost(post.id) },
                                    onReschedule = {},
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }

                    // Recent posts
                    if (uiState.recentPosts.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = stringResource(R.string.home_recent_posts),
                                onSeeAllClick = onNavigateToHistory,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        itemsIndexed(
                            items = uiState.recentPosts,
                            key = { _, post -> "recent_${post.id}" }
                        ) { index, post ->
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(
                                    animationSpec = tween(400, delayMillis = index * 80)
                                ) + slideInVertically(
                                    animationSpec = tween(400, delayMillis = index * 80),
                                    initialOffsetY = { it / 3 }
                                )
                            ) {
                                PostCard(
                                    post = post,
                                    onClick = {},
                                    onDelete = { viewModel.deletePost(post.id) },
                                    onReschedule = {},
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }

                    // Empty state
                    if (uiState.scheduledPosts.isEmpty() && uiState.recentPosts.isEmpty()) {
                        item {
                            EmptyState(
                                onCreatePost = onNavigateToNewPost,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }

                    // Bottom spacing for FAB
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
                }
            }
        }

        // Навбар поверх контента - glassmorphism overlay
        GlassTopBar(onNavigateToSettings = onNavigateToSettings)

        // FAB для создания нового поста
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            GradientFAB(onClick = onNavigateToNewPost)
        }
    }
}

@Composable
private fun GlassTopBar(onNavigateToSettings: () -> Unit) {
    // Форма: низ закруглённый, верх прямой
    val shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        // Чёрный фон - сливается с системной шторкой
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(Color.Black)
        )

        // Blur слой для Android 12+ - матовое стекло
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .graphicsLayer {
                        renderEffect = android.graphics.RenderEffect
                            .createBlurEffect(40f, 40f, android.graphics.Shader.TileMode.CLAMP)
                            .asComposeRenderEffect()
                    }
                    .background(Color.White.copy(alpha = 0.05f))
            )
        }

        // Градиентный оверлей
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            BrandCyan.copy(alpha = 0.06f),
                            BrandPink.copy(alpha = 0.04f),
                            Color.Transparent
                        )
                    )
                )
                // Градиентная рамка только снизу
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            BrandCyan.copy(alpha = 0.5f),
                            BrandPink.copy(alpha = 0.5f)
                        )
                    ),
                    shape = shape
                )
        )

        // Контент навбара
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Logo
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Title with gradient + tagline
                Column {
                    Text(
                        text = "KotKit",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            brush = Brush.linearGradient(
                                colors = listOf(BrandCyan, BrandPink)
                            )
                        ),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "purr-fect постинг 🐾",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }

            // Settings button with glass effect
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                BrandCyan.copy(alpha = 0.1f),
                                BrandPink.copy(alpha = 0.1f)
                            )
                        )
                    )
                    .border(
                        1.dp,
                        Brush.linearGradient(
                            colors = listOf(
                                BrandCyan.copy(alpha = 0.3f),
                                BrandPink.copy(alpha = 0.3f)
                            )
                        ),
                        RoundedCornerShape(12.dp)
                    )
                    .clickable(onClick = onNavigateToSettings),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.screen_settings),
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun GradientFAB(onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }

    // Press animation
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "fab_scale"
    )

    // Pulsing glow animation
    val infiniteTransition = rememberInfiniteTransition(label = "fab_glow")
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_scale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    // Rotating gradient angle
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(
        modifier = Modifier.size(72.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulsing glow
        Box(
            modifier = Modifier
                .size(72.dp)
                .scale(glowScale)
                .blur(20.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            BrandPink.copy(alpha = glowAlpha),
                            BrandCyan.copy(alpha = glowAlpha * 0.5f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Main FAB button
        Box(
            modifier = Modifier
                .size(64.dp)
                .scale(scale)
                .shadow(
                    elevation = 12.dp,
                    shape = CircleShape,
                    ambientColor = BrandPink.copy(alpha = 0.5f),
                    spotColor = BrandCyan.copy(alpha = 0.5f)
                )
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(BrandCyan, BrandPink),
                        start = androidx.compose.ui.geometry.Offset(
                            x = kotlin.math.cos(Math.toRadians(rotationAngle.toDouble())).toFloat() * 100,
                            y = kotlin.math.sin(Math.toRadians(rotationAngle.toDouble())).toFloat() * 100
                        ),
                        end = androidx.compose.ui.geometry.Offset(
                            x = kotlin.math.cos(Math.toRadians((rotationAngle + 180).toDouble())).toFloat() * 100,
                            y = kotlin.math.sin(Math.toRadians((rotationAngle + 180).toDouble())).toFloat() * 100
                        )
                    )
                )
                .border(
                    width = 2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.4f),
                            Color.White.copy(alpha = 0.1f)
                        )
                    ),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        isPressed = true
                        onClick()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.action_new_post),
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            kotlinx.coroutines.delay(150)
            isPressed = false
        }
    }
}

@Composable
private fun StatusSection(
    isAccessibilityEnabled: Boolean,
    hasUnlockCredentials: Boolean,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Accessibility status - показываем только когда НЕ включена
        AnimatedVisibility(
            visible = !isAccessibilityEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            StylishStatusCard(
                icon = Icons.Default.Warning,
                title = stringResource(R.string.home_accessibility_service),
                subtitle = stringResource(R.string.home_accessibility_not_enabled),
                isPositive = false,
                actionText = stringResource(R.string.enable),
                onActionClick = onSettingsClick
            )
        }

        // Unlock credentials status
        AnimatedVisibility(
            visible = !hasUnlockCredentials,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            StylishStatusCard(
                icon = Icons.Default.Lock,
                title = stringResource(R.string.home_screen_unlock),
                subtitle = stringResource(R.string.home_screen_unlock_subtitle),
                isPositive = null,
                actionText = stringResource(R.string.setup),
                onActionClick = onSettingsClick
            )
        }
    }
}

@Composable
private fun StylishStatusCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isPositive: Boolean?,
    actionText: String?,
    onActionClick: () -> Unit
) {
    val iconBgColor = when (isPositive) {
        true -> Success.copy(alpha = 0.15f)
        false -> StatusFailed.copy(alpha = 0.15f)
        null -> BrandCyan.copy(alpha = 0.15f)
    }
    val iconColor = when (isPositive) {
        true -> Success
        false -> StatusFailed
        null -> BrandCyan
    }

    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconBgColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
            if (actionText != null) {
                Text(
                    text = actionText,
                    color = BrandPink,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onActionClick)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun StatsSection(
    scheduledCount: Int,
    failedCount: Int,
    completedCount: Int,
    onQueueClick: () -> Unit,
    onHistoryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Main stats row - Scheduled and Completed
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StylishStatCard(
                title = stringResource(R.string.status_scheduled),
                count = scheduledCount,
                icon = Icons.Default.Schedule,
                onClick = onQueueClick,
                modifier = Modifier.weight(1f),
                accentColor = BrandPink
            )
            StylishStatCard(
                title = stringResource(R.string.status_completed),
                count = completedCount,
                icon = Icons.Default.CheckCircle,
                onClick = onHistoryClick,
                modifier = Modifier.weight(1f),
                accentColor = StatusCompleted
            )
        }

        // History button - full width, shows when there are completed or failed posts
        val totalHistoryCount = completedCount + failedCount
        AnimatedVisibility(
            visible = totalHistoryCount > 0,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            HistoryButton(
                totalCount = totalHistoryCount,
                failedCount = failedCount,
                onClick = onHistoryClick
            )
        }
    }
}

@Composable
private fun HistoryButton(
    totalCount: Int,
    failedCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasErrors = failedCount > 0
    val accentColor = if (hasErrors) StatusFailed else BrandCyan

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (hasErrors) StatusFailed.copy(alpha = 0.15f)
                else SurfaceGlassLight
            )
            .border(
                width = 1.dp,
                color = if (hasErrors) StatusFailed.copy(alpha = 0.3f) else BorderDefault,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (hasErrors) Icons.Default.Error else Icons.Default.History,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.home_stats_button),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasErrors) StatusFailed else TextPrimary
                        )
                        if (hasErrors) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(StatusFailed)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "$failedCount",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.home_stats_button_subtitle, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (hasErrors) StatusFailed else TextTertiary
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    onSeeAllClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = BrandCyan
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (accentColor == StatusFailed) StatusFailed else TextPrimary
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onSeeAllClick)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.see_all),
                color = accentColor,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = accentColor
            )
        }
    }
}

@Composable
private fun EmptyState(
    onCreatePost: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(120.dp),
            contentAlignment = Alignment.Center
        ) {
            // Glow background
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .blur(30.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                BrandPink.copy(alpha = 0.3f),
                                Color.Transparent
                            )
                        )
                    )
            )
            // Icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(BrandPink.copy(alpha = 0.1f))
                    .border(
                        width = 2.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                BrandCyan.copy(alpha = 0.3f),
                                BrandPink.copy(alpha = 0.3f)
                            )
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = BrandPink
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.message_no_posts),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.message_create_first_post),
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        GradientButton(
            onClick = onCreatePost,
            gradient = BrandGradient
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.home_create_post),
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun WorkerModeCard(
    onNavigateToWorkerDashboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier, onClick = onNavigateToWorkerDashboard) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    BrandCyan.copy(alpha = 0.15f),
                                    BrandPink.copy(alpha = 0.15f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WorkspacePremium,
                        contentDescription = null,
                        tint = BrandCyan,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column {
                    Text(
                        text = "Начать зарабатывать",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Зарабатывай на публикациях",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextTertiary
            )
        }
    }
}


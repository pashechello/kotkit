package com.kotkit.basic.ui.screens.worker

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotkit.basic.R
import com.kotkit.basic.ui.components.TikTokUsernameDialog
import com.kotkit.basic.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerDashboardScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAvailableTasks: () -> Unit = {},
    onNavigateToActiveTasks: () -> Unit = {},
    onNavigateToEarnings: () -> Unit = {},
    onNavigateToCompletedTasks: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: WorkerDashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show error
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // TikTok username dialog
    if (uiState.showTiktokUsernameDialog) {
        TikTokUsernameDialog(
            initialUsername = uiState.profile?.tiktokUsername ?: "",
            isSaving = uiState.isSavingUsername,
            onSave = { username -> viewModel.saveTiktokUsername(username) },
            onDismiss = { viewModel.dismissTiktokUsernameDialog() }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.worker_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceBase
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(SurfaceBase)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // TOP: Income display
            IncomeCard(
                totalEarned = uiState.totalEarned,
                availableBalance = uiState.availableBalance
            )

            // TikTok account label
            val tiktokUsername = uiState.profile?.tiktokUsername
            if (!tiktokUsername.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(SurfaceElevated1)
                        .clickable { viewModel.openUsernameEditor() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "@$tiktokUsername",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = BrandCyan
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // CENTER: Big Money Button (takes remaining space)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MoneyButton(
                        isActive = uiState.isWorkerModeActive,
                        isToggling = uiState.isToggling,
                        onClick = { viewModel.requestToggleWorkerMode() }
                    )

                    Spacer(Modifier.height(16.dp))

                    // Status text
                    Text(
                        text = if (uiState.isWorkerModeActive) {
                            stringResource(R.string.worker_mode_active)
                        } else {
                            stringResource(R.string.worker_mode_inactive)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.isWorkerModeActive) Success else TextTertiary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // BOTTOM: Stats Card (clickable → opens completed tasks)
            StatsCard(
                completedTasks = uiState.completedTasks,
                successRate = uiState.successRate,
                rating = uiState.rating,
                onClick = onNavigateToCompletedTasks
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IncomeCard(
    totalEarned: Float,
    availableBalance: Float
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceElevated2
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.worker_your_income),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${String.format("%.2f", totalEarned)} ₽",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Success,
                fontSize = 36.sp
            )
            if (availableBalance > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.worker_available_to_withdraw, availableBalance),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
        }
    }
}

@Composable
private fun MoneyButton(
    isActive: Boolean,
    isToggling: Boolean,
    onClick: () -> Unit
) {
    // Press animation
    var isPressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed || isToggling) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "press_scale"
    )

    // Pulsing animation for the glow effect
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    // Red gradient - inactive state (объёмный эффект)
    val redGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFFFF6B6B), // Lighter red top (highlight)
            Color(0xFFEE4444), // Main red
            Color(0xFFCC2222), // Darker red bottom (shadow)
            Color(0xFFAA1111)  // Deep red edge
        )
    )

    // Green gradient - active state (объёмный эффект)
    val greenGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF6BFF6B), // Lighter green top (highlight)
            Color(0xFF44EE44), // Main green
            Color(0xFF22CC22), // Darker green bottom (shadow)
            Color(0xFF11AA11)  // Deep green edge
        )
    )

    val currentGradient = if (isActive) greenGradient else redGradient
    val glowColor = if (isActive) Color(0xFF44FF44) else Color(0xFFFF4444)
    val glowColorDark = if (isActive) Color(0xFF00CC00) else Color(0xFFCC0000)

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Calculate button size based on available width (90% of width, but keep it circular)
        val buttonSize = (maxWidth * 0.85f).coerceAtMost(320.dp)
        val glowSize = buttonSize + 20.dp

        // Glow effect behind button (pulsing)
        Box(
            modifier = Modifier
                .size(glowSize)
                .graphicsLayer {
                    scaleX = pulseScale * pressScale
                    scaleY = pulseScale * pressScale
                    alpha = glowAlpha
                }
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = 0.6f),
                            glowColorDark.copy(alpha = 0.3f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Main button - fills most of the width
        Button(
            onClick = onClick,
            enabled = !isToggling,
            modifier = Modifier
                .size(buttonSize)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .shadow(
                    elevation = 24.dp,
                    shape = CircleShape,
                    ambientColor = glowColor,
                    spotColor = glowColorDark
                ),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent
            ),
            contentPadding = PaddingValues(0.dp),
            interactionSource = remember { MutableInteractionSource() }.also { interactionSource ->
                LaunchedEffect(interactionSource) {
                    interactionSource.interactions.collect { interaction ->
                        when (interaction) {
                            is androidx.compose.foundation.interaction.PressInteraction.Press -> {
                                isPressed = true
                            }
                            is androidx.compose.foundation.interaction.PressInteraction.Release,
                            is androidx.compose.foundation.interaction.PressInteraction.Cancel -> {
                                isPressed = false
                            }
                        }
                    }
                }
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = currentGradient,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Inner highlight for 3D effect
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.25f),
                                    Color.Transparent,
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.2f)
                                ),
                                startY = 0f,
                                endY = 500f
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isToggling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = Color.White,
                            strokeWidth = 4.dp
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.worker_button_money),
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = 4.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(
    completedTasks: Int,
    successRate: Float,
    rating: Float,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceElevated2
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.worker_stats_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = Icons.Default.CheckCircle,
                    value = completedTasks.toString(),
                    label = stringResource(R.string.worker_stats_completed),
                    color = Success
                )
                StatItem(
                    icon = Icons.Default.TrendingUp,
                    value = "${(successRate * 100).toInt()}%",
                    label = stringResource(R.string.worker_stats_success_rate),
                    color = Info
                )
                StatItem(
                    icon = Icons.Default.Star,
                    value = String.format("%.1f", rating),
                    label = stringResource(R.string.worker_stats_rating),
                    color = Warning
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

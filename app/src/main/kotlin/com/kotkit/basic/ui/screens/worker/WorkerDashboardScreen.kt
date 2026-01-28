package com.kotkit.basic.ui.screens.worker

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotkit.basic.R
import com.kotkit.basic.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerDashboardScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAvailableTasks: () -> Unit = {},
    onNavigateToActiveTasks: () -> Unit = {},
    onNavigateToEarnings: () -> Unit = {},
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
                        onClick = { /* TODO: Coming soon */ },
                        enabled = false // Пока недоступно
                    )

                    Spacer(Modifier.height(16.dp))

                    // Coming soon text
                    Text(
                        text = stringResource(R.string.worker_coming_soon),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // BOTTOM: Stats Card
            StatsCard(
                completedTasks = uiState.completedTasks,
                successRate = uiState.successRate,
                rating = uiState.rating
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
                text = "$${String.format("%.2f", totalEarned)}",
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
    onClick: () -> Unit,
    enabled: Boolean
) {
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

    // Red gradient for enabled state - объёмный эффект
    val redGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFFFF6B6B), // Lighter red top (highlight)
            Color(0xFFEE4444), // Main red
            Color(0xFFCC2222), // Darker red bottom (shadow)
            Color(0xFFAA1111)  // Deep red edge
        )
    )

    // Disabled grey gradient - тоже объёмный
    val disabledGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF5A5A5A), // Lighter grey top
            Color(0xFF4A4A4A), // Main grey
            Color(0xFF3A3A3A), // Darker grey bottom
            Color(0xFF2A2A2A)  // Deep grey edge
        )
    )

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Calculate button size based on available width (90% of width, but keep it circular)
        val buttonSize = (maxWidth * 0.85f).coerceAtMost(320.dp)
        val glowSize = buttonSize + 20.dp

        // Glow effect behind button (only when enabled)
        if (enabled) {
            Box(
                modifier = Modifier
                    .size(glowSize)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = glowAlpha
                    }
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFFF4444).copy(alpha = 0.6f),
                                Color(0xFFFF0000).copy(alpha = 0.3f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
        }

        // Main button - fills most of the width
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(buttonSize)
                .shadow(
                    elevation = if (enabled) 24.dp else 8.dp,
                    shape = CircleShape,
                    ambientColor = if (enabled) Color(0xFFFF4444) else Color.Black,
                    spotColor = if (enabled) Color(0xFFCC0000) else Color.Black
                ),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent
            ),
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = if (enabled) redGradient else disabledGradient,
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
                                    Color.White.copy(alpha = if (enabled) 0.2f else 0.1f),
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
                    Text(
                        text = stringResource(R.string.worker_button_money),
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        color = if (enabled) Color.White else TextDisabled,
                        letterSpacing = 4.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsCard(
    completedTasks: Int,
    successRate: Float,
    rating: Float
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceElevated2
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.worker_stats_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

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

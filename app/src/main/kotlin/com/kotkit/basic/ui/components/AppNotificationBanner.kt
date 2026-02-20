package com.kotkit.basic.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kotkit.basic.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Top-positioned notification banner with glassmorphism styling.
 * Shows one notification at a time with slide-in/slide-out animation.
 * Positioned below the status bar.
 */
@Composable
fun AppNotificationBanner(
    modifier: Modifier = Modifier
) {
    var currentMessage by remember { mutableStateOf<SnackbarController.Message?>(null) }
    var visible by remember { mutableStateOf(false) }
    var displayMessage by remember { mutableStateOf<SnackbarController.Message?>(null) }

    LaunchedEffect(Unit) {
        SnackbarController.messages.collect { msg ->
            if (visible) {
                visible = false
                delay(300)
            }
            currentMessage = msg
            displayMessage = msg
            visible = true
        }
    }

    LaunchedEffect(currentMessage) {
        val msg = currentMessage ?: return@LaunchedEffect
        val dismissDelay = when (msg.duration) {
            NotificationDuration.SHORT -> 3000L
            NotificationDuration.LONG -> 6000L
        }
        delay(dismissDelay)
        visible = false
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        AnimatedVisibility(
            visible = visible && currentMessage != null,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(250)
            ) + fadeOut(animationSpec = tween(200))
        ) {
            displayMessage?.let { msg ->
                NotificationBannerContent(
                    message = msg,
                    onDismiss = { visible = false },
                    onAction = {
                        msg.onAction?.invoke()
                        visible = false
                    }
                )
            }
        }
    }
}

@Composable
private fun NotificationBannerContent(
    message: SnackbarController.Message,
    onDismiss: () -> Unit,
    onAction: () -> Unit
) {
    val (icon, accentColor, _, _) = remember(message.type) {
        when (message.type) {
            NotificationType.ERROR -> BannerStyle(
                icon = Icons.Default.ErrorOutline,
                accent = Error,
                background = Error.copy(alpha = 0.12f),
                borderBrush = Brush.linearGradient(listOf(Error, ErrorLight))
            )
            NotificationType.SUCCESS -> BannerStyle(
                icon = Icons.Default.CheckCircleOutline,
                accent = Success,
                background = Success.copy(alpha = 0.12f),
                borderBrush = Brush.linearGradient(listOf(Success, SuccessLight))
            )
            NotificationType.WARNING -> BannerStyle(
                icon = Icons.Default.WarningAmber,
                accent = Warning,
                background = Warning.copy(alpha = 0.12f),
                borderBrush = Brush.linearGradient(listOf(Warning, WarningLight))
            )
            NotificationType.INFO -> BannerStyle(
                icon = Icons.Default.Info,
                accent = BrandCyan,
                background = BrandCyan.copy(alpha = 0.08f),
                borderBrush = Brush.linearGradient(listOf(BrandCyan, BrandPink))
            )
        }
    }

    // --- Animations ---

    // Rotating border highlight angle
    val infiniteTransition = rememberInfiniteTransition(label = "banner")
    val borderAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing)
        ),
        label = "borderAngle"
    )

    // Icon glow pulse
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    // One-shot shimmer sweep on entry
    var shimmerTriggered by remember { mutableStateOf(false) }
    val shimmerOffset by animateFloatAsState(
        targetValue = if (shimmerTriggered) 2.5f else -0.5f,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "shimmer"
    )
    LaunchedEffect(Unit) { shimmerTriggered = true }

    val shape = RoundedCornerShape(20.dp)

    // Animated border brush: accent-tinted sweep that rotates
    val animatedBorderBrush = remember(borderAngle, accentColor) {
        val rad = Math.toRadians(borderAngle.toDouble())
        val startX = (0.5f + 0.5f * cos(rad)).toFloat()
        val startY = (0.5f + 0.5f * sin(rad)).toFloat()
        val endX = (0.5f - 0.5f * cos(rad)).toFloat()
        val endY = (0.5f - 0.5f * sin(rad)).toFloat()
        Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to accentColor.copy(alpha = 0.5f),
                0.35f to Color.White.copy(alpha = 0.08f),
                0.65f to accentColor.copy(alpha = 0.2f),
                1.0f to accentColor.copy(alpha = 0.5f)
            ),
            start = Offset(startX * 1000f, startY * 1000f),
            end = Offset(endX * 1000f, endY * 1000f)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            // Layer 1: Deep dark base
            .background(Color(0xF2101018))
            // Layer 2: Radial accent color wash (the "tinted glass" feel)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.14f),
                            accentColor.copy(alpha = 0.04f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.15f, size.height * 0.5f),
                        radius = size.width * 0.7f
                    )
                )
            }
            // Layer 3: Top-edge highlight (light refraction simulation)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.07f),
                            Color.White.copy(alpha = 0.02f),
                            Color.Transparent
                        ),
                        endY = size.height * 0.45f
                    )
                )
            }
            // Animated gradient border
            .border(
                width = 1.dp,
                brush = animatedBorderBrush,
                shape = shape
            )
            // One-shot shimmer overlay
            .drawWithContent {
                drawContent()
                if (shimmerOffset < 2.0f) {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.06f),
                                Color.White.copy(alpha = 0.12f),
                                Color.White.copy(alpha = 0.06f),
                                Color.Transparent
                            ),
                            start = Offset(size.width * shimmerOffset, 0f),
                            end = Offset(size.width * (shimmerOffset + 0.4f), size.height)
                        ),
                        blendMode = BlendMode.SrcOver
                    )
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Icon with glow
            Box(
                modifier = Modifier
                    .size(40.dp)
                    // Outer glow ring
                    .drawBehind {
                        drawCircle(
                            color = accentColor.copy(alpha = glowAlpha * 0.35f),
                            radius = size.minDimension * 0.85f
                        )
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(alpha = glowAlpha)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Text
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Action button or dismiss
            if (message.actionLabel != null) {
                Text(
                    text = message.actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.1f))
                        .clickable(onClick = onAction)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = TextMuted,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                )
            }
        }
    }
}

private data class BannerStyle(
    val icon: ImageVector,
    val accent: Color,
    val background: Color,
    val borderBrush: Brush
)

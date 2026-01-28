package com.kotkit.basic.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kotkit.basic.ui.theme.*

/**
 * Glass Card - карточка с glassmorphism эффектом
 * Как на landing: градиентный фон cyan→pink + градиентный border
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accentColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)

    // Градиентный фон как на landing
    val glassBackground = Brush.linearGradient(
        colors = listOf(
            BrandCyan.copy(alpha = 0.08f),
            BrandPink.copy(alpha = 0.08f)
        )
    )

    // Градиентная граница cyan → pink
    val glassBorder = Brush.linearGradient(
        colors = listOf(
            BrandCyan.copy(alpha = 0.25f),
            BrandPink.copy(alpha = 0.25f)
        )
    )

    Column(
        modifier = modifier
            .clip(shape)
            .background(glassBackground)
            .border(
                width = 1.dp,
                brush = glassBorder,
                shape = shape
            )
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(16.dp),
        content = content
    )
}

/**
 * Glass Card with accent border glow
 */
@Composable
fun GlassCardAccent(
    modifier: Modifier = Modifier,
    accentColor: Color = BrandCyan,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier
            .clip(shape)
            .background(SurfaceGlassMedium)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.4f),
                        accentColor.copy(alpha = 0.1f)
                    )
                ),
                shape = shape
            )
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(16.dp),
        content = content
    )
}

/**
 * Gradient Card - карточка с градиентным фоном
 */
@Composable
fun GradientCard(
    modifier: Modifier = Modifier,
    gradient: Brush = BrandGradient,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier
            .clip(shape)
            .background(gradient)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(16.dp),
        content = content
    )
}

/**
 * Gradient Button - кнопка с градиентом и bounce анимацией
 */
@Composable
fun GradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: Brush = BrandGradient,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "button_scale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = if (enabled) 12.dp else 0.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = BrandPink.copy(alpha = 0.3f),
                spotColor = BrandCyan.copy(alpha = 0.3f)
            )
            .clip(RoundedCornerShape(16.dp))
            .background(
                brush = if (enabled) gradient else Brush.linearGradient(
                    colors = listOf(TextMuted, TextMuted)
                )
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    isPressed = true
                    onClick()
                }
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            kotlinx.coroutines.delay(100)
            isPressed = false
        }
    }
}

/**
 * Gradient Icon Button - круглая кнопка с градиентом
 */
@Composable
fun GradientIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    gradient: Brush = BrandGradient,
    size: Dp = 56.dp
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "icon_button_scale"
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .shadow(
                elevation = 16.dp,
                shape = CircleShape,
                ambientColor = BrandPink.copy(alpha = 0.4f),
                spotColor = BrandCyan.copy(alpha = 0.4f)
            )
            .clip(CircleShape)
            .background(gradient)
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
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.45f)
        )
    }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            kotlinx.coroutines.delay(100)
            isPressed = false
        }
    }
}

/**
 * Stylish Stat Card - карточка статистики с glassmorphism
 */
@Composable
fun StylishStatCard(
    title: String,
    count: Int,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = BrandCyan
) {
    val shape = RoundedCornerShape(20.dp)

    // Градиентный фон cyan → pink
    val glassBackground = Brush.linearGradient(
        colors = listOf(
            BrandCyan.copy(alpha = 0.08f),
            BrandPink.copy(alpha = 0.08f)
        )
    )

    // Градиентная граница cyan → pink
    val glassBorder = Brush.linearGradient(
        colors = listOf(
            BrandCyan.copy(alpha = 0.25f),
            BrandPink.copy(alpha = 0.25f)
        )
    )

    Column(
        modifier = modifier
            .clip(shape)
            .background(glassBackground)
            .border(
                width = 1.dp,
                brush = glassBorder,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Icon with accent color background
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.15f))
                .border(
                    width = 1.dp,
                    color = accentColor.copy(alpha = 0.3f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Number
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary
        )
    }
}

/**
 * Glowing Icon - иконка с пульсирующим свечением
 */
@Composable
fun GlowingIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    color: Color = BrandCyan,
    size: Dp = 24.dp,
    glowEnabled: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "icon_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (glowEnabled) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color.copy(alpha = glowAlpha * 0.5f),
                modifier = Modifier
                    .size(size * 1.5f)
                    .blur(10.dp)
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size)
        )
    }
}

/**
 * Animated Gradient Border - анимированная градиентная рамка
 */
@Composable
fun AnimatedGradientBorder(
    modifier: Modifier = Modifier,
    borderWidth: Dp = 2.dp,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        BrandCyan,
                        BrandPink,
                        GradientPurple,
                        BrandCyan
                    )
                )
            )
            .padding(borderWidth)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(SurfaceBase),
            content = content
        )
    }
}

/**
 * Pulsing Dot - анимированная точка
 */
@Composable
fun PulsingDot(
    color: Color = BrandCyan,
    size: Dp = 8.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .shadow(
                elevation = 4.dp,
                shape = CircleShape,
                ambientColor = color.copy(alpha = 0.5f),
                spotColor = color.copy(alpha = 0.5f)
            )
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

/**
 * Glass Switch Row - строка с переключателем в стиле glass
 */
@Composable
fun GlassSwitchRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconColor: Color = BrandCyan
) {
    GlassCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconColor.copy(alpha = 0.15f))
                        .border(
                            width = 1.dp,
                            color = iconColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp)
                        ),
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
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                    }
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = BrandCyan,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = SurfaceElevated2
                )
            )
        }
    }
}

/**
 * Glass Setting Row - строка настройки с navigation arrow
 */
@Composable
fun GlassSettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconColor: Color = BrandCyan,
    trailingContent: @Composable (() -> Unit)? = null
) {
    GlassCard(
        modifier = modifier,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconColor.copy(alpha = 0.15f))
                        .border(
                            width = 1.dp,
                            color = iconColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp)
                        ),
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
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                    }
                }
            }
            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}

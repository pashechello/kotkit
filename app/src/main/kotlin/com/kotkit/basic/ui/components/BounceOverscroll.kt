package com.kotkit.basic.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.kotkit.basic.ui.theme.BrandCyan
import com.kotkit.basic.ui.theme.BrandPink
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * iOS-style bounce overscroll effect container with optional pull-to-refresh.
 * Wraps scrollable content (LazyColumn, Column+verticalScroll, etc.)
 */
@Composable
fun BounceOverscrollContainer(
    modifier: Modifier = Modifier,
    orientation: Orientation = Orientation.Vertical,
    maxOverscroll: Float = 200f,
    resistanceFactor: Float = 0.5f,
    resistanceDivisor: Float = 300f,
    dampingRatio: Float = 0.8f,
    stiffness: Float = Spring.StiffnessMedium,
    // Pull-to-refresh support
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    refreshThreshold: Float = 120f,
    content: @Composable () -> Unit
) {
    // Синхронное состояние для немедленного отклика на scroll events
    var currentOffset by remember { mutableFloatStateOf(0f) }

    // Animatable только для spring-анимации возврата
    val animatedOffset = remember { Animatable(0f) }

    // Флаг: идёт ли анимация возврата
    var isAnimating by remember { mutableStateOf(false) }

    // Финальный offset для отрисовки
    val displayOffset = if (isAnimating) animatedOffset.value else currentOffset

    // Pull-to-refresh state
    val canRefresh = onRefresh != null && orientation == Orientation.Vertical
    var hasTriggeredRefresh by remember { mutableStateOf(false) }

    // Refresh indicator progress (0 to 1+)
    val refreshProgress = if (canRefresh && displayOffset > 0) {
        (displayOffset / refreshThreshold).coerceIn(0f, 1.5f)
    } else 0f

    // Infinite rotation when refreshing
    val infiniteTransition = rememberInfiniteTransition(label = "refresh_rotation")
    val infiniteRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing)
        ),
        label = "infinite_rotation"
    )

    // Reset trigger when refresh completes
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) {
            hasTriggeredRefresh = false
        }
    }

    val nestedScrollConnection = remember(orientation, maxOverscroll, resistanceFactor, resistanceDivisor) {
        object : NestedScrollConnection {

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = if (orientation == Orientation.Vertical) available.y else available.x

                // Если в состоянии overscroll и пользователь скроллит обратно
                if (currentOffset != 0f) {
                    val isReturning = (currentOffset > 0 && delta < 0) ||
                                     (currentOffset < 0 && delta > 0)

                    if (isReturning) {
                        // Прерываем анимацию если она шла
                        isAnimating = false

                        // Не перескакиваем через ноль
                        val consumed = if (abs(delta) > abs(currentOffset)) {
                            val result = -currentOffset
                            currentOffset = 0f
                            result
                        } else {
                            currentOffset += delta
                            delta
                        }

                        return if (orientation == Orientation.Vertical) {
                            Offset(0f, consumed)
                        } else {
                            Offset(consumed, 0f)
                        }
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val delta = if (orientation == Orientation.Vertical) available.y else available.x

                // Применяем overscroll только при drag (не fling) и когда есть остаток
                if (delta != 0f && source == NestedScrollSource.Drag) {
                    // Прерываем анимацию
                    isAnimating = false

                    // Rubber-band resistance — чем дальше тянешь, тем сложнее
                    val resistance = resistanceFactor / (1f + abs(currentOffset) / resistanceDivisor)
                    currentOffset = (currentOffset + delta * resistance).coerceIn(-maxOverscroll, maxOverscroll)

                    return if (orientation == Orientation.Vertical) {
                        Offset(0f, delta)
                    } else {
                        Offset(delta, 0f)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                return animateBackIfNeeded(available)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return animateBackIfNeeded(available)
            }

            private suspend fun animateBackIfNeeded(available: Velocity): Velocity {
                if (currentOffset != 0f) {
                    // Запоминаем текущую velocity для возврата
                    val velocityValue = if (orientation == Orientation.Vertical) available.y else available.x

                    // Check if should trigger refresh (pulled down past threshold)
                    if (canRefresh && currentOffset >= refreshThreshold && !hasTriggeredRefresh && !isRefreshing) {
                        hasTriggeredRefresh = true
                        onRefresh?.invoke()
                    }

                    // Синхронизируем Animatable с текущим offset
                    animatedOffset.snapTo(currentOffset)
                    isAnimating = true

                    // Spring-анимация возврата
                    animatedOffset.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = dampingRatio,
                            stiffness = stiffness
                        ),
                        initialVelocity = velocityValue * 0.2f // Минимальное влияние velocity
                    )

                    // После анимации синхронизируем состояние
                    currentOffset = 0f
                    isAnimating = false

                    return if (orientation == Orientation.Vertical) {
                        Velocity(0f, velocityValue)
                    } else {
                        Velocity(velocityValue, 0f)
                    }
                }
                return Velocity.Zero
            }
        }
    }

    Box(modifier = modifier) {
        // Refresh indicator (appears when pulling down)
        if (canRefresh && (refreshProgress > 0 || isRefreshing)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        IntOffset(0, (displayOffset * 0.4f - 40.dp.toPx()).roundToInt())
                    }
                    .alpha(if (isRefreshing) 1f else refreshProgress.coerceIn(0f, 1f))
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(28.dp)
                        .rotate(if (isRefreshing) infiniteRotation else refreshProgress * 270f),
                    color = BrandCyan,
                    trackColor = BrandPink.copy(alpha = 0.2f),
                    strokeWidth = 2.5.dp,
                    strokeCap = StrokeCap.Round
                )
            }
        }

        // Main content
        Box(
            modifier = Modifier
                .nestedScroll(nestedScrollConnection)
                .graphicsLayer {
                    if (orientation == Orientation.Vertical) {
                        translationY = displayOffset
                    } else {
                        translationX = displayOffset
                    }
                }
        ) {
            content()
        }
    }
}

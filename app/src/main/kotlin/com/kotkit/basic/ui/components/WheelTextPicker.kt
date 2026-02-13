package com.kotkit.basic.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.commandiron.wheel_picker_compose.core.SelectorProperties
import com.commandiron.wheel_picker_compose.core.WheelPickerDefaults
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * Custom WheelTextPicker implementation since the library version doesn't export it.
 * Provides iOS-style wheel picker for text lists.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelTextPicker(
    texts: List<String>,
    modifier: Modifier = Modifier,
    startIndex: Int = 0,
    rowCount: Int = 3,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    selectorProperties: SelectorProperties = WheelPickerDefaults.selectorProperties(),
    onScrollFinished: (index: Int) -> Unit = {}
) {
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val itemHeight = 40.dp
    val itemHeightPx = with(density) { itemHeight.toPx() }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = startIndex.coerceIn(0, (texts.size - 1).coerceAtLeast(0))
    )

    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // Calculate center item
    val centerIndex by remember {
        derivedStateOf {
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val firstVisibleOffset = listState.firstVisibleItemScrollOffset

            if (firstVisibleOffset > itemHeightPx / 2) {
                firstVisibleIndex + 1
            } else {
                firstVisibleIndex
            }.coerceIn(0, (texts.size - 1).coerceAtLeast(0))
        }
    }

    // Haptic feedback when center item changes (casino-style click)
    // Using snapshotFlow with drop(1) to skip initial composition
    LaunchedEffect(Unit) {
        snapshotFlow { centerIndex }
            .distinctUntilChanged()
            .drop(1) // Skip initial value to avoid haptic on screen load
            .collect {
                if (texts.isNotEmpty()) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
    }

    // Notify on scroll finished
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { isScrolling ->
                if (!isScrolling && texts.isNotEmpty()) {
                    onScrollFinished(centerIndex)
                }
            }
    }

    val halfRowCount = rowCount / 2
    val totalHeight = itemHeight * rowCount

    Box(
        modifier = modifier.height(totalHeight),
        contentAlignment = Alignment.Center
    ) {
        // Selector indicator
        if (selectorProperties.enabled().value) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clip(selectorProperties.shape().value)
                    .background(selectorProperties.color().value)
                    .then(
                        selectorProperties.border().value?.let { border ->
                            Modifier.border(border, selectorProperties.shape().value)
                        } ?: Modifier
                    )
            )
        }

        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier.height(totalHeight)
        ) {
            // Top padding items
            items(halfRowCount) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {}
            }

            // Actual items
            items(texts.size) { index ->
                val isSelected = index == centerIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = texts[index],
                        style = style,
                        color = if (isSelected) color else color.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }

            // Bottom padding items
            items(halfRowCount) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {}
            }
        }
    }
}

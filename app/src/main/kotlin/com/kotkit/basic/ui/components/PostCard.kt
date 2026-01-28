package com.kotkit.basic.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kotkit.basic.R
import com.kotkit.basic.data.local.db.entities.PostEntity
import com.kotkit.basic.data.local.db.entities.PostStatus
import com.kotkit.basic.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PostCard(
    post: PostEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onReschedule: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val noCaptionText = stringResource(R.string.post_no_caption)
    val todayFormat = stringResource(R.string.post_today)
    val tomorrowFormat = stringResource(R.string.post_tomorrow)

    val shape = RoundedCornerShape(20.dp)

    // Accent color based on status
    val accentColor = when (post.status) {
        PostStatus.SCHEDULED -> StatusScheduled
        PostStatus.POSTING -> StatusPosting
        PostStatus.COMPLETED -> StatusCompleted
        PostStatus.FAILED -> StatusFailed
        PostStatus.NEEDS_ACTION -> StatusNeedsAction
        PostStatus.CANCELLED -> StatusCancelled
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = accentColor.copy(alpha = 0.15f),
                spotColor = accentColor.copy(alpha = 0.15f)
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.2f),
                        Color.Transparent
                    )
                ),
                shape = shape
            )
            .clickable(onClick = onClick)
    ) {
        // Subtle gradient overlay at top
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            accentColor,
                            accentColor.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Video thumbnail with rounded corners
            Box(
                modifier = Modifier
                    .width(85.dp)
                    .height(150.dp)
                    .shadow(
                        elevation = 4.dp,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clip(RoundedCornerShape(12.dp))
            ) {
                VideoThumbnail(
                    videoPath = post.videoPath,
                    thumbnailPath = post.thumbnailPath,
                    modifier = Modifier.fillMaxSize(),
                    showPlayIcon = false
                )
            }

            // Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(150.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    // Status badge
                    StatusBadge(status = post.status)

                    Spacer(modifier = Modifier.height(10.dp))

                    // Caption
                    Text(
                        text = post.caption.ifBlank { noCaptionText },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column {
                    // Scheduled time with icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatDateTime(post.scheduledTime, todayFormat, tomorrowFormat),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Error message if failed
                    if (post.status == PostStatus.FAILED && !post.errorMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = post.errorMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusFailed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Menu button with better styling
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.post_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    if (post.status == PostStatus.SCHEDULED || post.status == PostStatus.FAILED) {
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(stringResource(R.string.action_reschedule))
                                }
                            },
                            onClick = {
                                showMenu = false
                                onReschedule()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = StatusFailed
                                )
                                Text(
                                    stringResource(R.string.action_delete),
                                    color = StatusFailed
                                )
                            }
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

private fun formatDateTime(timestamp: Long, todayFormat: String, tomorrowFormat: String): String {
    val now = System.currentTimeMillis()
    val diff = timestamp - now
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    return when {
        diff < 0 -> {
            // Past
            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        diff < 24 * 60 * 60 * 1000 -> {
            // Today
            String.format(todayFormat, timeFormat.format(Date(timestamp)))
        }
        diff < 48 * 60 * 60 * 1000 -> {
            // Tomorrow
            String.format(tomorrowFormat, timeFormat.format(Date(timestamp)))
        }
        else -> {
            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

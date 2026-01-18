package com.autoposter.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoposter.data.local.db.entities.PostEntity
import com.autoposter.data.local.db.entities.PostStatus
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

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Video thumbnail
            VideoThumbnail(
                videoPath = post.videoPath,
                modifier = Modifier
                    .width(80.dp)
                    .height(142.dp),
                showPlayIcon = false
            )

            // Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(142.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    // Status badge
                    StatusBadge(status = post.status)

                    Spacer(modifier = Modifier.height(8.dp))

                    // Caption
                    Text(
                        text = post.caption.ifBlank { "(No caption)" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Scheduled time
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Scheduled",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatDateTime(post.scheduledTime),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Error message if failed
                if (post.status == PostStatus.FAILED && !post.errorMessage.isNullOrBlank()) {
                    Text(
                        text = post.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Menu button
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options"
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (post.status == PostStatus.SCHEDULED || post.status == PostStatus.FAILED) {
                        DropdownMenuItem(
                            text = { Text("Reschedule") },
                            onClick = {
                                showMenu = false
                                onReschedule()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete") },
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

private fun formatDateTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = timestamp - now

    return when {
        diff < 0 -> {
            // Past
            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        diff < 24 * 60 * 60 * 1000 -> {
            // Today
            "Today, " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        diff < 48 * 60 * 60 * 1000 -> {
            // Tomorrow
            "Tomorrow, " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        else -> {
            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

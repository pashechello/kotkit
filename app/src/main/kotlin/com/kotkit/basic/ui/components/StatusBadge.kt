package com.kotkit.basic.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kotkit.basic.R
import com.kotkit.basic.data.local.db.entities.PostStatus
import com.kotkit.basic.ui.theme.*

@Composable
fun StatusBadge(
    status: PostStatus,
    modifier: Modifier = Modifier
) {
    val (gradient, icon, textResId, showPulse) = when (status) {
        PostStatus.SCHEDULED -> StatusConfig(
            gradient = Brush.linearGradient(listOf(StatusScheduled, StatusScheduled.copy(alpha = 0.8f))),
            icon = Icons.Default.Schedule,
            textResId = R.string.status_scheduled,
            showPulse = false
        )
        PostStatus.POSTING -> StatusConfig(
            gradient = Brush.linearGradient(listOf(StatusPosting, Color(0xFFFF9500))),
            icon = Icons.Default.CloudUpload,
            textResId = R.string.status_posting,
            showPulse = true
        )
        PostStatus.COMPLETED -> StatusConfig(
            gradient = Brush.linearGradient(listOf(StatusCompleted, Color(0xFF00B894))),
            icon = Icons.Default.CheckCircle,
            textResId = R.string.status_completed,
            showPulse = false
        )
        PostStatus.FAILED -> StatusConfig(
            gradient = Brush.linearGradient(listOf(StatusFailed, Color(0xFFFF6B81))),
            icon = Icons.Default.Error,
            textResId = R.string.status_failed,
            showPulse = false
        )
        PostStatus.NEEDS_ACTION -> StatusConfig(
            gradient = Brush.linearGradient(listOf(StatusNeedsAction, Color(0xFFFF69B4))),
            icon = Icons.Default.PriorityHigh,
            textResId = R.string.status_needs_action,
            showPulse = true
        )
        PostStatus.CANCELLED -> StatusConfig(
            gradient = Brush.linearGradient(listOf(StatusCancelled, Color(0xFF9CA3AF))),
            icon = Icons.Default.Cancel,
            textResId = R.string.status_cancelled,
            showPulse = false
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (showPulse) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Row(
        modifier = modifier
            .scale(pulseScale)
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = when (status) {
                    PostStatus.COMPLETED -> StatusCompleted.copy(alpha = 0.3f)
                    PostStatus.FAILED -> StatusFailed.copy(alpha = 0.3f)
                    PostStatus.POSTING -> StatusPosting.copy(alpha = 0.3f)
                    else -> Color.Transparent
                },
                spotColor = when (status) {
                    PostStatus.COMPLETED -> StatusCompleted.copy(alpha = 0.3f)
                    PostStatus.FAILED -> StatusFailed.copy(alpha = 0.3f)
                    PostStatus.POSTING -> StatusPosting.copy(alpha = 0.3f)
                    else -> Color.Transparent
                }
            )
            .clip(RoundedCornerShape(16.dp))
            .background(gradient)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = stringResource(textResId),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        if (showPulse) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

private data class StatusConfig(
    val gradient: Brush,
    val icon: ImageVector,
    val textResId: Int,
    val showPulse: Boolean
)

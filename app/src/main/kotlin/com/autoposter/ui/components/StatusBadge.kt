package com.autoposter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.autoposter.data.local.db.entities.PostStatus
import com.autoposter.ui.theme.*

@Composable
fun StatusBadge(
    status: PostStatus,
    modifier: Modifier = Modifier
) {
    val (backgroundColor, text) = when (status) {
        PostStatus.SCHEDULED -> StatusScheduled to "Scheduled"
        PostStatus.POSTING -> StatusPosting to "Posting..."
        PostStatus.COMPLETED -> StatusCompleted to "Completed"
        PostStatus.FAILED -> StatusFailed to "Failed"
        PostStatus.NEEDS_ACTION -> StatusNeedsAction to "Action Needed"
        PostStatus.CANCELLED -> StatusCancelled to "Cancelled"
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = backgroundColor
        )
    }
}

package com.kotkit.basic.ui.screens.queue

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotkit.basic.R
import com.kotkit.basic.ui.components.BounceOverscrollContainer
import com.kotkit.basic.ui.components.GradientButton
import com.kotkit.basic.ui.components.PostCard
import com.kotkit.basic.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    onNavigateBack: () -> Unit,
    onNavigateToNewPost: () -> Unit,
    viewModel: QueueViewModel = hiltViewModel()
) {
    val posts by viewModel.queuePosts.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBase)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Custom Glass Top Bar
            GlassTopBar(
                title = stringResource(R.string.screen_queue),
                onBackClick = onNavigateBack,
                postCount = posts.size
            )

            if (posts.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    GlassEmptyState(onCreatePost = onNavigateToNewPost)
                }
            } else {
                BounceOverscrollContainer(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            bottom = 100.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(posts, key = { it.id }) { post ->
                            PostCard(
                                post = post,
                                onClick = {},
                                onDelete = { viewModel.deletePost(post.id) },
                                onReschedule = {}
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassTopBar(
    title: String,
    onBackClick: () -> Unit,
    postCount: Int
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SurfaceElevated1,
                        SurfaceBase
                    )
                )
            )
            .padding(top = 48.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Back button
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(SurfaceGlassLight)
                        .border(1.dp, BorderSubtle, CircleShape)
                        .clickable(onClick = onBackClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    if (postCount > 0) {
                        Text(
                            text = "$postCount scheduled",
                            style = MaterialTheme.typography.bodySmall,
                            color = BrandCyan
                        )
                    }
                }
            }

            // Post count badge
            if (postCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(BrandCyan, BrandPink)
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = postCount.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassEmptyState(onCreatePost: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "empty_state")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Icon with glow effect
        Box(contentAlignment = Alignment.Center) {
            // Glow
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier
                    .size(120.dp)
                    .blur(30.dp),
                tint = BrandCyan.copy(alpha = alpha * 0.5f)
            )
            // Main icon
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(SurfaceGlassMedium)
                    .border(
                        width = 2.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                BrandCyan.copy(alpha = alpha),
                                BrandPink.copy(alpha = alpha)
                            )
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = BrandCyan
                )
            }
        }

        // Text
        Text(
            text = stringResource(R.string.queue_no_scheduled),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = TextSecondary
        )

        // Subtle hint
        Text(
            text = "Создайте новый пост",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Create post button
        GradientButton(
            onClick = onCreatePost,
            gradient = BrandGradient
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.home_create_post),
                color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

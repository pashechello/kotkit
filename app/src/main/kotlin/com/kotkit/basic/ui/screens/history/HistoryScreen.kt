package com.kotkit.basic.ui.screens.history

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotkit.basic.R
import com.kotkit.basic.data.local.db.entities.PostEntity
import com.kotkit.basic.data.repository.PostRepository
import com.kotkit.basic.ui.components.BounceOverscrollContainer
import com.kotkit.basic.ui.components.PostCard
import com.kotkit.basic.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val postRepository: PostRepository
) : ViewModel() {

    val completedPosts: StateFlow<List<PostEntity>> = postRepository.getCompletedPosts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val failedPosts: StateFlow<List<PostEntity>> = postRepository.getFailedPosts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deletePost(postId: Long) {
        viewModelScope.launch {
            postRepository.deletePost(postId)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val completedPosts by viewModel.completedPosts.collectAsState()
    val failedPosts by viewModel.failedPosts.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBase)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Custom Glass Top Bar
            GlassTopBar(
                title = stringResource(R.string.history_title),
                onBackClick = onNavigateBack
            )

            // Glass Tabs
            GlassTabs(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                completedCount = completedPosts.size,
                failedCount = failedPosts.size
            )

            val posts = if (selectedTab == 0) completedPosts else failedPosts

            if (posts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    GlassEmptyState(
                        icon = if (selectedTab == 0) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                        color = if (selectedTab == 0) Success else Error,
                        message = if (selectedTab == 0)
                            stringResource(R.string.history_no_completed)
                        else
                            stringResource(R.string.history_no_failed)
                    )
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
    onBackClick: () -> Unit
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
            verticalAlignment = Alignment.CenterVertically
        ) {
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

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun GlassTabs(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    completedCount: Int,
    failedCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassTab(
            icon = Icons.Default.CheckCircle,
            title = stringResource(R.string.status_completed),
            count = completedCount,
            color = Success,
            isSelected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            modifier = Modifier.weight(1f)
        )

        GlassTab(
            icon = Icons.Default.Error,
            title = stringResource(R.string.status_failed),
            count = failedCount,
            color = Error,
            isSelected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun GlassTab(
    icon: ImageVector,
    title: String,
    count: Int,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) color.copy(alpha = 0.15f) else SurfaceGlassLight
    val borderColor = if (isSelected) color.copy(alpha = 0.4f) else BorderDefault

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) color else TextMuted,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) color else TextSecondary
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isSelected) color.copy(alpha = 0.2f) else SurfaceGlassMedium
                )
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) color else TextMuted
            )
        }
    }
}

@Composable
private fun GlassEmptyState(
    icon: ImageVector,
    color: Color,
    message: String
) {
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
                icon,
                contentDescription = null,
                modifier = Modifier
                    .size(120.dp)
                    .blur(30.dp),
                tint = color.copy(alpha = alpha * 0.5f)
            )
            // Main icon
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(SurfaceGlassMedium)
                    .border(
                        width = 2.dp,
                        color = color.copy(alpha = alpha),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = color
                )
            }
        }

        // Text
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = TextSecondary
        )
    }
}

package com.autoposter.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autoposter.ui.components.PostCard
import com.autoposter.ui.theme.StatusFailed
import com.autoposter.ui.theme.Success

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToNewPost: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshAccessibilityStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoPoster") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToNewPost,
                icon = { Icon(Icons.Default.Add, "Add") },
                text = { Text("New Post") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status cards
            item {
                StatusSection(
                    isAccessibilityEnabled = uiState.isAccessibilityEnabled,
                    hasUnlockCredentials = uiState.hasUnlockCredentials,
                    onSettingsClick = onNavigateToSettings
                )
            }

            // Stats
            item {
                StatsSection(
                    scheduledCount = uiState.scheduledCount,
                    completedCount = uiState.completedCount,
                    onQueueClick = onNavigateToQueue,
                    onHistoryClick = onNavigateToHistory
                )
            }

            // Upcoming posts
            if (uiState.scheduledPosts.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Upcoming Posts",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = onNavigateToQueue) {
                            Text("See All")
                        }
                    }
                }

                items(uiState.scheduledPosts.take(3), key = { it.id }) { post ->
                    PostCard(
                        post = post,
                        onClick = {},
                        onDelete = { viewModel.deletePost(post.id) },
                        onReschedule = {}
                    )
                }
            }

            // Recent posts
            if (uiState.recentPosts.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Posts",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = onNavigateToHistory) {
                            Text("See All")
                        }
                    }
                }

                items(uiState.recentPosts, key = { it.id }) { post ->
                    PostCard(
                        post = post,
                        onClick = {},
                        onDelete = { viewModel.deletePost(post.id) },
                        onReschedule = {}
                    )
                }
            }

            // Empty state
            if (uiState.scheduledPosts.isEmpty() && uiState.recentPosts.isEmpty() && !uiState.isLoading) {
                item {
                    EmptyState(onCreatePost = onNavigateToNewPost)
                }
            }
        }
    }
}

@Composable
private fun StatusSection(
    isAccessibilityEnabled: Boolean,
    hasUnlockCredentials: Boolean,
    onSettingsClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Accessibility status
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isAccessibilityEnabled)
                    Success.copy(alpha = 0.1f)
                else
                    StatusFailed.copy(alpha = 0.1f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isAccessibilityEnabled)
                            Icons.Default.CheckCircle
                        else
                            Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isAccessibilityEnabled) Success else StatusFailed
                    )
                    Column {
                        Text(
                            text = "Accessibility Service",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isAccessibilityEnabled) "Enabled" else "Not enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!isAccessibilityEnabled) {
                    TextButton(onClick = onSettingsClick) {
                        Text("Enable")
                    }
                }
            }
        }

        // Unlock credentials status
        if (!hasUnlockCredentials) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column {
                            Text(
                                text = "Screen Unlock",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Save PIN/password for auto-unlock",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(onClick = onSettingsClick) {
                        Text("Setup")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsSection(
    scheduledCount: Int,
    completedCount: Int,
    onQueueClick: () -> Unit,
    onHistoryClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            title = "Scheduled",
            count = scheduledCount,
            icon = Icons.Default.Schedule,
            onClick = onQueueClick
        )
        StatCard(
            modifier = Modifier.weight(1f),
            title = "Completed",
            count = completedCount,
            icon = Icons.Default.CheckCircle,
            onClick = onHistoryClick
        )
    }
}

@Composable
private fun StatCard(
    title: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyState(onCreatePost: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.VideoLibrary,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No posts yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create your first scheduled post",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onCreatePost) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create Post")
        }
    }
}

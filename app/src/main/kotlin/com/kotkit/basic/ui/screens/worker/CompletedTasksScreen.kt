package com.kotkit.basic.ui.screens.worker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotkit.basic.data.remote.api.models.CompletedTaskItem
import com.kotkit.basic.ui.components.SnackbarController
import com.kotkit.basic.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter = DateTimeFormatter
    .ofPattern("dd MMM, HH:mm", Locale("ru"))
    .withZone(ZoneId.systemDefault())

private fun formatDate(timestampSeconds: Long): String {
    return dateFormatter.format(Instant.ofEpochSecond(timestampSeconds))
}

private enum class StatsTab(val title: String) {
    ALL("Все"),
    SUCCESS("Успешные"),
    FAILED("Безуспешные"),
    LINKS("Ссылки")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompletedTasksScreen(
    onNavigateBack: () -> Unit,
    viewModel: CompletedTasksViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(StatsTab.ALL) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            SnackbarController.showError(error)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.submitSuccess) {
        if (uiState.submitSuccess) {
            val reward = uiState.submitRewardRub
            val message = if (reward != null && reward > 0) {
                "Мяу! Ссылка отправлена 🐾 Бонус: ${String.format("%.0f", reward)} ₽ 💰"
            } else {
                "Мяу! Ссылка отправлена 😸"
            }
            SnackbarController.showSuccess(message)
            viewModel.clearSubmitSuccess()
        }
    }

    // URL input dialog
    if (uiState.showUrlDialog) {
        UrlSubmissionDialog(
            campaignName = uiState.dialogCampaignName ?: "",
            isSubmitting = uiState.submittingVerificationId != null,
            onSubmit = { url ->
                uiState.dialogVerificationId?.let { id ->
                    viewModel.submitUrl(id, url)
                }
            },
            onDismiss = { viewModel.dismissUrlDialog() }
        )
    }

    // Filter tasks based on selected tab
    val filteredTasks = remember(uiState.tasks, selectedTab) {
        when (selectedTab) {
            StatsTab.ALL -> uiState.tasks
            StatsTab.SUCCESS -> uiState.tasks.filter {
                it.verificationStatus in listOf("completed", "processed") && it.tiktokVideoUrl != null
            }
            StatsTab.FAILED -> uiState.tasks.filter {
                it.verificationStatus == "failed"
            }
            StatsTab.LINKS -> uiState.tasks
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ваша статистика") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadTasks() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceBase
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(SurfaceBase)
        ) {
            // Tab row
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = SurfaceBase,
                contentColor = TextPrimary,
                edgePadding = 16.dp,
                divider = {},
                indicator = { tabPositions ->
                    if (selectedTab.ordinal < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                            color = BrandCyan
                        )
                    }
                }
            ) {
                StatsTab.entries.forEach { tab ->
                    val count = when (tab) {
                        StatsTab.ALL -> uiState.tasks.size
                        StatsTab.SUCCESS -> uiState.tasks.count {
                            it.verificationStatus in listOf("completed", "processed") && it.tiktokVideoUrl != null
                        }
                        StatsTab.FAILED -> uiState.tasks.count {
                            it.verificationStatus == "failed"
                        }
                        StatsTab.LINKS -> uiState.tasks.count {
                            it.tiktokVideoUrl != null
                        }
                    }
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = {
                            Text(
                                text = if (count > 0) "${tab.title} ($count)" else tab.title,
                                fontWeight = if (selectedTab == tab) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        selectedContentColor = BrandCyan,
                        unselectedContentColor = TextSecondary
                    )
                }
            }

            // Content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SurfaceBase)
            ) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    filteredTasks.isEmpty() -> {
                        EmptyTabState(
                            tab = selectedTab,
                            modifier = Modifier.align(Alignment.Center),
                            onRefresh = { viewModel.loadTasks() }
                        )
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Show URL banner only on ALL and LINKS tabs
                            if (selectedTab == StatsTab.ALL || selectedTab == StatsTab.LINKS) {
                                val needsUrlCount = uiState.tasks.count { it.tiktokVideoUrl == null }
                                if (needsUrlCount > 0) {
                                    item {
                                        UrlNeededBanner(count = needsUrlCount)
                                    }
                                }
                            }

                            if (selectedTab == StatsTab.LINKS) {
                                // Links tab: alternating pink/cyan colors
                                itemsIndexed(filteredTasks, key = { _, task -> task.verificationId }) { index, task ->
                                    LinkStatusCard(
                                        task = task,
                                        index = index,
                                        onSubmitUrl = {
                                            viewModel.showUrlDialog(task.verificationId, task.campaignName)
                                        }
                                    )
                                }
                            } else {
                                itemsIndexed(filteredTasks, key = { _, task -> task.verificationId }) { index, task ->
                                    CompletedTaskCard(
                                        task = task,
                                        index = index,
                                        onSubmitUrl = {
                                            viewModel.showUrlDialog(task.verificationId, task.campaignName)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTabState(
    tab: StatsTab,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit
) {
    val (icon, title, subtitle) = when (tab) {
        StatsTab.ALL -> Triple(
            Icons.Default.Assignment,
            "Нет завершённых задач",
            "Выполненные задачи появятся здесь"
        )
        StatsTab.SUCCESS -> Triple(
            Icons.Default.CheckCircle,
            "Нет успешных задач",
            "Успешно выполненные задачи появятся здесь"
        )
        StatsTab.FAILED -> Triple(
            Icons.Default.Cancel,
            "Нет безуспешных задач",
            "Отлично! Все задачи выполнены успешно"
        )
        StatsTab.LINKS -> Triple(
            Icons.Default.Link,
            "Нет ссылок",
            "Ссылки на опубликованные видео появятся здесь"
        )
    }

    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = TextSecondary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Обновить")
        }
    }
}

@Composable
private fun UrlNeededBanner(count: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = BrandPink.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Link,
                contentDescription = null,
                tint = BrandPink,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (count == 1) "1 пост без ссылки" else "$count постов без ссылки",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = BrandPink
                )
                Text(
                    text = "Отправьте ссылку и получите бонус 2 ₽",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun CompletedTaskCard(
    task: CompletedTaskItem,
    index: Int,
    onSubmitUrl: () -> Unit
) {
    val accentColor = if (index % 2 == 0) BrandPink else BrandCyan
    val buttonTextColor = if (accentColor == BrandCyan) SurfaceBase else TextPrimary

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceElevated
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header: campaign name + price
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = task.campaignName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${String.format("%.0f", task.priceRub)} ₽",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = AccentGreen
                )
            }

            Spacer(Modifier.height(8.dp))

            // Date + status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatDate(task.completedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                VerificationStatusBadge(status = task.verificationStatus, hasUrl = task.tiktokVideoUrl != null)
            }

            // URL status
            if (task.tiktokVideoUrl != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = task.tiktokVideoUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Submit URL button - alternating colors
            if (task.tiktokVideoUrl == null) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onSubmitUrl,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = buttonTextColor
                    )
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Отправить ссылку", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun LinkStatusCard(
    task: CompletedTaskItem,
    index: Int,
    onSubmitUrl: () -> Unit
) {
    val hasLink = task.tiktokVideoUrl != null
    // Alternating accent color: pink, cyan, pink, cyan...
    val accentColor = if (index % 2 == 0) BrandPink else BrandCyan
    val buttonTextColor = if (accentColor == BrandCyan) SurfaceBase else TextPrimary
    val borderColor = if (hasLink) AccentGreen.copy(alpha = 0.4f) else accentColor.copy(alpha = 0.3f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hasLink) AccentGreen.copy(alpha = 0.08f) else SurfaceElevated
        ),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header: campaign name + link icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (hasLink) Icons.Default.CheckCircle else Icons.Default.LinkOff,
                        contentDescription = null,
                        tint = if (hasLink) AccentGreen else accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = task.campaignName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${String.format("%.0f", task.priceRub)} ₽",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = AccentGreen
                )
            }

            Spacer(Modifier.height(8.dp))

            if (hasLink) {
                // Green link display
                Text(
                    text = task.tiktokVideoUrl!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentGreen,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatDate(task.completedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            } else {
                // No link - show submit button
                Text(
                    text = "Ссылка не отправлена",
                    style = MaterialTheme.typography.bodySmall,
                    color = accentColor
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatDate(task.completedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onSubmitUrl,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = buttonTextColor
                    )
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Отправить ссылку", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun VerificationStatusBadge(status: String, hasUrl: Boolean) {
    val (text, color) = when {
        // Verified + has URL = truly verified
        status in listOf("completed", "processed") && hasUrl -> "Проверено" to AccentGreen
        // Verified but no URL = link missing
        status in listOf("completed", "processed") && !hasUrl -> "Нет ссылки" to BrandPink
        // Pending + has URL = waiting for check
        status == "pending" && hasUrl -> "Ожидание проверки" to AccentBlue
        // Pending + no URL = waiting for link
        status == "pending" && !hasUrl -> "Ожидание ссылки" to BrandCyan
        // Failed
        status == "failed" -> "Ошибка" to Error
        else -> status to TextSecondary
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = color
    )
}

@Composable
private fun UrlSubmissionDialog(
    campaignName: String,
    isSubmitting: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    val isValidUrl = url.isNotBlank() && url.trim().contains("tiktok.com")

    Dialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            BrandCyan.copy(alpha = 0.4f),
                            BrandPink.copy(alpha = 0.4f)
                        )
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1A1A2E),
                            Color(0xFF16162A),
                            Color(0xFF0F0F1A)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // Header with icon
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BrandCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = null,
                            tint = BrandCyan,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Отправить ссылку",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Вставьте ссылку на TikTok видео для «$campaignName»",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(Modifier.height(20.dp))

                // URL input field
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("TikTok URL") },
                    placeholder = { Text("https://www.tiktok.com/@user/video/...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSubmitting,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandCyan,
                        unfocusedBorderColor = BorderDefault,
                        focusedLabelColor = BrandCyan,
                        cursorColor = BrandCyan
                    )
                )

                Spacer(Modifier.height(12.dp))

                // Bonus badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(BrandCyan.copy(alpha = 0.1f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Бонус за отправку ссылки: 2 ₽",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = BrandCyan
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isSubmitting
                    ) {
                        Text("Отмена", color = TextTertiary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSubmit(url.trim()) },
                        enabled = isValidUrl && !isSubmitting,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BrandPink
                        )
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = TextPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Отправить", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

package com.kotkit.basic.ui.screens.worker

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import timber.log.Timber
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kotkit.basic.R
import com.kotkit.basic.permission.DeviceProtectionChecker
import com.kotkit.basic.ui.components.TikTokUsernameDialog
import com.kotkit.basic.ui.components.SnackbarController
import com.kotkit.basic.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerDashboardScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAvailableTasks: () -> Unit = {},
    onNavigateToActiveTasks: () -> Unit = {},
    onNavigateToEarnings: () -> Unit = {},
    onNavigateToCompletedTasks: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: WorkerDashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Refresh protection status when returning from system settings
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshProtectionStatus()
    }

    // Show error
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            SnackbarController.showError(error)
            viewModel.clearError()
        }
    }

    // TikTok username dialog
    if (uiState.showTiktokUsernameDialog) {
        TikTokUsernameDialog(
            initialUsername = uiState.profile?.tiktokUsername ?: "",
            isSaving = uiState.isSavingUsername,
            onSave = { username -> viewModel.saveTiktokUsername(username) },
            onDismiss = { viewModel.dismissTiktokUsernameDialog() }
        )
    }

    // Device protection setup dialog
    if (uiState.showSetupDialog && uiState.protectionStatus != null) {
        DeviceProtectionSetupDialog(
            protectionStatus = uiState.protectionStatus!!,
            onOpenAccessibility = { viewModel.openAccessibilitySettings() },
            onOpenNotifications = { viewModel.openNotificationSettings() },
            onOpenBattery = { viewModel.openBatteryOptimizationSettings() },
            onOpenAutostart = { viewModel.openAutostartSettings() },
            onRetry = { viewModel.retryToggleAfterSetup() },
            onDismiss = { viewModel.dismissSetupDialog() }
        )
    }

    // Autostart confirm dialog (after returning from OEM settings)
    if (uiState.showAutostartConfirmDialog) {
        AutostartConfirmDialog(
            manufacturerName = viewModel.getManufacturerName(),
            checklist = viewModel.getAutostartChecklist(),
            onConfirm = { viewModel.confirmAutostartEnabled() },
            onDismiss = { viewModel.dismissAutostartConfirmDialog() }
        )
    }

    // Autostart manual instructions dialog
    if (uiState.showAutostartManualDialog) {
        AutostartManualDialog(
            instructions = viewModel.getAutostartInstructions(),
            onOpenSettings = { viewModel.openAutostartSettings() },
            onDismiss = { viewModel.dismissAutostartManualDialog() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.worker_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // TOP: Income display
            IncomeCard(
                totalEarned = uiState.totalEarned,
                availableBalance = uiState.availableBalance
            )

            // TikTok account label
            val tiktokUsername = uiState.profile?.tiktokUsername
            if (!tiktokUsername.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(SurfaceElevated1)
                        .clickable { viewModel.openUsernameEditor() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "@$tiktokUsername",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = BrandCyan
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Protection warning card
            if (uiState.hasProtectionIssues) {
                Spacer(Modifier.height(12.dp))
                ProtectionWarningCard(
                    missingCount = uiState.protectionStatus?.missingItems?.size ?: 0,
                    onClick = { viewModel.showSetupDialog() }
                )
            }

            // CENTER: Big Money Button (takes remaining space)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // MC Hammer sound for money button
                    val moneyContext = LocalContext.current
                    val moneyPlayer = remember {
                        MediaPlayer.create(moneyContext, R.raw.money_button)
                    }
                    DisposableEffect(Unit) {
                        onDispose { moneyPlayer?.release() }
                    }

                    MoneyButton(
                        isActive = uiState.isWorkerModeActive,
                        isToggling = uiState.isToggling,
                        onClick = {
                            moneyPlayer?.let { mp ->
                                try {
                                    if (mp.isPlaying) mp.seekTo(0)
                                    mp.start()
                                } catch (e: Exception) {
                                    Log.e("MONEY_SOUND", "Error playing", e)
                                }
                            }
                            viewModel.requestToggleWorkerMode()
                        }
                    )

                    Spacer(Modifier.height(16.dp))

                    // Status text
                    Text(
                        text = if (uiState.isWorkerModeActive) {
                            stringResource(R.string.worker_mode_active)
                        } else {
                            stringResource(R.string.worker_mode_inactive)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.isWorkerModeActive) Success else TextTertiary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // BOTTOM: Stats Card (clickable -> opens completed tasks)
            StatsCard(
                completedTasks = uiState.completedTasks,
                successRate = uiState.successRate,
                rating = uiState.rating,
                onClick = onNavigateToCompletedTasks
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

// --- Protection Warning Card ---

@Composable
private fun ProtectionWarningCard(
    missingCount: Int,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Warning.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = Warning,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        text = stringResource(R.string.worker_protection_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.worker_protection_warning_count, missingCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
            GlassChip(stringResource(R.string.fix))
        }
    }
}

// --- Device Protection Setup Dialog ---

@Composable
private fun DeviceProtectionSetupDialog(
    protectionStatus: DeviceProtectionChecker.ProtectionStatus,
    onOpenAccessibility: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenAutostart: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 24.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = Warning.copy(alpha = 0.3f),
                    spotColor = BrandCyan.copy(alpha = 0.3f)
                )
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceDialog)
                .border(1.dp, BorderStrong, RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(Warning.copy(alpha = 0.3f), BrandCyan.copy(alpha = 0.3f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = Warning,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.worker_setup_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.worker_setup_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(20.dp))

                // Checklist items
                SetupChecklistItem(
                    title = stringResource(R.string.worker_setup_accessibility),
                    subtitle = if (protectionStatus.isAccessibilityOk)
                        stringResource(R.string.worker_setup_accessibility_ok)
                    else stringResource(R.string.worker_setup_accessibility_missing),
                    isOk = protectionStatus.isAccessibilityOk,
                    onClick = if (!protectionStatus.isAccessibilityOk) onOpenAccessibility else null
                )

                Spacer(Modifier.height(10.dp))

                SetupChecklistItem(
                    title = stringResource(R.string.worker_setup_notifications),
                    subtitle = if (protectionStatus.isNotificationsOk)
                        stringResource(R.string.worker_setup_notifications_ok)
                    else stringResource(R.string.worker_setup_notifications_missing),
                    isOk = protectionStatus.isNotificationsOk,
                    onClick = if (!protectionStatus.isNotificationsOk) onOpenNotifications else null
                )

                Spacer(Modifier.height(10.dp))

                SetupChecklistItem(
                    title = stringResource(R.string.worker_setup_battery),
                    subtitle = if (protectionStatus.isBatteryOk)
                        stringResource(R.string.worker_setup_battery_ok)
                    else stringResource(R.string.worker_setup_battery_missing),
                    isOk = protectionStatus.isBatteryOk,
                    onClick = if (!protectionStatus.isBatteryOk) onOpenBattery else null
                )

                if (protectionStatus.isAutostartRequired) {
                    Spacer(Modifier.height(10.dp))

                    SetupChecklistItem(
                        title = stringResource(R.string.worker_setup_autostart, protectionStatus.manufacturerName),
                        subtitle = if (protectionStatus.isAutostartOk)
                            stringResource(R.string.worker_setup_autostart_ok)
                        else stringResource(R.string.worker_setup_autostart_missing),
                        isOk = protectionStatus.isAutostartOk,
                        onClick = if (!protectionStatus.isAutostartOk) onOpenAutostart else null
                    )
                }

                Spacer(Modifier.height(24.dp))

                // "Check and Start" button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(Success, BrandCyan)
                            )
                        )
                        .clickable { onRetry() }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.worker_setup_retry),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(Modifier.height(12.dp))

                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.worker_setup_later),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupChecklistItem(
    title: String,
    subtitle: String,
    isOk: Boolean,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isOk) Success.copy(alpha = 0.08f) else Warning.copy(alpha = 0.08f))
            .border(
                1.dp,
                if (isOk) Success.copy(alpha = 0.3f) else Warning.copy(alpha = 0.3f),
                RoundedCornerShape(14.dp)
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isOk) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isOk) Success else Warning,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isOk) Success else TextTertiary
            )
        }
        if (!isOk) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// --- Autostart Confirm Dialog (reused from SettingsScreen pattern) ---

@Composable
private fun AutostartConfirmDialog(
    manufacturerName: String,
    checklist: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val checklistText = checklist.joinToString("\n") { "\u2022 $it" }
    val message = stringResource(R.string.autostart_confirm_message_generic, manufacturerName) + "\n\n" + checklistText

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(24.dp, RoundedCornerShape(24.dp), ambientColor = Success.copy(alpha = 0.3f), spotColor = BrandCyan.copy(alpha = 0.3f))
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceDialog)
                .border(1.dp, BorderStrong, RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Success.copy(alpha = 0.3f), BrandCyan.copy(alpha = 0.3f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PhonelinkSetup, null, tint = Success, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.autostart_confirm_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.padding(horizontal = 8.dp))
                Spacer(Modifier.height(24.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(listOf(Success, BrandCyan)))
                        .clickable { onConfirm() }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.autostart_confirm_yes), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.autostart_confirm_no), style = MaterialTheme.typography.labelMedium, color = TextTertiary)
                }
            }
        }
    }
}

@Composable
private fun AutostartManualDialog(
    instructions: String,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(24.dp, RoundedCornerShape(24.dp), ambientColor = BrandPink.copy(alpha = 0.3f), spotColor = BrandCyan.copy(alpha = 0.3f))
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceDialog)
                .border(1.dp, BorderStrong, RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(BrandPink.copy(alpha = 0.3f), BrandCyan.copy(alpha = 0.3f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Settings, null, tint = BrandPink, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.autostart_manual_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.autostart_manual_message, instructions), style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.padding(horizontal = 8.dp))
                Spacer(Modifier.height(24.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(listOf(BrandPink, BrandCyan)))
                        .clickable { onOpenSettings() }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.autostart_manual_open_settings), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.autostart_manual_done), style = MaterialTheme.typography.labelMedium, color = TextTertiary)
                }
            }
        }
    }
}

// --- Shared UI Components ---

@Composable
private fun GlassChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(BrandCyan, BrandPink)
                )
            )
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

// --- Existing Components ---

@Composable
private fun IncomeCard(
    totalEarned: Float,
    availableBalance: Float
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceElevated2
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.worker_your_income),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${String.format("%.2f", totalEarned)} ₽",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Success,
                fontSize = 36.sp
            )
            if (availableBalance > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.worker_available_to_withdraw, availableBalance),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
        }
    }
}

@Composable
private fun MoneyButton(
    isActive: Boolean,
    isToggling: Boolean,
    onClick: () -> Unit
) {
    // Press animation
    var isPressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed || isToggling) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "press_scale"
    )

    // Pulsing animation for the glow effect
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    // Red gradient - inactive state
    val redGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFFFF6B6B),
            Color(0xFFEE4444),
            Color(0xFFCC2222),
            Color(0xFFAA1111)
        )
    )

    // Green gradient - active state
    val greenGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF6BFF6B),
            Color(0xFF44EE44),
            Color(0xFF22CC22),
            Color(0xFF11AA11)
        )
    )

    val currentGradient = if (isActive) greenGradient else redGradient
    val glowColor = if (isActive) Color(0xFF44FF44) else Color(0xFFFF4444)
    val glowColorDark = if (isActive) Color(0xFF00CC00) else Color(0xFFCC0000)

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        val buttonSize = (maxWidth * 0.85f).coerceAtMost(320.dp)
        val glowSize = buttonSize + 20.dp

        // Glow effect behind button (pulsing)
        Box(
            modifier = Modifier
                .size(glowSize)
                .graphicsLayer {
                    scaleX = pulseScale * pressScale
                    scaleY = pulseScale * pressScale
                    alpha = glowAlpha
                }
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = 0.6f),
                            glowColorDark.copy(alpha = 0.3f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Main button
        Button(
            onClick = onClick,
            enabled = !isToggling,
            modifier = Modifier
                .size(buttonSize)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .shadow(
                    elevation = 24.dp,
                    shape = CircleShape,
                    ambientColor = glowColor,
                    spotColor = glowColorDark
                ),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent
            ),
            contentPadding = PaddingValues(0.dp),
            interactionSource = remember { MutableInteractionSource() }.also { interactionSource ->
                LaunchedEffect(interactionSource) {
                    interactionSource.interactions.collect { interaction ->
                        when (interaction) {
                            is androidx.compose.foundation.interaction.PressInteraction.Press -> {
                                isPressed = true
                            }
                            is androidx.compose.foundation.interaction.PressInteraction.Release,
                            is androidx.compose.foundation.interaction.PressInteraction.Cancel -> {
                                isPressed = false
                            }
                        }
                    }
                }
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = currentGradient,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.25f),
                                    Color.Transparent,
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.2f)
                                ),
                                startY = 0f,
                                endY = 500f
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isToggling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = Color.White,
                            strokeWidth = 4.dp
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.worker_button_money),
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = 4.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(
    completedTasks: Int,
    successRate: Float,
    rating: Float,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceElevated2
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.worker_stats_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = Icons.Default.CheckCircle,
                    value = completedTasks.toString(),
                    label = stringResource(R.string.worker_stats_completed),
                    color = Success
                )
                StatItem(
                    icon = Icons.Default.TrendingUp,
                    value = "${(successRate * 100).toInt()}%",
                    label = stringResource(R.string.worker_stats_success_rate),
                    color = Info
                )
                StatItem(
                    icon = Icons.Default.Star,
                    value = String.format("%.1f", rating),
                    label = stringResource(R.string.worker_stats_rating),
                    color = Warning
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

package com.kotkit.basic.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.Code
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotkit.basic.BuildConfig
import com.kotkit.basic.R
import com.kotkit.basic.scheduler.AudiencePersona
import com.kotkit.basic.ui.components.BounceOverscrollContainer
import com.kotkit.basic.ui.components.ExactAlarmSettingsRow
import com.kotkit.basic.ui.components.GlassCard
import com.kotkit.basic.ui.components.GlassSettingRow
import com.kotkit.basic.ui.components.GlowingIcon
import com.kotkit.basic.ui.components.PulsingDot
import com.kotkit.basic.ui.components.TikTokUsernameDialog
import com.kotkit.basic.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToUnlockSettings: () -> Unit,
    onNavigateToCaptionSettings: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Refresh state every time the screen resumes (e.g., returning from system settings)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshState()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBase)
    ) {
        BounceOverscrollContainer(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
            // Custom Top Bar with gradient
            GlassTopBar(
                title = stringResource(R.string.screen_settings),
                onBackClick = onNavigateBack
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Accessibility Section
            SettingsSectionHeader(
                title = stringResource(R.string.settings_accessibility),
                icon = Icons.Outlined.Accessibility
            )

            GlassSettingRow(
                title = stringResource(R.string.settings_accessibility_service),
                subtitle = when {
                    uiState.isLoading -> stringResource(R.string.settings_checking)
                    uiState.isAccessibilityEnabled -> stringResource(R.string.settings_accessibility_enabled)
                    else -> stringResource(R.string.settings_accessibility_not_enabled)
                },
                icon = Icons.Default.Accessibility,
                onClick = {
                    if (!uiState.isLoading && !uiState.isAccessibilityEnabled) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                },
                iconColor = when {
                    uiState.isLoading -> TextTertiary
                    uiState.isAccessibilityEnabled -> Success
                    else -> BrandPink
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                trailingContent = {
                    when {
                        uiState.isLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = BrandCyan,
                                strokeWidth = 2.dp
                            )
                        }
                        uiState.isAccessibilityEnabled -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PulsingDot(color = Success, size = 8.dp)
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Success,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        else -> {
                            GlassChip(text = stringResource(R.string.enable))
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Notification Permission (Android 13+)
            GlassSettingRow(
                title = stringResource(R.string.settings_notification_permission),
                subtitle = if (uiState.hasNotificationPermission)
                    stringResource(R.string.settings_notification_enabled)
                else
                    stringResource(R.string.settings_notification_not_enabled),
                icon = Icons.Default.Notifications,
                onClick = {
                    if (!uiState.hasNotificationPermission) {
                        viewModel.openNotificationSettings()
                    }
                },
                iconColor = if (uiState.hasNotificationPermission) Success else BrandPink,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                trailingContent = {
                    if (uiState.hasNotificationPermission) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PulsingDot(color = Success, size = 8.dp)
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Success,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        GlassChip(text = stringResource(R.string.enable))
                    }
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Worker Mode Reliability Section
            SettingsSectionHeader(
                title = stringResource(R.string.settings_worker_reliability),
                icon = Icons.Outlined.Shield
            )

            // Battery Optimization
            GlassSettingRow(
                title = stringResource(R.string.settings_battery_optimization),
                subtitle = if (uiState.isBatteryOptimizationDisabled)
                    stringResource(R.string.settings_battery_disabled)
                else
                    stringResource(R.string.settings_battery_enabled),
                icon = Icons.Default.BatteryFull,
                onClick = {
                    if (!uiState.isBatteryOptimizationDisabled) {
                        viewModel.openBatteryOptimizationSettings()
                    }
                },
                iconColor = if (uiState.isBatteryOptimizationDisabled) Success else Warning,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                trailingContent = {
                    if (uiState.isBatteryOptimizationDisabled) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PulsingDot(color = Success, size = 8.dp)
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Success,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        GlassChip(text = stringResource(R.string.settings_battery_action))
                    }
                }
            )

            // OEM-specific autostart (conditionally shown for Xiaomi/Samsung/etc)
            if (uiState.isAutostartRequired) {
                Spacer(modifier = Modifier.height(8.dp))

                GlassSettingRow(
                    title = stringResource(R.string.settings_oem_autostart, uiState.manufacturerName),
                    subtitle = if (uiState.isAutostartConfirmed)
                        stringResource(R.string.settings_oem_autostart_enabled)
                    else
                        stringResource(R.string.settings_oem_autostart_subtitle, uiState.manufacturerName),
                    icon = Icons.Default.PhonelinkSetup,
                    onClick = {
                        if (!uiState.isAutostartConfirmed) {
                            viewModel.openAutostartSettings()
                        }
                    },
                    iconColor = if (uiState.isAutostartConfirmed) Success else BrandPink,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    trailingContent = {
                        if (uiState.isAutostartConfirmed) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PulsingDot(color = Success, size = 8.dp)
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Success,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        } else {
                            GlassChip(text = stringResource(R.string.enable))
                        }
                    }
                )
            }

            // Autostart confirmation dialog
            if (uiState.showAutostartConfirmDialog) {
                GlassAutostartConfirmDialog(
                    manufacturerName = uiState.manufacturerName,
                    onConfirm = { viewModel.confirmAutostartEnabled() },
                    onDismiss = { viewModel.dismissAutostartDialog() }
                )
            }

            // Autostart manual instructions dialog (when settings can't be opened automatically)
            if (uiState.showAutostartManualDialog) {
                GlassAutostartManualDialog(
                    instructions = viewModel.getAutostartInstructions(),
                    onOpenSettings = {
                        viewModel.dismissAutostartManualDialog()
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    },
                    onDismiss = { viewModel.dismissAutostartManualDialog() }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Screen Unlock Section
            SettingsSectionHeader(
                title = stringResource(R.string.settings_screen_unlock),
                icon = Icons.Outlined.Lock
            )

            GlassSettingRow(
                title = stringResource(R.string.settings_unlock_credentials),
                subtitle = when {
                    uiState.hasStoredPin -> stringResource(R.string.settings_pin_saved)
                    uiState.hasStoredPassword -> stringResource(R.string.settings_password_saved)
                    uiState.hasNoPinMode -> stringResource(R.string.settings_no_pin_mode)
                    else -> stringResource(R.string.settings_not_configured)
                },
                icon = Icons.Default.Lock,
                onClick = onNavigateToUnlockSettings,
                iconColor = BrandCyan,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                trailingContent = {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Exact Alarm Permission
            ExactAlarmSettingsRow(
                hasPermission = uiState.canScheduleExactAlarms,
                onOpenSettings = { viewModel.openExactAlarmSettings() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Language Section
            SettingsSectionHeader(
                title = stringResource(R.string.settings_language),
                icon = Icons.Outlined.Language
            )

            var showLanguageDialog by remember { mutableStateOf(false) }

            GlassSettingRow(
                title = stringResource(R.string.settings_language),
                subtitle = when (uiState.currentLanguage) {
                    "ru" -> stringResource(R.string.language_russian)
                    "en" -> stringResource(R.string.language_english)
                    else -> stringResource(R.string.language_russian)
                },
                icon = Icons.Default.Language,
                onClick = { showLanguageDialog = true },
                iconColor = GradientPurple,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (uiState.currentLanguage == "ru") "🇷🇺" else "🇬🇧",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )

            if (showLanguageDialog) {
                GlassLanguageDialog(
                    currentLanguage = uiState.currentLanguage,
                    onLanguageSelected = { language ->
                        viewModel.setLanguage(language)
                        val locale = if (language == "ru") "ru" else "en"
                        val localeList = LocaleListCompat.forLanguageTags(locale)
                        AppCompatDelegate.setApplicationLocales(localeList)
                        showLanguageDialog = false
                    },
                    onDismiss = { showLanguageDialog = false }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Caption Settings Section
            SettingsSectionHeader(
                title = stringResource(R.string.home_caption_settings),
                icon = Icons.Outlined.AutoAwesome
            )

            GlassSettingRow(
                title = stringResource(R.string.home_caption_settings),
                subtitle = stringResource(R.string.home_caption_settings_subtitle),
                icon = Icons.Default.AutoAwesome,
                onClick = onNavigateToCaptionSettings,
                iconColor = GradientPurple,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                trailingContent = {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Target Audience Section
            SettingsSectionHeader(
                title = stringResource(R.string.settings_target_audience),
                icon = Icons.Outlined.People
            )

            var showPersonaDialog by remember { mutableStateOf(false) }

            GlassSettingRow(
                title = stringResource(R.string.settings_target_audience),
                subtitle = stringResource(uiState.selectedPersona.descriptionRes),
                icon = Icons.Default.People,
                onClick = { showPersonaDialog = true },
                iconColor = BrandPink,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(uiState.selectedPersona.displayNameRes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = BrandPink
                        )
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )

            if (showPersonaDialog) {
                GlassPersonaDialog(
                    currentPersona = uiState.selectedPersona,
                    onPersonaSelected = { persona ->
                        viewModel.setPersona(persona)
                        showPersonaDialog = false
                    },
                    onDismiss = { showPersonaDialog = false }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Account Section
            SettingsSectionHeader(
                title = stringResource(R.string.settings_account),
                icon = Icons.Outlined.Person
            )

            if (uiState.isLoggedIn) {
                GlassSettingRow(
                    title = uiState.userEmail ?: stringResource(R.string.settings_logged_in),
                    subtitle = stringResource(R.string.settings_tap_to_logout),
                    icon = Icons.Default.Person,
                    onClick = { viewModel.logout() },
                    iconColor = Success,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    trailingContent = {
                        Icon(
                            Icons.Default.Logout,
                            contentDescription = null,
                            tint = Error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                )
            } else {
                // Login via website button
                GlassSettingRow(
                    title = stringResource(R.string.settings_login_via_website),
                    subtitle = stringResource(R.string.settings_login_via_website_desc),
                    icon = Icons.Default.Language,
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://kotkit.pro/auth/app"))
                        context.startActivity(intent)
                    },
                    iconColor = BrandCyan,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    trailingContent = {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = null,
                            tint = BrandCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // TikTok Section
            SettingsSectionHeader(
                title = stringResource(R.string.settings_tiktok),
                icon = Icons.Outlined.VideoLibrary
            )

            if (uiState.isLoggedIn) {
                GlassSettingRow(
                    title = stringResource(R.string.settings_tiktok_username),
                    subtitle = uiState.tiktokUsername?.let { "@$it" }
                        ?: stringResource(R.string.settings_tiktok_not_set),
                    icon = Icons.Default.AccountCircle,
                    onClick = { viewModel.showTiktokDialog() },
                    iconColor = if (uiState.tiktokUsername != null) Success else Warning,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    trailingContent = {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )

                if (uiState.showTiktokUsernameDialog) {
                    TikTokUsernameDialog(
                        initialUsername = uiState.tiktokUsername ?: "",
                        isSaving = uiState.isSavingTiktokUsername,
                        onSave = { username -> viewModel.updateTiktokUsername(username) },
                        onDismiss = { viewModel.dismissTiktokDialog() }
                    )
                }
            } else {
                GlassSettingRow(
                    title = stringResource(R.string.settings_tiktok_username),
                    subtitle = stringResource(R.string.settings_tiktok_login_required),
                    icon = Icons.Default.AccountCircle,
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://kotkit.pro/auth/app"))
                        context.startActivity(intent)
                    },
                    iconColor = TextTertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    trailingContent = {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = null,
                            tint = BrandCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // About Section
            SettingsSectionHeader(
                title = stringResource(R.string.settings_about),
                icon = Icons.Outlined.Info
            )

            GlassSettingRow(
                title = stringResource(R.string.settings_version),
                subtitle = "1.0.0",
                icon = Icons.Default.Info,
                onClick = { },
                iconColor = BrandCyan,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                trailingContent = {
                    GlassVersionBadge(version = "1.0.0")
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            GlassSettingRow(
                title = stringResource(R.string.settings_privacy_policy),
                subtitle = null,
                icon = Icons.Default.Security,
                onClick = { /* Open privacy policy */ },
                iconColor = GradientPurple,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                trailingContent = {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Footer brand
            BrandFooter()

            Spacer(modifier = Modifier.height(24.dp))
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
            .padding(top = 48.dp) // Status bar
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button with glass effect
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
private fun SettingsSectionHeader(
    title: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = BrandCyan,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = BrandCyan,
            letterSpacing = 1.5.sp
        )
    }
}

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

@Composable
private fun GlassVersionBadge(version: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceGlassMedium)
            .border(1.dp, BorderAccentCyan, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = "v$version",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = BrandCyan
        )
    }
}

@Composable
private fun GlassLanguageDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 24.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = BrandCyan.copy(alpha = 0.3f),
                    spotColor = BrandPink.copy(alpha = 0.3f)
                )
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceDialog)
                .border(1.dp, BorderStrong, RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlowingIcon(
                        icon = Icons.Default.Language,
                        color = BrandCyan,
                        size = 28.dp
                    )
                    Text(
                        text = stringResource(R.string.settings_language),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Language options
                GlassLanguageOption(
                    language = "ru",
                    label = stringResource(R.string.language_russian),
                    flag = "🇷🇺",
                    isSelected = currentLanguage == "ru",
                    onClick = { onLanguageSelected("ru") }
                )

                Spacer(modifier = Modifier.height(12.dp))

                GlassLanguageOption(
                    language = "en",
                    label = stringResource(R.string.language_english),
                    flag = "🇬🇧",
                    isSelected = currentLanguage == "en",
                    onClick = { onLanguageSelected("en") }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Close button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceGlassMedium)
                        .border(1.dp, BorderDefault, RoundedCornerShape(12.dp))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.dialog_ok),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassLanguageOption(
    language: String,
    label: String,
    flag: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) BrandCyan else BorderDefault
    val backgroundColor = if (isSelected) BrandCyan.copy(alpha = 0.1f) else SurfaceGlassLight

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = flag,
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) BrandCyan else TextPrimary
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = BrandCyan,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun GlassPersonaDialog(
    currentPersona: AudiencePersona,
    onPersonaSelected: (AudiencePersona) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 32.dp,
                    shape = RoundedCornerShape(28.dp),
                    ambientColor = BrandPink.copy(alpha = 0.4f),
                    spotColor = BrandCyan.copy(alpha = 0.4f)
                )
                .clip(RoundedCornerShape(28.dp))
                .background(SurfaceDialog)
                .border(1.dp, BorderStrong, RoundedCornerShape(28.dp))
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header with icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(BrandPink, GradientPurple)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.People,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.persona_dialog_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.persona_dialog_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Persona options
                AudiencePersona.entries.forEach { persona ->
                    GlassPersonaCard(
                        persona = persona,
                        isSelected = currentPersona == persona,
                        onClick = { onPersonaSelected(persona) }
                    )
                    if (persona != AudiencePersona.entries.last()) {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Close button with gradient
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    BrandCyan.copy(alpha = 0.15f),
                                    BrandPink.copy(alpha = 0.15f)
                                )
                            )
                        )
                        .border(1.dp, BorderDefault, RoundedCornerShape(14.dp))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.dialog_ok),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassPersonaCard(
    persona: AudiencePersona,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) BrandPink else BorderSubtle
    val backgroundColor = if (isSelected) BrandPink.copy(alpha = 0.12f) else SurfaceGlassLight

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Emoji avatar
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isSelected)
                        Brush.linearGradient(listOf(BrandPink.copy(alpha = 0.2f), GradientPurple.copy(alpha = 0.2f)))
                    else
                        Brush.linearGradient(listOf(SurfaceGlassMedium, SurfaceGlassHeavy))
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(persona.emojiRes),
                style = MaterialTheme.typography.titleLarge
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Content
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(persona.displayNameRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) BrandPink else TextPrimary
            )
            Text(
                text = stringResource(persona.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Peak hours visualization
            PeakHoursBar(
                peakHours = persona.peakHours,
                isSelected = isSelected
            )
        }

        // Checkmark
        if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(BrandPink),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun PeakHoursBar(
    peakHours: List<Int>,
    isSelected: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(SurfaceGlassLight),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        // 24 hour slots (showing 6-24, 0-5 is usually inactive)
        for (hour in 6..23) {
            val isPeakHour = hour in peakHours
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        when {
                            isPeakHour && isSelected -> BrandPink
                            isPeakHour -> BrandCyan.copy(alpha = 0.6f)
                            else -> Color.Transparent
                        }
                    )
            )
        }
    }
}

@Composable
private fun BrandFooter() {
    val infiniteTransition = rememberInfiniteTransition(label = "footer")

    // Shimmer offset for gradient text
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -200f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    // Pulsing glow
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    // Heart pulse
    val heartScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heart"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            // Ambient glow behind content
            Box(
                modifier = Modifier
                    .size(160.dp, 110.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                BrandCyan.copy(alpha = glowAlpha),
                                BrandPink.copy(alpha = glowAlpha * 0.4f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Cat paw
                Text(
                    text = "\uD83D\uDC3E",
                    fontSize = 28.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Gradient shimmer "KotKit"
                Text(
                    text = "KotKit",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                BrandCyan,
                                BrandPink,
                                BrandCyan
                            ),
                            start = Offset(shimmerOffset, 0f),
                            end = Offset(shimmerOffset + 200f, 0f)
                        )
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Made with pulsing heart
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Made with ",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Text(
                        text = "\u2764\uFE0F",
                        fontSize = 14.sp,
                        modifier = Modifier.graphicsLayer {
                            scaleX = heartScale
                            scaleY = heartScale
                        }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // App version
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled
                )
            }
        }
    }
}

/**
 * Диалог подтверждения включения автозапуска.
 * Показывается после того как пользователь вернулся из системных настроек OEM.
 */
@Composable
private fun GlassAutostartConfirmDialog(
    manufacturerName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val message = stringResource(R.string.autostart_confirm_message_generic, manufacturerName)

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 24.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = Success.copy(alpha = 0.3f),
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
                                colors = listOf(Success.copy(alpha = 0.3f), BrandCyan.copy(alpha = 0.3f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhonelinkSetup,
                        contentDescription = null,
                        tint = Success,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.autostart_confirm_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Confirm button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(Success, BrandCyan)
                            )
                        )
                        .clickable { onConfirm() }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.autostart_confirm_yes),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Dismiss button
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.autostart_confirm_no),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary
                    )
                }
            }
        }
    }
}

/**
 * Диалог с инструкциями для ручного включения разрешений.
 * Показывается когда не удалось открыть настройки OEM автоматически.
 */
@Composable
private fun GlassAutostartManualDialog(
    instructions: String,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 24.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = BrandPink.copy(alpha = 0.3f),
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
                                colors = listOf(BrandPink.copy(alpha = 0.3f), BrandCyan.copy(alpha = 0.3f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = BrandPink,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.autostart_manual_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.autostart_manual_message, instructions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Open settings button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(BrandPink, BrandCyan)
                            )
                        )
                        .clickable { onOpenSettings() }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.autostart_manual_open_settings),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Dismiss button
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.autostart_manual_done),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary
                    )
                }
            }
        }
    }
}


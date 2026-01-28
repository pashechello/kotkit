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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import com.kotkit.basic.R
import com.kotkit.basic.ui.components.BounceOverscrollContainer
import com.kotkit.basic.ui.components.ExactAlarmSettingsRow
import com.kotkit.basic.ui.components.GlassCard
import com.kotkit.basic.ui.components.GlassSettingRow
import com.kotkit.basic.ui.components.GlowingIcon
import com.kotkit.basic.ui.components.PulsingDot
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
                subtitle = if (uiState.isAccessibilityEnabled)
                    stringResource(R.string.settings_accessibility_enabled)
                else
                    stringResource(R.string.settings_accessibility_not_enabled),
                icon = Icons.Default.Accessibility,
                onClick = {
                    if (!uiState.isAccessibilityEnabled) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                },
                iconColor = if (uiState.isAccessibilityEnabled) Success else BrandPink,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                trailingContent = {
                    if (uiState.isAccessibilityEnabled) {
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

            Spacer(modifier = Modifier.height(8.dp))

            // Overlay Permission (for floating indicator)
            GlassSettingRow(
                title = stringResource(R.string.settings_overlay_permission),
                subtitle = if (uiState.hasOverlayPermission)
                    stringResource(R.string.settings_overlay_enabled)
                else
                    stringResource(R.string.settings_overlay_not_enabled),
                icon = Icons.Default.Layers,
                onClick = {
                    if (!uiState.hasOverlayPermission) {
                        viewModel.openOverlaySettings()
                    }
                },
                iconColor = if (uiState.hasOverlayPermission) Success else BrandPink,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                trailingContent = {
                    if (uiState.hasOverlayPermission) {
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

            // Account Section
            SettingsSectionHeader(
                title = stringResource(R.string.settings_account),
                icon = Icons.Outlined.Person
            )

            if (uiState.isLoggedIn) {
                GlassSettingRow(
                    title = stringResource(R.string.settings_logged_in),
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
                    ambientColor = BrandCyan.copy(alpha = 0.2f),
                    spotColor = BrandPink.copy(alpha = 0.2f)
                )
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceElevated1)
                .border(1.dp, BorderDefault, RoundedCornerShape(24.dp))
        ) {
            // Gradient overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                BrandCyan.copy(alpha = 0.05f),
                                BrandPink.copy(alpha = 0.05f)
                            )
                        )
                    )
            )

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
private fun BrandFooter() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Brand gradient line
        Box(
            modifier = Modifier
                .width(60.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(BrandCyan, BrandPink)
                    )
                )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "KotKit",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextTertiary
        )

        Text(
            text = "Made with ❤️",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
    }
}

package com.autoposter.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autoposter.ui.theme.Success
import com.autoposter.ui.theme.StatusFailed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToUnlockSettings: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshState()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Accessibility Service
            SettingsSection(title = "Accessibility") {
                SettingsItem(
                    icon = Icons.Default.Accessibility,
                    title = "Accessibility Service",
                    subtitle = if (uiState.isAccessibilityEnabled)
                        "Enabled - Ready to automate TikTok"
                    else
                        "Not enabled - Required for automation",
                    trailing = {
                        if (uiState.isAccessibilityEnabled) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Enabled",
                                tint = Success
                            )
                        } else {
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                )
                            }) {
                                Text("Enable")
                            }
                        }
                    }
                )
            }

            // Screen Unlock
            SettingsSection(title = "Screen Unlock") {
                SettingsItem(
                    icon = Icons.Default.Lock,
                    title = "Unlock Credentials",
                    subtitle = when {
                        uiState.hasStoredPin -> "PIN saved"
                        uiState.hasStoredPassword -> "Password saved"
                        else -> "Not configured - Required for scheduled posts"
                    },
                    onClick = onNavigateToUnlockSettings,
                    trailing = {
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                )
            }

            // Account
            SettingsSection(title = "Account") {
                if (uiState.isLoggedIn) {
                    SettingsItem(
                        icon = Icons.Default.Person,
                        title = "Logged In",
                        subtitle = "Tap to logout",
                        onClick = { viewModel.logout() }
                    )
                } else {
                    SettingsItem(
                        icon = Icons.Default.Login,
                        title = "Login",
                        subtitle = "Sign in to your account",
                        onClick = { /* Navigate to login */ }
                    )
                }
            }

            // About
            SettingsSection(title = "About") {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "Version",
                    subtitle = "1.0.0"
                )
                SettingsItem(
                    icon = Icons.Default.Security,
                    title = "Privacy Policy",
                    onClick = { /* Open privacy policy */ },
                    trailing = {
                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        content()
        Divider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

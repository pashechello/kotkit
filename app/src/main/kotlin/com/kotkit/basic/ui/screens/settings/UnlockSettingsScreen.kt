package com.kotkit.basic.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotkit.basic.R
import com.kotkit.basic.ui.components.BounceOverscrollContainer
import com.kotkit.basic.ui.components.GlassCard
import com.kotkit.basic.ui.components.GradientButton
import com.kotkit.basic.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val testUnlockCountdown by viewModel.testUnlockCountdown.collectAsState()
    val testUnlockResult by viewModel.testUnlockResult.collectAsState()
    var selectedType by remember { mutableStateOf(if (uiState.hasStoredPassword) 1 else 0) }
    var pin by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showSnackbar by remember { mutableStateOf<String?>(null) }

    val savedMessage = stringResource(R.string.unlock_saved)
    val pinError = stringResource(R.string.unlock_pin_error)
    val passwordError = stringResource(R.string.unlock_password_error)
    val clearedMessage = stringResource(R.string.unlock_cleared)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBase)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Glass Top Bar
            GlassTopBar(
                title = stringResource(R.string.unlock_title),
                onBackClick = onNavigateBack
            )

            BounceOverscrollContainer(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Accessibility status card
                    AccessibilityStatusCard(
                        isEnabled = uiState.isAccessibilityEnabled
                    )

                    // Info card
                    GlassCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Info.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = Info,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Text(
                                text = stringResource(R.string.unlock_info),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Lock type selection
                    Text(
                        text = stringResource(R.string.unlock_lock_type).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = BrandCyan
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GlassTypeChip(
                            text = stringResource(R.string.unlock_pin),
                            icon = Icons.Default.Pin,
                            isSelected = selectedType == 0,
                            onClick = { selectedType = 0 },
                            modifier = Modifier.weight(1f)
                        )
                        GlassTypeChip(
                            text = stringResource(R.string.unlock_password),
                            icon = Icons.Default.Password,
                            isSelected = selectedType == 1,
                            onClick = { selectedType = 1 },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Input field
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = if (selectedType == 0)
                                    stringResource(R.string.unlock_enter_pin)
                                else
                                    stringResource(R.string.unlock_enter_password),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextTertiary
                            )

                            if (selectedType == 0) {
                                GlassTextField(
                                    value = pin,
                                    onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) pin = it },
                                    placeholder = "****",
                                    keyboardType = KeyboardType.NumberPassword,
                                    isPassword = true
                                )
                            } else {
                                GlassTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    placeholder = "********",
                                    keyboardType = KeyboardType.Password,
                                    isPassword = !showPassword,
                                    trailingIcon = {
                                        Icon(
                                            if (showPassword) Icons.Default.VisibilityOff
                                            else Icons.Default.Visibility,
                                            contentDescription = stringResource(R.string.unlock_toggle_visibility),
                                            tint = TextTertiary,
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clickable { showPassword = !showPassword }
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // Warning if Accessibility not enabled
                    if (!uiState.isAccessibilityEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Warning.copy(alpha = 0.1f))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Warning,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Включите Accessibility Service для автоматической разблокировки",
                                style = MaterialTheme.typography.bodySmall,
                                color = Warning
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Save button
                    GradientButton(
                        onClick = {
                            val success = if (selectedType == 0) {
                                viewModel.savePin(pin)
                            } else {
                                viewModel.savePassword(password)
                            }
                            if (success) {
                                showSnackbar = savedMessage
                                onNavigateBack()
                            } else {
                                showSnackbar = if (selectedType == 0) pinError else passwordError
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        gradient = Brush.linearGradient(
                            colors = listOf(BrandCyan, BrandPink)
                        )
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.save),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Clear button if credentials exist
                    if (uiState.hasStoredPin || uiState.hasStoredPassword) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Error.copy(alpha = 0.1f))
                                .border(1.dp, Error.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                .clickable {
                                    viewModel.clearUnlockCredentials()
                                    pin = ""
                                    password = ""
                                    showSnackbar = clearedMessage
                                }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = Error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    stringResource(R.string.unlock_clear),
                                    color = Error,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Test Unlock section (only show when Accessibility enabled and credentials saved)
                    if (uiState.isAccessibilityEnabled && (uiState.hasStoredPin || uiState.hasStoredPassword)) {
                        Spacer(modifier = Modifier.height(8.dp))

                        // Test unlock button or countdown
                        if (testUnlockCountdown != null) {
                            // Countdown in progress
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Warning.copy(alpha = 0.15f))
                                    .border(1.dp, Warning.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                    .clickable { viewModel.cancelTestUnlock() }
                                    .padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = Warning
                                        )
                                        Text(
                                            "Разблокировка через $testUnlockCountdown сек...",
                                            color = Warning,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Text(
                                        "Нажмите чтобы отменить",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Warning.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        } else {
                            // Test unlock button
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(BrandCyan.copy(alpha = 0.1f))
                                    .border(1.dp, BrandCyan.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                    .clickable { viewModel.startTestUnlock() }
                                    .padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.LockOpen,
                                        contentDescription = null,
                                        tint = BrandCyan,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        "Тест разблокировки",
                                        color = BrandCyan,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            // Hint text
                            Text(
                                text = "Нажмите кнопку, заблокируйте телефон — через 15 сек он разблокируется сам",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }

                        // Show result if available
                        testUnlockResult?.let { result ->
                            val isSuccess = result.contains("успешн", ignoreCase = true)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSuccess) Success.copy(alpha = 0.1f) else Error.copy(alpha = 0.1f))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (isSuccess) Success else Error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = result,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSuccess) Success else Error,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = null,
                                    tint = TextTertiary,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { viewModel.clearTestUnlockResult() }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // Snackbar
        showSnackbar?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                GlassSnackbar(
                    message = message,
                    onDismiss = { showSnackbar = null }
                )
            }
        }
    }
}

@Composable
private fun AccessibilityStatusCard(
    isEnabled: Boolean
) {
    GlassCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BrandCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Accessibility,
                            contentDescription = null,
                            tint = BrandCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Accessibility Service",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val statusColor = if (isEnabled) Success else Error
                            val statusText = if (isEnabled) "Включён" else "Выключен"
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor
                            )
                        }
                    }
                }
            }

            Text(
                text = "Accessibility Service используется для автоматического ввода PIN-кода на экране блокировки.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            if (isEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Success.copy(alpha = 0.1f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Success,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Готово! Автоматическая разблокировка будет работать.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Success
                    )
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
                    colors = listOf(SurfaceElevated1, SurfaceBase)
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
private fun GlassTypeChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) BrandCyan.copy(alpha = 0.15f) else SurfaceGlassLight
    val borderColor = if (isSelected) BrandCyan.copy(alpha = 0.4f) else BorderDefault
    val contentColor = if (isSelected) BrandCyan else TextSecondary

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor
        )
        if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = BrandCyan,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    isPassword: Boolean,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceGlassLight)
            .border(1.dp, BorderDefault, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            singleLine = true,
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextMuted
                        )
                    }
                    innerTextField()
                }
            }
        )
        trailingIcon?.invoke()
    }
}

@Composable
private fun GlassSnackbar(
    message: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceElevated2)
            .border(1.dp, Success.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
        }
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.dismiss),
            tint = TextTertiary,
            modifier = Modifier
                .size(20.dp)
                .clickable(onClick = onDismiss)
        )
    }
}

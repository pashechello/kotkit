package com.kotkit.basic.ui.screens.captionprefs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotkit.basic.R
import com.kotkit.basic.ui.components.BounceOverscrollContainer
import com.kotkit.basic.ui.components.GlassCard
import com.kotkit.basic.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionPreferencesScreen(
    onNavigateBack: () -> Unit,
    viewModel: CaptionPreferencesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showSavedSnackbar by remember { mutableStateOf(false) }

    val savedMessage = stringResource(R.string.caption_prefs_saved)
    LaunchedEffect(uiState.showSavedMessage) {
        if (uiState.showSavedMessage) {
            showSavedSnackbar = true
            viewModel.clearSavedMessage()
        }
    }

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
                title = stringResource(R.string.caption_prefs_title),
                onBackClick = onNavigateBack,
                onSaveClick = { viewModel.savePreferences() }
            )

            BounceOverscrollContainer(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Description card with gradient
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        BrandCyan.copy(alpha = 0.1f),
                                        BrandPink.copy(alpha = 0.1f)
                                    )
                                )
                            )
                            .border(
                                width = 1.dp,
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        BrandCyan.copy(alpha = 0.3f),
                                        BrandPink.copy(alpha = 0.3f)
                                    )
                                ),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(BrandPink.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = BrandPink,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.caption_prefs_customize_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = stringResource(R.string.caption_prefs_customize_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextTertiary
                                )
                            }
                        }
                    }

                // Enable/Disable toggle
                GlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.caption_prefs_use_custom),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = stringResource(R.string.caption_prefs_use_custom_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                        }
                        GlassSwitch(
                            checked = uiState.isEnabled,
                            onCheckedChange = viewModel::setEnabled
                        )
                    }
                }

                // Tone prompt input
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.caption_prefs_style_label).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (uiState.isEnabled) BrandCyan else TextMuted
                    )

                    GlassTextArea(
                        value = uiState.tonePrompt,
                        onValueChange = viewModel::setTonePrompt,
                        placeholder = stringResource(R.string.caption_prefs_style_placeholder),
                        enabled = uiState.isEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                    )

                    Text(
                        text = "${uiState.tonePrompt.length}/${CaptionPreferencesViewModel.MAX_TONE_LENGTH}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.align(Alignment.End)
                    )
                }

                    // Examples
                    val funnyExample = stringResource(R.string.caption_prefs_funny_example)
                    val professionalExample = stringResource(R.string.caption_prefs_professional_example)
                    val motivationalExample = stringResource(R.string.caption_prefs_motivational_example)
                    val shortExample = stringResource(R.string.caption_prefs_short_example)

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.caption_prefs_examples).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextTertiary
                        )

                        GlassCard {
                            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                ExampleItem(
                                    title = stringResource(R.string.caption_prefs_funny_title),
                                    example = funnyExample,
                                    onClick = { viewModel.setTonePrompt(funnyExample) }
                                )
                                GlassDivider()
                                ExampleItem(
                                    title = stringResource(R.string.caption_prefs_professional_title),
                                    example = professionalExample,
                                    onClick = { viewModel.setTonePrompt(professionalExample) }
                                )
                                GlassDivider()
                                ExampleItem(
                                    title = stringResource(R.string.caption_prefs_motivational_title),
                                    example = motivationalExample,
                                    onClick = { viewModel.setTonePrompt(motivationalExample) }
                                )
                                GlassDivider()
                                ExampleItem(
                                    title = stringResource(R.string.caption_prefs_short_title),
                                    example = shortExample,
                                    onClick = { viewModel.setTonePrompt(shortExample) }
                                )
                            }
                        }
                    }

                Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // Snackbar
        if (showSavedSnackbar) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                GlassSnackbar(
                    message = savedMessage,
                    onDismiss = { showSavedSnackbar = false }
                )
            }
        }
    }
}

@Composable
private fun GlassTopBar(
    title: String,
    onBackClick: () -> Unit,
    onSaveClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceBase)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
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

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        // Save button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(BrandCyan, BrandPink)
                    )
                )
                .clickable(onClick = onSaveClick)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = stringResource(R.string.save),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val backgroundColor = if (checked) BrandCyan.copy(alpha = 0.3f) else SurfaceGlassMedium
    val thumbColor = if (checked) BrandCyan else TextMuted

    Box(
        modifier = Modifier
            .width(52.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor)
            .border(1.dp, BorderDefault, RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

@Composable
private fun GlassTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val borderColor = if (enabled) BorderDefault else BorderSubtle
    val backgroundColor = if (enabled) SurfaceGlassLight else SurfaceGlassLight.copy(alpha = 0.5f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier.fillMaxSize(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = if (enabled) TextPrimary else TextMuted
            ),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun GlassDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(BorderSubtle)
    )
}

@Composable
private fun ExampleItem(
    title: String,
    example: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = example,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            tint = BrandCyan,
            modifier = Modifier.size(20.dp)
        )
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

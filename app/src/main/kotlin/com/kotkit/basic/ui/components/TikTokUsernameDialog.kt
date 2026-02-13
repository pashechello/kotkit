package com.kotkit.basic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kotkit.basic.R
import com.kotkit.basic.ui.theme.*

private val USERNAME_REGEX = Regex("[a-zA-Z0-9_.]{1,24}")

private val CYRILLIC_TO_LATIN = mapOf(
    'а' to 'a', 'б' to 'b', 'в' to 'v', 'г' to 'g', 'д' to 'd',
    'е' to 'e', 'ё' to 'e', 'ж' to 'z', 'з' to 'z', 'и' to 'i',
    'й' to 'y', 'к' to 'k', 'л' to 'l', 'м' to 'm', 'н' to 'n',
    'о' to 'o', 'п' to 'p', 'р' to 'r', 'с' to 's', 'т' to 't',
    'у' to 'u', 'ф' to 'f', 'х' to 'h', 'ц' to 'c', 'ч' to 'c',
    'ш' to 's', 'щ' to 's', 'ъ' to '_', 'ы' to 'y', 'ь' to '_',
    'э' to 'e', 'ю' to 'u', 'я' to 'y',
)

private fun transliterateToAscii(input: String): String = buildString {
    for (ch in input) {
        val lower = ch.lowercaseChar()
        when {
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '_' -> append(ch)
            CYRILLIC_TO_LATIN.containsKey(lower) -> {
                val mapped = CYRILLIC_TO_LATIN[lower]!!
                append(if (ch.isUpperCase()) mapped.uppercaseChar() else mapped)
            }
        }
    }
}

@Composable
fun TikTokUsernameDialog(
    initialUsername: String = "",
    isSaving: Boolean = false,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(initialUsername) }
    var showError by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun validate(text: String): Boolean {
        val cleaned = text.trimStart('@').trim()
        return cleaned.isNotEmpty() && USERNAME_REGEX.matches(cleaned)
    }

    fun submit() {
        val cleaned = input.trimStart('@').trim()
        if (validate(cleaned)) {
            keyboardController?.hide()
            onSave(cleaned)
        } else {
            showError = true
        }
    }

    Dialog(onDismissRequest = { if (!isSaving) onDismiss() }) {
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
                        icon = Icons.Default.Person,
                        color = BrandPink,
                        size = 28.dp
                    )
                    Text(
                        text = stringResource(R.string.tiktok_username_dialog_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.tiktok_username_dialog_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Username input
                OutlinedTextField(
                    value = input,
                    onValueChange = { newValue ->
                        // Transliterate Cyrillic → Latin, strip anything else
                        val filtered = transliterateToAscii(newValue)
                        if (filtered.length <= 24) {
                            input = filtered
                            showError = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    prefix = {
                        Text(
                            text = "@",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = BrandPink
                        )
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.tiktok_username_hint),
                            color = TextTertiary
                        )
                    },
                    isError = showError,
                    supportingText = if (showError) {
                        { Text(stringResource(R.string.tiktok_username_invalid)) }
                    } else null,
                    singleLine = true,
                    enabled = !isSaving,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { submit() }
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandPink,
                        unfocusedBorderColor = BorderDefault,
                        focusedContainerColor = SurfaceGlassMedium,
                        unfocusedContainerColor = SurfaceGlassMedium,
                        cursorColor = BrandPink,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Save button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(BrandPink, BrandCyan)
                            )
                        )
                        .then(
                            if (!isSaving) Modifier.clickable { submit() }
                            else Modifier
                        )
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.tiktok_username_save),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Cancel button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceGlassMedium)
                        .border(1.dp, BorderDefault, RoundedCornerShape(12.dp))
                        .then(
                            if (!isSaving) Modifier.clickable(onClick = onDismiss)
                            else Modifier
                        )
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.tiktok_username_cancel),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

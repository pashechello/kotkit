package com.kotkit.basic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
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
import com.kotkit.basic.R
import com.kotkit.basic.ui.theme.*

/**
 * Settings row for SettingsScreen.
 * Shows exact alarm permission status and allows user to fix it.
 */
@Composable
fun ExactAlarmSettingsRow(
    hasPermission: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassSettingRow(
        title = stringResource(R.string.exact_alarm_settings_title),
        subtitle = if (hasPermission)
            stringResource(R.string.exact_alarm_settings_enabled)
        else
            stringResource(R.string.exact_alarm_settings_disabled),
        icon = Icons.Default.Schedule,
        onClick = {
            if (!hasPermission) {
                onOpenSettings()
            }
        },
        iconColor = if (hasPermission) Success else Warning,
        modifier = modifier,
        trailingContent = {
            if (hasPermission) {
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
                GlassChip(text = stringResource(R.string.exact_alarm_action_fix))
            }
        }
    )
}

@Composable
private fun GlassChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Warning, GradientOrange)
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

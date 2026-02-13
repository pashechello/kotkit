package com.kotkit.basic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kotkit.basic.R
import com.kotkit.basic.ui.theme.BrandCyan
import com.kotkit.basic.ui.theme.BrandPink
import com.kotkit.basic.ui.theme.BorderDefault
import com.kotkit.basic.ui.theme.GradientPurple
import com.kotkit.basic.ui.theme.SurfaceElevated1
import com.kotkit.basic.ui.theme.SurfaceGlassLight
import com.kotkit.basic.ui.theme.TextPrimary
import com.kotkit.basic.ui.theme.TextSecondary
import com.kotkit.basic.ui.theme.TextTertiary

/**
 * Reason for showing the login prompt
 */
enum class LoginPromptReason {
    SCHEDULE_POST,
    AI_CAPTION,
    WORKER_MODE,
    GENERAL
}

/**
 * Bottom sheet prompting user to log in.
 * Shows contextual message based on [reason].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginPromptBottomSheet(
    onDismiss: () -> Unit,
    onLoginClick: () -> Unit,
    reason: LoginPromptReason = LoginPromptReason.GENERAL
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceElevated1,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(BorderDefault)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Lock icon with glow
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                BrandCyan.copy(alpha = 0.15f),
                                GradientPurple.copy(alpha = 0.15f)
                            )
                        )
                    )
                    .border(
                        1.dp,
                        Brush.linearGradient(
                            colors = listOf(
                                BrandCyan.copy(alpha = 0.4f),
                                GradientPurple.copy(alpha = 0.4f)
                            )
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = BrandCyan,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Title
            Text(
                text = stringResource(R.string.auth_required_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Description based on reason
            Text(
                text = when (reason) {
                    LoginPromptReason.SCHEDULE_POST -> stringResource(R.string.auth_required_schedule)
                    LoginPromptReason.AI_CAPTION -> stringResource(R.string.auth_required_ai)
                    LoginPromptReason.WORKER_MODE -> stringResource(R.string.auth_required_worker)
                    LoginPromptReason.GENERAL -> stringResource(R.string.auth_required_general)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Login button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(BrandCyan, BrandPink)
                        )
                    )
                    .clickable(onClick = onLoginClick)
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.auth_login_via_website),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Later button
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.auth_later),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextTertiary
                )
            }
        }
    }
}

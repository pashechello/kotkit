package com.kotkit.basic.ui.screens.worker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotkit.basic.data.remote.api.models.PayoutMethod
import com.kotkit.basic.data.remote.api.models.PayoutResponse
import com.kotkit.basic.data.remote.api.models.PayoutStatus
import com.kotkit.basic.ui.components.SnackbarController
import com.kotkit.basic.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayoutScreen(
    onNavigateBack: () -> Unit,
    viewModel: PayoutViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            SnackbarController.showError(error)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.payoutSubmitted) {
        if (uiState.payoutSubmitted) {
            SnackbarController.showSuccess("Заявка создана!")
            viewModel.clearSubmitted()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Вывод средств") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceBase)
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = BrandCyan)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(SurfaceBase),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Balance info
                item {
                    BalanceCard(
                        available = uiState.availableBalance,
                        minPayout = uiState.minPayoutAmount
                    )
                }

                // Active payout
                uiState.activePayout?.let { payout ->
                    item {
                        ActivePayoutCard(
                            payout = payout,
                            onCancel = { viewModel.cancelPayout(payout.id) }
                        )
                    }
                }

                // Payout form (only if no active payout)
                if (uiState.activePayout == null) {
                    item {
                        PayoutForm(
                            amount = uiState.amount,
                            method = uiState.method,
                            currency = uiState.currency,
                            walletAddress = uiState.walletAddress,
                            availableBalance = uiState.availableBalance,
                            minPayoutAmount = uiState.minPayoutAmount,
                            isSubmitting = uiState.isSubmitting,
                            onAmountChange = viewModel::updateAmount,
                            onMethodChange = viewModel::updateMethod,
                            onCurrencyChange = viewModel::updateCurrency,
                            onWalletChange = viewModel::updateWalletAddress,
                            onSubmit = viewModel::submitPayout
                        )
                    }
                }

                // Payout history
                if (uiState.payoutHistory.isNotEmpty()) {
                    item {
                        Text(
                            "История выплат",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(uiState.payoutHistory) { payout ->
                        PayoutHistoryItem(payout)
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(available: Float, minPayout: Float) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Success.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Доступно к выводу", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Text(
                "${String.format("%.2f", available)} \u20BD",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Success
            )
            if (available < minPayout) {
                Text(
                    "Мин. для вывода: ${"%.0f".format(minPayout)} \u20BD",
                    style = MaterialTheme.typography.bodySmall,
                    color = Warning,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ActivePayoutCard(payout: PayoutResponse, onCancel: () -> Unit) {
    val statusText = when (payout.status) {
        PayoutStatus.PENDING -> "Ожидает рассмотрения"
        PayoutStatus.PROCESSING -> "В обработке"
        else -> payout.status
    }
    val statusColor = when (payout.status) {
        PayoutStatus.PENDING -> Warning
        PayoutStatus.PROCESSING -> BrandCyan
        else -> TextMuted
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = BrandCyan.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Активная заявка", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    Text(
                        "${String.format("%.2f", payout.amountRub)} \u20BD",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Text(
                    statusText,
                    color = statusColor,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                "${methodLabel(payout.method)} \u00B7 ${payout.currency}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (payout.adminComment != null) {
                Text(
                    payout.adminComment,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (payout.status == PayoutStatus.PENDING) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.padding(top = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Error)
                ) {
                    Text("Отменить")
                }
            }
        }
    }
}

@Composable
private fun PayoutForm(
    amount: String,
    method: String,
    currency: String,
    walletAddress: String,
    availableBalance: Float,
    minPayoutAmount: Float,
    isSubmitting: Boolean,
    onAmountChange: (String) -> Unit,
    onMethodChange: (String) -> Unit,
    onCurrencyChange: (String) -> Unit,
    onWalletChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val amountNum = amount.toFloatOrNull() ?: 0f
    val canSubmit = amountNum >= minPayoutAmount &&
        amountNum <= availableBalance &&
        walletAddress.isNotBlank() &&
        !isSubmitting

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated1),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Создать заявку", style = MaterialTheme.typography.titleMedium, color = Color.White)

            // Amount
            OutlinedTextField(
                value = amount,
                onValueChange = onAmountChange,
                label = { Text("Сумма (\u20BD)") },
                placeholder = { Text("Мин. ${"%.0f".format(minPayoutAmount)} \u20BD") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = { onAmountChange("%.2f".format(availableBalance)) }) {
                        Text("Всё", color = BrandCyan, fontSize = 12.sp)
                    }
                }
            )

            // Method selector
            Text("Способ вывода", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    PayoutMethod.CRYPTO to "Крипто",
                    PayoutMethod.SBP to "СБП",
                    PayoutMethod.CARD to "Карта"
                ).forEach { (value, label) ->
                    val selected = method == value
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) BrandCyan.copy(alpha = 0.15f) else SurfaceElevated2)
                            .border(
                                1.dp,
                                if (selected) BrandCyan.copy(alpha = 0.4f) else BorderDefault,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onMethodChange(value) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (selected) BrandCyan else TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Currency (crypto only)
            if (method == PayoutMethod.CRYPTO) {
                Text("Валюта", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("USDT", "TRX", "TON").forEach { c ->
                        val selected = currency == c
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) BrandCyan.copy(alpha = 0.15f) else SurfaceElevated2)
                                .border(
                                    1.dp,
                                    if (selected) BrandCyan.copy(alpha = 0.4f) else BorderDefault,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onCurrencyChange(c) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                c,
                                color = if (selected) BrandCyan else TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Wallet / Card / Phone
            val addressLabel = when (method) {
                PayoutMethod.CRYPTO -> "Адрес кошелька"
                PayoutMethod.SBP -> "Номер телефона"
                else -> "Номер карты"
            }
            val addressPlaceholder = when (method) {
                PayoutMethod.CRYPTO -> "TRC20, ERC20 или TON адрес"
                PayoutMethod.SBP -> "+7 900 123-45-67"
                else -> "2200 0000 0000 0000"
            }
            OutlinedTextField(
                value = walletAddress,
                onValueChange = onWalletChange,
                label = { Text(addressLabel) },
                placeholder = { Text(addressPlaceholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Submit
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BrandCyan)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Создать заявку на вывод")
                }
            }

            Text(
                "Заявка будет рассмотрена администратором в течение 24 часов",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun PayoutHistoryItem(payout: PayoutResponse) {
    val statusText = when (payout.status) {
        PayoutStatus.PENDING -> "Ожидает"
        PayoutStatus.PROCESSING -> "В обработке"
        PayoutStatus.COMPLETED -> "Выполнено"
        PayoutStatus.REJECTED -> "Отклонено"
        PayoutStatus.CANCELLED -> "Отменено"
        PayoutStatus.FAILED -> "Ошибка"
        else -> payout.status
    }
    val statusColor = when (payout.status) {
        PayoutStatus.PENDING -> Warning
        PayoutStatus.PROCESSING -> BrandCyan
        PayoutStatus.COMPLETED -> Success
        PayoutStatus.REJECTED -> Error
        PayoutStatus.CANCELLED -> TextMuted
        PayoutStatus.FAILED -> Error
        else -> TextMuted
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated1),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "${String.format("%.2f", payout.amountRub)} \u20BD",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    "${methodLabel(payout.method)} \u00B7 ${payout.currency} \u00B7 ${formatTimestamp(payout.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                if (payout.adminComment != null && payout.status == PayoutStatus.REJECTED) {
                    Text(
                        payout.adminComment,
                        style = MaterialTheme.typography.bodySmall,
                        color = Error,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Text(
                statusText,
                color = statusColor,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun methodLabel(method: String): String = when (method) {
    PayoutMethod.CRYPTO -> "Крипто"
    PayoutMethod.SBP -> "СБП"
    PayoutMethod.CARD -> "Карта"
    else -> method
}

private fun formatTimestamp(timestamp: Long): String {
    return try {
        val instant = Instant.ofEpochSecond(timestamp)
        val formatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale("ru"))
        instant.atZone(ZoneId.systemDefault()).format(formatter)
    } catch (e: Exception) {
        ""
    }
}

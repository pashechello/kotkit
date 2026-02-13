package com.kotkit.basic.ui.screens.worker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotkit.basic.data.local.db.entities.WorkerEarningEntity
import com.kotkit.basic.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarningsScreen(
    onNavigateBack: () -> Unit,
    onRequestPayout: () -> Unit,
    viewModel: EarningsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Заработок") },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(SurfaceBase),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Balance Card
            item {
                BalanceCard(
                    availableBalance = uiState.availableBalance,
                    pendingBalance = uiState.pendingBalance,
                    totalEarned = uiState.totalEarned,
                    onRequestPayout = onRequestPayout
                )
            }

            // Stats for this month
            item {
                MonthlyStatsCard(
                    thisMonthEarned = uiState.thisMonthEarned,
                    todayEarned = uiState.todayEarned
                )
            }

            // Earnings history header
            item {
                Text(
                    text = "История заработка",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Earnings list
            if (uiState.earnings.isEmpty() && !uiState.isLoading) {
                item {
                    EmptyEarningsState()
                }
            } else {
                items(uiState.earnings) { earning ->
                    EarningItem(earning = earning)
                }
            }

            // Loading more indicator
            if (uiState.isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(
    availableBalance: Float,
    pendingBalance: Float,
    totalEarned: Float,
    onRequestPayout: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AccentBlue.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "Доступно к выводу",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Text(
                text = "${String.format("%.2f", availableBalance)} ₽",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = AccentGreen
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "В ожидании",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "${String.format("%.2f", pendingBalance)} ₽",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentOrange
                    )
                }

                Column {
                    Text(
                        text = "Всего заработано",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "${String.format("%.2f", totalEarned)} ₽",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onRequestPayout,
                modifier = Modifier.fillMaxWidth(),
                enabled = availableBalance >= 5.0f, // Minimum payout
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGreen
                )
            ) {
                Icon(Icons.Default.AccountBalanceWallet, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Вывести средства")
            }

            if (availableBalance < 5.0f) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Минимальная сумма для вывода: 500 ₽",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun MonthlyStatsCard(
    thisMonthEarned: Float,
    todayEarned: Float
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${String.format("%.2f", todayEarned)} ₽",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AccentGreen
                )
                Text(
                    text = "Сегодня",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            VerticalDivider(
                modifier = Modifier.height(48.dp),
                color = SurfaceBase
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${String.format("%.2f", thisMonthEarned)} ₽",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "В этом месяце",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun EarningItem(earning: WorkerEarningEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Задача выполнена",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = formatDate(earning.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "+${String.format("%.2f", earning.amountRub)} ₽",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AccentGreen
                )
                EarningStatusBadge(status = earning.status)
            }
        }
    }
}

@Composable
private fun EarningStatusBadge(status: String) {
    val (text, color) = when (status) {
        "pending" -> "Ожидание" to AccentOrange
        "confirmed" -> "Подтверждено" to AccentBlue
        "paid" -> "Выплачено" to AccentGreen
        else -> status to TextSecondary
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
}

@Composable
private fun EmptyEarningsState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.MoneyOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = TextSecondary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Пока нет заработка",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = "Выполните задачи чтобы начать зарабатывать",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

private val dateFormatter = DateTimeFormatter
    .ofPattern("dd MMM yyyy, HH:mm", Locale("ru"))
    .withZone(ZoneId.systemDefault())

private fun formatDate(timestamp: Long): String {
    return dateFormatter.format(Instant.ofEpochMilli(timestamp))
}

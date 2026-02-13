package com.kotkit.basic.data.remote.api.models

import com.google.gson.annotations.SerializedName

/**
 * Network Mode API Models - Payouts & Earnings
 *
 * These models correspond to the backend API endpoints in:
 * tiktok-agent/api/routes/payouts.py
 * tiktok-agent/api/schemas/payout.py
 */

// ============================================================================
// Balance Response
// ============================================================================

data class BalanceResponse(
    @SerializedName("pending_balance") val pendingBalance: Float,
    @SerializedName("available_balance") val availableBalance: Float,
    @SerializedName("total_earned") val totalEarned: Float,
    @SerializedName("min_payout_amount") val minPayoutAmount: Float
)

// ============================================================================
// Earnings History
// ============================================================================

data class EarningResponse(
    val id: String,
    @SerializedName("task_id") val taskId: String,
    @SerializedName("campaign_id") val campaignId: String,
    @SerializedName("campaign_name") val campaignName: String?,
    @SerializedName("amount_rub") val amountRub: Float,
    val status: String, // pending, approved, available, paid, cancelled
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("approved_at") val approvedAt: Long?,
    @SerializedName("available_at") val availableAt: Long?,
    @SerializedName("paid_at") val paidAt: Long?
)

data class EarningsListResponse(
    val earnings: List<EarningResponse>,
    val total: Int,
    @SerializedName("total_amount") val totalAmount: Float
)

// ============================================================================
// Payout Request
// ============================================================================

data class PayoutRequestCreate(
    @SerializedName("amount_rub") val amountRub: Float,
    val method: String, // crypto, card, sbp
    val currency: String, // USDT, RUB, TRX, TON
    @SerializedName("wallet_address") val walletAddress: String
) {
    // Mask sensitive data in logs
    override fun toString(): String {
        return "PayoutRequestCreate(amount=$amountRub, method=$method, " +
            "currency=$currency, wallet=${walletAddress.maskMiddle()})"
    }

    private fun String.maskMiddle(): String {
        return if (length <= 6) "***" else "${take(3)}***${takeLast(3)}"
    }
}

data class PayoutResponse(
    val id: String,
    @SerializedName("amount_rub") val amountRub: Float,
    val currency: String,
    @SerializedName("amount_currency") val amountCurrency: Float?,
    val method: String,
    @SerializedName("wallet_address") val walletAddress: String,
    val status: String, // pending, processing, completed, failed, cancelled
    @SerializedName("transaction_id") val transactionId: String?,
    @SerializedName("transaction_hash") val transactionHash: String?,
    @SerializedName("error_message") val errorMessage: String?,
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("processed_at") val processedAt: Long?
)

data class PayoutListResponse(
    val payouts: List<PayoutResponse>,
    val total: Int
)

// ============================================================================
// Payout Methods & Status & Currency
// ============================================================================

object PayoutMethod {
    const val CRYPTO = "crypto"
    const val CARD = "card"
    const val SBP = "sbp"
}

object PayoutCurrency {
    const val USDT = "USDT"
    const val RUB = "RUB"
    const val TRX = "TRX"
    const val TON = "TON"
}

object PayoutStatus {
    const val PENDING = "pending"
    const val PROCESSING = "processing"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

object EarningStatus {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val AVAILABLE = "available"
    const val PAID = "paid"
    const val CANCELLED = "cancelled"
}

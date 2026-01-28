package com.kotkit.basic.data.local.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local cache for earnings history.
 */
@Entity(
    tableName = "worker_earnings",
    indices = [
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
data class WorkerEarningEntity(
    @PrimaryKey
    val id: String, // UUID from backend

    val taskId: String,
    val campaignId: String,

    val amountUsd: Float,
    val status: String, // pending, approved, available, paid, cancelled

    val createdAt: Long,
    val approvedAt: Long?,
    val paidAt: Long?,

    // Local sync
    val lastSyncedAt: Long = System.currentTimeMillis()
)

/**
 * Earning status (mirrors backend).
 */
object EarningStatus {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val AVAILABLE = "available"
    const val PAID = "paid"
    const val CANCELLED = "cancelled"
}

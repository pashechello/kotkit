package com.kotkit.basic.data.local.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local cache for worker profile.
 *
 * Stores worker's profile and earnings for offline display.
 */
@Entity(tableName = "worker_profile")
data class WorkerProfileEntity(
    @PrimaryKey
    val id: String, // Worker UUID from backend

    // TikTok account info
    val userId: String, // User account ID (auth)
    val tiktokUsername: String?,
    val followersCount: Int,

    // Status
    val isActive: Boolean = false, // Network mode on/off
    val isVerified: Boolean = false,
    val rating: Float = 0f,

    // Statistics
    val totalTasks: Int = 0,
    val completedTasks: Int = 0,
    val failedTasks: Int = 0,
    val successRate: Float = 0f,

    // Earnings
    val totalEarnedRub: Float = 0f,
    val pendingBalanceRub: Float = 0f,
    val availableBalanceRub: Float = 0f,
    val minPricePerPost: Float = 0f,

    // Timestamps
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    // Local sync
    val lastSyncedAt: Long = 0
)

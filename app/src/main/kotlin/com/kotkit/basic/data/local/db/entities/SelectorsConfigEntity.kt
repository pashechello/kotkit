package com.kotkit.basic.data.local.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached remote selectors config.
 *
 * Stores TikTok UI selectors for offline usage.
 */
@Entity(tableName = "selectors_config")
data class SelectorsConfigEntity(
    @PrimaryKey
    val id: Int = 1, // Singleton

    val version: String,
    val updatedAt: Long,

    // Serialized JSON maps
    val selectorsJson: String, // Map<String, String>
    val contentDescriptionsJson: String, // Map<String, String>

    // Timeouts
    val pageLoadMs: Int,
    val elementWaitMs: Int,
    val uploadTimeoutMs: Int,
    val postConfirmMs: Int,

    // Local tracking
    val fetchedAt: Long = System.currentTimeMillis()
)

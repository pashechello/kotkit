package com.autoposter.data.local.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val username: String,
    val email: String?,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

package com.kotkit.basic.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.kotkit.basic.data.local.db.entities.AccountEntity
import com.kotkit.basic.data.local.db.entities.NetworkTaskEntity
import com.kotkit.basic.data.local.db.entities.PostEntity
import com.kotkit.basic.data.local.db.entities.PostStatus
import com.kotkit.basic.data.local.db.entities.SelectorsConfigEntity
import com.kotkit.basic.data.local.db.entities.WorkerEarningEntity
import com.kotkit.basic.data.local.db.entities.WorkerProfileEntity

@Database(
    entities = [
        // Solo mode
        PostEntity::class,
        AccountEntity::class,
        // Network mode
        NetworkTaskEntity::class,
        WorkerProfileEntity::class,
        WorkerEarningEntity::class,
        SelectorsConfigEntity::class,
    ],
    version = 4, // Added thumbnailPath to posts for persistent thumbnails
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    // Solo mode DAOs
    abstract fun postDao(): PostDao
    abstract fun accountDao(): AccountDao

    // Network mode DAOs
    abstract fun networkTaskDao(): NetworkTaskDao
    abstract fun workerProfileDao(): WorkerProfileDao
    abstract fun workerEarningDao(): WorkerEarningDao
    abstract fun selectorsConfigDao(): SelectorsConfigDao
}

class Converters {
    @TypeConverter
    fun fromPostStatus(status: PostStatus): String {
        return status.name
    }

    @TypeConverter
    fun toPostStatus(value: String): PostStatus {
        return PostStatus.valueOf(value)
    }
}

package com.autoposter.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.autoposter.data.local.db.entities.AccountEntity
import com.autoposter.data.local.db.entities.PostEntity
import com.autoposter.data.local.db.entities.PostStatus

@Database(
    entities = [PostEntity::class, AccountEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun postDao(): PostDao
    abstract fun accountDao(): AccountDao
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

package com.autoposter.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.autoposter.data.local.db.AppDatabase
import com.autoposter.data.local.db.AccountDao
import com.autoposter.data.local.db.PostDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Migration from version 1 to 2
     * Example: Add new column to posts table
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Example: Add hashtags column
            // database.execSQL("ALTER TABLE posts ADD COLUMN hashtags TEXT DEFAULT NULL")
        }
    }

    /**
     * Migration from version 2 to 3
     * Example: Add index for better query performance
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Example: Add index on status and scheduledTime
            // database.execSQL("CREATE INDEX IF NOT EXISTS index_posts_status ON posts(status)")
            // database.execSQL("CREATE INDEX IF NOT EXISTS index_posts_scheduledTime ON posts(scheduledTime)")
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "autoposter_db"
        )
            // Add migrations here as the schema evolves
            // .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            //
            // IMPORTANT: Only use fallbackToDestructiveMigration during development.
            // For production, implement proper migrations above.
            // Remove this line and uncomment addMigrations when going to production.
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides
    @Singleton
    fun providePostDao(database: AppDatabase): PostDao {
        return database.postDao()
    }

    @Provides
    @Singleton
    fun provideAccountDao(database: AppDatabase): AccountDao {
        return database.accountDao()
    }
}

package com.kotkit.basic.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kotkit.basic.data.local.db.AppDatabase
import com.kotkit.basic.data.local.db.AccountDao
import com.kotkit.basic.data.local.db.NetworkTaskDao
import com.kotkit.basic.data.local.db.PostDao
import com.kotkit.basic.data.local.db.SelectorsConfigDao
import com.kotkit.basic.data.local.db.WorkerEarningDao
import com.kotkit.basic.data.local.db.WorkerProfileDao
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
     * Adds Network Mode tables: network_tasks, worker_profile, worker_earnings, selectors_config
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Create network_tasks table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS network_tasks (
                    id TEXT NOT NULL PRIMARY KEY,
                    campaignId TEXT NOT NULL,
                    videoS3Key TEXT NOT NULL,
                    videoHash TEXT,
                    videoSizeBytes INTEGER,
                    caption TEXT NOT NULL,
                    status TEXT NOT NULL,
                    priceUsd REAL NOT NULL,
                    assignedAt INTEGER,
                    scheduledFor INTEGER,
                    lastHeartbeat INTEGER,
                    startedAt INTEGER,
                    completedAt INTEGER,
                    tiktokVideoId TEXT,
                    tiktokPostUrl TEXT,
                    proofScreenshotPath TEXT,
                    errorMessage TEXT,
                    errorType TEXT,
                    retryCount INTEGER NOT NULL DEFAULT 0,
                    videoLocalPath TEXT,
                    downloadProgress INTEGER NOT NULL DEFAULT 0,
                    syncStatus TEXT NOT NULL DEFAULT 'synced',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """.trimIndent())
            database.execSQL("CREATE INDEX IF NOT EXISTS index_network_tasks_status ON network_tasks(status)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_network_tasks_syncStatus ON network_tasks(syncStatus)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_network_tasks_scheduledFor ON network_tasks(scheduledFor)")

            // Create worker_profile table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS worker_profile (
                    id TEXT NOT NULL PRIMARY KEY,
                    userId TEXT NOT NULL,
                    tiktokUsername TEXT NOT NULL,
                    tiktokUserId TEXT,
                    followersCount INTEGER NOT NULL DEFAULT 0,
                    bio TEXT,
                    isActive INTEGER NOT NULL DEFAULT 0,
                    isVerified INTEGER NOT NULL DEFAULT 0,
                    rating REAL NOT NULL DEFAULT 0,
                    totalTasks INTEGER NOT NULL DEFAULT 0,
                    completedTasks INTEGER NOT NULL DEFAULT 0,
                    failedTasks INTEGER NOT NULL DEFAULT 0,
                    successRate REAL NOT NULL DEFAULT 0,
                    totalEarnedUsd REAL NOT NULL DEFAULT 0,
                    pendingBalanceUsd REAL NOT NULL DEFAULT 0,
                    availableBalanceUsd REAL NOT NULL DEFAULT 0,
                    minPricePerPost REAL NOT NULL DEFAULT 0,
                    lastActiveAt INTEGER,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    lastSyncedAt INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())

            // Create worker_earnings table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS worker_earnings (
                    id TEXT NOT NULL PRIMARY KEY,
                    workerId TEXT NOT NULL,
                    taskId TEXT NOT NULL,
                    campaignId TEXT NOT NULL,
                    amountUsd REAL NOT NULL,
                    status TEXT NOT NULL,
                    earnedAt INTEGER NOT NULL,
                    confirmedAt INTEGER,
                    paidAt INTEGER,
                    lastSyncedAt INTEGER NOT NULL
                )
            """.trimIndent())
            database.execSQL("CREATE INDEX IF NOT EXISTS index_worker_earnings_status ON worker_earnings(status)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_worker_earnings_earnedAt ON worker_earnings(earnedAt)")

            // Create selectors_config table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS selectors_config (
                    id INTEGER NOT NULL PRIMARY KEY,
                    version TEXT NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    selectorsJson TEXT NOT NULL,
                    contentDescriptionsJson TEXT NOT NULL,
                    pageLoadMs INTEGER NOT NULL,
                    elementWaitMs INTEGER NOT NULL,
                    uploadTimeoutMs INTEGER NOT NULL,
                    postConfirmMs INTEGER NOT NULL,
                    fetchedAt INTEGER NOT NULL
                )
            """.trimIndent())
        }
    }

    /**
     * Migration from version 3 to 4
     * Adds thumbnailPath column to posts table for persisted thumbnails
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE posts ADD COLUMN thumbnailPath TEXT DEFAULT NULL")
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
            .addMigrations(MIGRATION_1_2, MIGRATION_3_4)
            .fallbackToDestructiveMigration()
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

    // ========================================================================
    // Network Mode DAOs
    // ========================================================================

    @Provides
    @Singleton
    fun provideNetworkTaskDao(database: AppDatabase): NetworkTaskDao {
        return database.networkTaskDao()
    }

    @Provides
    @Singleton
    fun provideWorkerProfileDao(database: AppDatabase): WorkerProfileDao {
        return database.workerProfileDao()
    }

    @Provides
    @Singleton
    fun provideWorkerEarningDao(database: AppDatabase): WorkerEarningDao {
        return database.workerEarningDao()
    }

    @Provides
    @Singleton
    fun provideSelectorsConfigDao(database: AppDatabase): SelectorsConfigDao {
        return database.selectorsConfigDao()
    }
}

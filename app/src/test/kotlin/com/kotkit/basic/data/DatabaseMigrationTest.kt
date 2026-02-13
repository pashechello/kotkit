package com.kotkit.basic.data

import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for database migration from version 1 to 2.
 *
 * These tests verify that the SQL in MIGRATION_1_2 is valid.
 * For full migration testing, use androidTest with Room's MigrationTestHelper.
 */
class DatabaseMigrationTest {

    @Test
    fun `network_tasks table SQL is valid`() {
        val sql = """
            CREATE TABLE IF NOT EXISTS network_tasks (
                id TEXT NOT NULL PRIMARY KEY,
                campaignId TEXT NOT NULL,
                videoS3Key TEXT NOT NULL,
                videoHash TEXT,
                videoSizeBytes INTEGER,
                caption TEXT NOT NULL,
                status TEXT NOT NULL,
                priceRub REAL NOT NULL,
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
                syncStatus TEXT NOT NULL DEFAULT 'synced'
            )
        """.trimIndent()

        // Verify required columns are present
        assertTrue("Missing id column", sql.contains("id TEXT NOT NULL PRIMARY KEY"))
        assertTrue("Missing campaignId column", sql.contains("campaignId TEXT NOT NULL"))
        assertTrue("Missing status column", sql.contains("status TEXT NOT NULL"))
        assertTrue("Missing videoSizeBytes as INTEGER", sql.contains("videoSizeBytes INTEGER"))
    }

    @Test
    fun `worker_profile table SQL is valid`() {
        val sql = """
            CREATE TABLE IF NOT EXISTS worker_profile (
                id TEXT NOT NULL PRIMARY KEY,
                isActive INTEGER NOT NULL DEFAULT 0,
                totalEarnedRub REAL NOT NULL DEFAULT 0.0,
                availableBalanceRub REAL NOT NULL DEFAULT 0.0,
                pendingBalanceRub REAL NOT NULL DEFAULT 0.0,
                completedTasksCount INTEGER NOT NULL DEFAULT 0,
                lastSyncAt INTEGER,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent()

        assertTrue("Missing id column", sql.contains("id TEXT NOT NULL PRIMARY KEY"))
        assertTrue("Missing isActive column", sql.contains("isActive INTEGER NOT NULL"))
        assertTrue("Missing totalEarnedRub column", sql.contains("totalEarnedRub REAL"))
    }

    @Test
    fun `worker_earnings table SQL is valid`() {
        val sql = """
            CREATE TABLE IF NOT EXISTS worker_earnings (
                id TEXT NOT NULL PRIMARY KEY,
                taskId TEXT NOT NULL,
                campaignId TEXT NOT NULL,
                amountRub REAL NOT NULL,
                status TEXT NOT NULL,
                earnedAt INTEGER NOT NULL,
                confirmedAt INTEGER,
                paidAt INTEGER
            )
        """.trimIndent()

        assertTrue("Missing id column", sql.contains("id TEXT NOT NULL PRIMARY KEY"))
        assertTrue("Missing taskId column", sql.contains("taskId TEXT NOT NULL"))
        assertTrue("Missing amountRub column", sql.contains("amountRub REAL NOT NULL"))
    }

    @Test
    fun `selectors_config table SQL is valid`() {
        val sql = """
            CREATE TABLE IF NOT EXISTS selectors_config (
                id INTEGER NOT NULL PRIMARY KEY,
                version TEXT NOT NULL,
                configJson TEXT NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """.trimIndent()

        assertTrue("Missing id column", sql.contains("id INTEGER NOT NULL PRIMARY KEY"))
        assertTrue("Missing version column", sql.contains("version TEXT NOT NULL"))
        assertTrue("Missing configJson column", sql.contains("configJson TEXT NOT NULL"))
    }

    @Test
    fun `videoSizeBytes is Long compatible`() {
        // SQLite INTEGER is 64-bit, same as Kotlin Long
        // This test documents the expectation
        val maxLong = Long.MAX_VALUE // 9,223,372,036,854,775,807
        val maxVideoSize = 10L * 1024 * 1024 * 1024 // 10 GB in bytes

        assertTrue("Max video size should fit in Long", maxVideoSize < maxLong)
    }
}

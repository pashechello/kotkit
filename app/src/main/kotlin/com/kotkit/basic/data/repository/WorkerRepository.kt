package com.kotkit.basic.data.repository

import android.util.Log
import com.kotkit.basic.data.local.db.WorkerEarningDao
import com.kotkit.basic.data.local.db.WorkerProfileDao
import com.kotkit.basic.data.local.db.entities.WorkerEarningEntity
import com.kotkit.basic.data.local.db.entities.WorkerProfileEntity
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.BalanceResponse
import com.kotkit.basic.data.remote.api.models.PayoutRequestCreate
import com.kotkit.basic.data.remote.api.models.PayoutResponse
import com.kotkit.basic.data.remote.api.models.RegisterDeviceRequest
import com.kotkit.basic.data.remote.api.models.WorkerRegisterRequest
import com.kotkit.basic.data.remote.api.models.WorkerResponse
import com.kotkit.basic.data.remote.api.models.WorkerStatsResponse
import com.kotkit.basic.data.remote.api.models.WorkerToggleRequest
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkerRepository @Inject constructor(
    private val workerProfileDao: WorkerProfileDao,
    private val workerEarningDao: WorkerEarningDao,
    private val apiService: ApiService
) {
    companion object {
        private const val TAG = "WorkerRepository"
    }

    // ========================================================================
    // Profile - Flow Queries
    // ========================================================================

    fun getProfileFlow(): Flow<WorkerProfileEntity?> =
        workerProfileDao.getProfileFlow()

    fun isWorkerActiveFlow(): Flow<Boolean?> =
        workerProfileDao.isActiveFlow()

    // ========================================================================
    // Profile - API Operations
    // ========================================================================

    suspend fun getProfile(): WorkerProfileEntity? =
        workerProfileDao.getProfile()

    suspend fun isWorkerActive(): Boolean =
        workerProfileDao.isActive() ?: false

    suspend fun registerWorker(
        tiktokUsername: String?,
        categoryIds: List<String>?,
        countryCode: String?,
        timezone: String?
    ): Result<WorkerProfileEntity> {
        return try {
            val response = apiService.registerWorker(
                WorkerRegisterRequest(
                    tiktokUsername = tiktokUsername,
                    categoryIds = categoryIds,
                    countryCode = countryCode,
                    timezone = timezone
                )
            )
            val entity = response.toEntity()
            workerProfileDao.insert(entity)
            Log.i(TAG, "Worker registered: ${entity.id}")
            Result.success(entity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register worker", e)
            Result.failure(e)
        }
    }

    suspend fun fetchAndSyncProfile(): Result<WorkerProfileEntity> {
        return try {
            val response = apiService.getWorkerProfile()
            val entity = response.toEntity()
            workerProfileDao.insert(entity)
            Log.i(TAG, "Profile synced: ${entity.id}")
            Result.success(entity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch profile", e)
            Result.failure(e)
        }
    }

    suspend fun toggleWorkerMode(isActive: Boolean): Result<WorkerProfileEntity> {
        return try {
            val response = apiService.toggleWorkerMode(WorkerToggleRequest(isActive))
            val entity = response.toEntity()
            workerProfileDao.insert(entity)
            Log.i(TAG, "Worker mode toggled: isActive=$isActive")
            Result.success(entity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle worker mode", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // Stats
    // ========================================================================

    suspend fun fetchStats(): Result<WorkerStatsResponse> {
        return try {
            val stats = apiService.getWorkerStats()
            // Update local profile with today stats
            workerProfileDao.getProfile()?.let { profile ->
                workerProfileDao.updateStats(
                    id = profile.id,
                    total = stats.weekTasks,
                    completed = stats.weekCompleted,
                    failed = stats.weekTasks - stats.weekCompleted,
                    successRate = if (stats.weekTasks > 0) stats.weekCompleted.toFloat() / stats.weekTasks else 0f
                )
            }
            Log.i(TAG, "Stats fetched: ${stats.todayCompleted}/${stats.todayTasks}")
            Result.success(stats)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch stats", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // Device Registration
    // ========================================================================

    suspend fun registerDevice(
        deviceFingerprint: String,
        deviceModel: String,
        androidVersion: String,
        appVersion: String,
        fcmToken: String? = null
    ): Result<Unit> {
        return try {
            apiService.registerDevice(
                RegisterDeviceRequest(
                    deviceFingerprint = deviceFingerprint,
                    deviceModel = deviceModel,
                    androidVersion = androidVersion,
                    appVersion = appVersion,
                    fcmToken = fcmToken
                )
            )
            Log.i(TAG, "Device registered: $deviceModel")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register device", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // Balance & Earnings
    // ========================================================================

    fun getEarningsFlow(): Flow<List<WorkerEarningEntity>> =
        workerEarningDao.getAllFlow()

    suspend fun fetchBalance(): Result<BalanceResponse> {
        return try {
            val balance = apiService.getBalance()
            // Update local profile
            workerProfileDao.getProfile()?.let { profile ->
                workerProfileDao.updateBalance(
                    id = profile.id,
                    totalEarned = balance.totalEarned,
                    pending = balance.pendingBalance,
                    available = balance.availableBalance
                )
            }
            Log.i(TAG, "Balance fetched: available=${balance.availableBalance}")
            Result.success(balance)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch balance", e)
            Result.failure(e)
        }
    }

    suspend fun fetchEarnings(limit: Int = 50, offset: Int = 0): Result<List<WorkerEarningEntity>> {
        return try {
            val response = apiService.getEarnings(limit, offset)
            val entities = response.earnings.map { it.toEntity() }
            workerEarningDao.insertAll(entities)
            Log.i(TAG, "Fetched ${entities.size} earnings")
            Result.success(entities)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch earnings", e)
            Result.failure(e)
        }
    }

    suspend fun getTodayEarnings(): Float {
        val startOfDay = System.currentTimeMillis() - (System.currentTimeMillis() % (24 * 60 * 60 * 1000))
        return workerEarningDao.sumTodayEarnings(startOfDay) ?: 0f
    }

    // ========================================================================
    // Payouts
    // ========================================================================

    suspend fun requestPayout(
        amountUsd: Float,
        method: String,
        currency: String,
        walletAddress: String
    ): Result<PayoutResponse> {
        return try {
            val response = apiService.requestPayout(
                PayoutRequestCreate(
                    amountUsd = amountUsd,
                    method = method,
                    currency = currency,
                    walletAddress = walletAddress
                )
            )
            Log.i(TAG, "Payout requested: $amountUsd via $method")
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request payout", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun WorkerResponse.toEntity(): WorkerProfileEntity {
        return WorkerProfileEntity(
            id = id,
            userId = userId,
            tiktokUsername = tiktokUsername,
            followersCount = followersCount,
            isActive = isActive,
            isVerified = isVerified,
            rating = rating,
            totalTasks = totalTasks,
            completedTasks = completedTasks,
            failedTasks = failedTasks,
            successRate = successRate,
            totalEarnedUsd = totalEarned,
            pendingBalanceUsd = pendingBalance,
            availableBalanceUsd = availableBalance,
            minPricePerPost = minPricePerPost,
            createdAt = createdAt,
            lastSyncedAt = System.currentTimeMillis()
        )
    }

    private fun com.kotkit.basic.data.remote.api.models.EarningResponse.toEntity(): WorkerEarningEntity {
        return WorkerEarningEntity(
            id = id,
            taskId = taskId,
            campaignId = campaignId,
            amountUsd = amountUsd,
            status = status,
            createdAt = createdAt,
            approvedAt = approvedAt,
            paidAt = paidAt
        )
    }
}

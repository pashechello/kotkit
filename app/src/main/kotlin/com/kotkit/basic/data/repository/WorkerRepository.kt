package com.kotkit.basic.data.repository

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.kotkit.basic.BuildConfig
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
import com.kotkit.basic.data.remote.api.models.WorkerUpdateRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workerProfileDao: WorkerProfileDao,
    private val workerEarningDao: WorkerEarningDao,
    private val apiService: ApiService
) {
    companion object {
        private const val TAG = "WorkerRepository"
    }

    // Timestamp of the last toggle operation - any profile fetch that started
    // BEFORE this timestamp should be ignored to prevent race conditions
    private val lastToggleTimestamp = AtomicLong(0L)

    /**
     * Insert profile only if it's not stale (operation started after last toggle).
     * Returns true if inserted, false if skipped.
     */
    private suspend fun insertProfileIfNotStale(
        entity: WorkerProfileEntity,
        operationStartTime: Long
    ): Boolean {
        val toggleTime = lastToggleTimestamp.get()
        if (operationStartTime < toggleTime) {
            Log.w(TAG, "Skipping stale profile insert: opTime=$operationStartTime < toggleTime=$toggleTime, isActive=${entity.isActive}")
            return false
        }
        workerProfileDao.insert(entity)
        return true
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
        val fetchStartTime = System.currentTimeMillis()
        return try {
            Log.i(TAG, "Fetching profile from API... (startTime=$fetchStartTime)")
            val response = apiService.getWorkerProfile()
            Log.i(TAG, "Profile API response: is_active=${response.isActive}, id=${response.id}")
            val entity = response.toEntity()

            val inserted = insertProfileIfNotStale(entity, fetchStartTime)
            if (inserted) {
                Log.i(TAG, "Profile synced to DB: isActive=${entity.isActive}")
            } else {
                Log.i(TAG, "Profile sync SKIPPED (stale): isActive=${entity.isActive}")
            }

            Result.success(entity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch profile", e)
            Result.failure(e)
        }
    }

    suspend fun updateTiktokUsername(username: String): Result<WorkerProfileEntity> {
        return try {
            val response = apiService.updateWorkerProfile(
                WorkerUpdateRequest(
                    tiktokUsername = username,
                    categoryIds = null,
                    maxDailyTasks = null,
                    minPricePerPost = null,
                    isActive = null,
                    countryCode = null,
                    timezone = null
                )
            )
            val entity = response.toEntity()
            workerProfileDao.insert(entity)
            Log.i(TAG, "TikTok username updated: @$username")
            Result.success(entity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update TikTok username", e)
            Result.failure(e)
        }
    }

    suspend fun toggleWorkerMode(isActive: Boolean): Result<WorkerProfileEntity> {
        val toggleStartTime = System.currentTimeMillis()
        // Mark this toggle time - any fetch that started before this is stale
        lastToggleTimestamp.set(toggleStartTime)

        return try {
            val request = WorkerToggleRequest(isActive)
            Log.i(TAG, "Toggle API call: requesting isActive=$isActive (toggleTime=$toggleStartTime)")
            val response = apiService.toggleWorkerMode(request)
            Log.i(TAG, "Toggle API response: is_active=${response.isActive}, requested=$isActive, match=${response.isActive == isActive}")
            val entity = response.toEntity()

            // Toggle response is always authoritative - no stale check needed
            workerProfileDao.insert(entity)
            Log.i(TAG, "Toggle saved to DB: isActive=${entity.isActive}")

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
    // Worker Heartbeat (Worker-level, not Task-level)
    // ========================================================================

    /**
     * Send worker-level heartbeat to backend.
     *
     * This indicates the worker is alive and available for tasks,
     * independent of whether they have any active tasks.
     * Should be called every 5 minutes while worker mode is active.
     */
    suspend fun sendWorkerHeartbeat(): Result<Unit> {
        return try {
            val response = apiService.workerHeartbeat()
            if (response.ok) {
                Log.d(TAG, "Worker heartbeat sent, last_active_at=${response.lastActiveAt}")
                Result.success(Unit)
            } else {
                Log.w(TAG, "Worker heartbeat returned ok=false")
                Result.failure(Exception("Heartbeat failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send worker heartbeat", e)
            Result.failure(e)
        }
    }

    /**
     * Notify backend that worker is going offline.
     *
     * Best-effort call when app is being closed.
     * Not guaranteed to complete (Android may kill process).
     */
    suspend fun sendWorkerOffline(): Result<Unit> {
        return try {
            val response = apiService.workerOffline()
            Log.i(TAG, "Worker offline notification sent")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send offline notification (expected)", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // Payouts
    // ========================================================================

    suspend fun requestPayout(
        amountRub: Float,
        method: String,
        currency: String,
        walletAddress: String
    ): Result<PayoutResponse> {
        return try {
            val response = apiService.requestPayout(
                PayoutRequestCreate(
                    amountRub = amountRub,
                    method = method,
                    currency = currency,
                    walletAddress = walletAddress
                )
            )
            Log.i(TAG, "Payout requested: $amountRub via $method")
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request payout", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // FCM Device Registration
    // ========================================================================

    /**
     * Register FCM token with backend.
     */
    suspend fun registerDeviceToken(fcmToken: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = RegisterDeviceRequest(
                    deviceFingerprint = getDeviceFingerprint(),
                    deviceModel = Build.MODEL,
                    androidVersion = Build.VERSION.RELEASE,
                    appVersion = BuildConfig.VERSION_NAME,
                    fcmToken = fcmToken
                )

                val response = apiService.registerDevice(request)
                Log.i(TAG, "FCM token registered: ${response.id}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register FCM token", e)
                false
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun getDeviceFingerprint(): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        return "${Build.MODEL}_${androidId}"
    }

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
            totalEarnedRub = totalEarned,
            pendingBalanceRub = pendingBalance,
            availableBalanceRub = availableBalance,
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
            amountRub = amountRub,
            status = status,
            createdAt = createdAt,
            approvedAt = approvedAt,
            paidAt = paidAt
        )
    }
}

package com.kotkit.basic.data.remote.api

import com.kotkit.basic.data.remote.api.models.*
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    // ========================================================================
    // Solo Mode Endpoints (existing)
    // ========================================================================

    @POST("api/v1/analyze")
    suspend fun analyze(@Body request: AnalyzeRequest): AnalyzeResponse

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    @POST("api/v1/auth/register")
    suspend fun register(@Body request: RegisterRequest): AuthResponse

    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): AuthResponse

    @GET("api/v1/license/status")
    suspend fun getLicenseStatus(): LicenseStatus

    @POST("api/v1/analytics/event")
    suspend fun trackEvent(@Body event: AnalyticsEvent): AnalyticsEventResponse

    @POST("api/v1/generate-caption")
    suspend fun generateCaption(@Body request: GenerateCaptionRequest): GenerateCaptionResponse

    @POST("api/v1/verify_feed")
    suspend fun verifyFeed(@Body request: VerifyFeedRequest): VerifyFeedResponse

    // ========================================================================
    // Network Mode - Worker Endpoints
    // ========================================================================

    @POST("api/v1/workers/register")
    suspend fun registerWorker(@Body request: WorkerRegisterRequest): WorkerResponse

    @GET("api/v1/workers/profile")
    suspend fun getWorkerProfile(): WorkerResponse

    @PATCH("api/v1/workers/profile")
    suspend fun updateWorkerProfile(@Body request: WorkerUpdateRequest): WorkerResponse

    @POST("api/v1/workers/toggle")
    suspend fun toggleWorkerMode(@Body request: WorkerToggleRequest): WorkerResponse

    @GET("api/v1/workers/stats")
    suspend fun getWorkerStats(): WorkerStatsResponse

    @POST("api/v1/workers/devices/register")
    suspend fun registerDevice(@Body request: RegisterDeviceRequest): WorkerDeviceResponse

    @GET("api/v1/workers/devices")
    suspend fun getWorkerDevices(): WorkerDeviceListResponse

    // ========================================================================
    // Network Mode - Task Endpoints
    // ========================================================================

    @GET("api/v1/tasks/available")
    suspend fun getAvailableTasks(
        @Query("limit") limit: Int = 10,
        @Query("category_id") categoryId: String? = null
    ): TaskListResponse

    @POST("api/v1/tasks/{task_id}/claim")
    suspend fun claimTask(@Path("task_id") taskId: String): TaskResponse

    @POST("api/v1/tasks/{task_id}/heartbeat")
    suspend fun sendHeartbeat(@Path("task_id") taskId: String): HeartbeatResponse

    @GET("api/v1/tasks/{task_id}/video-url")
    suspend fun getVideoUrl(@Path("task_id") taskId: String): TaskVideoUrlResponse

    @POST("api/v1/tasks/{task_id}/progress")
    suspend fun updateTaskProgress(
        @Path("task_id") taskId: String,
        @Body request: TaskProgressRequest
    ): TaskResponse

    @POST("api/v1/tasks/{task_id}/complete")
    suspend fun completeTask(
        @Path("task_id") taskId: String,
        @Body request: CompleteTaskRequest
    ): TaskResponse

    @POST("api/v1/tasks/{task_id}/fail")
    suspend fun failTask(
        @Path("task_id") taskId: String,
        @Body request: FailTaskRequest
    ): TaskResponse

    @GET("api/v1/tasks/active")
    suspend fun getActiveTasks(): TaskListResponse

    @GET("api/v1/tasks/history")
    suspend fun getTaskHistory(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): TaskListResponse

    // ========================================================================
    // Network Mode - Config Endpoints
    // ========================================================================

    @GET("api/v1/config/selectors")
    suspend fun getSelectorsConfig(
        @Query("app_version") appVersion: String? = null
    ): SelectorsConfigResponse

    @GET("api/v1/config/app")
    suspend fun getAppConfig(
        @Query("app_version") appVersion: String? = null
    ): AppConfigResponse

    @GET("api/v1/config/features")
    suspend fun getFeatureFlags(): FeatureFlagsResponse

    // ========================================================================
    // Network Mode - Payout Endpoints
    // ========================================================================

    @GET("api/v1/payouts/balance")
    suspend fun getBalance(): BalanceResponse

    @GET("api/v1/payouts/earnings")
    suspend fun getEarnings(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("status") status: String? = null
    ): EarningsListResponse

    @POST("api/v1/payouts/request")
    suspend fun requestPayout(@Body request: PayoutRequestCreate): PayoutResponse

    @GET("api/v1/payouts/history")
    suspend fun getPayoutHistory(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): PayoutListResponse

    // ========================================================================
    // Network Mode - Error Logging
    // ========================================================================

    @POST("api/v1/logs/error")
    suspend fun reportError(@Body request: ErrorLogRequest): ErrorLogResponse

    @POST("api/v1/logs/batch")
    suspend fun reportBatchErrors(@Body request: BatchLogRequest): BatchLogResponse

    // ========================================================================
    // Network Mode - Verification Endpoints (Post & Check anti-fraud)
    // ========================================================================

    @GET("api/v1/verification/pending")
    suspend fun getPendingVerifications(
        @Query("limit") limit: Int = 5
    ): VerificationListResponse

    @POST("api/v1/verification/{verification_id}/claim")
    suspend fun claimVerification(
        @Path("verification_id") verificationId: String,
        @Query("device_id") deviceId: String? = null
    ): VerificationClaimResponse

    @POST("api/v1/verification/{verification_id}/complete")
    suspend fun completeVerification(
        @Path("verification_id") verificationId: String,
        @Body request: VerificationCompleteRequest
    ): VerificationCompleteResponse

    @GET("api/v1/verification/stats")
    suspend fun getVerificationStats(): VerificationStatsResponse
}

package com.autoposter.data.remote.api

import com.autoposter.data.remote.api.models.*
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {

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
    suspend fun trackEvent(@Body event: AnalyticsEvent)
}

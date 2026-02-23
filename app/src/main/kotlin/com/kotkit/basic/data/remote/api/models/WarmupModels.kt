package com.kotkit.basic.data.remote.api.models

import com.google.gson.annotations.SerializedName

data class WarmupReportRequest(
    val status: String, // "completed", "cancelled", "failed"
    @SerializedName("videos_watched") val videosWatched: Int,
    @SerializedName("likes_given") val likesGiven: Int,
    @SerializedName("duration_seconds") val durationSeconds: Int,
    @SerializedName("vlm_recovery_calls") val vlmRecoveryCalls: Int,
    @SerializedName("popups_dismissed") val popupsDismissed: Int,
    @SerializedName("error_message") val errorMessage: String?
)

data class WarmupReportResponse(
    val id: String,
    val status: String
)

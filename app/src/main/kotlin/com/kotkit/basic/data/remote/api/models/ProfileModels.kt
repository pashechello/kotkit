package com.kotkit.basic.data.remote.api.models

import com.google.gson.annotations.SerializedName

/**
 * Response from GET /api/v1/user/profile
 */
data class ProfileResponse(
    val id: String,
    val email: String,
    val name: String?,
    @SerializedName("is_brand") val isBrand: Boolean = false,
    @SerializedName("company_name") val companyName: String? = null,
    @SerializedName("company_website") val companyWebsite: String? = null,
    @SerializedName("balance_rub") val balanceRub: Double = 0.0,
    val role: String = "user",
    @SerializedName("audience_persona") val audiencePersona: String = "GENERAL",
    @SerializedName("created_at") val createdAt: Long
)

/**
 * Request for PATCH /api/v1/user/profile
 */
data class UpdateProfileRequest(
    val name: String? = null,
    @SerializedName("is_brand") val isBrand: Boolean? = null,
    @SerializedName("company_name") val companyName: String? = null,
    @SerializedName("company_website") val companyWebsite: String? = null,
    @SerializedName("audience_persona") val audiencePersona: String? = null
)

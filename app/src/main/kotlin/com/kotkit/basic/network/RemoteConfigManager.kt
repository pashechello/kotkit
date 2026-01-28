package com.kotkit.basic.network

import android.util.Log
import com.google.gson.Gson
import com.kotkit.basic.BuildConfig
import com.kotkit.basic.data.local.db.SelectorsConfigDao
import com.kotkit.basic.data.local.db.entities.SelectorsConfigEntity
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.AppConfigResponse
import com.kotkit.basic.data.remote.api.models.FeatureFlagsResponse
import com.kotkit.basic.data.remote.api.models.SelectorsConfigResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages remote configuration from the backend.
 *
 * Responsibilities:
 * - Fetch and cache TikTok UI selectors
 * - Fetch app configuration (limits, timeouts)
 * - Fetch feature flags for A/B testing
 * - Version checking and force update detection
 */
@Singleton
class RemoteConfigManager @Inject constructor(
    private val apiService: ApiService,
    private val selectorsConfigDao: SelectorsConfigDao,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "RemoteConfigManager"
    }

    // In-memory caches
    private var appConfig: AppConfigResponse? = null
    private var featureFlags: FeatureFlagsResponse? = null

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _needsForceUpdate = MutableStateFlow(false)
    val needsForceUpdate: StateFlow<Boolean> = _needsForceUpdate.asStateFlow()

    /**
     * Initialize remote config on app startup.
     * Loads cached config and refreshes from server.
     */
    suspend fun initialize() {
        Log.i(TAG, "Initializing remote config")

        try {
            // Load cached selectors
            val cached = selectorsConfigDao.getConfig()
            if (cached != null) {
                Log.i(TAG, "Loaded cached selectors version ${cached.version}")
            }

            // Refresh from server (non-blocking errors)
            refreshSelectorsConfig()
            refreshAppConfig()
            refreshFeatureFlags()

            _isInitialized.value = true
            Log.i(TAG, "Remote config initialized")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize remote config", e)
            // Still mark as initialized with cached/default values
            _isInitialized.value = true
        }
    }

    // ========================================================================
    // Selectors Config
    // ========================================================================

    /**
     * Observe selectors config changes.
     */
    fun getSelectorsConfigFlow(): Flow<SelectorsConfigEntity?> =
        selectorsConfigDao.getConfigFlow()

    /**
     * Get current selectors config.
     */
    suspend fun getSelectorsConfig(): SelectorsConfigEntity? =
        selectorsConfigDao.getConfig()

    /**
     * Refresh selectors from server.
     */
    suspend fun refreshSelectorsConfig(): Result<SelectorsConfigEntity> {
        return try {
            val response = apiService.getSelectorsConfig(BuildConfig.VERSION_NAME)
            val entity = response.toEntity()
            selectorsConfigDao.insert(entity)
            Log.i(TAG, "Selectors updated to version ${response.version}")
            Result.success(entity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh selectors", e)
            Result.failure(e)
        }
    }

    /**
     * Get selector by ID.
     */
    suspend fun getSelector(id: String): String? {
        val config = selectorsConfigDao.getConfig() ?: return null
        val selectors = parseSelectorsMap(config.selectorsJson)
        return selectors[id]
    }

    /**
     * Get content description by ID.
     */
    suspend fun getContentDescription(id: String): String? {
        val config = selectorsConfigDao.getConfig() ?: return null
        val descriptions = parseSelectorsMap(config.contentDescriptionsJson)
        return descriptions[id]
    }

    /**
     * Get timeout value.
     */
    suspend fun getTimeout(type: TimeoutType): Int {
        val config = selectorsConfigDao.getConfig() ?: return type.defaultValue
        return when (type) {
            TimeoutType.PAGE_LOAD -> config.pageLoadMs
            TimeoutType.ELEMENT_WAIT -> config.elementWaitMs
            TimeoutType.UPLOAD -> config.uploadTimeoutMs
            TimeoutType.POST_CONFIRM -> config.postConfirmMs
        }
    }

    // ========================================================================
    // App Config
    // ========================================================================

    /**
     * Get current app config.
     */
    fun getAppConfig(): AppConfigResponse? = appConfig

    /**
     * Refresh app config from server.
     */
    suspend fun refreshAppConfig(): Result<AppConfigResponse> {
        return try {
            val response = apiService.getAppConfig(BuildConfig.VERSION_NAME)
            appConfig = response

            // Check for force update
            if (response.forceUpdate && isVersionLower(BuildConfig.VERSION_NAME, response.minSupportedVersion)) {
                Log.w(TAG, "Force update required! Min version: ${response.minSupportedVersion}")
                _needsForceUpdate.value = true
            }

            Log.i(TAG, "App config updated: maxDailyPosts=${response.maxDailyPosts}")
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh app config", e)
            Result.failure(e)
        }
    }

    /**
     * Get max daily posts limit.
     */
    fun getMaxDailyPosts(): Int = appConfig?.maxDailyPosts ?: 5

    /**
     * Get task timeout in minutes.
     */
    fun getTaskTimeoutMinutes(): Int = appConfig?.taskTimeoutMinutes ?: 30

    /**
     * Get cooldown range.
     */
    fun getCooldownRange(): Pair<Int, Int> {
        val min = appConfig?.cooldownMinutesMin ?: 5
        val max = appConfig?.cooldownMinutesMax ?: 15
        return min to max
    }

    // ========================================================================
    // Feature Flags
    // ========================================================================

    /**
     * Refresh feature flags from server.
     */
    suspend fun refreshFeatureFlags(): Result<FeatureFlagsResponse> {
        return try {
            val response = apiService.getFeatureFlags()
            featureFlags = response
            Log.i(TAG, "Feature flags updated: ${response.features.size} features, ${response.experiments.size} experiments")
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh feature flags", e)
            Result.failure(e)
        }
    }

    /**
     * Check if a feature is enabled.
     */
    fun isFeatureEnabled(key: String): Boolean =
        featureFlags?.features?.get(key) ?: false

    /**
     * Check if an experiment is enabled.
     */
    fun isExperimentEnabled(key: String): Boolean =
        featureFlags?.experiments?.get(key) ?: false

    /**
     * Check if network mode is enabled.
     */
    fun isNetworkModeEnabled(): Boolean =
        isFeatureEnabled("network_mode_enabled")

    /**
     * Check if error reporting is enabled.
     */
    fun isErrorReportingEnabled(): Boolean =
        isFeatureEnabled("error_reporting_enabled")

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun SelectorsConfigResponse.toEntity(): SelectorsConfigEntity {
        return SelectorsConfigEntity(
            version = version,
            updatedAt = updatedAt,
            selectorsJson = gson.toJson(selectors),
            contentDescriptionsJson = gson.toJson(contentDescriptions),
            pageLoadMs = timeouts.pageLoadMs,
            elementWaitMs = timeouts.elementWaitMs,
            uploadTimeoutMs = timeouts.uploadTimeoutMs,
            postConfirmMs = timeouts.postConfirmMs
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSelectorsMap(json: String): Map<String, String> {
        return try {
            gson.fromJson(json, Map::class.java) as? Map<String, String> ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse selectors map", e)
            emptyMap()
        }
    }

    private fun isVersionLower(current: String, required: String): Boolean {
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val requiredParts = required.split(".").map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(currentParts.size, requiredParts.size)) {
            val c = currentParts.getOrElse(i) { 0 }
            val r = requiredParts.getOrElse(i) { 0 }
            if (c < r) return true
            if (c > r) return false
        }
        return false
    }
}

/**
 * Timeout types with default values.
 */
enum class TimeoutType(val defaultValue: Int) {
    PAGE_LOAD(10_000),
    ELEMENT_WAIT(5_000),
    UPLOAD(120_000),
    POST_CONFIRM(30_000)
}

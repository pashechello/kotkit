package com.kotkit.basic.data.repository

import android.content.Context
import timber.log.Timber
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kotkit.basic.data.local.db.SelectorsConfigDao
import com.kotkit.basic.data.local.db.entities.SelectorsConfigEntity
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.AppConfigResponse
import com.kotkit.basic.data.remote.api.models.FeatureFlagsResponse
import com.kotkit.basic.data.remote.api.models.SelectorsConfigResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val selectorsConfigDao: SelectorsConfigDao,
    private val apiService: ApiService
) {
    companion object {
        private const val TAG = "ConfigRepository"
        private const val PREFS_NAME = "network_config"
        private const val KEY_APP_CONFIG = "app_config"
        private const val KEY_FEATURE_FLAGS = "feature_flags"
        private const val CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour
    }

    private val gson = Gson()
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // In-memory cache
    private var cachedAppConfig: AppConfigResponse? = null
    private var cachedFeatureFlags: FeatureFlagsResponse? = null

    // ========================================================================
    // Selectors Config (TikTok UI automation)
    // ========================================================================

    fun getSelectorsConfigFlow(): Flow<SelectorsConfigEntity?> =
        selectorsConfigDao.getConfigFlow()

    suspend fun getSelectorsConfig(): SelectorsConfig? {
        // Try cached first
        val cached = selectorsConfigDao.getConfig()
        if (cached != null && !isStale(cached.fetchedAt)) {
            return cached.toSelectorsConfig()
        }

        // Fetch from API
        return try {
            val response = apiService.getSelectorsConfig()
            val entity = response.toEntity()
            selectorsConfigDao.insert(entity)
            Timber.tag(TAG).i("Selectors config fetched: version=${response.version}")
            entity.toSelectorsConfig()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to fetch selectors, using cached")
            // Return cached or embedded fallback
            cached?.toSelectorsConfig() ?: getEmbeddedSelectorsConfig()
        }
    }

    suspend fun fetchSelectorsConfig(): Result<SelectorsConfig> {
        return try {
            val response = apiService.getSelectorsConfig()
            val entity = response.toEntity()
            selectorsConfigDao.insert(entity)
            Timber.tag(TAG).i("Selectors config updated: version=${response.version}")
            Result.success(entity.toSelectorsConfig())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to fetch selectors config")
            Result.failure(e)
        }
    }

    // ========================================================================
    // App Config
    // ========================================================================

    suspend fun getAppConfig(): AppConfigResponse? {
        // In-memory cache
        cachedAppConfig?.let { return it }

        // SharedPrefs cache
        val cached = prefs.getString(KEY_APP_CONFIG, null)
        if (cached != null) {
            try {
                val config = gson.fromJson(cached, AppConfigResponse::class.java)
                cachedAppConfig = config
                return config
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to parse cached app config")
            }
        }

        // Fetch from API
        return fetchAppConfig().getOrNull()
    }

    suspend fun fetchAppConfig(): Result<AppConfigResponse> {
        return try {
            val config = apiService.getAppConfig()
            // Cache
            cachedAppConfig = config
            prefs.edit().putString(KEY_APP_CONFIG, gson.toJson(config)).apply()
            Timber.tag(TAG).i("App config fetched: version=${config.version}")
            Result.success(config)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to fetch app config")
            Result.failure(e)
        }
    }

    // ========================================================================
    // Feature Flags
    // ========================================================================

    suspend fun getFeatureFlags(): FeatureFlagsResponse? {
        cachedFeatureFlags?.let { return it }

        val cached = prefs.getString(KEY_FEATURE_FLAGS, null)
        if (cached != null) {
            try {
                val flags = gson.fromJson(cached, FeatureFlagsResponse::class.java)
                cachedFeatureFlags = flags
                return flags
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to parse cached feature flags")
            }
        }

        return fetchFeatureFlags().getOrNull()
    }

    suspend fun fetchFeatureFlags(): Result<FeatureFlagsResponse> {
        return try {
            val flags = apiService.getFeatureFlags()
            cachedFeatureFlags = flags
            prefs.edit().putString(KEY_FEATURE_FLAGS, gson.toJson(flags)).apply()
            Timber.tag(TAG).i("Feature flags fetched: ${flags.features.size} features")
            Result.success(flags)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to fetch feature flags")
            Result.failure(e)
        }
    }

    fun isFeatureEnabled(featureName: String): Boolean {
        return cachedFeatureFlags?.features?.get(featureName) ?: false
    }

    fun isExperimentEnabled(experimentName: String): Boolean {
        return cachedFeatureFlags?.experiments?.get(experimentName) ?: false
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun isStale(fetchedAt: Long): Boolean {
        return System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS
    }

    private fun SelectorsConfigResponse.toEntity(): SelectorsConfigEntity {
        return SelectorsConfigEntity(
            version = version,
            updatedAt = updatedAt,
            selectorsJson = gson.toJson(selectors),
            contentDescriptionsJson = gson.toJson(contentDescriptions),
            pageLoadMs = timeouts.pageLoadMs,
            elementWaitMs = timeouts.elementWaitMs,
            uploadTimeoutMs = timeouts.uploadTimeoutMs,
            postConfirmMs = timeouts.postConfirmMs,
            fetchedAt = System.currentTimeMillis()
        )
    }

    private fun SelectorsConfigEntity.toSelectorsConfig(): SelectorsConfig {
        val selectorsType = object : TypeToken<Map<String, String>>() {}.type
        return SelectorsConfig(
            version = version,
            selectors = gson.fromJson(selectorsJson, selectorsType),
            contentDescriptions = gson.fromJson(contentDescriptionsJson, selectorsType),
            pageLoadMs = pageLoadMs,
            elementWaitMs = elementWaitMs,
            uploadTimeoutMs = uploadTimeoutMs,
            postConfirmMs = postConfirmMs
        )
    }

    private fun getEmbeddedSelectorsConfig(): SelectorsConfig {
        // Fallback embedded config
        return SelectorsConfig(
            version = "embedded.1.0.0",
            selectors = mapOf(
                "nav_home" to "com.zhiliaoapp.musically:id/b8z",
                "nav_create" to "com.zhiliaoapp.musically:id/b91",
                "btn_upload" to "com.zhiliaoapp.musically:id/d4r",
                "btn_next" to "com.zhiliaoapp.musically:id/d6e",
                "btn_post" to "com.zhiliaoapp.musically:id/d7a",
                "input_caption" to "com.zhiliaoapp.musically:id/cm6"
            ),
            contentDescriptions = mapOf(
                "create_button" to "Create",
                "upload_button" to "Upload",
                "post_button" to "Post",
                "next_button" to "Next"
            ),
            pageLoadMs = 5000,
            elementWaitMs = 3000,
            uploadTimeoutMs = 120000,
            postConfirmMs = 30000
        )
    }
}

/**
 * Parsed selectors config for use in PostingAgent.
 */
data class SelectorsConfig(
    val version: String,
    val selectors: Map<String, String>,
    val contentDescriptions: Map<String, String>,
    val pageLoadMs: Int,
    val elementWaitMs: Int,
    val uploadTimeoutMs: Int,
    val postConfirmMs: Int
) {
    fun getSelector(key: String): String? = selectors[key]
    fun getContentDescription(key: String): String? = contentDescriptions[key]
}

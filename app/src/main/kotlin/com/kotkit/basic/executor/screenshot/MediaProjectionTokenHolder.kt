package com.kotkit.basic.executor.screenshot

import android.content.Intent
import timber.log.Timber

/**
 * Holds the MediaProjection consent token (resultCode + data Intent).
 *
 * Used on API 29 where AccessibilityService.takeScreenshot() is unavailable.
 * The token is obtained once via MediaProjectionConsentActivity when Worker Mode starts.
 * It is consumed by MediaProjectionScreenshot.initialize() to create a cached MediaProjection.
 *
 * Thread-safe: all fields are @Volatile.
 */
object MediaProjectionTokenHolder {
    private const val TAG = "MPTokenHolder"

    @Volatile
    var resultCode: Int = 0
        private set

    @Volatile
    var data: Intent? = null
        private set

    val hasToken: Boolean
        get() = data != null && resultCode != 0

    fun store(resultCode: Int, data: Intent) {
        this.resultCode = resultCode
        this.data = data.clone() as Intent
        Timber.tag(TAG).i("MediaProjection token stored (resultCode=$resultCode)")
    }

    fun clear() {
        resultCode = 0
        data = null
        Timber.tag(TAG).i("MediaProjection token cleared")
    }
}

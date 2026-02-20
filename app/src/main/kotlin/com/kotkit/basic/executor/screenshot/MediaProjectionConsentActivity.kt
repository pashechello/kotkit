package com.kotkit.basic.executor.screenshot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import timber.log.Timber

/**
 * Transparent activity that requests MediaProjection consent from the user.
 *
 * MediaProjection requires startActivityForResult() which needs an Activity context.
 * This activity:
 * 1. Launches the system consent dialog ("Allow screen recording?")
 * 2. Stores the result token in MediaProjectionTokenHolder
 * 3. Finishes itself immediately
 *
 * Used only on API 29 where AccessibilityService.takeScreenshot() is unavailable.
 */
class MediaProjectionConsentActivity : Activity() {

    companion object {
        private const val TAG = "MPConsentActivity"
        private const val REQUEST_CODE_CAPTURE = 1001

        @Volatile
        var onConsentResult: ((granted: Boolean) -> Unit)? = null

        fun start(context: Context, onResult: (granted: Boolean) -> Unit) {
            onConsentResult = onResult
            val intent = Intent(context, MediaProjectionConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.tag(TAG).i("Requesting MediaProjection consent")

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_CAPTURE) {
            val granted = resultCode == RESULT_OK && data != null
            if (granted) {
                MediaProjectionTokenHolder.store(resultCode, data!!)
                Timber.tag(TAG).i("MediaProjection consent GRANTED")
            } else {
                Timber.tag(TAG).w("MediaProjection consent DENIED (resultCode=$resultCode)")
            }

            onConsentResult?.invoke(granted)
            onConsentResult = null
        }

        finish()
    }
}

package com.kotkit.basic.proxy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import timber.log.Timber

/**
 * Transparent activity that requests VPN consent via [VpnService.prepare].
 *
 * Android requires the consent dialog to be launched from an Activity context.
 * This mirrors the [com.kotkit.basic.executor.screenshot.MediaProjectionConsentActivity]
 * pattern already used for screenshot consent.
 *
 * Usage:
 *   VpnConsentActivity.start(context) { granted ->
 *       if (granted) startWorkerService()
 *   }
 *
 * If already consented ([VpnService.prepare] returns null), the callback fires
 * immediately with granted=true without showing any dialog.
 */
class VpnConsentActivity : Activity() {

    companion object {
        private const val TAG = "VpnConsentActivity"
        private const val REQUEST_CODE_VPN = 1002

        @Volatile
        var onConsentResult: ((granted: Boolean) -> Unit)? = null

        fun start(context: Context, onResult: (granted: Boolean) -> Unit) {
            val consentIntent = VpnService.prepare(context)
            if (consentIntent == null) {
                // Already consented — no dialog needed
                Timber.tag(TAG).i("VPN already consented, skipping dialog")
                onResult(true)
                return
            }
            onConsentResult = onResult
            val intent = Intent(context, VpnConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.tag(TAG).i("Requesting VPN consent")

        val consentIntent = VpnService.prepare(this)
        if (consentIntent == null) {
            // Already consented (race: another call consented between start() and here)
            Timber.tag(TAG).i("VPN already consented inside Activity")
            onConsentResult?.invoke(true)
            onConsentResult = null
            finish()
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(consentIntent, REQUEST_CODE_VPN)
        }
    }

    override fun onDestroy() {
        // Null out the static callback to prevent memory leaks if the Activity is
        // destroyed before onActivityResult fires (e.g. process kill during the dialog).
        if (onConsentResult != null) {
            Timber.tag(TAG).w("VpnConsentActivity destroyed before result — clearing callback")
            onConsentResult = null
        }
        super.onDestroy()
    }

    @Deprecated("Using deprecated onActivityResult for minSdk 26 compat")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_VPN) {
            val granted = resultCode == RESULT_OK
            if (granted) {
                Timber.tag(TAG).i("VPN consent GRANTED")
            } else {
                Timber.tag(TAG).w("VPN consent DENIED (resultCode=$resultCode)")
            }
            onConsentResult?.invoke(granted)
            onConsentResult = null
        }

        finish()
    }
}

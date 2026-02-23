package com.kotkit.basic.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log

/**
 * Debug-only receiver for testing AudioMuter behavior via ADB.
 *
 * Usage:
 *   adb shell am broadcast -a com.kotkit.basic.DEBUG_MUTE
 *   adb shell am broadcast -a com.kotkit.basic.DEBUG_RESTORE
 *
 * Only included in debug builds (src/debug/).
 */
class DebugAudioTestReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DebugAudioTest"

        private val STREAMS = intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_SYSTEM
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        when (intent.action) {
            "com.kotkit.basic.DEBUG_MUTE" -> {
                Log.i(TAG, "=== MUTING ALL STREAMS ===")
                for (stream in STREAMS) {
                    val before = audioManager.getStreamVolume(stream)
                    try {
                        audioManager.setStreamVolume(stream, 0, 0)
                        val after = audioManager.getStreamVolume(stream)
                        Log.i(TAG, "Stream $stream: $before -> $after")
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Stream $stream: SecurityException (DND) - ${e.message}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Stream $stream: ${e.javaClass.simpleName} - ${e.message}")
                    }
                }
                Log.i(TAG, "=== MUTE COMPLETE ===")
            }

            "com.kotkit.basic.DEBUG_RESTORE" -> {
                Log.i(TAG, "=== RESTORING STREAMS ===")
                for (stream in STREAMS) {
                    val maxVol = audioManager.getStreamMaxVolume(stream)
                    val target = maxVol / 2 // restore to 50%
                    try {
                        audioManager.setStreamVolume(stream, target, 0)
                        val after = audioManager.getStreamVolume(stream)
                        Log.i(TAG, "Stream $stream: restored to $after (max=$maxVol)")
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Stream $stream: SecurityException (DND) - ${e.message}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Stream $stream: ${e.javaClass.simpleName} - ${e.message}")
                    }
                }
                Log.i(TAG, "=== RESTORE COMPLETE ===")
            }
        }
    }
}

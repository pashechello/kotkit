package com.kotkit.basic.executor.screen

import android.content.Context
import android.media.AudioManager
import timber.log.Timber
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mutes device audio streams before posting and restores them after.
 *
 * Prevents TikTok sounds and system tap sounds from playing during automated posting.
 * Works for both Personal Mode and Worker Mode.
 *
 * Thread-safe: all methods are @Synchronized since this is a @Singleton
 * that could be called from concurrent coroutines (PostWorker + NetworkTaskWorker).
 */
@Singleton
class AudioMuter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AudioMuter"

        private val STREAMS_TO_MUTE = intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_SYSTEM
        )
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedVolumes: Map<Int, Int>? = null

    @Synchronized
    fun muteAll() {
        if (savedVolumes != null) {
            Timber.tag(TAG).w("Already muted, skipping")
            return
        }

        try {
            val volumes = mutableMapOf<Int, Int>()
            for (stream in STREAMS_TO_MUTE) {
                volumes[stream] = audioManager.getStreamVolume(stream)
                audioManager.setStreamVolume(stream, 0, 0)
            }
            savedVolumes = volumes
            Timber.tag(TAG).i("Muted all streams (saved: $volumes)")
        } catch (e: SecurityException) {
            // MIUI/Android requires DND access permission to change some stream volumes
            Timber.tag(TAG).w("Cannot mute: ${e.message} (DND access needed)")
        } catch (e: Exception) {
            // EMUI: IllegalArgumentException for STREAM_NOTIFICATION under notification policy
            Timber.tag(TAG).w("Cannot mute stream (${e.javaClass.simpleName}): ${e.message}")
        }
    }

    @Synchronized
    fun restoreAll() {
        val volumes = savedVolumes ?: return
        try {
            for ((stream, volume) in volumes) {
                audioManager.setStreamVolume(stream, volume, 0)
            }
            Timber.tag(TAG).i("Restored all streams: $volumes")
        } catch (e: SecurityException) {
            Timber.tag(TAG).w("Cannot restore: ${e.message}")
        } catch (e: Exception) {
            Timber.tag(TAG).w("Cannot restore stream (${e.javaClass.simpleName}): ${e.message}")
        }
        savedVolumes = null
    }
}

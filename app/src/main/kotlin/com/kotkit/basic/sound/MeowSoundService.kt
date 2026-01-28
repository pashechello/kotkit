package com.kotkit.basic.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.kotkit.basic.data.local.preferences.EncryptedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for playing meow sounds during app lifecycle events.
 *
 * Features:
 * - Proper audio focus management
 * - Volume control based on settings
 * - Vibration patterns
 * - Resource cleanup
 *
 * Thread-safe: All public methods can be called from any thread.
 */
@Singleton
class MeowSoundService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: EncryptedPreferences
) {
    companion object {
        private const val TAG = "MeowSoundService"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    @Volatile
    private var currentPlayer: MediaPlayer? = null
    @Volatile
    private var audioFocusRequest: AudioFocusRequest? = null

    private val playerLock = Any()

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * Play warning sound (before scheduled posting).
     */
    suspend fun playWarning() = play(SoundType.MEOW_WARNING)

    /**
     * Play starting sound (posting begins).
     */
    suspend fun playStarting() = play(SoundType.MEOW_STARTING)

    /**
     * Play success sound (posting completed).
     */
    suspend fun playSuccess() = play(SoundType.MEOW_SUCCESS)

    /**
     * Play error sound (posting failed or interrupted).
     */
    suspend fun playError() = play(SoundType.MEOW_ERROR)

    /**
     * Play UI sound (positive UI interactions).
     */
    suspend fun playUI() = play(SoundType.MEOW_UI)

    /**
     * Play a specific sound type.
     */
    suspend fun play(soundType: SoundType) = withContext(Dispatchers.Main) {
        val settings = preferences.soundSettings

        if (!settings.isEnabled(soundType)) {
            Log.d(TAG, "Sound ${soundType.name} is disabled, skipping")
            return@withContext
        }

        synchronized(playerLock) {
            try {
                // Stop any currently playing sound
                stopCurrentSound()

                // Request audio focus
                requestAudioFocus()

                // Create and configure MediaPlayer
                val player = MediaPlayer.create(context, soundType.rawResId)
                if (player == null) {
                    Log.w(TAG, "Could not create MediaPlayer for ${soundType.name}")
                    return@synchronized
                }

                currentPlayer = player.apply {
                    setAudioAttributes(audioAttributes)
                    val volume = settings.getVolumeFloat()
                    setVolume(volume, volume)

                    setOnCompletionListener { mp ->
                        synchronized(playerLock) {
                            mp.release()
                            abandonAudioFocus()
                            if (currentPlayer == mp) currentPlayer = null
                        }
                    }

                    setOnErrorListener { mp, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                        synchronized(playerLock) {
                            mp.release()
                            abandonAudioFocus()
                            if (currentPlayer == mp) currentPlayer = null
                        }
                        true
                    }

                    start()
                }

                Log.i(TAG, "Playing sound: ${soundType.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Error playing sound ${soundType.name}", e)
            }
        }

        // Vibrate outside of lock
        if (settings.vibrationEnabled) {
            vibrate(soundType.vibrationPattern)
        }
    }

    /**
     * Play sound synchronously (blocking). Use for notifications.
     */
    fun playSync(soundType: SoundType) {
        val settings = preferences.soundSettings

        if (!settings.isEnabled(soundType)) {
            return
        }

        var player: MediaPlayer? = null
        try {
            player = MediaPlayer.create(context, soundType.rawResId)
            if (player == null) {
                Log.w(TAG, "Could not create MediaPlayer for ${soundType.name}")
                return
            }

            player.apply {
                setAudioAttributes(audioAttributes)
                val volume = settings.getVolumeFloat()
                setVolume(volume, volume)
                setOnCompletionListener { mp -> mp.release() }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer sync error: what=$what, extra=$extra")
                    mp.release()
                    true
                }
                start()
            }

            if (settings.vibrationEnabled) {
                vibrate(soundType.vibrationPattern)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing sound sync ${soundType.name}", e)
            player?.release()
        }
    }

    /**
     * Get URI for notification sound.
     */
    fun getSoundUri(soundType: SoundType): Uri {
        return Uri.parse("android.resource://${context.packageName}/${soundType.rawResId}")
    }

    private fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* Ignore changes */ }
                .build()

            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioFocusRequest = null
    }

    private fun vibrate(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not vibrate", e)
        }
    }

    private fun stopCurrentSound() {
        currentPlayer?.apply {
            try {
                if (isPlaying) {
                    stop()
                }
                release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping current player", e)
            }
        }
        currentPlayer = null
        abandonAudioFocus()
    }

    /**
     * Preview a sound (for settings screen).
     */
    suspend fun preview(soundType: SoundType) = withContext(Dispatchers.Main) {
        try {
            stopCurrentSound()
            requestAudioFocus()

            currentPlayer = MediaPlayer.create(context, soundType.rawResId)?.apply {
                setAudioAttributes(audioAttributes)
                val volume = preferences.soundSettings.getVolumeFloat()
                setVolume(volume, volume)

                setOnCompletionListener {
                    release()
                    abandonAudioFocus()
                    currentPlayer = null
                }

                start()
            }

            // Also vibrate for preview
            vibrate(soundType.vibrationPattern)
        } catch (e: Exception) {
            Log.e(TAG, "Error previewing sound ${soundType.name}", e)
        }
    }

    /**
     * Clean up resources.
     */
    fun release() {
        stopCurrentSound()
    }
}

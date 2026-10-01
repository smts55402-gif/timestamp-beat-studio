package com.timestampbeatstudio.app.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Thin [MediaPlayer] wrapper for the result screen.
 *
 * Emits [onSecondChanged] with the whole-second playback position while playing
 * (polled every 250 ms) so the UI can highlight the active second.
 */
class AudioPlayer(context: Context) {

    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    /** Called with the current whole second while playing, and on manual seeks. */
    var onSecondChanged: ((Int) -> Unit)? = null

    /** Called when playback starts/stops (pause, or natural end of audio). */
    var onPlayingChanged: ((Boolean) -> Unit)? = null

    /** Prepares [uri] for playback. Must be called off the main thread (blocking prepare). */
    fun load(uri: Uri) {
        releasePlayer()
        player = MediaPlayer().apply {
            setDataSource(appContext, uri)
            prepare()
        }
        onSecondChanged?.invoke(0)
    }

    fun play() {
        player?.start()
        onPlayingChanged?.invoke(true)
        startPolling()
    }

    fun pause() {
        player?.pause()
        onPlayingChanged?.invoke(false)
        pollJob?.cancel()
        pollJob = null
    }

    /** Seeks to a whole second; tapping a timestamp jumps the audio to that second. */
    fun seekTo(seconds: Int) {
        val p = player ?: return
        p.seekTo((seconds * 1000).coerceIn(0, p.duration))
        onSecondChanged?.invoke(seconds)
    }

    fun durationSeconds(): Int = ((player?.duration ?: 0) / 1000).coerceAtLeast(0)

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun currentSecond(): Int = ((player?.currentPosition ?: 0) / 1000).coerceAtLeast(0)

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var lastSecond = -1
            while (isActive) {
                val p = player
                if (p == null || !p.isPlaying) break
                val second = (p.currentPosition / 1000).coerceAtLeast(0)
                if (second != lastSecond) {
                    lastSecond = second
                    onSecondChanged?.invoke(second)
                }
                delay(250)
            }
            onPlayingChanged?.invoke(false)
        }
    }

    private fun releasePlayer() {
        pollJob?.cancel()
        pollJob = null
        runCatching { player?.release() }
        player = null
    }

    /** Releases the player and the internal polling scope. */
    fun release() {
        releasePlayer()
        scope.cancel()
    }
}

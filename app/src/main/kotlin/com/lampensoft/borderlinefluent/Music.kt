package com.lampensoft.borderlinefluent

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One track at a time: menu loop, a win or game-over sting, or silence during the interview. */
class Music(private val ctx: Context, private val scope: CoroutineScope) {
    private var player: MediaPlayer? = null
    private var track = 0
    private var volume = VOLUME
    var enabled = true
        set(on) { field = on; if (!on) stop() }

    fun play(res: Int, loop: Boolean, volume: Float = VOLUME) {
        if (!enabled) return
        if (res == track && player != null) return
        stop()
        track = res; this.volume = volume
        player = MediaPlayer.create(ctx, res)?.apply { isLooping = loop; setVolume(volume, volume); start() }
    }

    /** Fades out over ~0.6 s so the interview starts in quiet without a hard cut. */
    fun fadeOut() {
        val p = player ?: return
        player = null; track = 0
        val v = volume
        scope.launch {
            for (i in 9 downTo 0) { runCatching { p.setVolume(v * i / 10, v * i / 10) }; delay(60) }
            p.release()
        }
    }

    /** Playback position in ms of the playing track (wraps when looping), or null when nothing plays. */
    fun position(): Long? = runCatching { player?.takeIf { it.isPlaying }?.currentPosition?.toLong() }.getOrNull()

    companion object {
        /**
         * Loudness plan (2026-10-05; the res/raw files carry the balance):
         * music beds ~-16 LUFS and ambience -25.6 LUFS in the files, both played at [VOLUME] (-4.4 dB): music ~-20.5,
         * ambience -30 effective. SFX are set by momentary max in the files and play at [Sfx.GAIN]. The officer's voice
         * is normalized per sentence to [Piper.TARGET_RMS] (~-18 LUFS), on top of everything.
         */
        const val VOLUME = 0.6f
        /** result stings (win, over, broken): ~2 dB over the bed, as the owner had them */
        const val STING = 0.8f
    }

    fun stop() { player?.release(); player = null; track = 0 }
    fun pause() { runCatching { player?.takeIf { it.isPlaying }?.pause() } }
    fun resume() { runCatching { player?.start() } }
}

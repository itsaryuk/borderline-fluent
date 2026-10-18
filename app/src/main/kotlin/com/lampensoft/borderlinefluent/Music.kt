package com.lampensoft.borderlinefluent

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch
import java.io.File

internal enum class PrologueCue { SOFT, HARD, AIRPORT }

/** Derive the bed from story position, including a cold start at any persisted frame. */
internal fun cueFor(frame: Int, turn: Int): PrologueCue {
    require(frame in PROLOGUE_FRAMES.indices && turn in PROLOGUE_FRAMES[frame].indices)
    var cue = PrologueCue.SOFT
    for (f in 0..frame) {
        for (t in 0..(if (f == frame) turn else PROLOGUE_FRAMES[f].lastIndex)) {
            cue = when (PROLOGUE_FRAMES[f][t].text) {
                // Owner: soft until the realization ("That's when it hit me…"), hard from there to the end.
                R.string.prologue_realize -> PrologueCue.HARD
                else -> cue
            }
        }
    }
    return cue
}

/** Owns ordinary playback and the two position-preserving prologue beds. */
class Music(private val ctx: Context, private val scope: CoroutineScope) {
    private var player: LoopPlayer? = null
    private var track: Any = 0 // a res id, or a pack file ([play] with a File)
    private var volume = VOLUME
    private var paused = false
    private val fading = HashMap<LoopPlayer, Job>() // outgoing beds ramping to silence; stop() and pause() release them
    private var fadeJob: Job? = null
    private var prologue: PrologueMusic? = null
    private var leaving: PrologueMusic? = null // fading out under the next screen; stop() and pause() still cut it
    private var held = false // the music player paused the song by hand: app resume must not restart it
    var enabled = true
        set(on) { field = on; if (!on) stop() }
    /** Called with the res id (or the pack [File]) when a different track (or prologue bed) actually starts; not for a repeat play() of the same one. */
    var onTrackStart: ((Any) -> Unit)? = null

    /** [onEnd] runs when a non-looping track plays to its end. */
    fun play(res: Int, loop: Boolean, volume: Float = VOLUME, onEnd: (() -> Unit)? = null) = begin(res, loop, volume, onEnd)
    /** A music file from a language pack (Packs.musicFile), played like a res track. */
    /** [startMs]: where a fresh start begins (a repeat taxi ride drops in mid-song); ignored when the track is already playing. */
    fun play(file: File, loop: Boolean, volume: Float = VOLUME, onEnd: (() -> Unit)? = null, startMs: Int = 0) = begin(file, loop, volume, onEnd, startMs)

    private fun begin(res: Any, loop: Boolean, volume: Float, onEnd: (() -> Unit)?, startMs: Int = 0) {
        // Leaving the prologue: its bed plays on under the next screen and fades out (owner: "keep it until I'm going
        // home, then fade"); stop() and pause still cut at once.
        prologue?.let { beds -> prologue = null; leaving?.release(); leaving = beds; beds.fadeOutAll(); scope.launch { delay(2700); beds.release(); if (leaving === beds) leaving = null } }
        if (!enabled) return
        if (res == track && player != null) return
        // Crossfade (~0.8 s); a result sting starts at once and the outgoing bed leaves in ~0.25 s.
        val sting = volume == STING
        player?.let { fadeAway(it, if (sting) 250 else 800) }
        player = null; track = 0; fadeJob?.cancel()
        startTrack(res, loop, volume, fadeInMs = if (sting) 0 else 800, onEnd = onEnd, startMs = startMs)
        if (player != null) onTrackStart?.invoke(res)
    }

    /** The music player's pause button: holds or resumes the current track by hand. */
    fun hold(on: Boolean) {
        held = on
        if (on) player?.pause() else if (!paused) player?.start()
    }

    private fun startTrack(res: Any, loop: Boolean, volume: Float = VOLUME, fadeInMs: Long = 0, onEnd: (() -> Unit)? = null, startMs: Int = 0) {
        track = res; this.volume = volume; held = false
        val p = LoopPlayer(ctx, scope, res, loop).takeIf { it.ok } ?: return
        p.onEnd = onEnd
        if (startMs > 0) p.seek(startMs)
        player = p
        p.setVolume(if (fadeInMs > 0) 0f else volume)
        if (!paused) p.start()
        if (fadeInMs > 0) fadeJob = scope.launch { ramp(0f, volume, fadeInMs) { p.setVolume(it) } }
    }

    private suspend fun ramp(from: Float, to: Float, ms: Long, set: (Float) -> Unit) {
        val steps = (ms / 50).toInt().coerceAtLeast(1)
        for (i in 1..steps) { set(from + (to - from) * i / steps); delay(50) }
    }

    private fun fadeAway(p: LoopPlayer, ms: Long) {
        val v = volume
        fading[p] = scope.launch { ramp(v, 0f, ms) { p.setVolume(it) }; p.release(); fading.remove(p) }
    }

    internal fun prologue(cue: PrologueCue) {
        if (!enabled) return
        val beds = prologue ?: run {
            stop()
            PrologueMusic(ctx, scope).also { prologue = it; if (paused) it.pause() }
        }
        if (cue == PrologueCue.AIRPORT) {
            beds.fadeOutAll()
            if (track != R.raw.ambience_airport || player == null)
                startTrack(R.raw.ambience_airport, loop = true)
        } else {
            player?.release(); player = null; track = 0
            val before = beds.current
            beds.crossfade(cue)
            if (beds.current != before) onTrackStart?.invoke(if (cue == PrologueCue.SOFT) R.raw.music_prologue_soft else R.raw.music_prologue_hard)
        }
    }

    /** Fades out over ~0.6 s so the interview starts in quiet without a hard cut. */
    fun fadeOut() {
        val p = player ?: return
        fadeJob?.cancel()
        player = null; track = 0
        fadeAway(p, 600)
    }

    /** Silences the track at once without a fade, keeping its position (the Thalassia door). [fadeIn] brings it back. */
    fun cut() {
        fadeJob?.cancel()
        player?.apply { pause(); setVolume(0f) }
    }

    /** After a [silence] the cut track resumes from where it stopped and rises over [ms]. [pause] restores the volume, so a backgrounded app never stays muted. */
    fun fadeIn(ms: Long, silence: Long) {
        val p = player ?: return
        fadeJob?.cancel()
        fadeJob = scope.launch {
            delay(silence)
            if (!paused) p.start()
            ramp(0f, volume, ms) { p.setVolume(it) }
        }
    }

    /** Playback position in ms of the playing track (wraps when looping), or null when nothing plays. */
    fun position(): Long? = player?.takeIf { it.isPlaying }?.position()

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
        /** The bar track ("Walking on the Borderline") sits 3 dB over the other beds in its file: [VOLUME] - 3 dB, under the bartender. */
        const val BAR = 0.42f
        /** The taxi radio (pack songs, ~-15.4 LUFS, 0.9 dB under the bar track's file): about [BAR] + 1 dB, under the machine's voice. */
        const val TAXI = 0.47f
    }

    private fun releaseFading() { fading.forEach { (p, j) -> j.cancel(); p.release() }; fading.clear() }

    fun stop() {
        fadeJob?.cancel(); fadeJob = null
        releaseFading()
        prologue?.release(); prologue = null
        leaving?.release(); leaving = null
        player?.release(); player = null; track = 0
    }
    fun pause() {
        paused = true
        fadeJob?.cancel(); releaseFading()
        player?.setVolume(volume) // a cut or fade-in cancelled here must not leave it muted
        player?.takeIf { it.isPlaying }?.pause()
        prologue?.pause()
        leaving?.release(); leaving = null
    }
    fun resume() {
        paused = false
        if (!enabled) return
        if (!held) player?.start()
        prologue?.resume()
    }
}

/** All calls and the fade run on the owner's main-thread scope. */
private class PrologueMusic(private val ctx: Context, private val scope: CoroutineScope) {
    private val players = arrayOfNulls<LoopPlayer>(2)
    private val gains = floatArrayOf(0f, 0f)
    private var target: PrologueCue? = null
    private var paused = false
    private var fade: Job? = null
    val current get() = target

    // Both beds are prepared up front (owner heard a hitch at the harbour when HARD was created mid-crossfade).
    init {
        for (cue in listOf(PrologueCue.SOFT, PrologueCue.HARD)) players[cue.ordinal] = LoopPlayer(ctx, scope,
            if (cue == PrologueCue.SOFT) R.raw.music_prologue_soft else R.raw.music_prologue_hard, loop = true)
            .takeIf { it.ok }?.apply { setVolume(0f) }
    }

    fun crossfade(to: PrologueCue) {
        require(to != PrologueCue.AIRPORT)
        change(to)
    }

    fun fadeOutAll() = change(PrologueCue.AIRPORT)

    private fun change(to: PrologueCue) {
        if (target == to) return
        target = to
        fade?.cancel()
        if (to != PrologueCue.AIRPORT) {
            if (!paused) players[to.ordinal]?.start()
        }
        if (paused) { settle(); return }
        val from = gains.copyOf()
        fade = scope.launch {
            // Incoming bed rises in ~0.2 s (owner heard lag at the switch), the outgoing one fades over ~1.8 s.
            for (step in 1..36) {
                delay(50)
                val t = step / 36f
                val tIn = minOf(1f, step / 4f)
                for (i in players.indices) {
                    val ease = if (destination(i) > from[i]) kotlin.math.sin(tIn * Math.PI.toFloat() / 2)
                        else 1f - kotlin.math.cos(t * Math.PI.toFloat() / 2)
                    gains[i] = from[i] + (destination(i) - from[i]) * ease
                    players[i]?.setVolume(gains[i])
                }
            }
            settle()
        }
    }

    private fun destination(i: Int) = if (target?.ordinal == i) Music.VOLUME else 0f

    private fun settle() {
        for (i in players.indices) {
            gains[i] = destination(i)
            players[i]?.setVolume(gains[i])
            if (gains[i] == 0f) players[i]?.takeIf { it.isPlaying }?.pause()
        }
    }

    fun pause() {
        paused = true
        fade?.cancel()
        players.forEach { it?.takeIf { p -> p.isPlaying }?.pause() }
        settle()
    }

    fun resume() {
        paused = false
        players.forEachIndexed { i, player -> if (destination(i) > 0f) player?.start() }
    }

    fun release() {
        fade?.cancel()
        players.forEachIndexed { i, player -> player?.release(); players[i] = null }
    }
}

/**
 * One music file played by MediaPlayer, with a seamless loop: ~[TAIL] ms before the end a second player of the same file
 * starts at [HEAD] ms (past the faded-in head) and crossfades in over [XFADE] ms while this one fades out before its own
 * faded tail, so a loop never drops to silence. Main-thread only; every MediaPlayer is released by [release].
 */
internal class LoopPlayer(private val ctx: Context, private val scope: CoroutineScope, private val res: Any, loop: Boolean) {
    /** [res] is a res id or a [File]; null when it can't be read. */
    private fun create(): MediaPlayer? = if (res is File) MediaPlayer.create(ctx, Uri.fromFile(res)) else MediaPlayer.create(ctx, res as Int)
    private var cur: MediaPlayer? = create()
    private var old: MediaPlayer? = null
    private var standby: MediaPlayer? = null
    private var curGain = 1f
    private var oldGain = 0f
    private var vol = 0f
    private var job: Job? = null
    private val looping = loop && (cur?.duration ?: 0) > 2 * TAIL
    val ok get() = cur != null
    /** Runs when the file plays to its end (a non-looping track). */
    var onEnd: (() -> Unit)? = null

    init { // too short to crossfade: the plain loop
        if (loop && !looping) cur?.isLooping = true
        cur?.setOnCompletionListener { onEnd?.invoke() }
    }

    fun setVolume(v: Float) { vol = v; apply() }
    private fun apply() {
        runCatching { cur?.setVolume(vol * curGain, vol * curGain) }
        runCatching { old?.setVolume(vol * oldGain, vol * oldGain) }
    }
    val isPlaying get() = runCatching { cur?.isPlaying == true }.getOrDefault(false)
    fun position(): Long? = runCatching { cur?.currentPosition?.toLong() }.getOrNull()

    /** Before [start]: begin [ms] into the file (clamped clear of the end so the loop's crossfade still has room). */
    fun seek(ms: Int) { cur?.let { p -> runCatching { p.seekTo(ms.coerceIn(0, (p.duration - 2 * TAIL.toInt()).coerceAtLeast(0))) } } }

    fun start() {
        runCatching { cur?.start() }
        if (looping && job?.isActive != true) job = scope.launch { watch() }
    }

    fun pause() {
        job?.cancel(); job = null
        old?.release(); old = null; curGain = 1f; apply() // a half-done crossfade ends on the incoming copy
        runCatching { cur?.takeIf { it.isPlaying }?.pause() }
    }

    fun release() {
        job?.cancel(); job = null
        for (p in listOf(cur, old, standby)) p?.release()
        cur = null; old = null; standby = null
    }

    private suspend fun watch() {
        while (true) {
            delay(100)
            val c = cur ?: return
            val left = runCatching { if (c.isPlaying) c.duration - c.currentPosition else Int.MAX_VALUE }.getOrDefault(Int.MAX_VALUE)
            if (left < TAIL + 4000 && standby == null) standby = create() // prepared ahead, off the crossfade tick
            if (left <= TAIL) crossfade(c)
        }
    }

    private suspend fun crossfade(from: MediaPlayer) {
        val next = standby ?: create() ?: return
        standby = null
        next.seekTo(HEAD)
        old = from; oldGain = 1f; cur = next; curGain = 0f
        apply()
        next.start()
        val steps = (XFADE / 50).toInt()
        for (i in 1..steps) {
            delay(50)
            val t = i.toFloat() / steps // equal power: two different passages, not one correlated signal
            curGain = sin(t * PI.toFloat() / 2); oldGain = cos(t * PI.toFloat() / 2)
            apply()
        }
        old = null; oldGain = 0f; from.release()
    }

    companion object {
        const val TAIL = 8000L
        const val XFADE = 2500L
        const val HEAD = 4000
    }
}

package com.lampensoft.borderlinefluent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.edit
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Matches the officer's voice to their portrait. Android's TTS API doesn't expose a voice's gender, so each
 * installed offline voice speaks one word once, its pitch is measured, and the verdict is cached in prefs.
 */
object Voices {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("voices", 0)

    fun offline(tts: TextToSpeech, lang: String): List<Voice> = tts.voices.orEmpty().filter {
        it.locale.language == lang && !it.isNetworkConnectionRequired && "notInstalled" !in it.features
    }.sortedBy { it.name }

    /** Forgets failed probes for [lang], e.g. after the player installed its voice data. */
    fun forgetFailures(ctx: Context, lang: String) = prefs(ctx).edit {
        prefs(ctx).all.filter { (k, v) -> k.startsWith(lang) && v == -1f }.keys.forEach(::remove)
    }

    /** Measures every not-yet-measured offline voice for [lang]. Returns how many offline voices exist. */
    suspend fun survey(ctx: Context, tts: TextToSpeech, lang: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        // ponytail: 6 voices per language is plenty to find both genders and keeps first setup short.
        val voices = offline(tts, lang).take(6)
        voices.forEachIndexed { i, v ->
            onProgress(i, voices.size)
            if (prefs(ctx).contains(v.name)) return@forEachIndexed
            // A voice listed as offline whose data isn't on the phone makes Google TTS open its installer and the
            // probe time out. Cache that as -1 so it's never probed again and never picked.
            val hz = pitch(ctx, tts, v, Language.of(lang).native) ?: -1.0
            prefs(ctx).edit { putFloat(v.name, hz.toFloat()) }
        }
        return voices.count { prefs(ctx).getFloat(it.name, -1f) > 0f }
    }

    /**
     * Sets a voice of the officer's gender. If the language only has the other gender, shifts pitch instead.
     * ponytail: 165 Hz is the textbook male/female F0 boundary; a voice near it may land on the wrong side.
     * [pitch]: on top of either (the associate speaks lower than the officers).
     */
    fun apply(ctx: Context, tts: TextToSpeech, lang: String, female: Boolean, seed: String, pitch: Float = 1f) {
        val voices = offline(tts, lang).take(6)
        val known = voices.filter { prefs(ctx).getFloat(it.name, -1f) > 0f }
        val match = known.filter { (prefs(ctx).getFloat(it.name, 0f) > 165f) == female }
        val pick = match.getOrNull(Math.floorMod(seed.hashCode(), match.size.coerceAtLeast(1)))
        tts.language = Locale.forLanguageTag(lang)
        if (pick != null) { tts.voice = pick; tts.setPitch(pitch); return }
        (known.firstOrNull() ?: voices.firstOrNull())?.let { tts.voice = it }
        tts.setPitch(pitch * if (known.isEmpty()) 1f else if (female) 1.3f else 0.75f)
    }

    private suspend fun pitch(ctx: Context, tts: TextToSpeech, v: Voice, text: String): Double? {
        val f = File(ctx.cacheDir, "voice_probe.wav")
        tts.voice = v
        tts.setPitch(1f)
        val ok = withTimeoutOrNull(8000) {
            suspendCancellableCoroutine { k ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (k.isActive) k.resume(true) }
                    @Deprecated("") override fun onError(id: String?) { if (k.isActive) k.resume(false) }
                })
                tts.synthesizeToFile("$text. $text.", null, f, "probe")
            }
        } ?: false
        if (!ok || !f.exists()) return null
        return medianF0(f.readBytes())
    }

    /** Median fundamental frequency of a 16-bit mono WAV, by autocorrelation over voiced 40 ms frames. */
    fun medianF0(wav: ByteArray): Double? {
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        val rate = bb.getInt(24)
        var p = 12
        while (p + 8 <= wav.size) { // walk chunks to "data"; TTS WAVs can carry extra chunks
            val id = String(wav, p, 4); val len = bb.getInt(p + 4)
            if (id == "data") { p += 8; break }
            p += 8 + len
        }
        val n = (wav.size - p) / 2
        if (n <= 0 || rate <= 0) return null
        val x = FloatArray(n) { bb.getShort(p + it * 2) / 32768f }
        val frame = rate * 40 / 1000
        val minLag = rate / 400; val maxLag = rate / 60
        val f0s = mutableListOf<Double>()
        var i = 0
        while (i + frame + maxLag < n) {
            var energy = 0.0
            for (j in 0 until frame) energy += x[i + j] * x[i + j]
            if (energy / frame > 1e-4) {
                val ac = DoubleArray(maxLag + 1)
                for (lag in minLag..maxLag) for (j in 0 until frame) ac[lag] += x[i + j] * x[i + j + lag]
                val best = ac.max()
                // Two periods correlate about as well as one; take the shortest lag near the best, or it reads an octave low.
                val lag = (minLag + 1 until maxLag).firstOrNull { ac[it] >= 0.9 * best && ac[it] >= ac[it - 1] && ac[it] >= ac[it + 1] }
                if (lag != null && best / energy > 0.5) f0s += rate.toDouble() / lag
            }
            i += frame
        }
        return f0s.sorted().getOrNull(f0s.size / 2)
    }
}

package com.lampensoft.borderlinefluent

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import kotlin.math.PI
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * Piper voices through sherpa-onnx: ~17 MB per voice plus espeak-ng-data (trimmed per language pack). The officer's
 * voice when a vetted one exists for the language and gender (tts/<lang>_<m|f>/ in the language pack, or files/tts
 * for dev pushes); otherwise the system TTS. Bench: first audio ~0.11 s, RTF 0.11-0.16 on the Pixel 11 Pro XL.
 */
object Piper {
    /** The voice dir and its espeak-ng-data for [lang]/[female], or null. Packs first, then dev pushes. */
    fun find(ctx: android.content.Context, lang: String, female: Boolean): Pair<File, File>? {
        val name = "${lang}_${if (female) "f" else "m"}"
        val roots = listOfNotNull(Packs.ttsDir(ctx, lang), File(ctx.filesDir, "tts"))
        for (r in roots) {
            val v = File(r, name); val e = File(r, "espeak-ng-data")
            if (File(v, "model.onnx").exists() && e.isDirectory) return v to e
        }
        return null
    }

    /**
     * Speaks [text] as it is generated (sentence by sentence into a streaming AudioTrack), then [onDone]. Cancel the
     * job to stop. [robot]: how much ring modulation to mix in (0 = clean; [ANDROID] for officers, [CRASH] for BROKEN).
     */
    fun speak(scope: kotlinx.coroutines.CoroutineScope, tts: OfflineTts, text: String, speed: Float, robot: Float, onDone: () -> Unit) =
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val rate = tts.sampleRate()
            val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 4).build()
            var written = 0
            try {
                track.play()
                tts.generateWithCallback(text, synchronized(sids) { sids[tts] } ?: 0, speed, OnChunk({ isActive }) { chunk ->
                    val x = level(if (robot > 0f) robot(chunk, rate, mix = robot) else chunk); track.write(x, 0, x.size, AudioTrack.WRITE_BLOCKING); written += x.size
                })
                // Let the tail drain: the buffer still holds up to its size in samples.
                while (isActive && track.playbackHeadPosition < written) delay(30)
            } finally { runCatching { track.stop() }; track.release() }
            if (isActive) onDone()
        }

    /** Speaker id per loaded voice, from the optional speaker.txt of a multi-speaker voice dir (default 0). */
    private val sids = java.util.WeakHashMap<OfflineTts, Int>()

    fun load(voice: File, espeak: File) = loadVoice(voice, espeak).also { t ->
        File(voice, "speaker.txt").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()?.let { synchronized(sids) { sids[t] = it } }
    }

    private fun loadVoice(voice: File, espeak: File) = OfflineTts(null, OfflineTtsConfig(model = OfflineTtsModelConfig(
        vits = OfflineTtsVitsModelConfig(model = File(voice, "model.onnx").path, tokens = File(voice, "tokens.txt").path, dataDir = espeak.path),
        numThreads = 2)))

    /**
     * Voices differ by ~19 dB out of the box (measured 2026-10-05: es_ES-davefx -15 LUFS, uk_UA -35 LUFS), so every
     * generated chunk (a sentence) is scaled to [TARGET_RMS] (~-18 LUFS for speech), never over -1 dBFS peak and at most
     * +20 dB, so a near-silent chunk is not blown up into noise.
     */
    const val TARGET_RMS = 0.126f // -18 dBFS
    fun level(x: FloatArray, target: Float = TARGET_RMS, ceiling: Float = 0.89f, maxGain: Float = 10f): FloatArray {
        if (x.isEmpty()) return x
        var sum = 0.0; var peak = 0f
        for (v in x) { sum += v * v; peak = maxOf(peak, kotlin.math.abs(v)) }
        val rms = kotlin.math.sqrt(sum / x.size).toFloat()
        if (rms < 1e-5f) return x
        val g = minOf(target / rms, ceiling / peak, maxGain)
        return FloatArray(x.size) { x[it] * g }
    }

    /** Owner, 2026-10-05: the 0.55 bench mix was too strong for everyday speech; half of it for every officer. */
    const val ANDROID = 0.27f
    /** the upgraded units after the finale: still a machine, less obviously */
    const val ANDROID_V2 = 0.12f
    const val CRASH = 0.55f

    /**
     * First-generation android: ring modulation (the classic robot voice) mixed with the dry signal, so the words
     * stay clear for a learner. ponytail: one fixed 55 Hz carrier; per-officer pitch if they sound too alike.
     */
    fun robot(x: FloatArray, rate: Int, carrierHz: Double = 55.0, mix: Float = ANDROID) = FloatArray(x.size) { i ->
        val ring = x[i] * sin(2 * PI * carrierHz * i / rate).toFloat()
        (1 - mix) * x[i] + mix * ring * 1.6f
    }

    /**
     * Streaming callback for generateWithCallback. Must be a real class: sherpa-onnx's JNI looks up exactly
     * invoke([F)Ljava/lang/Integer; and a D8-desugared lambda only has invoke(Object), which aborted the app.
     */
    class OnChunk(private val going: () -> Boolean = { true }, private val f: (FloatArray) -> Unit) : Function1<FloatArray, Int> {
        // 0 stops the native generation: a cancelled line must not keep the voice (and Game's voiceLock) busy.
        override fun invoke(samples: FloatArray): Int { if (!going()) return 0; f(samples); return 1 }
    }

    /** Blocking playback, for the bench. */
    fun play(x: FloatArray, rate: Int) {
        val t = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(x.size * 4).build()
        t.write(x, 0, x.size, AudioTrack.WRITE_BLOCKING); t.play()
        Thread.sleep(x.size * 1000L / rate + 300); t.release()
    }
}

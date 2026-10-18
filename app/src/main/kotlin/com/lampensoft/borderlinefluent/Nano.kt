package com.lampensoft.borderlinefluent

import android.os.ParcelFileDescriptor
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Not a FeatureStatus: the status check threw on every try (AICore busy, updating, not bound yet after boot). */
const val NANO_UNKNOWN = -1

/** [check] up to [tries] times, backing off 1 s, 2 s, …; only a returned status counts, throws all round give [NANO_UNKNOWN]. */
suspend fun probeNano(tries: Int = 3, backoffMs: Long = 1000, name: String = "nano", log: (String) -> Unit, check: suspend () -> Int): Int {
    repeat(tries) { i ->
        try { return check() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log("$name probe ${i + 1}/$tries failed: $e")
            if (i < tries - 1) delay(backoffMs * (i + 1))
        }
    }
    return NANO_UNKNOWN
}

/**
 * After a Nano download flow ends, trusts [check] (a [probeNano]) rather than the flow: AICore emits DownloadCompleted,
 * or DownloadFailed on an IPC disconnect, while it keeps fetching. Waits while DOWNLOADING or unknown (no overall timeout:
 * the setup screen is a foreground wait), runs [download] again on DOWNLOADABLE up to [redownloads] times. True = AVAILABLE.
 */
suspend fun awaitNano(log: (String) -> Unit, waitMs: Long = 2000, redownloads: Int = 3, maxUnknown: Int = 10,
                      check: suspend () -> Int, download: suspend () -> Unit, waiting: () -> Unit = {}): Boolean {
    var again = 0; var unknown = 0; var waits = 0; var told = false
    while (true) {
        val st = check()
        unknown = if (st == NANO_UNKNOWN) unknown + 1 else 0
        when (st) {
            FeatureStatus.AVAILABLE -> return true
            FeatureStatus.DOWNLOADABLE -> {
                if (++again > redownloads) { log("nano still downloadable after $redownloads re-downloads"); return false }
                download()
            }
            FeatureStatus.DOWNLOADING, NANO_UNKNOWN -> {
                if (unknown >= maxUnknown) { log("nano status unknown $unknown times in a row"); return false }
                if (st == FeatureStatus.DOWNLOADING && !told) { told = true; waiting() }
                if (++waits % 30 == 0) log("nano waiting for AICore status=$st waits=$waits") // ~once a minute at 2 s
                delay(waitMs)
            }
            else -> { log("nano not available after download status=$st"); return false }
        }
    }
}

/**
 * A basic speech-recognition probe ([probeNano] around [Nano.basicAsr]) as the game keeps it: true = a system
 * recognizer (ready or downloadable), false = a returned UNAVAILABLE (needs Whisper), null = the check kept throwing
 * (unknown: never a reason to fetch Whisper, see [slowLang]).
 */
fun asrKnown(status: Int): Boolean? = when (status) {
    NANO_UNKNOWN -> null
    FeatureStatus.UNAVAILABLE -> false
    else -> true
}

/**
 * The system's Gemini Nano through ML Kit GenAI (AICore). The only brain. Foreground only, with a per-app
 * quota.
 */
object Nano {
    /**
     * Speech-recognition locales are region-specific (ML Kit docs, 2026-10); uk has none in advanced mode. Portuguese
     * listens as pt-BR in basic mode (pt-PT is advanced-only); voices and subtitles keep the plain "pt".
     */
    private val LOCALES = mapOf("de" to "de-DE", "fr" to "fr-FR", "es" to "es-ES", "it" to "it-IT", "pt" to "pt-BR",
        "pl" to "pl-PL", "ru" to "ru-RU", "tr" to "tr-TR", "ar" to "ar-SA", "ja" to "ja-JP", "zh" to "cmn-Hans-CN",
        "en" to "en-US", "uk" to "uk-UA")

    /** The recognizer locale for [lang]: pt-PT only in advanced mode. */
    fun asrLocale(lang: String, advanced: Boolean = false): Locale =
        Locale.forLanguageTag(if (advanced && lang == "pt") "pt-PT" else LOCALES[lang] ?: lang)

    /** The model ready to use, or a reason it isn't. [log] gets download progress. */
    suspend fun model(log: (String) -> Unit, progress: (done: Long, total: Long) -> Unit = { _, _ -> },
                      waiting: () -> Unit = {}): GenerativeModel? {
        val m = Generation.getClient()
        when (val st = probeNano(log = log) { m.checkStatus() }) { // right after boot AICore may throw a few times
            FeatureStatus.AVAILABLE -> {}
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                var total = 0L
                suspend fun download() = m.download().collect {
                    log("nano download $it")
                    if (it is DownloadStatus.DownloadStarted) total = it.bytesToDownload
                    else if (it is DownloadStatus.DownloadProgress && total > 0) progress(it.totalBytesDownloaded, total)
                }
                download()
                if (!awaitNano(log, check = { probeNano(log = log) { m.checkStatus() } }, download = { download() }, waiting = waiting)) { m.close(); return null }
            }
            else -> { log("nano unavailable status=$st"); m.close(); return null }
        }
        m.warmup()
        log("nano ready model=${m.getBaseModelName()} systemPrompt=${m.isSystemPromptAvailable()} tokens=${m.getTokenLimit()}")
        return m
    }

    /** Temperature 0: the answer check (Judge, room classifier) must give the same verdict for the same answer. */
    fun oneShot(m: GenerativeModel): OneShot = { system, user, max -> ask(m, system, user, max).reply }

    /** The officer's lines and the hints: at 0 identical input gave identical lines and the officer looped. */
    const val CHAT_TEMPERATURE = 0.6f

    /**
     * One streamed call. [onSpeakable] fires once the first (target-language) line is complete.
     * BUSY (AICore serving another app) is retried briefly; the per-app battery quota and other errors surface.
     */
    suspend fun ask(m: GenerativeModel, system: String, user: String, max: Int, onSpeakable: (String) -> Unit = {}, temperature: Float = 0f): Turn {
        repeat(3) { attempt ->
            try {
                return stream(m, system, user, max, onSpeakable, temperature)
            } catch (e: GenAiException) {
                if (e.errorCode != GenAiException.ErrorCode.BUSY || attempt == 2) throw e
                delay(500L * (attempt + 1)) // reads as the officer thinking
            }
        }
        error("unreachable")
    }

    private suspend fun stream(m: GenerativeModel, system: String, user: String, max: Int, onSpeakable: (String) -> Unit, temp: Float): Turn {
        val t0 = System.nanoTime(); var first = 0L
        var text = ""
        var spoke = false
        m.generateContentStream(generateContentRequest(SystemInstruction(system), TextPart(user)) {
            maxOutputTokens = max; temperature = temp
        }).collect { r ->
            if (first == 0L) first = System.nanoTime()
            val chunk = r.candidates.firstOrNull()?.text.orEmpty()
            text = if (chunk.startsWith(text)) chunk else text + chunk // tolerate cumulative or delta chunks
            if (!spoke && text.contains("\nEN:")) { spoke = true; onSpeakable(Models.targetLine(text)) }
        }
        if (!spoke) onSpeakable(Models.targetLine(text))
        return Turn(text.trim(), (first - t0) / 1_000_000, Models.ms(t0), -1, -1, -1.0, -1.0)
    }

    /** Is ML Kit's basic on-device speech recognition there (or downloadable) for [lang]? */
    suspend fun basicAsr(lang: String): Int = SpeechRecognition.getClient(speechRecognizerOptions {
        locale = asrLocale(lang); preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
    }).use { it.checkStatus() }

    /** Downloads the basic speech model for [lang] (a system language pack, ~110 MB). Null on success, else a reason. */
    suspend fun downloadAsr(lang: String, progress: (Long, Long) -> Unit): String? = SpeechRecognition.getClient(speechRecognizerOptions {
        locale = asrLocale(lang); preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
    }).use { rec ->
        if (rec.checkStatus() == FeatureStatus.AVAILABLE) return@use null
        var total = 0L; var failed: String? = null
        rec.download().takeWhile { it !is DownloadStatus.DownloadCompleted && failed == null }.collect { st ->
            when (st) {
                is DownloadStatus.DownloadStarted -> total = st.bytesToDownload
                is DownloadStatus.DownloadProgress -> progress(st.totalBytesDownloaded, total)
                is DownloadStatus.DownloadFailed -> failed = "Speech model download failed: ${st.e.message}"
                else -> {}
            }
        }
        failed
    }

    /** ML Kit speech recognition, advanced mode, forced to [lang]. Sends a finished clip in ONE_SHOT mode. */
    suspend fun transcribe(lang: String, pcm: FloatArray, log: (String) -> Unit, advanced: Boolean = true, rate: Int = 16000): Pair<String, Long>? {
        val opts = speechRecognizerOptions {
            locale = asrLocale(lang, advanced)
            preferredMode = if (advanced) SpeechRecognizerOptions.Mode.MODE_ADVANCED else SpeechRecognizerOptions.Mode.MODE_BASIC
        }
        val rec = SpeechRecognition.getClient(opts)
        try {
            when (val st = rec.checkStatus()) {
                FeatureStatus.AVAILABLE -> {}
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> rec.download().takeWhile { it !is DownloadStatus.DownloadCompleted && it !is DownloadStatus.DownloadFailed }.collect { log("asr download $lang $it") }
                else -> { log("nano asr $lang unavailable status=$st"); return null }
            }
            // One-shot clips of ~8 s and longer failed with ERROR_TYPE_AUDIO_BUFFER_OVERFLOW (Pixel 11 Pro XL,
            // 2026-10-13) and counted as silence: long answers go in segments of at most 7 s, cut in pauses.
            // A fresh client per later segment: a failed session closes the engine.
            // The latency returned is the whole recognition (every segment), not just the time after the last write.
            val startAt = System.nanoTime()
            var endAt = 0L
            val text = StringBuilder()
            val segments = asrSegments(pcm, rate)
            // A later segment that throws keeps the text so far.
            for ((i, seg) in segments.withIndex()) {
                val clip = pcm.copyOfRange(seg.first, seg.last + 1)
                if (i == 0) { endAt = oneShot(rec, clip, rate, text, log); continue }
                endAt = try { SpeechRecognition.getClient(opts).use { oneShot(it, clip, rate, text, log) } }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { log("nano asr segment $i failed: $e"); break }
            }
            val now = System.nanoTime()
            if (segments.size > 1) log("nano asr ${segments.size} segments total=${(now - startAt) / 1_000_000}ms last_after_write=${(now - endAt) / 1_000_000}ms")
            return text.toString().trim() to ((now - startAt) / 1_000_000)
        } finally { rec.close() }
    }

    /** Transcribes one clip in ONE_SHOT mode, appending final text to [text]; returns when the last byte was written. */
    private suspend fun oneShot(rec: SpeechRecognizer, pcm: FloatArray, rate: Int, text: StringBuilder, log: (String) -> Unit): Long {
        val (read, write) = ParcelFileDescriptor.createPipe()
        var endAt = 0L
        coroutineScope {
            val feeder = async(Dispatchers.IO) {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    val bytes = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    pcm.forEach { bytes.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
                    out.write(bytes.array()) // ONE_SHOT: the whole clip at once, no real-time pacing
                }
                endAt = System.nanoTime()
            }
            val listen = launch {
                rec.startRecognition(speechRecognizerRequest { audioSource = AudioSource.fromPfd(read, AudioSource.Mode.ONE_SHOT,
                    android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate).setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO).build()) })
                    .takeWhile { it !is SpeechRecognizerResponse.CompletedResponse }
                    .collect { r ->
                        when (r) {
                            is SpeechRecognizerResponse.FinalTextResponse -> text.append(r.text).append(' ')
                            is SpeechRecognizerResponse.ErrorResponse -> log("nano asr error $r")
                            else -> {}
                        }
                    }
            }
            feeder.await()
            // EOF should complete the stream; if the recognizer keeps waiting, stop it (what the game would do
            // when the player releases the mic) and give it a moment to flush the final text.
            if (withTimeoutOrNull(6000) { listen.join() } == null) {
                rec.stopRecognition()
                withTimeoutOrNull(4000) { listen.join() } ?: listen.cancel()
            }
        }
        return endAt
    }
}

/** Splits a clip into ASR segments of at most 7 s, each cut at the quietest 200 ms window 4-7 s into what remains,
 *  leaving at least 1 s after the cut (clips end in silence: a tiny silent tail cost its own session, up to 10 s). */
internal fun asrSegments(pcm: FloatArray, rate: Int): List<IntRange> {
    val max = 7 * rate; val min = 4 * rate; val half = rate / 10; val step = maxOf(1, rate / 100)
    val out = ArrayList<IntRange>()
    var start = 0
    while (pcm.size - start > max) {
        val end = minOf(start + max, pcm.size - rate) // > start + 6 s here, so the range is never empty
        var cut = end; var best = Double.MAX_VALUE
        for (c in start + min..end step step) {
            var e = 0.0
            for (i in maxOf(0, c - half) until minOf(pcm.size, c + half)) e += pcm[i] * pcm[i]
            if (e < best) { best = e; cut = c }
        }
        out += start until cut; start = cut
    }
    out += start until pcm.size
    return out
}

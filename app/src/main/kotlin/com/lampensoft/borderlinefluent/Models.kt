package com.lampensoft.borderlinefluent

import android.content.Context
import android.speech.tts.TextToSpeech
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device model plumbing shared by the game and the benchmarks.
 * Models live in internal storage (files/models): files adb pushes into Android/data stay shell-owned and
 * unreadable to the app, so scripts/push.sh copies them in with `run-as`.
 */
object Models {
    fun dir(ctx: Context) = File(ctx.filesDir, "models")

    /** Whisper, downloaded only for languages without system speech recognition (see [Game.install]). */
    val WHISPER = listOf("turbo-encoder.int8.onnx", "turbo-decoder.int8.onnx", "turbo-tokens.txt") // ~1 GB
    fun has(ctx: Context, files: List<String>) = files.all { File(dir(ctx), it).length() > 0 } // 0 also when missing

    /** [auto] lets Whisper detect the language; the game forces the target language instead. */
    fun asr(ctx: Context, name: String, lang: String, auto: Boolean = false): OfflineRecognizer {
        val m = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = File(dir(ctx), "$name-encoder.int8.onnx").path,
                decoder = File(dir(ctx), "$name-decoder.int8.onnx").path,
                language = if (auto) "" else lang,
                task = "transcribe",
            ),
            tokens = File(dir(ctx), "$name-tokens.txt").path,
            numThreads = 4,
        )
        return OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = m))
    }

    fun transcribe(rec: OfflineRecognizer, samples: FloatArray): Heard {
        val s = rec.createStream()
        s.acceptWaveform(samples, 16000)
        rec.decode(s)
        val r = rec.getResult(s)
        s.release()
        return Heard(dedupe(r.text.trim()), r.lang.filter { it.isLetter() })
    }

    /** Whisper can loop on short clips ("Guten Tag! Guten Tag! Guten Tag!"); drop consecutive repeated sentences. */
    fun dedupe(text: String): String {
        fun key(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
        val parts = text.split(Regex("""(?<=[.!?。！？])\s*""")).filter { it.isNotBlank() }
        return parts.filterIndexed { i, p -> i == 0 || key(p) != key(parts[i - 1]) }.joinToString(" ").trim()
    }

    suspend fun tts(ctx: Context): TextToSpeech = suspendCancellableCoroutine { k ->
        var t: TextToSpeech? = null
        t = TextToSpeech(ctx) { k.resume(t!!) }
    }

    val OPTION = Regex("""^\s*[123][:.)].+""")
    val FORMAT = Regex("""^\s*[A-Z]{2}:.+\n\s*EN:.+""", RegexOption.MULTILINE)
    private val TAGS = Regex("""\s*\[(PASS|SECONDARY)]""")
    // Safety net for copied template markers: angle brackets anywhere, a "[German reply]" label at either end.
    private val MARKER = Regex("""^\s*\[[^\]]*]\s*|\s*\[[^\]]*]\s*$""")
    private fun clean(s: String) = s.replace(TAGS, "").replace("<", "").replace(">", "").replace(MARKER, "").trim()

    fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
    fun rssMb() = File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }
        .filter { it.isDigit() }.toLong() / 1024

    private val PREFIX = Regex("""^\s*([A-Z]{2}):""")

    /**
     * "DE: Guten Tag." -> "Guten Tag." The first line with a non-EN language prefix: Nano sometimes echoes a
     * "[thought]" line first or appends a second pair. Lines starting with a bracket (including incomplete streamed thoughts) are skipped; no prefixed line: the first line.
     */
    fun targetLine(s: String): String {
        val lines = s.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("[") }
        return (lines.firstOrNull { PREFIX.find(it)?.groupValues?.get(1).let { p -> p != null && p != "EN" } } ?: lines.firstOrNull())
            ?.let { PREFIX.replaceFirst(it, "") }?.let(::clean)?.takeUnless { '[' in it || ']' in it } ?: ""
    }

    fun englishLine(s: String) = s.lineSequence().firstOrNull { it.trimStart().startsWith("EN:") }
        ?.substringAfter(':')?.let(::clean)?.takeUnless { '[' in it || ']' in it } ?: ""

    /** "1: Ja, gerne. | Yes, gladly." or "1: はい。 | Hai. | Yes." -> Hint (the middle part is the reading aid). */
    fun option(line: String): Hint {
        val p = line.trim().drop(2).split('|').map(::clean)
        return if (p.size >= 3) Hint(p[0], p[1], p[2]) else Hint(p[0], "", p.getOrNull(1) ?: "")
    }

    fun writeWav(f: File, pcm: FloatArray) = RandomAccessFile(f, "rw").use { raf ->
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000)
            .putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.capacity())
        raf.write(h.array()); raf.write(data.array())
    }

    /** Character error rate on lowercased letters/digits only, so punctuation and casing don't count. */
    fun cer(ref: String, hyp: String): Double {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.replace(Regex(" +"), " ").trim()
        val a = norm(ref); val b = norm(hyp)
        if (a.isEmpty()) return if (b.isEmpty()) 0.0 else 1.0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length].toDouble() / a.length
    }
}

data class Hint(val target: String, val reading: String, val english: String)

data class Heard(val text: String, val lang: String)

data class Turn(val reply: String, val ttftMs: Long, val totalMs: Long, val prefillTok: Int, val decodeTok: Int,
                val decodeTps: Double, val prefillTps: Double)

data class Retina(val check: Check?, val options: List<String>, val turn: Turn)

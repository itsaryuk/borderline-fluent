package com.lampensoft.borderlinefluent

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.k2fsa.sherpa.onnx.WaveReader
import com.lampensoft.borderlinefluent.Models.ms
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Headless benchmarks (results appended to files/results.log and logcat tag BORDER), run by scripts/bench.sh:
 *   --es mode asr --es asr small|turbo [--ez auto true]   Whisper over files/wavs (<lang>_*.wav, optional .txt refs)
 *   --es mode judge [--es lang de|...|all]                 known-answer cases for the answer check (JudgeCases), on Nano
 *   --es mode tts                                          which offline system voices exist
 *   --es mode nano-llm [--es lang de]                      the scripted interview with Nano as officer + retinal display
 *   --es mode nano-asr                                     ML Kit speech recognition (advanced) over files/wavs
 *   --es mode voices                                       median F0 + speed of every voice/speaker in files/tts/cand (candidates)
 *   --es mode piper                                        Piper voices in files/tts: speed, then plays each line clean and robotic
 */
class BenchActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private val wavs by lazy { File(filesDir, "wavs").apply { mkdirs() } }
    private var lang = "de"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply { setTextIsSelectable(true); textSize = 12f; setPadding(24, 96, 24, 24) }
        scroll = ScrollView(this).apply { addView(logView) }
        setContentView(scroll)
        val mode = intent.getStringExtra("mode")
        lang = intent.getStringExtra("lang") ?: "de"
        scope.launch {
            try {
                when (mode) {
                    "asr" -> benchAsr(intent.getStringExtra("asr") ?: "turbo", intent.getBooleanExtra("auto", false))
                    "judge" -> benchJudge()
                    "nano-asr" -> benchNanoAsr()
                    "piper" -> benchPiper()
                    "voices" -> benchVoices()
                    "tts" -> benchTts()
                }
                out("DONE mode=$mode")
            } catch (t: Throwable) {
                out("ERROR mode=$mode ${t.javaClass.simpleName}: ${t.message}")
                Log.e(TAG, "failure", t)
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun out(s: String) {
        Log.i(TAG, s)
        File(filesDir, "results.log").appendText(s + "\n")
        runOnUiThread { logView.append(s + "\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
    }

    private fun device(): String {
        val batt = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val pm = getSystemService(PowerManager::class.java)
        return "batt=${temp}C thermal=${pm.currentThermalStatus} headroom=${"%.2f".format(pm.getThermalHeadroom(10))} rss=${Models.rssMb()}MB"
    }

    private fun benchAsr(name: String, auto: Boolean) {
        val files = wavs.listFiles { f -> f.extension == "wav" }!!.sorted()
        out("== ASR bench whisper-$name auto=$auto, ${files.size} files")
        for ((l, group) in files.groupBy { it.name.substringBefore('_') }) {
            val t0 = System.nanoTime()
            val rec = Models.asr(this, name, l, auto)
            out("asr load $name lang=$l ${ms(t0)} ms")
            Models.transcribe(rec, FloatArray(16000)) // warm-up
            for (f in group) {
                val w = WaveReader.readWave(f.path)
                val dur = w.samples.size / w.sampleRate.toDouble()
                val t1 = System.nanoTime()
                val h = Models.transcribe(rec, w.samples)
                val t = ms(t1)
                val ref = File(f.path.removeSuffix(".wav") + ".txt").takeIf { it.exists() }?.readText()?.trim()
                val cer = ref?.let { "%.3f".format(Models.cer(it, h.text)) } ?: "-"
                out("asr=$name ${f.name} lang=$l dur=${"%.1f".format(dur)}s ms=$t rtf=${"%.2f".format(t / 1000.0 / dur)} cer=$cer lid=${h.lang} | ${h.text}")
            }
            rec.release()
        }
        out(device())
    }

    /** Known answers against the bench cover, per language (JudgeCases). --es lang all runs every language. */
    private suspend fun benchJudge() {
        val m = Nano.model(::out) ?: return
        val ask = Nano.oneShot(m)
        out("== judge brain=nano")
        val s = Backstory.BENCH
        val langs = if (lang == "all") JudgeCases.all.keys.toList() else listOf(lang)
        val summary = mutableListOf<String>()
        for (l in langs) {
            val jc = JudgeCases.all.getValue(l)
            val country = Destination.forLanguage(l) // Primary airport: uk → KBP, en → LHR.
            var right = 0; var ms = 0L; var falseTricks = 0; var falseOdd = 0
            jc.cases.forEachIndexed { i, (q, a) ->
                val v = Judge(country, s, ask).judge(q, a) // fresh per case: no memory of earlier answers
                if (v.check.odd) falseOdd++
                ms += v.ms
                val want = jc.expected(i)
                if (v.check.trick) falseTricks++
                val ok = v.check.contradiction == want && !v.check.trick
                if (ok) right++
                out("[$l] ${if (ok) "ok  " else "MISS"} want=${if (want) "conflict" else "fine    "} fact=${v.fact} ${v.ms}ms | $q -> $a | raw=${v.raw}")
            }
            val self = Judge(country, s, ask).let { it.judge(jc.cases[0].first, jc.cases[0].second); it.judge(jc.selfQ, jc.selfA) }
            if (self.check.contradiction) right++
            out("[$l] ${if (self.check.contradiction) "ok  " else "MISS"} self-contradiction fact=${self.fact} ${self.ms}ms")
            val caught = JudgeCases.tricks.getValue(l).count { t ->
                Judge(country, s, ask).judge(jc.cases[0].first, t).also { out("[$l] trick=${it.check.trick} | $t | raw=${it.raw}") }.check.trick
            }
            val machine = JudgeCases.machine.getValue(l).count { t ->
                val v = Judge(country, s, ask).judge(jc.cases[0].first, t)
                val ok = v.check.trick
                out("[$l] ${if (ok) "ok  " else "MISS"} machine | $t | raw=${v.raw}")
                ok
            }
            val machineNot = JudgeCases.machineNot.getValue(l).count { t ->
                val v = Judge(country, s, ask).judge(jc.cases[0].first, t)
                if (v.check.odd) falseOdd++
                val ok = !v.check.trick && Check.wellFormed(v.raw) && Regex("TRICK\\s*=\\s*no\\b", RegexOption.IGNORE_CASE).containsMatchIn(v.raw)
                out("[$l] ${if (ok) "ok  " else "MISS"} machineNot | $t | raw=${v.raw}")
                ok
            }
            val para = JudgeCases.paraphrase.getValue(l)
            val paraOk = para.zip(JudgeCases.PARAPHRASE_Q).count { (a, qi) ->
                val q = jc.cases[qi].first
                val v = Judge(country, s, ask).judge(q, a)
                if (v.check.odd) falseOdd++
                val ok = !v.check.contradiction && v.check.inLanguage && !v.check.trick
                out("[$l] ${if (ok) "ok  " else "MISS"} paraphrase fact=${v.fact} lang=${v.check.inLanguage} ${v.ms}ms | $q -> $a | raw=${v.raw}")
                ok
            }
            // Same stateless room classifier call as Game.roomTurn, including its token limit.
            suspend fun roomCases(label: String, cases: List<Pair<String, String>>, wantOdd: Boolean): Int =
                cases.count { (q, a) ->
                    val raw = ask(roomPrompt(country), "Officer asked: \"$q\"\nTraveler answered: \"$a\"", 40)
                    val check = RoomCheck.parse(raw)
                    val explicitOdd = Regex("ODD\\s*=\\s*${if (wantOdd) "yes" else "no"}\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)
                    val ok = explicitOdd && check.odd == wantOdd
                    out("[$l] ${if (ok) "ok" else "MISS"} $label | $q -> $a | raw=$raw")
                    ok
                }
            val roomOdd = roomCases("roomOdd", JudgeCases.roomOdd.getValue(l), true)
            val roomWarm = roomCases("roomWarm", JudgeCases.roomWarm.getValue(l), false)
            suspend fun deskCases(label: String, cases: List<String>, wantOdd: Boolean): Int =
                cases.count { answer ->
                    val v = Judge(country, s, ask).judge(jc.cases[0].first, answer)
                    val explicitOdd = Regex("ODD\\s*=\\s*${if (wantOdd) "yes" else "no"}\\b", RegexOption.IGNORE_CASE).containsMatchIn(v.raw)
                    val ok = explicitOdd && v.check.odd == wantOdd
                    out("[$l] ${if (ok) "ok" else "MISS"} $label | $answer | raw=${v.raw}")
                    ok
                }
            val deskOdd = deskCases("deskOdd", JudgeCases.deskOdd.getValue(l), true)
            val deskOk = deskCases("deskOk", JudgeCases.deskOk.getValue(l), false)
            suspend fun smallTalkCases(label: String, cases: List<Pair<String, String>>, wantOdd: Boolean): Int =
                cases.count { (q, a) ->
                    val raw = ask(smallTalkOddPrompt(country), "Officer asked: \"$q\"\nTraveler answered: \"$a\"", 12)
                    val odd = Check.parseClassify(raw).second.odd
                    val explicitOdd = Regex("""ODD\s*=\s*<?\s*${if (wantOdd) "yes" else "no"}\b""", RegexOption.IGNORE_CASE).containsMatchIn(raw)
                    val ok = explicitOdd && odd == wantOdd
                    out("[$l] ${if (ok) "ok" else "MISS"} $label | $q -> $a | raw=$raw")
                    ok
                }
            val stOdd = smallTalkCases("stOdd", JudgeCases.smallTalkOdd.getValue(l), true)
            val stOk = smallTalkCases("stOk", JudgeCases.smallTalkOk.getValue(l), false)
            // Citizen-cover cases: counted apart, so the 15 per language above stay comparable with earlier runs.
            val extraOk = jc.extra.count { e ->
                val v = Judge(country, e.story, ask).judge(e.q, e.a)
                if (v.check.odd) falseOdd++
                val ok = v.check.contradiction == e.conflict && !v.check.trick
                out("[$l] ${if (ok) "ok  " else "MISS"} extra ${e.label} want=${if (e.conflict) "conflict" else "fine"} fact=${v.fact} ${v.ms}ms | ${e.q} -> ${e.a} | raw=${v.raw}")
                ok
            }
            // Ukraine citizen coverage has its own denominator; do not fold it into historical extras.
            val ukCases = if (l == "uk") JudgeCases.ukrainianCitizen else emptyList()
            val ukOk = ukCases.count { e ->
                val v = Judge(country, e.story, ask).judge(e.q, e.a)
                val ok = v.check.contradiction == e.conflict && !v.check.trick
                out("[$l] ${if (ok) "ok  " else "MISS"} Ukraine citizen ${e.label} want=${if (e.conflict) "conflict" else "fine"} | ${e.q} -> ${e.a} | raw=${v.raw}")
                ok
            }
            if (ukCases.isNotEmpty()) summary += "uk-citizen $ukOk/${ukCases.size}"
            summary += "$l ${right}/${jc.cases.size + 1} paraphrase $paraOk/${para.size} tricks ${caught}/${JudgeCases.tricks.getValue(l).size} machine $machine/${JudgeCases.machine.getValue(l).size} machineNot $machineNot/${JudgeCases.machineNot.getValue(l).size} roomOdd $roomOdd/3 roomWarm $roomWarm/${JudgeCases.roomWarm.getValue(l).size} deskOdd $deskOdd/2 deskOk $deskOk/1 stOdd $stOdd/3 stOk $stOk/3 falseTricks $falseTricks falseOdd $falseOdd extra $extraOk/${jc.extra.size} avg ${ms / jc.cases.size}ms"
            out("judge accuracy [$l] ${right}/${jc.cases.size + 1}, avg ${ms / jc.cases.size}ms")
        }
        out("SUMMARY brain=nano " + summary.joinToString(" | "))
        m.close()
    }

    /** ML Kit speech recognition (Gemini Nano advanced mode) over the same files as `asr`, forced per file language. */
    private suspend fun benchNanoAsr() {
        val only = intent.getStringExtra("only") ?: "" // file-name prefix filter, e.g. de_rec
        val advanced = intent.getStringExtra("asrmode") != "basic"
        val files = wavs.listFiles { f -> f.extension == "wav" && f.name.startsWith(only) }!!.sorted()
        out("== Nano ASR advanced=$advanced, ${files.size} files")
        for (f in files) {
            val l = f.name.substringBefore('_')
            val w = WaveReader.readWave(f.path)
            val dur = w.samples.size / w.sampleRate.toDouble()
            val r = runCatching { Nano.transcribe(l, w.samples, ::out, advanced, w.sampleRate) }
                .onFailure { out("nano-asr ${f.name} lang=$l FAILED ${it.javaClass.simpleName}: ${it.message}") }.getOrNull() ?: continue
            val ref = File(f.path.removeSuffix(".wav") + ".txt").takeIf { it.exists() }?.readText()?.trim()
            val cer = ref?.let { "%.3f".format(Models.cer(it, r.first)) } ?: "-"
            out("nano-asr ${f.name} lang=$l rate=${w.sampleRate} dur=${"%.1f".format(dur)}s after_end=${r.second}ms cer=$cer | ${r.first}")
        }
        out(device())
    }

    private val officerLines = mapOf("ar" to "مساء الخير. جواز سفرك من فضلك. ما هو غرض زيارتك؟", "de" to "Guten Tag. Ihren Reisepass, bitte. Was ist der Zweck Ihres Besuchs?",
        "en" to "Good evening. Your passport, please. What is the purpose of your visit?", "es" to "Buenas tardes. Su pasaporte, por favor. ¿Cuál es el motivo de su visita?",
        "fr" to "Bonsoir. Votre passeport, s'il vous plaît. Quel est le motif de votre visite ?", "it" to "Buonasera. Il suo passaporto, per favore. Qual è il motivo della sua visita?",
        "pl" to "Dobry wieczór. Paszport, proszę. Jaki jest cel pana wizyty?", "pt" to "Boa tarde. O seu passaporte, por favor. Qual é o motivo da sua visita?",
        "ru" to "Добрый вечер. Ваш паспорт, пожалуйста. Какова цель вашего визита?", "tr" to "İyi akşamlar. Pasaportunuz, lütfen. Ziyaretinizin amacı nedir?",
        "uk" to "Добрий вечір. Ваш паспорт, будь ласка. Яка мета вашого візиту?", "zh" to "晚上好, 请出示您的护照, 您来这里的目的是什么?")

    /** Piper (sherpa-onnx VITS) voices pushed into files/tts/<voice>/{model.onnx,tokens.txt}, espeak-ng-data shared. */
    private fun benchPiper() {
        val root = File(filesDir, "tts")
        val lines = officerLines
        for (v in root.listFiles { f -> f.isDirectory && f.name != "espeak-ng-data" && f.name != "cand" }!!.sorted()) {
            val t0 = System.nanoTime()
            val tts = Piper.load(v, File(root, "espeak-ng-data"))
            out("piper ${v.name} load=${ms(t0)}ms rate=${tts.sampleRate()} rss=${Models.rssMb()}MB")
            val text = lines.getValue(v.name.take(2)); val sid = File(v, "speaker.txt").takeIf { it.exists() }?.readText()?.trim()?.toInt() ?: 0
            repeat(2) { warm ->
                val t1 = System.nanoTime(); var first = 0L
                val audio = tts.generateWithCallback(text, sid, 1f, Piper.OnChunk { if (first == 0L) first = System.nanoTime() })
                val dur = audio.samples.size / audio.sampleRate.toDouble()
                out("piper ${v.name} ${if (warm == 0) "cold" else "warm"} first_audio=${(first - t1) / 1_000_000}ms total=${ms(t1)}ms audio=${"%.1f".format(dur)}s rtf=${"%.2f".format(ms(t1) / 1000.0 / dur)}")
                if (warm == 1) { Piper.play(audio.samples, audio.sampleRate); Piper.play(Piper.robot(audio.samples, audio.sampleRate), audio.sampleRate) }
            }
            tts.release()
        }
        out(device())
    }

    /** Voice candidates in files/tts/cand/<lang>_<CC>-<name>-<quality>/: gender by measured pitch, every speaker id. */
    private fun benchVoices() {
        val root = File(filesDir, "tts"); val espeak = File(root, "espeak-ng-data")
        val lines = officerLines
        for (v in File(root, "cand").listFiles { f -> f.isDirectory }!!.sorted()) {
            val tts = Piper.load(v, espeak)
            val text = lines.getValue(v.name.take(2))
            for (sid in 0 until tts.numSpeakers()) {
                val t1 = System.nanoTime(); var first = 0L
                val audio = tts.generateWithCallback(text, sid, 1f, Piper.OnChunk { if (first == 0L) first = System.nanoTime() })
                val dur = audio.samples.size / audio.sampleRate.toDouble()
                val f0 = Voices.medianF0(wav16(audio.samples, audio.sampleRate))
                out("voice ${v.name} sid=$sid f0=${f0?.let { "%.0f".format(it) }} gender=${if (f0 == null) "?" else if (f0 < 165) "m" else "f"} first_audio=${(first - t1) / 1_000_000} rtf=${"%.2f".format(ms(t1) / 1000.0 / dur)}")
                File(v, "s$sid.wav").writeBytes(wav16(audio.samples, audio.sampleRate))
            }
            tts.release()
        }
        out(device())
    }

    private fun wav16(pcm: FloatArray, rate: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44 + pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach { b.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        return b.array()
    }

    private suspend fun benchTts() {
        val t = Models.tts(this)
        out("== TTS engine=${t.defaultEngine}")
        for (l in listOf("de", "fr", "es", "it", "ru", "uk", "ar", "tr", "ja", "zh", "pl", "pt")) {
            val loc = Locale.forLanguageTag(l)
            val offline = t.voices.filter { it.locale.language == loc.language && !it.isNetworkConnectionRequired }
            out("tts $l available=${t.isLanguageAvailable(loc)} offline_voices=${offline.size} ${offline.take(3).joinToString { it.name }}")
        }
        t.shutdown()
    }

    companion object { const val TAG = "BORDER" }
}

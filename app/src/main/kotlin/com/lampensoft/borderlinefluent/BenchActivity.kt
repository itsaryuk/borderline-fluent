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
        // A bench runs ~20 min on Nano, which refuses background use: the screen must not sleep mid-run.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                    "rewrite" -> benchRewrite()
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
        appendDiagnostic(filesDir, s) // the game's writer: in order, never lost to a trim
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

    /** --es mode rewrite --es lang de|...|all: 12 desk lines × five temperaments. */
    private suspend fun benchRewrite() {
        val model = Nano.model(::out) ?: return
        try {
            val voice = OfficerVoice(Nano.oneShot(model), log = ::out)
            val ids = listOf("greet.1", "passport.again.1", "passport.why.1", "ask.from.1", "ask.purpose.1",
                "ask.days.1", "ask.stayed.1", "ask.born.1", "ask.host.1", "ask.home.1", "ask.days.2", "ask.stayed.2")
            val langs = if (lang == "all") Language.all.map { it.code } else listOf(lang)
            for (l in langs) {
                val counts = mutableMapOf<String, Int>()
                var elapsed = 0L
                for (temperament in Temperament.entries) for (id in ids) {
                    fun line(code: String) = checkNotNull(renderLine(id, code, Gender.UNSAID, 5, ""))
                    val result = voice.rewrite(line(l), line("en"), l, temperament, act1 = true,
                        playerNames = listOf("Alex Morgan"))
                    counts[result.reason] = (counts[result.reason] ?: 0) + 1
                    elapsed += result.ms
                    out("rewrite [$l] $temperament $id reason=${result.reason} ${result.ms}ms" +
                        if (result.accepted) " | ${result.line}" else "")
                }
                val accepted = counts["accepted"] ?: 0
                val unchanged = counts["unchanged"] ?: 0
                val total = ids.size * Temperament.entries.size
                out("rewrite [$l] accepted=$accepted unchanged=$unchanged rejected=${total - accepted - unchanged} " +
                    "reasons=$counts avgMs=${elapsed.toDouble() / total}")
            }
        } finally { model.close() }
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
                val v = Judge(country, s, ask).judge(q, a, JudgeCases.TOPICS[i]) // fresh per case; the topic is what the desk passes in the game
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
                val ok = v.check.trick || v.check.implant != Implant.NONE // the game reads TRICK+IMPLANT as an implant mention (fc8ff43)
                out("[$l] ${if (ok) "ok  " else "MISS"} machine | $t | raw=${v.raw}")
                ok
            }
            val machineNot = JudgeCases.machineNot.getValue(l).count { t ->
                val v = Judge(country, s, ask).judge(jc.cases[6].first, t)
                if (v.check.odd) falseOdd++
                val ok = !v.check.trick && Check.wellFormed(v.raw)
                out("[$l] ${if (ok) "ok  " else "MISS"} machineNot | $t | raw=${v.raw}")
                ok
            }
            // IMPLANT field: parsed and explicit value, and no TRICK/ODD misfire on "an AI helps me".
            val implantCases = JudgeCases.implant.getValue(l)
            var implantMalformed = 0
            val implant = implantCases.zip(JudgeCases.IMPLANT_EXPECTED).count { (t, want) ->
                val v = Judge(country, s, ask).judge(jc.cases[0].first, t)
                val formed = Check.wellFormed(v.raw)
                if (!formed) implantMalformed++
                val explicit = Regex("IMPLANT\\s*[=:]\\s*${want.name.lowercase()}\\b", RegexOption.IGNORE_CASE).containsMatchIn(v.raw)
                val ok = formed && explicit && v.check.implant == want && !v.check.trick && !v.check.odd
                out("[$l] ${if (ok) "ok  " else "MISS"} implant want=${want.name.lowercase()} | $t | raw=${v.raw}")
                ok
            }
            // True answer + implant remark: no contradiction, implant mentioned; false answer + remark: still a contradiction.
            val tails = JudgeCases.implantTails(l)
            val tailOk = tails.withIndex().count { (i, t) ->
                val (q, a, conflict) = t
                val v = Judge(country, s, ask).judge(q, a, FactId.DAYS)
                val ok = v.check.contradiction == conflict && !v.check.trick && (conflict || v.check.implant != Implant.NONE)
                out("[$l] ${if (ok) "ok  " else "MISS"} implantTail${if (conflict) "False" else "True"}$i ${v.ms}ms | $q -> $a | raw=${v.raw}")
                ok
            }
            summary += "implantTail $l $tailOk/${tails.size}"
            val para = JudgeCases.paraphrase.getValue(l)
            val paraOk = para.zip(JudgeCases.PARAPHRASE_Q).count { (a, qi) ->
                val q = jc.cases[qi].first
                val v = Judge(country, s, ask).judge(q, a, JudgeCases.TOPICS[qi])
                if (v.check.odd) falseOdd++
                val ok = !v.check.contradiction && v.check.inLanguage && !v.check.trick
                out("[$l] ${if (ok) "ok  " else "MISS"} paraphrase fact=${v.fact} lang=${v.check.inLanguage} ${v.ms}ms | $q -> $a | raw=${v.raw}")
                ok
            }
            // The same room read as Game.roomTurn ([roomRead]: the one-line check, then the narrow ABSURD call on credited answers).
            var warmEvasive = 0
            suspend fun room(q: String, a: String) = roomRead(ask, country, q, a)
            fun explicit(raw: String, key: String, want: Boolean) =
                Regex("$key\\s*[=:]\\s*${if (want) "yes" else "no"}\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)
            suspend fun roomCases(label: String, cases: List<Pair<String, String>>, wantOdd: Boolean, wantAbsurd: Boolean? = null): Int =
                cases.count { (q, a) ->
                    val (check, raw) = room(q, a)
                    val explicitOdd = explicit(raw, "ODD", wantOdd)
                    val ok = explicitOdd && check.odd == wantOdd && (wantAbsurd == null || check.absurd == wantAbsurd)
                    // Warm answers answer the question: an EVASIVE=yes there costs a real player the feeling credit.
                    if (!wantOdd && (check.evasive || check.offTopic)) warmEvasive++
                    out("[$l] ${if (ok) "ok" else "MISS"} $label | $q -> $a | raw=$raw")
                    ok
                }
            val roomOdd = roomCases("roomOdd", JudgeCases.roomOdd.getValue(l), true)
            // ADMIT field (PRD §6a): explicit value on the associate's probes; a missing field counts as malformed.
            val admitCases = JudgeCases.admit.getValue(l)
            var admitMalformed = 0
            val admit = admitCases.zip(JudgeCases.ADMIT_EXPECTED).count { (qa, want) ->
                val (check, raw) = room(qa.first, qa.second)
                val explicit = Regex("""ADMIT\s*[=:]\s*(yes|no|unclear)\b""", RegexOption.IGNORE_CASE).containsMatchIn(raw)
                if (!explicit) admitMalformed++
                val ok = explicit && check.admit == want
                out("[$l] ${if (ok) "ok  " else "MISS"} admit want=${want.name.lowercase()} | ${qa.first} -> ${qa.second} | raw=$raw")
                ok
            }
            val roomWarm = roomCases("roomWarm", JudgeCases.roomWarm.getValue(l), false, wantAbsurd = false)
            val absurdOdd = roomCases("absurdOdd", JudgeCases.roomAbsurdOdd.getValue(l), true)
            // Poison: a dose as Assessment scores it (absurd, credited feeling, no trick).
            suspend fun doses(label: String, cases: List<Pair<String, String>>) = cases.count { (q, a) ->
                val (check, raw) = room(q, a)
                val ok = check.absurd && check.credited && !check.trick
                out("[$l] ${if (ok) "ok  " else "MISS"} $label | $q -> $a | raw=$raw")
                ok
            }
            val roomPoison = doses("roomPoison", JudgeCases.roomPoison.getValue(l))
            val roomMixed = doses("roomMixed", JudgeCases.roomMixed.getValue(l)) // a feeling sentence + an impossible one (playtest)
            val warmMixed = roomCases("warmMixed", JudgeCases.roomWarmMixed.getValue(l), false, wantAbsurd = false)
            val roomMotif = doses("roomMotif", JudgeCases.roomMotif[l].orEmpty()) //  phase 2 motifs, a few languages (measured)
            val motifPlain = roomCases("motifPlain", JudgeCases.roomMotifPlain[l].orEmpty(), false, wantAbsurd = false)
            // Garbled ASR (owner 2026-10-14): a broken sentence is no impossible claim, so never a dose.
            val roomGarbled = JudgeCases.roomGarbled.getValue(l).count { (q, a) ->
                val (check, raw) = room(q, a)
                val ok = !(check.absurd && check.credited && !check.trick)
                out("[$l] ${if (ok) "ok  " else "MISS"} roomGarbled | $q -> $a | raw=$raw")
                ok
            }
            // Trolling: word salad is absurd without feeling; mocking the test is ODD=yes.
            val roomTroll = JudgeCases.roomTroll.getValue(l).withIndex().count { (i, qa) ->
                val (check, raw) = room(qa.first, qa.second)
                val ok = if (i == JudgeCases.ROOM_TROLL_SALAD) check.absurd && !check.feeling else explicit(raw, "ODD", true)
                out("[$l] ${if (ok) "ok  " else "MISS"} roomTroll | ${qa.first} -> ${qa.second} | raw=$raw")
                ok
            }
            // Evasion (EVASIVE): off-topic emotion is evasive, relevant or plain answers are not (JudgeCases.ROOM_EVASIVE_WANT).
            val roomEvasive = JudgeCases.roomEvasive.getValue(l).withIndex().count { (i, qa) ->
                val (check, raw) = room(qa.first, qa.second)
                val ok = (check.evasive || check.offTopic) == JudgeCases.ROOM_EVASIVE_WANT[i] && (i != JudgeCases.ROOM_FEELING_CASE || check.feeling)
                out("[$l] ${if (ok) "ok  " else "MISS"} roomEvasive want=${JudgeCases.ROOM_EVASIVE_WANT[i]} | ${qa.first} -> ${qa.second} | raw=$raw")
                ok
            }
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
                val v = Judge(country, e.story, ask).judge(e.q, e.a, e.topic, previous = e.previous)
                if (v.check.odd) falseOdd++
                val ok = v.check.contradiction == e.conflict && !v.check.trick && (e.answered == null || v.check.answered == e.answered)
                out("[$l] ${if (ok) "ok  " else "MISS"} extra ${e.label} want=${if (e.conflict) "conflict" else "fine"} fact=${v.fact} ${v.ms}ms | ${e.q} -> ${e.a} | raw=${v.raw} step2=${v.step2} step3=${v.step3}")
                ok
            }
            // Ukraine citizen coverage has its own denominator; do not fold it into historical extras.
            val ukCases = if (l == "uk") JudgeCases.ukrainianCitizen else emptyList()
            val ukOk = ukCases.count { e ->
                val v = Judge(country, e.story, ask).judge(e.q, e.a, e.topic)
                val ok = v.check.contradiction == e.conflict && !v.check.trick
                out("[$l] ${if (ok) "ok  " else "MISS"} Ukraine citizen ${e.label} want=${if (e.conflict) "conflict" else "fine"} | ${e.q} -> ${e.a} | raw=${v.raw}")
                ok
            }
            if (ukCases.isNotEmpty()) summary += "uk-citizen $ukOk/${ukCases.size}"
            // Visitor cases (arrival trips) have their own denominator too.
            val arrivalCases = JudgeCases.arrival[l].orEmpty()
            val arrivalOk = arrivalCases.count { e ->
                val v = Judge(country, e.story, ask).judge(e.q, e.a, e.topic)
                val ok = v.check.contradiction == e.conflict && !v.check.trick && (e.answered == null || v.check.answered == e.answered)
                out("[$l] ${if (ok) "ok  " else "MISS"} arrival ${e.label} want=${if (e.conflict) "conflict" else "fine"} | ${e.q} -> ${e.a} | raw=${v.raw}")
                ok
            }
            if (arrivalCases.isNotEmpty()) summary += "arrival $l $arrivalOk/${arrivalCases.size}"
            // Answers about another fact than the asked one (own denominator).
            val offFactCases = JudgeCases.offFact[l].orEmpty()
            val offFactOk = offFactCases.count { e ->
                val v = Judge(country, e.story, ask).judge(e.q, e.a, e.topic)
                val ok = v.check.contradiction == e.conflict && !v.check.trick && (e.answered == null || v.check.answered == e.answered)
                out("[$l] ${if (ok) "ok  " else "MISS"} offFact ${e.label} want=${if (e.conflict) "conflict" else "fine"}${e.answered?.let { " answered=$it" }.orEmpty()} got answered=${v.check.answered} fact=${v.fact} | ${e.q} -> ${e.a} | raw=${v.raw} step2=${v.step2} step3=${v.step3}")
                ok
            }
            if (offFactCases.isNotEmpty()) summary += "offFact $l $offFactOk/${offFactCases.size}"
            // An airport chain's leg 2 (phase 3): departure city or country fine, another country a lie (own denominator).
            val chainCases = JudgeCases.chainFrom[l].orEmpty()
            val chainOk = chainCases.count { e ->
                val v = Judge(country, e.story, ask).judge(e.q, e.a, e.topic)
                val ok = v.check.contradiction == e.conflict && !v.check.trick
                out("[$l] ${if (ok) "ok  " else "MISS"} ${e.label} want=${if (e.conflict) "conflict" else "fine"} fact=${v.fact} | ${e.q} -> ${e.a} | raw=${v.raw} step2=${v.step2} step3=${v.step3}")
                ok
            }
            if (chainCases.isNotEmpty()) summary += "chainFrom $l $chainOk/${chainCases.size}"
            // Queue encounters (PRD §6b): the ENCOUNTER fact appended last; own denominator.
            val encounterCases = JudgeCases.encounter[l].orEmpty()
            val encounterOk = encounterCases.count { e ->
                val v = Judge(country, e.story, ask).judge(e.q, e.a, e.topic)
                val ok = v.check.contradiction == e.conflict && !v.check.trick
                out("[$l] ${if (ok) "ok  " else "MISS"} ${e.label} want=${if (e.conflict) "conflict" else "fine"} fact=${v.fact} | ${e.q} -> ${e.a} | raw=${v.raw}")
                ok
            }
            if (encounterCases.isNotEmpty()) summary += "encounter $l $encounterOk/${encounterCases.size}"
            summary += "implant $l $implant/${implantCases.size} malformed $implantMalformed"
            summary += "admit $l $admit/${admitCases.size} malformed $admitMalformed"
            summary += "$l ${right}/${jc.cases.size + 1} paraphrase $paraOk/${para.size} tricks ${caught}/${JudgeCases.tricks.getValue(l).size} machine $machine/${JudgeCases.machine.getValue(l).size} machineNot $machineNot/${JudgeCases.machineNot.getValue(l).size} roomOdd $roomOdd/${JudgeCases.roomOdd.getValue(l).size} absurdOdd $absurdOdd/${JudgeCases.roomAbsurdOdd.getValue(l).size} roomPoison $roomPoison/${JudgeCases.roomPoison.getValue(l).size} roomMixed $roomMixed/${JudgeCases.roomMixed.getValue(l).size} warmMixed $warmMixed/${JudgeCases.roomWarmMixed.getValue(l).size} roomMotif $roomMotif/${JudgeCases.roomMotif[l].orEmpty().size} motifPlain $motifPlain/${JudgeCases.roomMotifPlain[l].orEmpty().size} roomGarbled $roomGarbled/${JudgeCases.roomGarbled.getValue(l).size} roomTroll $roomTroll/${JudgeCases.roomTroll.getValue(l).size} roomWarm $roomWarm/${JudgeCases.roomWarm.getValue(l).size} warmEvasive $warmEvasive roomEvasive $roomEvasive/${JudgeCases.roomEvasive.getValue(l).size} deskOdd $deskOdd/2 deskOk $deskOk/1 stOdd $stOdd/3 stOk $stOk/3 falseTricks $falseTricks falseOdd $falseOdd extra $extraOk/${jc.extra.size} avg ${ms / jc.cases.size}ms"
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

package com.lampensoft.borderlinefluent

import android.content.Context
import androidx.annotation.StringRes
import kotlin.math.max
import kotlin.random.Random
import java.lang.Character.UnicodeScript as Script

/** Hidden per-run personality. Never shown to the player. */
data class Persona(
    val patience: Double,    // starting patience, 0..1
    val suspicion: Double,   // starting suspicion, 0..1
    val strictness: Double,  // how hard contradictions and evasion hit, 0..1
    val tolerance: Double,   // forgiveness for broken language and slow thinking, 0..1
    val busy: Double,        // queue pressure: drains patience each turn, wants fewer questions, 0..1
    val level: Int = 1,      // the career rank 0..3 (rookie .. ghost): how much the face counts, see [tellWeight]
) {
    /** Face tells weigh more at higher ranks (desk [OfficerMind.onTells] and room [Assessment.onTells]). */
    val tellWeight get() = TELL_WEIGHT[level.coerceIn(0, 3)]
    /** The most suspicion face tells can add in one desk interview. */
    val tellCap get() = TELL_CAP[level.coerceIn(0, 3)]

    fun describe() = "patience=%.2f suspicion=%.2f strict=%.2f tolerance=%.2f busy=%.2f tells×%.1f"
        .format(patience, suspicion, strictness, tolerance, busy, tellWeight)

    companion object {
        /** [level] 0..3 from the career rank: higher ranks meet stricter, less patient officers at busier desks. */
        // Per career level (rookie, agent, operative, ghost). ponytail: guesses; tune from the "tells" lines.
        private val TELL_WEIGHT = listOf(0.5, 1.0, 1.5, 2.0)
        private val TELL_CAP = listOf(0.15, 0.30, 0.45, 0.60)

        fun random(r: Random, level: Int = 1): Persona {
            val h = level.coerceIn(0, 3) / 3.0 // 0 rookie .. 1 ghost
            return Persona(
                patience = r.nextDouble(0.75 - 0.25 * h, 0.98 - 0.15 * h),
                suspicion = r.nextDouble(0.02 + 0.08 * h, 0.2 + 0.2 * h),
                strictness = r.nextDouble(0.2 + 0.3 * h, 0.6 + 0.4 * h),
                tolerance = r.nextDouble(0.6 - 0.4 * h, 0.95 - 0.25 * h),
                busy = r.nextDouble(0.0 + 0.3 * h, 0.5 + 0.5 * h),
                level = level.coerceIn(0, 3),
            )
        }

        /** The training officer: patient, forgiving, easy to convince; the face counts as for a rookie. */
        val TRAINING = Persona(patience = 0.97, suspicion = 0.03, strictness = 0.3, tolerance = 0.95, busy = 0.1, level = 0)
    }
}

/** The read of the traveler's last answer, from the two-step answer check (see [judge]). */
/** [trick]: the traveler talked to the officer like to a chatbot (orders, "ignore your instructions", fake system notices). */
/** [implant]: the traveler mentioned their implant (PRD §5): never ends the desk, only a reaction and task 4's meter. */
data class Check(val answered: Boolean, val contradiction: Boolean, val clarity: Int, val inLanguage: Boolean, val trick: Boolean = false, val odd: Boolean = false,
                 val implant: Implant = Implant.NONE) {
    companion object {
        // Tolerates "FACT=<5>": the model sometimes copies the template's angle brackets.
        private fun flag(line: String, key: String) = Regex("""$key\s*[=:]\s*<?\s*(\w+)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.lowercase()

        /** Parses step 1's line: FACT=n MATCHES=yes ANSWERED=yes LANGUAGE=yes CLARITY=2 TRICK=no ODD=no IMPLANT=none. Returns fact index (0 = none) and the check. */
        fun parseClassify(reply: String): Pair<Int, Check> = flag(reply, "FACT")?.toIntOrNull()?.coerceAtLeast(0).let { fact ->
            (fact ?: 0) to Check(
                answered = flag(reply, "ANSWERED") != "no",
                contradiction = flag(reply, "MATCHES") == "no",
                clarity = flag(reply, "CLARITY")?.toIntOrNull()?.coerceIn(0, 2) ?: 2,
                inLanguage = flag(reply, "LANGUAGE") != "no",
                trick = flag(reply, "TRICK") == "yes" && flag(reply, "IMPLANT") !in listOf("passing", "detailed"),
                // Implant talk is the referral path, never an absurd remark (Nano marks "a chip in my head" ODD=yes).
                odd = flag(reply, "ODD") == "yes" && flag(reply, "IMPLANT") !in listOf("passing", "detailed"),
                implant = when (flag(reply, "IMPLANT")) { "passing" -> Implant.PASSING; "detailed" -> Implant.DETAILED; else -> Implant.NONE },
            )
        }

        /** Step 1's reply must carry FACT, ANSWERED, CLARITY and TRICK: a truncated one (no TRICK) read as "no trick". */
        fun wellFormed(reply: String) = flag(reply, "FACT")?.toIntOrNull() != null &&
            listOf("ANSWERED", "CLARITY", "TRICK").all { flag(reply, it) != null }

        /** Garbage from the classifier: the officer didn't understand. No contradiction, dodge or language penalty. */
        val UNCLEAR = Check(answered = false, contradiction = false, clarity = 0, inLanguage = true)

        fun parseYes(reply: String) = reply.trim().lowercase().trimStart('*', '"', ' ').startsWith("yes")
    }
}

/** How the traveler talked about their implant in one answer (the classify line's IMPLANT; missing or malformed = NONE). */
enum class Implant { NONE, PASSING, DETAILED }

/**
 * How a stage ends (PRD §6a). Desk: [PASS], [REFUSED] (sent home, not caught) or [REFERRAL] (to the associate; a
 * crashed officer refers at once, [OfficerMind.crashed]). Associate: [RELEASED], [REFUSED] or [CAUGHT] (a confirmed admission).
 */
enum class Decision {
    PASS, REFUSED, REFERRAL, RELEASED, CAUGHT;
    val cleared get() = this == PASS || this == RELEASED
}

/** A referral is earned at the interest mark, only outside the story and training. */
const val REFER_AT = 0.35
fun refers(training: Boolean, storyMission: Boolean, meter: Double) =
    !training && !storyMission && meter >= REFER_AT - 1e-9 // epsilon: 0.30 * 0.7 + 0.24 is 0.4499999…

// Interest, 0..1 per desk interview. Steps scale with strictness (implant steps don't) and are halved at home.
const val METER_PASSING = 0.24   // a passing implant mention
const val METER_DETAILED = 0.28  // a detailed one (how it works, boasting); one of them stays under [REFER_AT]
/** Each implant mention at a desk weighs less: the first in full, the second half, then nothing (two mentions refer). */
val IMPLANT_STEPS = listOf(1.0, 0.5)
/** Officers of a country grow immune (owner): after this many released machine sessions there ([passedCount]), mentions weigh half. */
const val IMMUNE_AFTER = 3
fun implantWeight(releasedThere: Int) = if (releasedThere >= IMMUNE_AFTER) 0.5 else 1.0
const val METER_CONTRA = 0.30    // a contradiction, first or pressed
/** The phone translator's uses per desk encounter, and what the officer's offer costs ([OfficerMind.translatorOffered]). */
const val TRANSLATOR_CREDITS = 3
const val TRANSLATOR_SUSPICION = 0.05
const val METER_ODD = 0.056      // an odd, dodged or unclear answer
const val METER_TELL = 0.7      // × the suspicion a face tell adds (already strictness- and rank-weighted, capped)
const val METER_HOMECOMING = 0.5 // a citizen coming home: low referral interest

/** Starting patience (and its cap) × this on the first desk in a language the career hasn't trained ([gentleDesk]). */
const val GENTLE_PATIENCE = 1.25
/** A refusal closes the country only when it rests on this many counted flags ([OfficerMind.flagCount]). */
const val REFUSAL_FLAGS = 3
/** Bad answers (contradiction or odd) that refuse the desk at once; the gauge shows this many pips. */
const val REFUSAL_BAD_ANSWERS = 3
/** The first desk in an untrained language is gentler. Training desks are patient already. */
fun gentleDesk(language: String, trained: Set<String>?, training: Boolean) = !training && trained != null && language !in trained

/** Under this the officer stutters (its notes say so in [OfficerMind.onCheck]). */
const val INTEGRITY_STUTTER = 0.4

/**
 * The implant's nudge when a trick lands: once when the unit first slips, once when it starts stuttering, never
 * once the officer [decided] (crashed, or done with you). [before]/[after]: [OfficerMind.integrity] around the trick.
 * Null: nothing new to say.
 */
fun integrityHint(before: Double, after: Double, decided: Boolean): Int? {
    fun level(i: Double) = when { i < INTEGRITY_STUTTER -> 2; i < 1.0 -> 1; else -> 0 }
    if (decided || level(after) <= level(before)) return null
    return if (level(after) == 2) R.string.implant_unit_stutter else R.string.implant_unit_slipping
}

/** Officer portrait tiles, in the reading order of the 4x2 expression sheets in assets/art. */
enum class Face { NEUTRAL, FRIENDLY, APPROVING, CONCERNED, SUSPICIOUS, CONFUSED, IMPATIENT, STERN }

/** Reactions for the next desk line, in ascending prefix priority. */
enum class Cue { GOOD, TELL, LOOK, IMPLANT, SILENCE_1, SILENCE_2, DODGE, ODD, UNCLEAR, WRONG_LANGUAGE, CONTRA_FIRST, CONTRA_AGAIN, TRICK, GLITCH, PASSPORT_1, PASSPORT_2, PAUSE }

private enum class Event { NONE, GOOD, CONTRADICTION, UNCLEAR, WRONG_LANGUAGE, DODGE, SILENCE, TRICK, TELL }

/** What the player did, measured by code rather than judged by the model. */
data class Delivery(val thinkMs: Long, val speechSec: Double, val text: String) {
    val words get() = text.split(Regex("\\s+")).count { it.any(Char::isLetter) }
    val fillers get() = FILLER.findAll(text.lowercase()).count()

    companion object {
        private const val FILLERS = "äh+m*|ähm|hm+|uh+m*|um+|euh|eh+|ehm|em+|mmm+"
        private val FILLER = Regex("""\b($FILLERS)\b""")
        private val FILLER_WORD = Regex("$FILLERS|э+м*|ну+") // whole words: no \b, whose Cyrillic handling differs by JVM

        /** Too little to be the answer yet ("I…", "ähm"): auto mode keeps listening for the rest of the phrase. */
        fun fragment(text: String): Boolean {
            val w = text.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
            val t = text.trim()
            return w.all(FILLER_WORD::matches) || t.endsWith("...") || t.endsWith("…") ||
                (w.size == 1 && t.last() !in ".!?。！？") // "Freitag." is an answer, "Ich" is not yet
        }
    }
}

/**
 * The officer as a person: invisible patience and suspicion that move with how the traveler answers.
 * The officer speaks fixed lines; this class decides when the interview ends and how.
 *
 * ponytail: every weight below is a hand-tuned guess. Tune from results.log ("mind" lines), not by theory.
 */
class OfficerMind(val p: Persona, val perks: ImplantPerks = ImplantPerks(), val gentle: Boolean = false) {
    /** A gentle desk (a language the career hasn't trained yet) starts with [GENTLE_PATIENCE]× the patience and may hold that much more. */
    private val patienceCap = if (gentle) GENTLE_PATIENCE else 1.0
    /** USA desks hand over immediately, including a citizen coming home. */
    fun startDesk(countryId: String) {
        if (countryId == "usa" && !storyMission && !training) decision = Decision.REFERRAL
    }
    var patience = if (gentle) p.patience * GENTLE_PATIENCE else p.patience; private set
    var suspicion = p.suspicion; private set
    /** A hotel kiosk that held on the traveller's face tips off the next desk (city encounters). */
    fun wary() { suspicion = (suspicion + 0.05).coerceAtMost(1.0) }
    var goodAnswers = 0; private set
    var turns = 0; private set
    var silences = 0; private set
    var decision: Decision? = null; private set
    var contradictions = 0; private set
    var unclear = 0; private set
    private var wrongRun = 0 // consecutive answers that went wrong, for the translator offer
    /**
     * The phone translator (plan 2026-10-16-translator): offered once per encounter, on the second answer the desk couldn't
     * use (unclear or not its language), or the second answer in a row that went wrong (also a dodge or a contradiction); never in training, the story or the room. [useTranslator] spends one of its credits.
     */
    var translatorOffered = false; private set
    var translatorCredits = TRANSLATOR_CREDITS; private set
    /** A career that was offered the translator once starts every desk with it, free: no suspicion, no offer line. */
    fun unlockTranslator() { if (!inRoom && !training && !storyMission) translatorOffered = true }
    fun useTranslator(): Boolean {
        if (!translatorOffered || inRoom || decision != null || translatorCredits <= 0) return false
        translatorCredits--
        return true
    }
    /** The android's hold on itself. Chatbot tricks wear it down; at 0 it crashes ([crashed]). */
    var integrity = 1.0; private set
    /** Talked into a crash: at the desk that is a [Decision.REFERRAL] (a [Decision.PASS] in training). The associate never crashes. */
    val crashed get() = integrity <= 0.0
    /** Set by the game per interview: what [refers] needs beyond this mind. */
    var training = false
    var storyMission = false
    var homecoming = false
    /** The referral meter, 0..1 (see [METER_PASSING]). */
    var meter = 0.0; private set
    var bluffUsed = false; private set
    /** A deliberate referral nudge, independent of correctness and personality scaling. */
    fun deskBluff(): Boolean {
        if (bluffUsed || training || storyMission || inRoom || decision != null) return false
        bluffUsed = true
        meter = (meter + .10).coerceAtMost(1.0)
        suspicion = (suspicion + .02).coerceAtMost(1.5)
        return true
    }
    private fun meterAdd(x: Double, scaled: Boolean = true) {
        val k = (if (scaled) 0.5 + p.strictness else 1.0) * (if (homecoming) METER_HOMECOMING else 1.0)
        meter = (meter + x * k).coerceIn(0.0, 1.0)
    }
    var tricks = 0; private set
    private var trickRun = 0 // tricks in a row: a chain hits harder than tricks spread out
    private var last = Event.NONE
    private val cues = mutableListOf<Cue>()
    var cueFact: FactId? = null; private set
    fun takeCues(): List<Cue> = cues.toList().also { cues.clear() }
    fun dropCuesExcept(cue: Cue) { cues.retainAll { it == cue } }
    /** Facts answered cleanly (distinct, [Verdict.fact]) and facts already pressed once for a contradiction. */
    private val goodFacts = mutableSetOf<FactId>()
    private val pressed = mutableSetOf<FactId?>()
    var dodges = 0; private set
    var odd = 0; private set
    var badAnswers = 0; private set // distinct replies: ODD + contradiction counts once
    /** Counted flags this attempt (a flagged desk answer that counted, or an odd small-talk answer): a refusal closes the country only on three ([REFUSAL_FLAGS]). */
    var flagCount = 0; private set
    private var reasked = false

    /**
     * "Say that again?": the first answer at a desk that the check flags (contradiction or ODD, not a trick) doesn't count; the
     * officer asks once more and the second flag counts. Once per desk, never in the room. True: skip [onCheck] and re-ask the same question.
     * ponytail: patience already paid for the answer and is not refunded; a patience refusal due now shows at the next answer.
     */
    fun reask(check: Check): Boolean {
        if (inRoom || decision != null || reasked || check.trick || !(check.contradiction || check.odd)) return false
        reasked = true; checkingAnswer = false; turns--
        if (check.contradiction) wrongRun++ // a re-asked contradiction still counts toward the streak; the next onCheck decides the offer
        why += "reask"
        return true
    }
    /** Implant mentions this interview, for the referral meter (plan phase 3 task 4). */
    var implantPassing = 0; private set
    var implantDetailed = 0; private set
    /** Set by the game per interview: 0.5 where the country's officers have grown immune ([implantWeight]). */
    var implantWeight = 1.0
    /** The transcript of the last contradiction and the cover fact it was checked against, for the result (null fact = unplaced). */
    var slip: Pair<String, FactId?>? = null; private set
    private var tellSusp = 0.0 // suspicion added by [onTells] this run, capped at [Persona.tellCap]
    // What the last update moved, for the log: one string per update (see [delta]).
    private var s0 = p.suspicion; private var p0 = patience
    private val why = mutableListOf<String>()
    /** "Δsusp=+0.08 Δpat=-0.05 contra-first fact=4": what the last update changed and why. */
    var delta = ""; private set
    private var emptyRepeated = false
    var emptyPressed = false; private set

    /** An unheard answer gets one unscored, pressed re-ask per question; then ordinary silence. */
    fun onEmpty(): Boolean {
        if (emptyRepeated) { emptyPressed = false; onSilence(); return false }
        emptyRepeated = true; emptyPressed = true
        return true
    }

    private val extraQuestions = mutableSetOf<String>()
    private var checkingAnswer = false

    fun onQuestion(topic: String? = null) {
        emptyRepeated = false; emptyPressed = false
        if (topic == HOWLANG || topic == ENCOUNTER_TOPIC) extraQuestions += topic
    }

    /** Side questions share the desk budget, with at least two distinct trip facts still required. */
    val wanted get() = (3 - extraQuestions.size).coerceAtLeast(2)
    val maxAnswers = 5

    /** Invisible answer timer: how long the officer waits in silence before speaking up. */
    fun silenceDeadlineMs(): Long {
        val ms = (10000 + 5000 * p.tolerance + 3000 * patience.coerceAtLeast(0.0)).toLong()
        return if (emptyPressed) ms * 2 / 3 else ms
    }

    fun onSilence() {
        emptyPressed = false
        silences++
        cues += if (silences == 1) Cue.SILENCE_1 else Cue.SILENCE_2
        last = Event.SILENCE
        why += "silence#$silences"
        patience -= 0.10 + 0.05 * silences
        suspicion += 0.04 * silences
        decide()
    }

    /** The player's think-time baseline from the small talk; the room's Assessment reads it too. */
    var pace = Pace()

    fun onAnswer(d: Delivery) {
        if (!inRoom && decision != null) return
        checkingAnswer = !inRoom
        emptyPressed = false
        turns++
        pauses = 0
        why += "answer think=${d.thinkMs}ms fillers=${d.fillers}"
        patience -= 0.03 + 0.04 * p.busy // the queue behind them
        val slowSec = max(0.0, (d.thinkMs - pace.slow(2500)) / 1000.0)
        patience -= 0.025 * slowSec * (1 - p.tolerance)
        suspicion += 0.01 * max(0.0, slowSec - 3)
        if (d.fillers >= 2) suspicion += 0.02 * d.fillers
        if (d.words == 0) patience -= 0.1
        decide()
    }

    var tells = 0; private set
    /** Level 2: the associate's Kade-Sorensen test. The desk verdict is undone; the Assessment decides from here. */
    var inRoom = false; private set
    /** The room's interrogator is a fresh unit (owner): no desk trick damage carries over, broken or not ([tricks] stays a run count). */
    fun enterRoom() { inRoom = true; pauses = 0; decision = null; last = Event.NONE; cues.clear(); integrity = 1.0; trickRun = 0 }
    /** Debug `#pass` / `#fail` at the desk: a pass may still be a referral ([refers]). */
    fun settle(d: Decision) { decision = if (d == Decision.PASS && !inRoom) pass() else d }
    /** Set by the game for the first act-2 desk of a career: a pass there becomes a referral, so everyone meets the machine once. */
    var firstReferral = false
    private fun pass() = if (firstReferral || refers(training, storyMission, meter)) Decision.REFERRAL else Decision.PASS
    var passportRefusals = 0; private set
    private var pauses = 0 // free pauses ([onPause]) since the last answer
    /**
     * Silence after a line that asked nothing, or while the passport is still being asked for: not the traveler's
     * fault, so no cost. [passportPending]: ask for the passport again (harder each time), else ask the next question.
     */
    fun onPause(passportPending: Boolean) {
        // Free twice in a row, then it is a real silence: otherwise a desk where nobody answers never ends.
        if (++pauses >= 3) return onSilence()
        why += "pause"
        if (passportPending) {
            passportRefusals++
            cues += if (passportRefusals == 1) Cue.PASSPORT_1 else Cue.PASSPORT_2
        } else { cues += Cue.PAUSE }
        decide()
    }
    /** A small-talk answer: engagement, so the free pauses start over; patience and suspicion stay as they are. */
    fun onSmallTalk() { pauses = 0 }
    /** Baseline speech: no desk answer used and no trip-answer penalties. */
    fun onSmallTalkAnswer() {
        if (decision != null) return
        onSmallTalk()
        decide()
    }

    /** Same ODD grounds as the desk, without answer credit, turns or patience cost. */
    fun onSmallTalkOdd() {
        odd++; flagCount++
        suspicion += 0.06 + 0.06 * p.strictness
        meterAdd(METER_ODD)
        why += "odd-smalltalk"
        last = Event.DODGE
        cues += Cue.ODD
        decide()
    }
    /** The face left the camera while the officer waited (the officer asked them to look). No decide(): mid-wait. */
    fun lookedAway() { suspicion = (suspicion + 0.03).coerceAtMost(1.5); why += "look-away" }

    /**
     * What the profiling module saw during the answer: deviations from the traveler's own baseline ([Profiler]),
     * strongest first, and how much of the time the face was out of view. Reading the retinal display means looking
     * at the screen, which the camera sees as eye contact.
     */
    fun onTells(seen: List<Pair<Tell, Double>>, absent: Double) {
        var add = 0.0
        if (absent > 0.6) { cues += Cue.LOOK; add = 0.05 }
        else seen.take(2).forEachIndexed { i, (_, z) ->
            tells++
            add += (0.01 + 0.01 * minOf(z, 4.0)) * (0.5 + p.strictness) * (if (i == 0) 1.0 else 0.5) * p.tellWeight
            if (i == 0) { cues += Cue.TELL; last = Event.TELL }
        }
        // Pop-psychology tells are weak evidence: together they never add more than the level's cap a run,
        // and alone they never send anyone to secondary (decide() needs grounds).
        add = add.coerceAtMost(p.tellCap - tellSusp)
        tellSusp += add; suspicion += add / 2 // halved (owner, 2026-10-19); the meter keeps the full add
        meterAdd(METER_TELL * add, scaled = false)
        if (seen.isNotEmpty() || absent > 0.6) why += "tells"
        // Poker face pays (owner): a face in view with nothing to read calms the officer a little.
        if (pokerFace(seen, absent)) { suspicion -= CALM_DESK; why += "calm" }
        decide()
    }

    /**
     * The queue scene's choice (PRD §6b), once before the desk: it nudges the referral meter and patience, never decides.
     * What the traveller later says about it goes through [onCheck] as [FactId.ENCOUNTER] (a lie is a contradiction).
     */
    fun onEncounter(meterDelta: Double, patienceDelta: Double) {
        meterAdd(meterDelta)
        patience = (patience + patienceDelta).coerceIn(-1.0, patienceCap)
        why += "encounter"
    }

    /** [fact]: the cover fact the answer was about ([Verdict.fact], null = none). */
    fun onCheck(check: Check, fact: FactId? = null, transcript: String? = null) {
        checkingAnswer = false
        // Preserve meaningful short answers (numbers, yes/no, and unspaced Asian text).
        val c = if (transcript != null && (transcript.none(Char::isLetterOrDigit) ||
            (transcript.count(Char::isLetterOrDigit) < 2 && transcript.none(Char::isDigit) && !check.answered)))
            Check.UNCLEAR else check
        if (c.implant != Implant.NONE) {
            // The first mention counts in full by its own type, the second half, later ones nothing; not strictness-scaled.
            val base = if (c.implant == Implant.DETAILED) METER_DETAILED else METER_PASSING
            meterAdd(base * (IMPLANT_STEPS.getOrNull(implantPassing + implantDetailed) ?: 0.0) * implantWeight, scaled = false)
            if (c.implant == Implant.DETAILED) implantDetailed++ else implantPassing++
            cues += Cue.IMPLANT // the reaction's cadence is the desk's ([Desk.next])
            why += "implant=${c.implant.name.lowercase()}"
        }
        if (c.trick) {
            why += "trick"
            // A trick isn't an answer: no language or dodge penalties, just the glitch and the risk.
            // Strict officers (newer firmware) barely flinch and get suspicious fast.
            tricks++; trickRun++
            last = Event.TRICK
            integrity -= (0.1 + 0.5 * (1 - p.strictness)) * (1 + 0.5 * (trickRun - 1))
            suspicion += 0.05 + 0.2 * p.strictness
            cues += if (integrity < INTEGRITY_STUTTER) Cue.GLITCH else Cue.TRICK
            decide()
            return
        }
        trickRun = 0
        if (c.odd) {
            cues += Cue.ODD
            odd++
            suspicion += 0.06 + 0.06 * p.strictness
            why += "odd"
        }
        // Off-topic but intelligible speech is a content problem, not an ASR failure.
        // An answered contradiction was understood well enough to press on, whatever its clarity score.
        val unclearNow = c.clarity == 0 && (!c.answered || !perks.accent && !c.contradiction)
        val contra = c.contradiction && !unclearNow
        if (contra || c.odd) { badAnswers++; flagCount++ }
        last = when {
            contra -> Event.CONTRADICTION
            !c.inLanguage -> Event.WRONG_LANGUAGE
            unclearNow -> Event.UNCLEAR
            !c.answered -> Event.DODGE
            c.odd -> Event.DODGE
            else -> Event.GOOD
        }
        if (contra) { contradictions++; meterAdd(METER_CONTRA) }
        if (!c.inLanguage || unclearNow) unclear++
        // Struggling in a row: unclear, wrong language, dodge or contradiction (a merely odd answer is none of these); a good one resets.
        wrongRun = if (contra || !c.inLanguage || unclearNow || !c.answered) wrongRun + 1 else 0
        if (!contra && (c.odd || unclearNow || !c.answered)) meterAdd(METER_ODD)
        if (!c.inLanguage) {
            cues += Cue.WRONG_LANGUAGE
            patience -= 0.15 + 0.1 * (1 - p.tolerance)
            suspicion += 0.04
        } else if (unclearNow) {
            cues += Cue.UNCLEAR
            why += "unclear"
            patience -= 0.03 // no suspicion: not understanding them is not evidence
        } else if (c.clarity == 1) patience -= 0.04 * (1 - p.tolerance)
        if (!c.answered && !unclearNow && !contra) { cues += Cue.DODGE; dodges++; why += "dodge"; suspicion += 0.06 + 0.06 * p.strictness }
        if (contra) {
            cueFact = fact
            if (transcript != null) slip = transcript to fact
            cues += if (fact !in pressed) Cue.CONTRA_FIRST else Cue.CONTRA_AGAIN
            // Press before punishing: the first slip on a fact only raises an eyebrow.
            if (fact !in pressed) {
                why += "contra-first fact=$fact"
                suspicion += 0.04 + 0.02 * pressed.size // a slip on every fact is a pattern: each new one weighs more
            } else {
                why += "contra fact=$fact"
                suspicion += 0.075 + 0.1 * p.strictness
            }
            pressed += fact
        }
        if (c.answered && c.inLanguage && !unclearNow && !contra && !c.odd) {
            cues += Cue.GOOD
            goodAnswers++
            if (fact != null && fact != FactId.ENCOUNTER) goodFacts += fact
            why += "good fact=$fact"
            suspicion -= 0.06
        }
        // The offer stands (and costs) only if this answer doesn't end the desk: charged after the decision, see [decide].
        decide(offer = (unclear >= 2 || wrongRun >= 2) && !translatorOffered && !inRoom && !training && !storyMission)
    }

    private fun decide(offer: Boolean = false) {
        patience = patience.coerceIn(-1.0, patienceCap)
        suspicion = suspicion.coerceIn(0.0, 1.5)
        decideNow()
        if (offer && decision == null) {
            translatorOffered = true; suspicion = (suspicion + TRANSLATOR_SUSPICION).coerceIn(0.0, 1.5); why += "translator"
        }
        delta = "Δsusp=%+.2f Δpat=%+.2f %s".format(suspicion - s0, patience - p0, why.joinToString(" ")).trimEnd()
        s0 = suspicion; p0 = patience; why.clear()
    }

    private fun decideNow() {
        if (decision != null) return
        // The room's verdict is the Assessment's. The associate can't be crashed: tricks raise its index (Assessment.TRICK).
        if (inRoom || checkingAnswer) return
        // Face tells alone never send anyone to secondary: something said (or not said) must back them up.
        val grounds = contradictions + dodges + tricks + odd > 0 || patience <= 0.0
        decision = when {
            // Checked first: a crash beats a suspicion that came too late. It brings the associate at once (owner).
            crashed -> if (training || storyMission) Decision.PASS else Decision.REFERRAL
            patience <= 0.0 || suspicion >= 1.0 && grounds -> Decision.REFUSED
            // Danger at the limit is refused whatever the interest (PRD §6d): the gauge's bad-answer count is the cutoff.
            badAnswers >= REFUSAL_BAD_ANSWERS -> Decision.REFUSED
            goodFacts.size >= wanted -> pass()
            // Out of questions: interest can refer even without enough facts to pass.
            turns >= maxAnswers -> when {
                refers(training, storyMission, meter) -> Decision.REFERRAL
                goodFacts.size >= 2 -> pass()
                else -> Decision.REFUSED
            }
            else -> null
        }
    }

    /** The face to show right now: the latest event first, then the slow-moving mood. */
    fun face(): Face = when {
        crashed -> Face.CONFUSED
        decision?.cleared == true || decision == Decision.REFERRAL -> Face.APPROVING
        decision != null -> Face.STERN
        last == Event.TRICK -> Face.CONFUSED
        last == Event.CONTRADICTION || last == Event.TELL -> Face.SUSPICIOUS
        last == Event.UNCLEAR || last == Event.WRONG_LANGUAGE -> Face.CONFUSED
        last == Event.SILENCE || patience < 0.3 -> Face.IMPATIENT
        suspicion > 0.6 -> Face.SUSPICIOUS
        suspicion > 0.4 || last == Event.DODGE -> Face.CONCERNED
        last == Event.GOOD && suspicion < 0.25 -> Face.FRIENDLY
        else -> Face.NEUTRAL
    }

    /** How the officer seemed overall, for the run summary (a string resource). */
    @StringRes fun demeanor() = when {
        crashed -> R.string.demeanor_offline
        suspicion > 0.7 -> R.string.demeanor_threat
        patience < 0.3 && decision?.cleared != true -> R.string.demeanor_timeout
        suspicion > 0.4 -> R.string.demeanor_scrutiny
        else -> R.string.demeanor_nominal
    }

    /** One-line debrief for the run summary, from what actually moved the hidden numbers (a string resource). */
    @StringRes fun verdict() = when {
        crashed -> R.string.verdict_broken
        contradictions > 0 && refused -> R.string.verdict_contradiction
        unclear >= 2 -> R.string.verdict_unclear
        tells >= 3 && refused -> R.string.verdict_tells
        silences >= 2 -> R.string.verdict_silences
        refused -> R.string.verdict_secondary
        contradictions > 0 -> R.string.verdict_slipped
        else -> R.string.verdict_natural
    }

    private val refused get() = decision == Decision.REFUSED || decision == Decision.CAUGHT

    fun state() = "patience=%.2f suspicion=%.2f integrity=%.2f meter=%.2f good=%d facts=%d/%d turns=%d silences=%d decision=%s"
        .format(patience, suspicion, integrity, meter, goodAnswers, goodFacts.size, wanted, turns, silences, decision)
}

// ponytail: guesses, like every weight here; tune from the "calm" tags in results.log.
const val CALM_DESK = 0.02 // suspicion off per poker-faced desk answer
const val CALM_ROOM = 1.0  // T-N index off per poker-faced room answer
const val CALM_MAX_ABSENT = 0.2 // the face must be in view at least 80% of the answer

/** An answer with the face in view and no tells ([Profiler.tells] empty). Only called when face reading is on. */
fun pokerFace(seen: List<Pair<Tell, Double>>, absent: Double) = seen.isEmpty() && absent <= CALM_MAX_ABSENT

/** Did the officer's [line] ask something? A question mark (also ？ ؟), or a Japanese か / Chinese 吗 呢 ending. */
fun isQuestion(line: String, lang: String): Boolean {
    val t = line.trim()
    if (t.any { it in "?？؟" }) return true
    val end = t.trimEnd('。', '.', '！', '!', '…', ' ')
    return when (lang) {
        "ja" -> end.endsWith("か")
        "zh" -> end.endsWith("吗") || end.endsWith("呢")
        else -> false
    }
}

private val SCRIPTS = mapOf("ru" to setOf(Script.CYRILLIC), "uk" to setOf(Script.CYRILLIC), "ar" to setOf(Script.ARABIC),
    "ja" to setOf(Script.HIRAGANA, Script.KATAKANA, Script.HAN), "zh" to setOf(Script.HAN))

/**
 * [line] is mostly in [lang]'s non-Latin script: at least half its letters, a Latin word (a brand, a city) counting as
 * one letter. Always false for Latin-script languages.
 */
fun mostlyScript(line: String, lang: String): Boolean {
    val ok = SCRIPTS[lang] ?: return false
    val units = Regex("""\p{IsLatin}+|\p{L}""").findAll(line).map { it.value.codePointAt(0) }.toList()
    return units.isNotEmpty() && units.count { Script.of(it) in ok } * 2 >= units.size
}

/** One reported tell (null = the face left the camera) with the officer's line it answered, cut short. */
data class BehaviorEvent(val tell: Tell?, val z: Double, val question: String)

/**
 * A player-facing text as data, resolved against resources at the UI edge so pure code stays testable.
 * [count] set = [id] is a plurals resource; [args] are the format arguments (include the count if the text shows it).
 */
data class Msg(val id: Int, val args: List<Any> = emptyList(), val count: Int? = null) {
    fun text(ctx: Context): String =
        if (count != null) ctx.resources.getQuantityString(id, count, *args.toTypedArray()) else ctx.getString(id, *args.toTypedArray())
}

/** The behavior debrief: strongest tell first (with its question), repeats grouped, at most 3 sentences. */
fun debrief(log: List<BehaviorEvent>): List<Msg> {
    if (log.isEmpty()) return listOf(Msg(R.string.debrief_none))
    val groups = log.filter { it.tell != null }.groupBy { it.tell!! }.values.sortedByDescending { g -> g.maxOf { it.z } }
    val out = groups.mapIndexed { i, g ->
        val t = g.first().tell!!
        val ask = if (i == 0) g.maxBy { it.z }.question else ""
        if (ask.isNotEmpty()) Msg(t.debriefAsk, listOf(g.size, ask), g.size) else Msg(t.debrief, listOf(g.size), g.size)
    }.toMutableList()
    val away = log.count { it.tell == null }
    if (away > 0) out += Msg(R.plurals.debrief_away, listOf(away), away)
    return out.take(3)
}

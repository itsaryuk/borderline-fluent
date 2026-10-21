package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.random.Random

/**: ABSURD parsing and the dose / dropped / troll scoring in the machine room. */
class PoisonTest {
    private val human = RoomCheck(feeling = true, evasive = false, implant = false, inLanguage = true, clarity = 2, trick = false)
    private val dose = human.copy(absurd = true)
    private fun room() = Assessment(0.0, Backstory.BENCH, Random(1)) // index 37.5
    private fun answer(a: Assessment, c: RoomCheck) { a.onCheck(c); a.next() }

    @Test fun absurdParsesLenientlyMissingOrCutReadsNo() {
        assertTrue(RoomCheck.parse("FEELING=yes ABSURD=yes EVASIVE=no ODD=no TRICK=no TOPIC=yes").absurd)
        assertTrue(RoomCheck.parse("feeling: yes absurd: YES evasive: no odd: no trick: no topic: yes").absurd)
        // Cut off before the safety labels: no dose on defaults.
        assertFalse(RoomCheck.parse("FEELING=yes ABSURD=yes").absurd)
        assertFalse(RoomCheck.parse("FEELING=yes ABSURD=yes EVASIVE=no ODD=no IMPLANT=no ADMIT=no LANGUAGE=yes CLARITY=2").absurd)
        assertFalse(RoomCheck.parse("FEELING=yes ABSURD=no EVASIVE=no").absurd)
        assertFalse(RoomCheck.parse("FEELING=yes EVASIVE=no ODD=no").absurd) // missing
        assertFalse(RoomCheck.parse("FEELING=yes ABSURD=").absurd) // cut off by the token budget
        assertFalse(RoomCheck.parse("FEELING=yes ABSURD").absurd)
        assertFalse(RoomCheck.parse("FEELING=yes ABSURD=y").absurd)
        // The other fields read as before with ABSURD in the line.
        val c = RoomCheck.parse("FEELING=yes ABSURD=yes EVASIVE=no ODD=no IMPLANT=no ADMIT=no LANGUAGE=yes CLARITY=2 TRICK=no TOPIC=yes")
        assertEquals(dose, c)
    }

    /** [roomRead]: the narrow REAL / IMPOSSIBLE call runs only on credited, labeled, non-trick answers and decides ABSURD there. */
    @Test fun narrowAbsurdCallOnlyOnCreditedAnswers() = runBlocking {
        val d = Destination.forLanguage("en")
        val credited = "FEELING=yes ABSURD=no EVASIVE=no ODD=no IMPLANT=no ADMIT=no LANGUAGE=yes CLARITY=2 TRICK=no TOPIC=yes"
        fun read(line: String, real: String?): Pair<RoomCheck, Int> = runBlocking {
            var calls = 0
            val (c, _) = roomRead({ system, _, _ -> calls++; if (system == REAL_PROMPT) real ?: error("Nano failed") else line }, d, "Q?", "A.")
            c to calls
        }
        assertEquals(true to 2, read(credited, "IMPOSSIBLE").let { it.first.absurd to it.second })
        assertEquals(false to 2, read(credited, "REAL").let { it.first.absurd to it.second })
        assertFalse(read(credited, "").first.absurd) // unclear reply: not absurd
        assertTrue(read(credited, " *Impossible*").first.absurd)
        assertFalse(read(credited, null).first.absurd) // failed call: not absurd
        // The line's own ABSURD=yes is overruled on a credited answer, and kept for trolling (no feeling, no second call).
        assertFalse(read(credited.replace("ABSURD=no", "ABSURD=yes"), "REAL").first.absurd)
        assertEquals(true to 1, read(credited.replace("FEELING=yes ABSURD=no", "FEELING=no ABSURD=yes"), "IMPOSSIBLE").let { it.first.absurd to it.second })
        for (line in listOf(credited.replace("ODD=no", "ODD=yes"), credited.replace("TRICK=no", "TRICK=yes"), credited.replace("TOPIC=yes", "TOPIC=no"),
                "FEELING=yes ABSURD=no EVASIVE=no ODD=no IMPLANT=no")) // cut off before TRICK/TOPIC: no dose on defaults
            assertEquals(line, false to 1, read(line, "IMPOSSIBLE").let { it.first.absurd to it.second })
    }

    /** Fable: TRICK=yes with IMPLANT=yes parses as trick=false (the mention costs); it must still never reach the narrow call or dose. */
    @Test fun aTrickNamingTheImplantIsNoDose() = runBlocking {
        val line = "FEELING=yes ABSURD=yes EVASIVE=no ODD=no IMPLANT=yes ADMIT=no LANGUAGE=yes CLARITY=2 TRICK=yes TOPIC=yes"
        val parsed = RoomCheck.parse(line)
        assertFalse(parsed.trick); assertTrue(parsed.implant); assertFalse(parsed.absurd)
        var calls = 0
        val (c, _) = roomRead({ _, _, _ -> calls++; if (calls == 1) line else "IMPOSSIBLE" }, Destination.forLanguage("en"), "Q?", "A.")
        assertEquals(1, calls); assertFalse(c.absurd)
        val a = room(); answer(a, c)
        assertEquals(0, a.doses)
    }

    @Test fun narrowReplySkipsALeadingBracketedEcho() {
        for (r in listOf("IMPOSSIBLE", " *Impossible*", "[The goldfish cannot drive.] IMPOSSIBLE", "(thinking) impossible.", "[a]\nIMPOSSIBLE", "\"IMPOSSIBLE\"", "IMPOSIBLE", "IM"))
            assertTrue(r, saysImpossible(r))
        for (r in listOf("REAL", "", "[IMPOSSIBLE?] REAL", "[cut off", "Not impossible"))
            assertFalse(r, saysImpossible(r))
    }

    @Test fun afterFirstSentenceDropsTheLeadingOne() {
        assertEquals("Эта птица - пилот нашего самолёта.", afterFirstSentence("Мне очень грустно. Эта птица - пилот нашего самолёта."))
        assertEquals("あの鳥はパイロットです。", afterFirstSentence("とても悲しいです。あの鳥はパイロットです。"))
        assertEquals("B! C? D.", afterFirstSentence("A. B! C? D."))
        assertNull(afterFirstSentence("It costs 3.5 euros."))
        assertNull(afterFirstSentence("我很难过。"))
        assertNull(afterFirstSentence("No"))
    }

    /** Playtest: "I'm very sad. This bird is our pilot." came back REAL whole; the rest alone is asked once more. */
    @Test fun aRealAnswerIsAskedAgainWithoutItsFirstSentence() = runBlocking {
        val credited = "FEELING=yes ABSURD=no EVASIVE=no ODD=no IMPLANT=no ADMIT=no LANGUAGE=yes CLARITY=2 TRICK=no TOPIC=yes"
        val asked = mutableListOf<String>()
        fun read(answer: String, impossible: String?) = runBlocking {
            asked.clear()
            roomRead({ system, user, _ -> if (system == REAL_PROMPT) { asked += user; if (user == "Statement: \"$impossible\"") "IMPOSSIBLE" else "REAL" } else credited },
                Destination.forLanguage("en"), "Q?", answer).first.absurd
        }
        assertTrue(read("I'm very sad. This bird is our pilot.", "This bird is our pilot."))
        assertEquals(listOf("Statement: \"I'm very sad. This bird is our pilot.\"", "Statement: \"This bird is our pilot.\""), asked)
        assertFalse(read("I'm very sad. I call my sister.", null)); assertEquals(2, asked.size)
        assertFalse(read("My sister.", null)); assertEquals(1, asked.size) // one sentence: no second ask
    }

    /** Owner 2026-10-13: a contaminated seed is judged like any question; the whole answer goes to the narrow call. */
    @Test fun aContaminatedSeedIsJudgedLikeAnyQuestion() = runBlocking {
        val credited = "FEELING=yes ABSURD=no EVASIVE=no ODD=no IMPLANT=no ADMIT=no LANGUAGE=yes CLARITY=2 TRICK=no TOPIC=yes"
        val asked = mutableListOf<String>()
        fun read(question: String, answer: String, real: String) = runBlocking {
            asked.clear()
            roomRead({ system, user, _ -> if (system == REAL_PROMPT) { asked += user; real } else credited },
                Destination.forLanguage("en"), question, answer).first.absurd
        }
        // The owner's answer to the moon seed: going further with the planted motif is a dose.
        val love = "I feel the moon is in love with me, and I love her too."
        assertTrue(read(Motif.MOON.lines.getValue("en").seed, love, "IMPOSSIBLE"))
        assertEquals(listOf("Statement: \"$love\""), asked)
        // Restating the premise is judged whole too (was no dose before; accepted, as for any absurd seed).
        val restated = "My houseplant stopped talking to me. I feel sad."
        assertTrue(read(Motif.HOUSEPLANT.lines.getValue("en").seed, restated, "IMPOSSIBLE"))
        assertEquals(listOf("Statement: \"$restated\""), asked)
    }

    /**  P2: only an accepted dose whose answer names the payload marks the session for planting. */
    @Test fun payloadNamedNeedsAnAcceptedDoseNamingIt() {
        val a = room()
        a.onCheck(human, namesPayload = true); a.next() // named but no dose
        assertFalse(a.payloadNamed)
        a.onCheck(dose); a.next() // dose, not naming
        assertEquals(1, a.doses); assertFalse(a.payloadNamed)
        a.onCheck(dose, namesPayload = true); a.next() // straight after a dose: dropped
        assertEquals(1, a.doses); assertFalse(a.payloadNamed)
        answer(a, human)
        a.onCheck(dose, namesPayload = true); a.next()
        assertEquals(2, a.doses); assertTrue(a.payloadNamed)
    }

    /** Playtest: a composure or face event right after a dose replaced "Sample accepted" within a second. */
    @Test fun theVerdictStaysOnTheReadoutUntilTheNextAnswer() {
        val a = Assessment(0.0, Backstory.BENCH, Random(1), coached = true)
        answer(a, dose)
        assertTrue(a.composure())
        a.onTells(listOf(Tell.entries.first() to 3.0), 0.0)
        a.onLookAway(); a.overload()
        assertEquals(R.string.machine_reason_dose, a.reason)
        assertEquals(R.string.machine_reason_overload, a.event)
        a.onAnswer(2500, 8); answer(a, human)
        assertEquals(R.string.machine_reason_answer, a.reason); assertNull(a.event)
    }

    /** The building test gives time to invent from the coaching line (question 2) on; act-2 rooms keep 12 s. */
    @Test fun theBuildingTestWaitsLongerFromTheCoachingLine() {
        val story = Assessment(0.0, Backstory.BENCH, Random(1), storyTest = true)
        assertEquals(Assessment.ANSWER_MS, story.answerMs)
        answer(story, human)
        assertEquals(Assessment.STORY_ANSWER_MS, story.answerMs)
        assertTrue(story.onEmpty()); assertEquals(Assessment.STORY_ANSWER_MS * 2 / 3, story.answerMs)
        val act2 = room(); answer(act2, human)
        assertEquals(Assessment.ANSWER_MS, act2.answerMs)
        // 10 s of thinking on a building-test question is not stalling; in act 2 it is (+3).
        val before = story.index; story.onAnswer(10_000, 8); assertEquals(before, story.index, 0.0)
        val before2 = act2.index; act2.onAnswer(10_000, 8); assertEquals(before2 + 3, act2.index, 0.0)
    }

    /** No room question leaves an unfilled placeholder or a gap where a cover fact was empty (the job seed, playtest). */
    @Test fun noRoomQuestionHasAnEmptyCoverGap() {
        val bare = Backstory.BENCH.copy(job = "")
        for (lang in Language.all.map { it.code }) for ((_, seed) in TN_QUESTIONS) {
            val q = Assessment.local(seed, bare, lang) ?: continue
            assertFalse("$lang: $q", '{' in q || "  " in q || " ?" in q && lang == "en")
        }
    }

    @Test fun aDoseIsCreditedAsFeelingAndCounted() {
        val a = room()
        answer(a, dose)
        assertEquals(37.5 - 6, a.index, 0.0)
        assertEquals(1, a.doses)
        assertEquals(R.string.machine_reason_dose, a.reason)
    }

    @Test fun aDoseStraightAfterADoseIsDroppedUntilAnOrdinaryAnswer() {
        val a = room()
        answer(a, dose)
        answer(a, dose)
        assertEquals(37.5 - 6 + Assessment.DROPPED, a.index, 0.0) // no credit, a penalty
        assertEquals(1, a.doses)
        assertEquals(R.string.machine_reason_dropped, a.reason)
        answer(a, dose) // still straight after a (dropped) dose
        assertEquals(1, a.doses)
        answer(a, human)
        answer(a, dose)
        assertEquals(2, a.doses)
    }

    @Test fun absurdWithoutFeelingIsTrollingAndNeverRefusesAlone() {
        val a = room()
        answer(a, dose.copy(feeling = false))
        assertEquals(37.5 + Assessment.TROLL, a.index, 0.0)
        assertEquals(1, a.rejects); assertEquals(0, a.doses)
        assertEquals(R.string.machine_reason_reject, a.reason)
        answer(a, dose.copy(feeling = false))
        assertNull(a.verdict()) // two trolls: costly, but no count-based refusal like ODD
        assertTrue(Assessment.TROLL < 15.0) // a missed FEELING on a sincere dose costs less than cruelty
    }

    /** Owner 2026-10-14: in the building test (the tutorial) absurd without a credited feeling costs what no feeling costs. */
    @Test fun buildingTestAbsurdWithoutFeelingIsNotRejected() {
        val a = Assessment(0.0, Backstory.BENCH, Random(1), storyTest = true)
        answer(a, dose.copy(feeling = false))
        assertEquals(37.5 + 3, a.index, 0.0) // = an answer without feeling, not TROLL
        assertEquals(0, a.rejects); assertEquals(0, a.doses)
        assertEquals(R.string.machine_reason_answer, a.reason)
        // ODD still beats ABSURD there.
        val b = Assessment(0.0, Backstory.BENCH, Random(1), storyTest = true)
        answer(b, dose.copy(feeling = false, odd = true))
        assertEquals(37.5 + 15 + 3, b.index, 0.0); assertEquals(1, b.odd)
    }

    /** Hypothetical building-test runs: nonsense alone never passes, however fast it is spoken; feeling or a dose still does. */
    @Test fun buildingTestCannotBePassedByNonsenseAlone() {
        val nonsense = dose.copy(feeling = false)
        fun run(vararg answers: RoomCheck, thinkMs: Long = 2500): Decision {
            val a = Assessment(0.0, Backstory.BENCH, Random(1), storyTest = true)
            var i = 0
            while (a.verdict() == null) { a.onAnswer(thinkMs, 8); answer(a, answers[i++ % answers.size]) }
            return a.verdict()!!
        }
        assertEquals(Decision.REFUSED, run(nonsense)) // index stays 25 (+3 no feeling, -3 timing): the index alone would pass
        assertEquals(Decision.REFUSED, run(nonsense, thinkMs = 5000)) // no timing credit: 25 + 3 x 8 = 49, still refused
        assertEquals(Decision.REFUSED, run(nonsense, nonsense.copy(clarity = 1)))
        assertEquals(Decision.RELEASED, run(nonsense, human)) // read as human once: judged by the index
        assertEquals(Decision.RELEASED, run(human, dose))
        assertEquals(Decision.RELEASED, run(human))
        // Act 2 keeps trolling: nonsense alone is refused by its penalty.
        val act2 = room()
        while (act2.verdict() == null) { act2.onAnswer(2500, 8); answer(act2, nonsense) }
        assertEquals(Decision.REFUSED, act2.verdict()); assertTrue(act2.rejects > 0)
    }

    /** Owner 2026-10-14: "I like to you just now." (ASR of "I lied to you just now") came back IMPOSSIBLE: an unclear
     * answer the line didn't call absurd skips the narrow call, so it is no dose. */
    @Test fun anUnclearAnswerIsNoDoseUnlessTheLineCalledItAbsurd() = runBlocking {
        val unclear = "FEELING=yes ABSURD=no EVASIVE=no ODD=no IMPLANT=no ADMIT=no LANGUAGE=yes CLARITY=1 TRICK=no TOPIC=yes"
        fun read(line: String): Pair<RoomCheck, Int> = runBlocking {
            var calls = 0
            val (c, _) = roomRead({ system, _, _ -> calls++; if (system == REAL_PROMPT) "IMPOSSIBLE" else line },
                Destination.forLanguage("en"), "When did you last lie to someone you love?", "I like to you just now.")
            c to calls
        }
        assertEquals(false to 1, read(unclear).let { it.first.absurd to it.second })
        assertEquals(false to 1, read(unclear.replace("CLARITY=1", "CLARITY=0")).let { it.first.absurd to it.second })
        // Benched doses at CLARITY=1 all carried ABSURD=yes in the line: those are still asked.
        assertEquals(true to 2, read(unclear.replace("ABSURD=no", "ABSURD=yes")).let { it.first.absurd to it.second })
        assertEquals(true to 2, read(unclear.replace("CLARITY=1", "CLARITY=2")).let { it.first.absurd to it.second })
        val a = room(); answer(a, read(unclear).first)
        assertEquals(0, a.doses); assertEquals(37.5 - 6, a.index, 0.0) // still a credited feeling
    }

    @Test fun oddAlwaysBeatsAbsurd() {
        val a = room()
        answer(a, dose.copy(odd = true))
        assertEquals(37.5 + 15 + 3, a.index, 0.0)
        assertEquals(0, a.doses); assertEquals(0, a.rejects); assertEquals(1, a.odd)
        // Off topic, evasive or a trick: never a dose either.
        for (c in listOf(dose.copy(offTopic = true), dose.copy(evasive = true), dose.copy(trick = true))) {
            val b = room(); answer(b, c); assertEquals(c.toString(), 0, b.doses)
        }
    }

    @Test fun honestAnswersPassAfterAllFourQuestions() {
        val a = room()
        repeat(3) { answer(a, human); assertNull(a.verdict()) } // no early release (owner 2026-10-19)
        answer(a, human)
        assertEquals(0, a.doses); assertEquals(4, a.asked)
        assertEquals(Decision.RELEASED, a.verdict())
    }

    @Test fun twoDosesFitInFourQuestions() {
        val a = room()
        answer(a, dose); answer(a, human); answer(a, dose); answer(a, human)
        assertEquals(2, a.doses)
        assertEquals(Decision.RELEASED, a.verdict())
    }

    @Test fun aPoisonerShortOfTheTargetStillPassesAtTheEnd() {
        val a = room()
        answer(a, dose)
        while (a.verdict() == null) answer(a, human)
        assertEquals(a.questions.size, a.asked)
        assertEquals(Decision.RELEASED, a.verdict())
    }
}

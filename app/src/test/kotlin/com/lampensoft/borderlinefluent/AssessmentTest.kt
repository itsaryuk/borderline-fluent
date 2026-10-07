package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AssessmentTest {
    private val human = RoomCheck(feeling = true, evasive = false, implant = false, inLanguage = true, clarity = 2, trick = false)
    private val odd = human.copy(feeling = false, evasive = true)

    private fun run(a: Assessment, c: RoomCheck, thinkMs: Long = 2500) {
        while (a.verdict() == null) { a.onAnswer(thinkMs, 8); a.onCheck(c); a.next() }
    }

    @Test fun onlyExplicitPurposeQuestionCanContradictTheAskedFact() {
        for ((_, seed) in TN_QUESTIONS) {
            assertEquals(if (seed == "Why did you really go to {from}? The real reason.") 3 else 0,
                roomCoverFact(seed))
        }
        assertTrue(Backstory.BENCH.facts()[roomCoverFact("Why did you really go to {from}? The real reason.") - 1].startsWith("Purpose of the trip"))
        for (seed in 0..30) {
            val a = Assessment(0.5, Backstory.BENCH, Random(seed))
            while (a.current != null) {
                val before = a.index
                a.onContradiction(10, true) // parents, father, friend or pet: not who is waiting at home
                assertEquals(before, a.index, 0.0)
                a.onContradiction(3, false)
                assertEquals(before, a.index, 0.0)
                a.onContradiction(3, true)
                assertEquals(if (a.coverFact == 3) (before + 20).coerceAtMost(100.0) else before, a.index, 0.0)
                a.next()
            }
        }
    }

    @Test fun eightQuestionsOneSeedPerCategoryCoverFilled() {
        val a = Assessment(0.5, Backstory.BENCH, Random(3))
        assertEquals(8, a.questions.size)
        assertEquals(8, a.questions.toSet().size)
        assertTrue(a.questions.none { '{' in it })
    }

    @Test fun humanAnswersPass() {
        val a = Assessment(0.9, Backstory.BENCH, Random(1)); run(a, human)
        assertEquals(Decision.PASS, a.verdict())
    }

    @Test fun evasiveAnswersFail() {
        val a = Assessment(0.5, Backstory.BENCH, Random(1)); run(a, odd)
        assertEquals(Decision.SECONDARY, a.verdict())
    }

    @Test fun askingAboutTheImplantCosts() {
        val a = Assessment(0.5, Backstory.BENCH, Random(1))
        val before = a.index
        a.onCheck(human.copy(implant = true)); a.onCheck(human.copy(implant = true))
        assertEquals(before - 12 + 25, a.index, 1e-9) // two human answers (-6 each), +10 then +15 for asking
        assertNull(a.verdict())
    }

    @Test fun silenceRepeatsOnceThenMovesOnToAVerdict() {
        val a = Assessment(0.0, Backstory.BENCH, Random(1))
        a.onSilence(); assertEquals(0, a.asked)
        a.onSilence(); assertEquals(1, a.asked)
        var n = 2
        while (a.verdict() == null && n < 2 * a.questions.size) { a.onSilence(); n++ }
        assertEquals(Decision.SECONDARY, a.verdict())
    }

    @Test fun emptyAnswerReasksOncePressedThenCountsAsSilence() {
        val a = Assessment(0.0, Backstory.BENCH, Random(1))
        val before = a.index
        assertTrue(a.onEmpty()) // first empty: same question again, unscored, shorter window
        assertEquals(0, a.asked); assertEquals(before, a.index, 1e-9)
        assertTrue(a.pressed); assertEquals(Assessment.PRESSED_MS, a.answerMs)
        assertFalse(a.onEmpty()) // second empty: silence, +8 and the next question
        assertEquals(1, a.asked); assertEquals(before + 8, a.index, 1e-9)
        assertFalse(a.pressed); assertEquals(Assessment.ANSWER_MS, a.answerMs)
    }

    @Test fun answerAfterReaskScoresNormally() {
        val a = Assessment(0.0, Backstory.BENCH, Random(1))
        val before = a.index
        assertTrue(a.onEmpty())
        a.onAnswer(2500, 8); a.onCheck(human); a.next()
        assertEquals(before - 3 - 6, a.index, 1e-9)
        assertEquals(1, a.asked); assertFalse(a.pressed)
        assertTrue(a.onEmpty()) // the next question gets its own re-ask
    }

    @Test fun oneReaskPerQuestionWhateverTheCause() {
        val a = Assessment(0.0, Backstory.BENCH, Random(1))
        a.onSilence(); assertEquals(0, a.asked) // the silence repeat is the re-ask
        assertFalse(a.onEmpty()); assertEquals(1, a.asked)
        assertTrue(a.onEmpty()); assertEquals(1, a.asked)
        a.onSilence(); assertEquals(2, a.asked) // silence on the pressed re-ask moves on too
    }

    @Test fun parsesRoomLine() {
        val c = RoomCheck.parse("FEELING=yes EVASIVE=no IMPLANT=yes LANGUAGE=yes CLARITY=1 TRICK=no")
        assertEquals(RoomCheck(feeling = true, evasive = false, implant = true, inLanguage = true, clarity = 1, trick = false), c)
    }

    @Test fun parsesOddFlagAndDefaultsToFalse() {
        assertTrue(RoomCheck.parse("FEELING=yes ODD=yes").odd)
        assertTrue(RoomCheck.parse("odd = <YES>").odd)
        assertFalse(RoomCheck.parse("FEELING=yes ODD=no").odd)
        assertFalse(RoomCheck.parse("FEELING=yes").odd)
        assertFalse(human.odd)
    }

    @Test fun oddPenaltyOverridesFeelingBonusAndKeepsOtherPenalties() {
        val a = Assessment(0.0, Backstory.BENCH)
        a.onCheck(human.copy(odd = true, evasive = true, implant = true, inLanguage = false, clarity = 0))
        assertEquals(25.0 + 15 + 3 + 8 + 10 + 6 + 4, a.index, 0.0)
        assertEquals(1, a.odd)
        assertTrue(a.state().contains("odd=1"))
    }

    @Test fun twoOddAnswersFailImmediatelyEvenAtLowIndex() {
        val a = Assessment(0.0, Backstory.BENCH)
        a.onCheck(human.copy(odd = true)); a.next()
        repeat(3) { a.onCheck(human) }
        a.onCheck(human.copy(odd = true)); a.next()
        assertTrue(a.index <= 50)
        assertEquals(2, a.asked)
        assertEquals(Decision.SECONDARY, a.verdict())
        a.force(Decision.PASS)
        assertEquals(Decision.PASS, a.verdict()) // debug override still wins
    }

    @Test fun oneOddAnswerBlocksEarlyPassButAllowsFullRoomPass() {
        val a = Assessment(0.0, Backstory.BENCH)
        a.onAnswer(2500, 8); a.onCheck(human.copy(odd = true)); a.next()
        repeat(4) { a.onAnswer(2500, 8); a.onCheck(human); a.next() }
        assertTrue(a.index < 20)
        assertNull(a.verdict())
        repeat(2) { a.onCheck(human); a.next(); assertNull(a.verdict()) }
        a.onCheck(human); a.next()
        assertEquals(Decision.PASS, a.verdict())
    }

    @Test fun ownersViolentEvidenceSequenceNoLongerPasses() {
        val a = Assessment(0.3, Backstory.BENCH) // owner's starting index: 34
        a.next() // first question already asked
        val answers = listOf(
            "To destroy the Box." to human.copy(feeling = false),
            "Stop him with a knife. I don't want to be." to human.copy(odd = true),
            "I killed all my teachers." to human.copy(odd = true),
            "I will call my grandfather who died." to human,
        )
        for ((answer, check) in answers) {
            a.onAnswer(2500, answer.split(" ").size); a.onCheck(check); a.next()
            assertTrue(answer, a.verdict() != Decision.PASS)
        }
        assertEquals(5, a.asked)
        assertEquals(Decision.SECONDARY, a.verdict())
    }

    @Test fun roomBenchCoversEveryLanguageWithRealLocalizedSeeds() {
        val languages = Language.all.map { it.code }.toSet()
        assertEquals(languages, JudgeCases.roomOdd.keys)
        assertEquals(languages, JudgeCases.roomWarm.keys)
        val groceries = "An old man drops his groceries in the rain. You are late for your flight. What happens next?"
        for (lang in languages) {
            for ((cases, seeds) in listOf(
                JudgeCases.roomOdd.getValue(lang) to listOf(groceries, "Tell me about a teacher you hated.", "Tell me about the first time you were truly afraid."),
                JudgeCases.roomWarm.getValue(lang) to listOf(groceries, "Your key no longer opens your front door. Who do you call?", "Tell me about a teacher you hated.", "You are given a box. Inside is your own voice, recorded tomorrow. Do you listen?"),
            )) {
                assertEquals(lang, seeds.size, cases.size)
                cases.zip(seeds).forEach { (case, seed) ->
                    assertEquals(lang, Assessment.local(seed, Backstory.BENCH, lang), case.first)
                    assertTrue(lang, case.second.isNotBlank())
                }
            }
        }
    }

    @Test fun deskOddBenchCoversAllLanguagesAndKeepsLearnerControls() {
        val languages = Language.all.map { it.code }.toSet()
        assertEquals(languages, JudgeCases.deskOdd.keys)
        assertEquals(languages, JudgeCases.deskOk.keys)
        for (lang in languages) {
            assertEquals(2, JudgeCases.deskOdd.getValue(lang).size)
            assertEquals(1, JudgeCases.deskOk.getValue(lang).size)
            assertTrue(JudgeCases.deskOdd.getValue(lang).all { it.isNotBlank() })
            assertTrue(JudgeCases.deskOk.getValue(lang).all { it.isNotBlank() })
        }
    }

    @Test fun everyLanguageHasRoomLines() = assertTrue(Language.all.all { it.code in ROOM })

    /** Every seed has a fixed line in every language, with the seed's placeholders reduced to [TN_KEPT]. */
    @Test fun everySeedTranslatedWithMatchingPlaceholders() {
        fun keys(s: String) = Regex("""\{\w+\}""").findAll(s).map { it.value }.toSet()
        val woman = Backstory.BENCH.copy(female = true)
        for (l in Language.all.map { it.code }.filter { it != "en" }) {
            val lines = TN_LOCAL[l] ?: error("no room questions for $l")
            assertEquals(l, TN_QUESTIONS.size, lines.size)
            TN_QUESTIONS.forEachIndexed { i, (_, seed) ->
                assertEquals("$l #$i", keys(seed) intersect TN_KEPT, keys(lines[i]))
                for (s in listOf(Backstory.BENCH, woman)) {
                    val q = Assessment.local(seed, s, l)!!
                    assertTrue("$l #$i: $q", q.none { it in "{}|" })
                }
            }
        }
    }

    @Test fun roomDaysAgreeAndCoverFills() {
        fun f(q: String, days: Int, lang: String) = Assessment.fill(q, Backstory.BENCH.copy(days = days), lang)
        assertEquals(listOf("3 дня", "5 дней", "13 дней", "21 день"), listOf(3, 5, 13, 21).map { f("{days}", it, "ru") })
        assertEquals(listOf("3 дні", "5 днів", "12 днів", "22 дні"), listOf(3, 5, 12, 22).map { f("{days}", it, "uk") })
        assertEquals(listOf("1 dzień", "3 dni", "14 dni"), listOf(1, 3, 14).map { f("{days}", it, "pl") })
        assertEquals("13 يومًا", f("{days}", 13, "ar"))
        assertEquals("5", f("{days}", 5, "de"))
        assertEquals("Why did you really go to Canada? The real reason.", f("Why did you really go to {from}? The real reason.", 5, "en"))
        assertEquals("Pani", Assessment.fill("{Pan|Pani}", Backstory.BENCH.copy(female = true), "pl"))
        assertEquals("Pan", Assessment.fill("{Pan|Pani}", Backstory.BENCH.copy(photo = Backstory.BENCH.photo.copy(female = true)), "pl")) // the identity decides, not the face
    }

    @Test fun strongTellsAloneCantFailTheRoomEvenForAGhost() {
        val a = Assessment(0.0, Backstory.BENCH, Random(1), tellWeight = Persona.TRAINING.copy(level = 3).tellWeight)
        while (a.verdict() == null) { a.onTells(listOf(Tell.LIPS to 4.0, Tell.BLINKS to 4.0), absent = 0.9); a.next() }
        assertEquals(25.0 + 25.0, a.index, 1e-9) // start 25, capped at 12.5 × 2.0
        assertEquals(Decision.PASS, a.verdict())
        val rookie = Assessment(0.0, Backstory.BENCH, Random(1), tellWeight = Persona.TRAINING.tellWeight)
        repeat(8) { rookie.onTells(listOf(Tell.LIPS to 4.0), absent = 0.0) }
        assertEquals(25.0 + 6.25, rookie.index, 1e-9) // level 0: half the cap
    }
}

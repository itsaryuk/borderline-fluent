package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficerMindTest {
    private val calm = Persona(patience = 0.8, suspicion = 0.2, strictness = 0.7, tolerance = 0.5, busy = 0.3)
    private val good = Check(answered = true, contradiction = false, clarity = 2, inLanguage = true)
    private fun answer(m: OfficerMind, think: Long = 2000) = m.onAnswer(Delivery(think, 2.0, "Ich bin hier für eine Messe."))

    @Test fun wantedScalesWithLevelAndBusyWithinFourToEight() {
        assertEquals(4, OfficerMind(calm.copy(level = 0, busy = 1.0)).wanted)
        assertEquals(8, OfficerMind(calm.copy(level = 3, busy = 0.0)).wanted)
        assertEquals(4, OfficerMind(Persona.TRAINING).wanted)
        for (level in 0..3) for (busy in listOf(0.0, 0.5, 1.0)) {
            assertEquals((5 + level - 2 * busy).toInt().coerceIn(4, 8),
                OfficerMind(calm.copy(level = level, busy = busy)).wanted)
        }
    }

    @Test fun cuesTrackSilenceAndFreePauses() {
        val m = OfficerMind(calm)
        m.onPause(true)
        assertEquals(listOf(Cue.PASSPORT_1), m.takeCues())
        m.onPause(true)
        assertEquals(listOf(Cue.PASSPORT_2), m.takeCues())
        m.onPause(true)
        assertEquals(listOf(Cue.SILENCE_1), m.takeCues())
        m.onSilence()
        assertEquals(listOf(Cue.SILENCE_2), m.takeCues())
        m.onSmallTalk()
        m.onPause(false)
        assertEquals(listOf(Cue.PAUSE), m.takeCues())
        assertEquals(emptyList<Cue>(), m.takeCues())
    }

    @Test fun cuesTrackFaceAndAnswerChecks() {
        val m = OfficerMind(calm)
        m.onTells(listOf(Tell.LIPS to 3.0), 0.7)
        assertEquals(listOf(Cue.LOOK), m.takeCues())
        m.onTells(listOf(Tell.LIPS to 3.0, Tell.BLINKS to 2.0), 0.0)
        assertEquals(listOf(Cue.TELL), m.takeCues())
        m.onTells(emptyList(), 0.0)
        assertEquals(emptyList<Cue>(), m.takeCues())
        for ((check, cue) in listOf(good to Cue.GOOD, good.copy(odd = true) to Cue.ODD,
            good.copy(inLanguage = false) to Cue.WRONG_LANGUAGE, Check.UNCLEAR to Cue.UNCLEAR,
            good.copy(answered = false) to Cue.DODGE)) {
            val fresh = OfficerMind(calm)
            fresh.onCheck(check)
            assertEquals(listOf(cue), fresh.takeCues())
        }
    }

    @Test fun cuesTrackContradictionsAndTheirLatestFact() {
        val m = OfficerMind(calm)
        m.onCheck(good.copy(contradiction = true, inLanguage = false), 4)
        assertEquals(listOf(Cue.WRONG_LANGUAGE, Cue.CONTRA_FIRST), m.takeCues())
        assertEquals(4, m.cueFact)
        m.onCheck(good.copy(contradiction = true), 6)
        assertEquals(listOf(Cue.CONTRA_FIRST), m.takeCues())
        assertEquals(6, m.cueFact)
        m.onCheck(good.copy(contradiction = true), 4)
        assertEquals(listOf(Cue.CONTRA_AGAIN), m.takeCues())
        assertEquals(4, m.cueFact)
    }

    @Test fun tricksEmitOnlyTrickOrGlitch() {
        val m = OfficerMind(calm.copy(strictness = 0.5))
        val trick = good.copy(trick = true, odd = true, inLanguage = false, contradiction = true)
        m.onCheck(trick)
        assertEquals(listOf(Cue.TRICK), m.takeCues())
        m.onCheck(trick)
        assertEquals(listOf(Cue.GLITCH), m.takeCues())
    }

    @Test fun smallTalkRetainsOnlyOddAndRoomClearsCues() {
        val m = OfficerMind(calm)
        m.onPause(false)
        m.onSmallTalkOdd()
        m.dropCuesExcept(Cue.ODD)
        assertEquals(listOf(Cue.ODD), m.takeCues())
        m.onSmallTalkOdd()
        m.onSilence()
        m.enterRoom()
        assertEquals(emptyList<Cue>(), m.takeCues())
    }

    @Test fun oddParsesAndIsOptionalForOlderClassifiers() {
        val old = "FACT=5 MATCHES=yes ANSWERED=yes LANGUAGE=yes CLARITY=2 TRICK=no"
        assertEquals(false, Check.parseClassify(old).second.odd)
        assertEquals(true, Check.wellFormed(old))
        assertEquals(true, Check.parseClassify("$old odd = <YES>").second.odd)
        assertEquals(false, Check.parseClassify("$old ODD=no").second.odd)
    }

    @Test fun oddCostsSuspicionAndNormalPatienceWithoutGoodAnswerOrFactCredit() {
        val m = OfficerMind(calm)
        answer(m)
        m.onCheck(good.copy(odd = true), 2)
        assertEquals(1, m.odd)
        assertEquals(calm.suspicion + 0.12 + 0.12 * calm.strictness, m.suspicion, 1e-9)
        assertEquals(calm.patience - 0.03 - 0.04 * calm.busy, m.patience, 1e-9)
        assertEquals(0, m.goodAnswers)
        assertEquals(true, m.state().contains("facts=0/"))
        assertEquals(true, m.delta.contains("odd"))
        assertEquals(listOf(Cue.ODD), m.takeCues())
        assertNull(m.decision)
        answer(m)
        m.onCheck(good.copy(odd = true), 3)
        assertEquals(2, m.odd)
        assertEquals(true, m.suspicion < 1.0)
        assertEquals(0, m.goodAnswers)
        assertNull(m.decision)
    }

    @Test fun oddProvidesGroundsForHighSuspicion() {
        val m = OfficerMind(calm.copy(suspicion = 0.85))
        m.onCheck(good.copy(odd = true))
        assertEquals(true, m.suspicion >= 1.0)
        assertEquals(1, m.odd)
        assertEquals(0, m.contradictions + m.dodges + m.tricks)
        assertEquals(true, m.patience > 0.0)
        assertEquals(Decision.SECONDARY, m.decision)
    }

    @Test fun trickTakesPrecedenceOverOdd() {
        val m = OfficerMind(calm.copy(strictness = 0.0))
        repeat(2) { m.onCheck(good.copy(trick = true, odd = true)) }
        assertEquals(0, m.odd)
        assertEquals(0, m.goodAnswers)
        assertEquals(Decision.BROKEN, m.decision)
    }

    @Test fun emptyAnswerReasksOnceWithoutScoringThenUsesSilence() {
        val m = OfficerMind(calm)
        val window = m.silenceDeadlineMs()
        assertEquals(true, m.onEmpty())
        assertEquals(true, m.emptyPressed)
        assertEquals(calm.patience, m.patience, 0.0)
        assertEquals(calm.suspicion, m.suspicion, 0.0)
        assertEquals(0, m.turns)
        assertEquals(0, m.goodAnswers)
        assertEquals(window * 2 / 3, m.silenceDeadlineMs())
        assertEquals(false, m.onEmpty())
        assertEquals(false, m.emptyPressed)
        assertEquals(1, m.silences)
        assertEquals(calm.patience - 0.15, m.patience, 1e-9)
        assertEquals(calm.suspicion + 0.04, m.suspicion, 1e-9)
        assertEquals(false, m.onEmpty()) // silence rephrasing does not grant another free retry
        m.onQuestion()
        assertEquals(true, m.onEmpty())
    }

    @Test fun spokenAnswerEndsPressedWindowButSameQuestionKeepsItsRetryBudget() {
        val m = OfficerMind(calm)
        m.onEmpty()
        answer(m)
        assertEquals(false, m.emptyPressed)
        assertEquals(false, m.onEmpty())
        m.onQuestion()
        assertEquals(true, m.onEmpty())
    }

    @Test fun implantHintsOncePerIntegrityLevel() {
        assertEquals(R.string.implant_unit_slipping, integrityHint(1.0, 0.7, decided = false))
        assertNull(integrityHint(0.7, 0.5, decided = false)) // same level: said already
        assertEquals(R.string.implant_unit_stutter, integrityHint(0.5, 0.3, decided = false))
        assertEquals(R.string.implant_unit_stutter, integrityHint(1.0, 0.3, decided = false)) // skipped a level
        assertNull(integrityHint(0.3, 0.1, decided = false))
        assertNull(integrityHint(0.3, -0.2, decided = true)) // crashed: nothing left to push
        assertNull(integrityHint(1.0, 0.7, decided = true))
        assertNull(integrityHint(1.0, 1.0, decided = false)) // no trick
        // A real chain on a lenient officer: slips, then stutters, then crashes.
        val m = OfficerMind(calm.copy(strictness = 0.5))
        val trick = good.copy(answered = false, trick = true)
        val seen = (1..3).map { answer(m); val b = m.integrity; m.onCheck(trick); integrityHint(b, m.integrity, m.decision != null) }
        assertEquals(listOf(R.string.implant_unit_slipping, R.string.implant_unit_stutter, null), seen)
        assertEquals(Decision.BROKEN, m.decision)
    }

    @Test fun cleanAnswersPass() {
        val m = OfficerMind(calm)
        repeat(m.wanted) { answer(m); m.onCheck(good, 3 + it) }
        assertEquals(Decision.PASS, m.decision)
    }

    @Test fun repeatedContradictionsSendToSecondary() {
        val m = OfficerMind(calm)
        repeat(4) { answer(m); m.onCheck(good.copy(contradiction = true), 5) }
        assertEquals(Decision.SECONDARY, m.decision)
    }

    @Test fun firstContradictionPressesSecondPunishes() {
        val m = OfficerMind(calm)
        m.onCheck(good.copy(contradiction = true), 4)
        assertEquals(0.28, m.suspicion, 1e-9) // +0.08 only
        org.junit.Assert.assertTrue(m.delta, m.delta.contains("contra-first fact=4"))
        assertEquals(listOf(Cue.CONTRA_FIRST), m.takeCues())
        m.onCheck(good.copy(contradiction = true), 5) // another fact: pressed first too, a little harder (+0.08+0.04·1)
        assertEquals(0.40, m.suspicion, 1e-9)
        m.onCheck(good.copy(contradiction = true), 4) // the same fact again: full weight
        assertEquals(0.40 + 0.15 + 0.2 * calm.strictness, m.suspicion, 1e-9)
        org.junit.Assert.assertTrue(m.delta, m.delta.contains("contra fact=4"))
    }

    @Test fun garbledContradictionCostsNoSuspicion() {
        val m = OfficerMind(calm)
        m.onCheck(good.copy(contradiction = true), 4)
        val before = m.suspicion
        m.onCheck(good.copy(contradiction = true, clarity = 0), 4) // not understood: no press, no punish
        assertEquals(before, m.suspicion, 1e-9)
        assertEquals(1, m.contradictions)
        org.junit.Assert.assertTrue(m.delta, m.delta.contains("unclear"))
        assertEquals(Cue.UNCLEAR, m.takeCues().last())
    }

    @Test fun misheardArabicAnswersDontEndTheRun() {
        // Pixel 11, Arabic over Whisper: every garbled transcript came back ANSWERED=no MATCHES=no CLARITY 0-1.
        val m = OfficerMind(calm)
        listOf(0, 1, 0, 1, 1).forEachIndexed { i, cl ->
            answer(m); m.onCheck(Check(answered = false, contradiction = true, clarity = cl, inLanguage = true), 3 + i)
        }
        assertNull(m.decision)
        assertEquals(calm.suspicion, m.suspicion, 1e-9)
        assertEquals(0, m.dodges); assertEquals(0, m.contradictions)
        assertEquals(calm.patience - 5 * (0.03 + 0.04 * calm.busy + 0.03), m.patience, 1e-9) // drains slowly
        assertEquals(Face.CONFUSED, m.face())
    }

    @Test fun aClearNonAnswerIsADodgeNotAContradiction() {
        val m = OfficerMind(calm)
        m.onCheck(good.copy(answered = false, contradiction = true), 4)
        assertEquals(calm.suspicion + 0.06 + 0.06 * calm.strictness, m.suspicion, 1e-9)
        assertEquals(1, m.dodges); assertEquals(0, m.contradictions)
    }

    @Test fun passNeedsDistinctFactsAndToleratesSomeSuspicion() {
        val same = OfficerMind(calm)
        repeat(same.wanted) { answer(same); same.onCheck(good, 3) } // the same fact over and over
        assertNull(same.decision)
        val m = OfficerMind(calm.copy(suspicion = 0.95))
        repeat(m.wanted) { answer(m); m.onCheck(good, 3 + it) }
        org.junit.Assert.assertTrue(m.suspicion in 0.55..0.7) // would not have passed under the old 0.55 bar
        assertEquals(Decision.PASS, m.decision)
    }

    @Test fun tellsAloneNeverSendToSecondary() {
        val m = OfficerMind(calm.copy(suspicion = 0.9))
        repeat(20) { m.onTells(listOf(Tell.LIPS to 4.0, Tell.BLINKS to 4.0), absent = 0.0) }
        assertEquals(0.9 + calm.tellCap, m.suspicion, 1e-9) // capped per run
        assertNull(m.decision)
        answer(m); m.onCheck(good.copy(answered = false)) // a dodge backs the tells up
        assertEquals(Decision.SECONDARY, m.decision)
    }

    @Test fun tellsWeighMoreAtHigherRanksButStayCappedAndNeedGrounds() {
        val tells = listOf(Tell.LIPS to 3.0, Tell.BLINKS to 2.0)
        val rookie = OfficerMind(calm.copy(level = 0)).apply { onTells(tells, absent = 0.0) }
        val ghost = OfficerMind(calm.copy(level = 3)).apply { onTells(tells, absent = 0.0) }
        assertEquals(4 * (rookie.suspicion - calm.suspicion), ghost.suspicion - calm.suspicion, 1e-9) // ×0.5 vs ×2.0
        val capped = OfficerMind(calm.copy(level = 3, suspicion = 0.9))
        repeat(40) { capped.onTells(listOf(Tell.LIPS to 4.0, Tell.BLINKS to 4.0), absent = 0.0) }
        assertEquals(0.9 + 0.60, capped.suspicion, 1e-9) // the ghost cap
        assertNull(capped.decision) // tells alone, at the top rank: no SECONDARY without grounds
        val room0 = Assessment(0.5, Backstory.BENCH, kotlin.random.Random(1), tellWeight = calm.copy(level = 0).tellWeight)
        val room3 = Assessment(0.5, Backstory.BENCH, kotlin.random.Random(1), tellWeight = calm.copy(level = 3).tellWeight)
        val start = room0.index
        room0.onTells(tells, 0.0); room3.onTells(tells, 0.0)
        assertEquals(4 * (room0.index - start), room3.index - start, 1e-9)
    }

    @Test fun silenceWindowAndCost() {
        val m = OfficerMind(calm)
        assertEquals((10000 + 5000 * 0.5 + 3000 * 0.8).toLong(), m.silenceDeadlineMs())
        m.onSilence(); assertEquals(0.8 - 0.15, m.patience, 1e-9)
        m.onSilence(); assertEquals(0.8 - 0.15 - 0.20, m.patience, 1e-9)
        val worst = OfficerMind(Persona(patience = 0.1, suspicion = 0.0, strictness = 1.0, tolerance = 0.0, busy = 1.0))
        repeat(3) { worst.onSilence() }
        org.junit.Assert.assertTrue(worst.patience < 0)
        assertEquals(10_000L, worst.silenceDeadlineMs()) // the floor
        assertEquals(18_000L, OfficerMind(Persona(1.0, 0.0, 0.0, 1.0, 0.0)).silenceDeadlineMs())
    }

    @Test fun pausesForeverEndInSecondary() {
        val m = OfficerMind(calm)
        repeat(10) { m.onPause(passportPending = true) }
        assertEquals(Decision.SECONDARY, m.decision)
    }

    @Test fun answeringResetsThePauseCount() {
        val m = OfficerMind(calm)
        repeat(10) { m.onPause(passportPending = false); m.onPause(passportPending = false); answer(m) }
        assertEquals(0, m.silences)
    }

    @Test fun serialLiarIsNotPassed() {
        // A new contradiction on each of five facts, then three good answers: the old flat +0.08 ended at 0.62 → PASS.
        val m = OfficerMind(calm)
        (3..7).forEach { answer(m); m.onCheck(good.copy(contradiction = true), it) }
        repeat(3) { answer(m); m.onCheck(good, 8) }
        org.junit.Assert.assertNotEquals(Decision.PASS, m.decision)
        org.junit.Assert.assertTrue(m.suspicion > 0.7)
    }

    @Test fun turnCapNeedsTwoGoodFacts() {
        val m = OfficerMind(calm.copy(busy = 0.0))
        repeat(m.wanted + 4) { answer(m); m.onCheck(good.copy(clarity = 1), 0) } // clean but never about a cover fact
        assertEquals(Decision.SECONDARY, m.decision)
        val ok = OfficerMind(calm.copy(busy = 0.0))
        repeat(m.wanted + 4) { answer(ok); ok.onCheck(good, if (it < 2) 3 + it else 0) }
        assertEquals(Decision.PASS, ok.decision)
    }

    @Test fun pauseAfterAStatementIsFree() {
        val m = OfficerMind(calm)
        m.onPause(passportPending = false)
        assertEquals(calm.patience, m.patience, 1e-9); assertEquals(calm.suspicion, m.suspicion, 1e-9)
        assertEquals(0, m.silences)
        assertEquals(listOf(Cue.PAUSE), m.takeCues())
    }

    @Test fun silenceExhaustsPatience() {
        val m = OfficerMind(calm)
        repeat(2) { m.onSilence() }
        assertNull(m.decision)
        repeat(2) { m.onSilence() }
        assertEquals(Decision.SECONDARY, m.decision)
    }

    @Test fun withheldPassportEscalates() {
        val m = OfficerMind(calm)
        m.onPause(passportPending = true)
        assertEquals(listOf(Cue.PASSPORT_1), m.takeCues())
        m.onPause(passportPending = true)
        assertEquals(listOf(Cue.PASSPORT_2), m.takeCues())
        assertEquals(calm.suspicion, m.suspicion, 1e-9) // waiting for the passport costs nothing
        assertNull(m.decision)
    }

    @Test fun chainedTricksBreakTheOfficer() {
        val m = OfficerMind(calm)
        repeat(2) { answer(m); m.onCheck(good.copy(trick = true)) }
        assertNull(m.decision)
        assertEquals(Face.CONFUSED, m.face())
        answer(m); m.onCheck(good.copy(trick = true))
        assertEquals(Decision.BROKEN, m.decision)
        // The story room after a desk break: a rebooted unit, not crashed again by the first answer.
        m.enterRoom(); answer(m); m.onCheck(good)
        assertNull(m.decision)
    }

    @Test fun roomInterrogatorIsAFreshUnit() {
        val m = OfficerMind(calm.copy(suspicion = 0.0))
        val trick = good.copy(answered = false, trick = true)
        answer(m); m.onCheck(trick); answer(m); m.onCheck(good, 3)
        answer(m); m.onCheck(trick) // spread out: two single hits, no chain
        assertEquals(0.5, m.integrity, 0.01)
        var fact = 4
        while (m.decision == null) { answer(m); m.onCheck(good, fact++) }
        assertEquals(Decision.PASS, m.decision)
        m.enterRoom()
        assertEquals(1.0, m.integrity, 0.0)
        val b = m.integrity; answer(m); m.onCheck(trick)
        assertEquals(R.string.implant_unit_slipping, integrityHint(b, m.integrity, m.decision != null))
        assertEquals(0.75, m.integrity, 0.01) // a first hit, not the second link of a desk chain
    }

    @Test fun spreadOutTricksDoNotChain() {
        val m = OfficerMind(calm)
        repeat(3) { answer(m); m.onCheck(good.copy(trick = true)); answer(m); m.onCheck(good) }
        assertNull(m.decision)
    }

    @Test fun strictOfficersCatchTricksFirst() {
        val m = OfficerMind(calm.copy(strictness = 1.0, suspicion = 0.4))
        repeat(4) { answer(m); m.onCheck(good.copy(trick = true)) }
        assertEquals(Decision.SECONDARY, m.decision)
    }

    @Test fun tellsRaiseSuspicion() {
        val quiet = OfficerMind(calm).apply { onTells(emptyList(), absent = 0.0) }
        val shifty = OfficerMind(calm).apply { onTells(listOf(Tell.LIPS to 3.0, Tell.BLINKS to 2.0), absent = 0.0) }
        assertEquals(calm.suspicion - CALM_DESK, quiet.suspicion, 1e-9) // a poker face pays a little
        org.junit.Assert.assertTrue(shifty.suspicion > calm.suspicion + 0.05)
        assertEquals(Face.SUSPICIOUS, shifty.face())
    }

    @Test fun pokerFaceCalmsTheDeskOnlyWhenTheFaceWasSeen() {
        val calmAnswer = OfficerMind(calm).apply { onTells(emptyList(), absent = 0.1) }
        assertEquals(calm.suspicion - 0.02, calmAnswer.suspicion, 1e-9)
        org.junit.Assert.assertTrue(calmAnswer.delta, calmAnswer.delta.contains("calm"))
        val away = OfficerMind(calm).apply { onTells(emptyList(), absent = 0.5) } // face mostly out of view: no bonus
        assertEquals(calm.suspicion, away.suspicion, 1e-9)
        val tell = OfficerMind(calm).apply { onTells(listOf(Tell.LIPS to 1.0), absent = 0.0) } // a tell: no bonus
        org.junit.Assert.assertTrue(tell.suspicion > calm.suspicion)
        org.junit.Assert.assertFalse(tell.delta.contains("calm"))
        // Face reading off: Game never calls onTells, so nothing moves.
        assertEquals(calm.suspicion, OfficerMind(calm).suspicion, 1e-9)
        val floor = OfficerMind(calm.copy(suspicion = 0.01)).apply { onTells(emptyList(), absent = 0.0) }
        assertEquals(0.0, floor.suspicion, 1e-9) // never below zero
    }

    @Test fun pokerFaceCalmsTheRoom() {
        val a = Assessment(0.5, Backstory.BENCH, kotlin.random.Random(1))
        val start = a.index
        a.onTells(emptyList(), absent = 0.0); assertEquals(start - 1, a.index, 1e-9)
        a.onTells(emptyList(), absent = 0.9); assertEquals(start - 1 + 3, a.index, 1e-9) // unseen: the absence costs, no bonus
    }

    @Test fun facesFollowEvents() {
        val m = OfficerMind(calm)
        assertEquals(Face.NEUTRAL, m.face())
        answer(m); m.onCheck(good.copy(clarity = 0))
        assertEquals(Face.CONFUSED, m.face())
        answer(m); m.onCheck(good.copy(contradiction = true))
        assertEquals(Face.SUSPICIOUS, m.face())
    }

    @Test fun parsesJudgeReplies() {
        val (fact, c) = Check.parseClassify("FACT=5 MATCHES=yes ANSWERED=yes LANGUAGE=no CLARITY=1")
        assertEquals(5, fact)
        assertEquals(Check(answered = true, contradiction = false, clarity = 1, inLanguage = false), c)
        assertEquals(0, Check.parseClassify("nothing useful").first)
        assertEquals(7, Check.parseClassify("FACT=<7> ANSWERED=no LANGUAGE=yes CLARITY=1").first) // seen on device
        org.junit.Assert.assertTrue(Check.parseClassify("FACT=5 MATCHES=no").second.contradiction)
        org.junit.Assert.assertTrue(Check.parseClassify("FACT=0 MATCHES=yes ANSWERED=no LANGUAGE=yes CLARITY=2 TRICK=yes").second.trick)
        org.junit.Assert.assertFalse(Check.parseClassify("FACT=5 MATCHES=yes ANSWERED=yes LANGUAGE=yes CLARITY=2").second.trick)
        org.junit.Assert.assertTrue(Check.parseYes("Yes."))
        org.junit.Assert.assertFalse(Check.parseYes("no"))
    }

    @Test fun ignoresOfficerEcho() {
        org.junit.Assert.assertFalse(Game.isEcho("I'm arriving.", "Where are you arriving from today?", 1242))
        val fr = "Bonjour, veuillez présenter votre passeport s'il vous plaît."
        org.junit.Assert.assertTrue(Game.isEcho("S'il vous plaît. S'", fr, 300)) // the tail, right as the mic opened
        org.junit.Assert.assertFalse(Game.isEcho("S'il vous plaît. S'", fr, 2500)) // said later: the player's words
        org.junit.Assert.assertFalse(Game.isEcho("Voici mon passeport.", fr, 300))
        org.junit.Assert.assertFalse(Game.isEcho("Bonjour, veuillez", fr, 300)) // not from the last words
        // A real answer built from the question's words, not a run of them.
        org.junit.Assert.assertFalse(Game.isEcho("Туристична поїздка.", "Яка у вас туристична чи робоча поїздка?", 400))
        org.junit.Assert.assertTrue(Game.isEcho("робоча поїздка", "Яка у вас туристична чи робоча поїздка?", 400))
        // ja/zh have no spaces: a whole clause is one "word", so only an exact clause repeat matches; a partial
        // tail bleed is left to ECHO_GAP_MS (pinned current behaviour).
        org.junit.Assert.assertTrue(Game.isEcho("何日滞在しますか", "こんにちは。何日滞在しますか？", 300))
        org.junit.Assert.assertFalse(Game.isEcho("滞在しますか", "こんにちは。何日滞在しますか？", 300))
    }

    @Test fun questionsAreRecognised() {
        org.junit.Assert.assertTrue(isQuestion("Wie lange bleiben Sie?", "de"))
        org.junit.Assert.assertTrue(isQuestion("¿Cuánto tiempo se queda?", "es"))
        org.junit.Assert.assertTrue(isQuestion("كم ستبقى؟", "ar"))
        org.junit.Assert.assertTrue(isQuestion("何日滞在しますか。", "ja"))
        org.junit.Assert.assertTrue(isQuestion("何日滞在しますか", "ja"))
        org.junit.Assert.assertTrue(isQuestion("您住在哪里呢。", "zh"))
        org.junit.Assert.assertTrue(isQuestion("您是来工作的吗", "zh"))
        org.junit.Assert.assertTrue(isQuestion("您住几天？", "zh"))
        org.junit.Assert.assertFalse(isQuestion("Десять дней. Хорошо.", "ru"))
        org.junit.Assert.assertFalse(isQuestion("わかりました。", "ja"))
        org.junit.Assert.assertFalse(isQuestion("好的。", "zh"))
    }

    @Test fun fragmentsWaitForTheRest() {
        org.junit.Assert.assertTrue(Delivery.fragment("I…"))
        org.junit.Assert.assertTrue(Delivery.fragment("Ich"))
        org.junit.Assert.assertTrue(Delivery.fragment("Ich…"))
        org.junit.Assert.assertTrue(Delivery.fragment("ähm"))
        org.junit.Assert.assertTrue(Delivery.fragment("Ich bleibe..."))
        org.junit.Assert.assertFalse(Delivery.fragment("Fünf Tage."))
        org.junit.Assert.assertFalse(Delivery.fragment("Freitag."))
        org.junit.Assert.assertFalse(Delivery.fragment("Am Freitag"))
        org.junit.Assert.assertTrue(Delivery.fragment(""))
        org.junit.Assert.assertTrue(Delivery.fragment("Äh, ähm, hmm."))
        org.junit.Assert.assertTrue(Delivery.fragment("Э… эм… ну"))
        org.junit.Assert.assertFalse(Delivery.fragment("Ich bleibe fünf Tage."))
        org.junit.Assert.assertFalse(Delivery.fragment("Uh, I am a consultant."))
    }

    @Test fun cleansModelLines() {
        assertEquals("Wie lange bleiben Sie?", Models.targetLine("DE: Wie lange bleiben Sie?\nEN: How long are you staying?"))
        assertEquals("How long are you staying?", Models.englishLine("DE: Wie lange bleiben Sie?\nEN: How long are you staying?"))
        assertEquals("Alles in Ordnung.", Models.targetLine("DE: Alles in Ordnung. [PASS]\nEN: All good. [PASS]"))
        assertEquals("All good.", Models.englishLine("DE: Alles in Ordnung. [PASS]\nEN: All good. [PASS]"))
        assertEquals("Guten Tag.", Models.targetLine("DE: <Guten Tag.>"))
        assertEquals("Guten Tag.", Models.targetLine("DE: [German line] Guten Tag."))
        assertEquals("Good day.", Models.englishLine("EN: Good day. [English translation]"))
        assertEquals("", Models.englishLine("DE: Guten Tag."))
        assertEquals(Hint("Fünf Tage.", "", "Five days."), Models.option("1: <Fünf Tage.> | Five days. [English]"))
        assertEquals(Hint("Пять дней.", "Pyat' dney.", "Five days."), Models.option("2: Пять дней. | Pyat' dney. | Five days. [PASS]"))
    }

    @Test fun collapsesWhisperLoops() {
        assertEquals("Guten Tag!", Models.dedupe("Guten Tag! Guten Tag! Guten Tag! Guten Tag"))
        assertEquals("Ja. Fünf Tage.", Models.dedupe("Ja. Fünf Tage."))
    }
}

class VoicesTest {
    private fun wav(hz: Double, rate: Int = 22050): ByteArray {
        val n = rate / 2
        val b = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(n * 2)
        // Voice-like: fundamental plus two harmonics, so autocorrelation must find the period, not a harmonic.
        repeat(n) { t ->
            val ph = 2 * Math.PI * hz * t / rate
            b.putShort(((0.5 * Math.sin(ph) + 0.25 * Math.sin(2 * ph) + 0.12 * Math.sin(3 * ph)) * 12000).toInt().toShort())
        }
        return b.array()
    }

    @org.junit.Test fun pitchSeparatesMaleAndFemale() {
        val male = Voices.medianF0(wav(115.0))!!
        val female = Voices.medianF0(wav(215.0))!!
        org.junit.Assert.assertEquals(115.0, male, 6.0)
        org.junit.Assert.assertEquals(215.0, female, 10.0)
    }
}

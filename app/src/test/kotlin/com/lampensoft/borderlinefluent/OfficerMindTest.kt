package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficerMindTest {
    /** Homecoming fact number → id (0 = none), as Nano numbers them. */
    private fun f(n: Int) = HOMECOMING_FACTS.getOrNull(n - 1)

    private val calm = Persona(patience = 0.8, suspicion = 0.2, strictness = 0.7, tolerance = 0.5, busy = 0.3)
    private val good = Check(answered = true, contradiction = false, clarity = 2, inLanguage = true)
    private fun answer(m: OfficerMind, think: Long = 2000) = m.onAnswer(Delivery(think, 2.0, "Ich bin hier für eine Messe."))

    @Test fun wantedIsThreeAtEveryRankIncludingTraining() {
        for (level in 0..3) for (busy in listOf(0.0, 0.5, 1.0)) {
            assertEquals(3, OfficerMind(calm.copy(level = level, busy = busy)).wanted)
        }
        assertEquals(3, OfficerMind(Persona.TRAINING).wanted)
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
        m.onCheck(good.copy(contradiction = true, inLanguage = false), f(4))
        assertEquals(listOf(Cue.WRONG_LANGUAGE, Cue.CONTRA_FIRST), m.takeCues())
        assertEquals(f(4), m.cueFact)
        m.onCheck(good.copy(contradiction = true), f(6))
        assertEquals(listOf(Cue.CONTRA_FIRST), m.takeCues())
        assertEquals(f(6), m.cueFact)
        m.onCheck(good.copy(contradiction = true), f(4))
        assertEquals(listOf(Cue.CONTRA_AGAIN), m.takeCues())
        assertEquals(f(4), m.cueFact)
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
        m.onCheck(good.copy(odd = true), f(2))
        assertEquals(1, m.odd)
        assertEquals(calm.suspicion + 0.06 + 0.06 * calm.strictness, m.suspicion, 1e-9)
        assertEquals(calm.patience - 0.03 - 0.04 * calm.busy, m.patience, 1e-9)
        assertEquals(0, m.goodAnswers)
        assertEquals(true, m.state().contains("facts=0/"))
        assertEquals(true, m.delta.contains("odd"))
        assertEquals(listOf(Cue.ODD), m.takeCues())
        assertNull(m.decision)
        answer(m)
        m.onCheck(good.copy(odd = true), f(3))
        assertEquals(2, m.odd)
        assertEquals(true, m.suspicion < 1.0)
        assertEquals(0, m.goodAnswers)
        assertNull(m.decision) // two bad answers no longer refuse
        answer(m)
        m.onCheck(good.copy(odd = true), f(4))
        assertEquals(Decision.REFUSED, m.decision)
    }

    @Test fun oddProvidesGroundsForHighSuspicion() {
        val m = OfficerMind(calm.copy(suspicion = 0.95))
        m.onCheck(good.copy(odd = true))
        assertEquals(true, m.suspicion >= 1.0)
        assertEquals(1, m.odd)
        assertEquals(0, m.contradictions + m.dodges + m.tricks)
        assertEquals(true, m.patience > 0.0)
        assertEquals(Decision.REFUSED, m.decision)
    }

    @Test fun trickTakesPrecedenceOverOdd() {
        val m = OfficerMind(calm.copy(strictness = 0.0))
        repeat(2) { m.onCheck(good.copy(trick = true, odd = true)) }
        assertEquals(0, m.odd)
        assertEquals(0, m.goodAnswers)
        assertEquals(Decision.REFERRAL, m.decision)
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
        assertEquals(Decision.REFERRAL, m.decision)
    }

    @Test fun cleanRunPassesInThreeAnswers() {
        val m = OfficerMind(calm)
        repeat(3) { answer(m); m.onCheck(good, f(3 + it)) }
        assertEquals(3, m.turns)
        assertEquals(Decision.PASS, m.decision)
    }

    @Test fun threeContradictionsEndTheDesk() {
        val m = OfficerMind(calm)
        repeat(2) { answer(m); m.onCheck(good.copy(contradiction = true), f(5)) }
        assertNull(m.decision)
        answer(m); m.onCheck(good.copy(contradiction = true), f(5))
        assertEquals(Decision.REFUSED, m.decision)
    }

    @Test fun firstContradictionPressesSecondPunishes() {
        val m = OfficerMind(calm)
        m.onCheck(good.copy(contradiction = true), f(4))
        assertEquals(0.24, m.suspicion, 1e-9) // +0.04 only
        org.junit.Assert.assertTrue(m.delta, m.delta.contains("contra-first fact=DAYS"))
        assertEquals(listOf(Cue.CONTRA_FIRST), m.takeCues())
        m.onCheck(good.copy(contradiction = true), f(5)) // another fact: pressed first too, a little harder (+0.04+0.02·1)
        assertEquals(0.30 + TRANSLATOR_SUSPICION, m.suspicion, 1e-9) // two wrong in a row: the officer offers the phone translator
        m.onCheck(good.copy(contradiction = true), f(4)) // the same fact again: full weight (third bad answer: refused, no new offer)
        assertEquals(0.30 + TRANSLATOR_SUSPICION + 0.075 + 0.1 * calm.strictness, m.suspicion, 1e-9)
        org.junit.Assert.assertTrue(m.delta, m.delta.contains("contra fact=DAYS"))
    }

    @Test fun garbledContradictionCostsNoSuspicion() {
        val m = OfficerMind(calm)
        m.onCheck(good.copy(contradiction = true), f(4))
        val before = m.suspicion + TRANSLATOR_SUSPICION // the second wrong answer in a row brings the phone offer
        m.onCheck(good.copy(contradiction = true, clarity = 0, answered = false), f(4)) // not understood: no press, no punish
        assertEquals(before, m.suspicion, 1e-9)
        assertEquals(1, m.contradictions)
        org.junit.Assert.assertTrue(m.delta, m.delta.contains("unclear"))
        assertEquals(Cue.UNCLEAR, m.takeCues().last())
    }

    @Test fun fiveUnheardAnswersEndWithoutContradictionOrDodge() {
        val m = OfficerMind(calm)
        repeat(5) { answer(m); m.onCheck(Check.UNCLEAR, f(3 + it)) }
        assertEquals(Decision.REFUSED, m.decision)
        assertEquals(calm.suspicion + TRANSLATOR_SUSPICION, m.suspicion, 1e-9) // only the phone offer at the second one costs suspicion
        assertEquals(0, m.dodges); assertEquals(0, m.contradictions)
    }

    @Test fun translatorOfferedOnSecondWrongAnswerInARow() {
        val dodge = good.copy(answered = false)
        val contra = good.copy(contradiction = true)
        val m = OfficerMind(calm)
        answer(m); m.onCheck(dodge, f(3)); assertFalse(m.translatorOffered)
        answer(m); m.onCheck(dodge, f(4)); assertTrue(m.translatorOffered)
        val n = OfficerMind(calm)
        answer(n); n.onCheck(contra, f(3)); answer(n); n.onCheck(Check.UNCLEAR, f(4)); assertTrue(n.translatorOffered)
        val r = OfficerMind(calm) // dodge, good, dodge: the good one resets the streak
        for (c in listOf(dodge, good, dodge)) { answer(r); r.onCheck(c, f(3)) }
        assertFalse(r.translatorOffered)
        val o = OfficerMind(calm) // a merely odd answer is not "wrong"
        for (c in listOf(good.copy(odd = true), good.copy(odd = true))) { answer(o); o.onCheck(c, f(3)) }
        assertFalse(o.translatorOffered)
        val u = OfficerMind(calm) // the whole-desk unclear >= 2 still works with a good answer between
        for (c in listOf(Check.UNCLEAR, good, Check.UNCLEAR)) { answer(u); u.onCheck(c, f(3)) }
        assertTrue(u.translatorOffered)
    }

    @Test fun translatorOfferDoesNotStandOrCostWhenTheAnswerEndsTheDesk() {
        val m = OfficerMind(calm)
        repeat(2) { answer(m); m.onCheck(good.copy(odd = true), f(3)) } // odd answers are no struggling run: no offer yet
        val before = m.suspicion
        answer(m); m.onCheck(good.copy(contradiction = true), f(4)) // third bad answer: refused, no offer
        assertEquals(Decision.REFUSED, m.decision)
        assertFalse(m.translatorOffered)
        assertEquals(before + 0.04, m.suspicion, 1e-9) // only the answer's own suspicion, no phone charge
    }

    @Test fun translatorNeverOfferedInTrainingStoryOrRoomOnWrongStreak() {
        val dodge = good.copy(answered = false)
        for (mode in 0..2) {
            val m = OfficerMind(Persona.TRAINING).apply { training = mode == 0; storyMission = mode == 1; if (mode == 2) enterRoom() }
            repeat(2) { answer(m); m.onCheck(dodge, f(3)) }
            assertFalse("mode $mode", m.translatorOffered)
        }
    }

    @Test fun aClearNonAnswerWithoutContradictionIsADodge() {
        val m = OfficerMind(calm)
        m.onCheck(good.copy(answered = false), f(4))
        assertEquals(calm.suspicion + 0.06 + 0.06 * calm.strictness, m.suspicion, 1e-9)
        assertEquals(1, m.dodges); assertEquals(0, m.contradictions)
    }

    @Test fun clearWrongAnswerNeverYieldsAnUnclearLine() {
        for (clarity in 0..2) for (addressed in listOf(false, true)) {
            if (clarity == 0 && !addressed) continue
            val m = OfficerMind(calm)
            val d = Desk(kotlin.random.Random(7), listOf("purpose", "days"))
            d.next(emptyList(), null, emptySet(), false, false, true, false)
            answer(m)
            m.onCheck(good.copy(contradiction = true, clarity = clarity, answered = addressed), FactId.PURPOSE,
                "I came to drink wine")
            val cues = m.takeCues()
            assertFalse(Cue.UNCLEAR in cues)
            val move = d.next(cues, m.cueFact, emptySet(), false, false, false, false)
            assertEquals("press.first", move.prefix?.substringBeforeLast('.'))
            assertFalse(move.question.startsWith("exact."))
        }
    }

    @Test fun briefUnheardTranscriptIsUnclearButShortAnswersRemainValid() {
        for (text in listOf("", "…", "I")) {
            val m = OfficerMind(calm)
            m.onCheck(good.copy(answered = false, contradiction = true), FactId.PURPOSE, text)
            assertEquals(listOf(Cue.UNCLEAR), m.takeCues())
            assertEquals(0, m.contradictions)
        }
        for (text in listOf("5", "No", "是")) {
            val m = OfficerMind(calm)
            m.onCheck(good, FactId.DAYS, text)
            assertEquals(listOf(Cue.GOOD), m.takeCues())
        }
    }

    @Test fun threeBadAnswersAreRefusedWhateverTheInterest() {
        for (interest in listOf(0.0, REFER_AT)) for (mode in 0..2) {
            val m = OfficerMind(Persona.TRAINING).apply {
                training = mode == 1; storyMission = mode == 2
                onEncounter(interest / (0.5 + p.strictness), 0.0)
            }
            repeat(2) { answer(m); m.onCheck(good.copy(odd = true), FactId.PURPOSE) }
            assertNull(m.decision)
            answer(m); m.onCheck(good.copy(odd = true), FactId.PURPOSE)
            assertEquals(Decision.REFUSED, m.decision)
            assertEquals(3, m.badAnswers)
        }
    }

    @Test fun oddAndContradictionOnOneAnswerDoNotFailFastTwice() {
        val m = OfficerMind(Persona.TRAINING)
        answer(m); m.onCheck(good.copy(contradiction = true, odd = true), FactId.PURPOSE)
        assertNull(m.decision)
    }

    @Test fun sideQuestionsReduceTripFactsButNeverBelowTwo() {
        val m = OfficerMind(Persona.TRAINING)
        m.onSmallTalkAnswer()
        answer(m); m.onCheck(good, FactId.PURPOSE)
        m.onQuestion(HOWLANG)
        assertEquals(2, m.wanted)
        answer(m); m.onCheck(good)
        m.onQuestion(ENCOUNTER_TOPIC)
        assertEquals(2, m.wanted)
        answer(m); m.onCheck(good, FactId.ENCOUNTER)
        assertNull(m.decision) // the encounter is not a second trip fact
        answer(m); m.onCheck(good, FactId.DAYS)
        assertEquals(Decision.PASS, m.decision)
        assertEquals(4, m.turns) // the small talk cost no answer
    }

    @Test fun fifthAnswerIsJudgedBeforeTheCapDecides() {
        val m = OfficerMind(Persona.TRAINING)
        answer(m); m.onCheck(good, FactId.PURPOSE)
        repeat(3) { answer(m); m.onCheck(good) }
        answer(m)
        m.onTells(emptyList(), 0.0) // Game profiles between delivery and classification
        assertNull(m.decision)
        m.onCheck(good, FactId.DAYS)
        assertEquals(Decision.PASS, m.decision)
        val bad = OfficerMind(Persona.TRAINING).apply { storyMission = true }
        answer(bad); bad.onCheck(good, FactId.PURPOSE)
        answer(bad); bad.onCheck(good, FactId.DAYS)
        answer(bad); bad.onCheck(good.copy(contradiction = true), FactId.FROM)
        answer(bad); bad.onCheck(good.copy(odd = true))
        assertNull(bad.decision)
        answer(bad); bad.onCheck(good.copy(contradiction = true), FactId.HOME)
        assertEquals(Decision.REFUSED, bad.decision) // the third bad answer, on the fifth, beats the cap's pass
    }

    @Test fun totalDeskAnswersNeverExceedFive() {
        for (mode in 0..2) for (smallTalk in 0..1) {
            val m = OfficerMind(Persona.TRAINING).apply { training = mode == 1; storyMission = mode == 2 }
            repeat(smallTalk) { m.onSmallTalkAnswer() }
            while (m.decision == null && m.turns < 10) { answer(m); m.onCheck(Check.UNCLEAR) }
            assertEquals(5, m.turns)
            assertEquals(Decision.REFUSED, m.decision)
            answer(m)
            assertEquals(5, m.turns)
        }
    }

    @Test fun passNeedsDistinctFactsAndToleratesSomeSuspicion() {
        val same = OfficerMind(calm)
        repeat(same.wanted) { answer(same); same.onCheck(good, f(3)) } // the same fact over and over
        assertNull(same.decision)
        val m = OfficerMind(calm.copy(suspicion = 0.85))
        repeat(m.wanted) { answer(m); m.onCheck(good, f(3 + it)) }
        org.junit.Assert.assertTrue(m.suspicion in 0.55..0.7) // would not have passed under the old 0.55 bar
        assertEquals(Decision.PASS, m.decision) // suspicion alone doesn't refer: the meter does ([refers]), and it is empty
    }

    private fun passAfter(m: OfficerMind, talk: Implant = Implant.NONE): OfficerMind {
        repeat(m.wanted) { answer(m); m.onCheck(good.copy(implant = if (it < 3) talk else Implant.NONE), f(3 + it)) }
        return m
    }

    @Test fun noTalkAndCleanAnswersLeaveTheMeterEmptyAndPass() {
        val m = passAfter(OfficerMind(calm))
        assertEquals(0.0, m.meter, 1e-9)
        assertEquals(Decision.PASS, m.decision)
    }

    @Test fun oneMentionAloneNeverRefersButTalkingTwiceDoes() {
        val one = OfficerMind(calm); one.onCheck(good.copy(implant = Implant.DETAILED))
        assertEquals(Decision.PASS, passAfter(one).decision)
        val m = passAfter(OfficerMind(calm), Implant.DETAILED)
        assertEquals(1.5 * METER_DETAILED, m.meter, 1e-9) // 0.28 + 0.14 + 0, not strictness-scaled
        assertEquals(Decision.REFERRAL, m.decision)
        val passing = passAfter(OfficerMind(calm), Implant.PASSING)
        assertEquals(1.5 * METER_PASSING, passing.meter, 1e-9)
        assertEquals(Decision.REFERRAL, passing.decision)
    }

    private val lenient = calm.copy(strictness = 0.2)

    @Test fun twoMentionsOfAnyKindReferButOneDoesNot() { // 0.24 + 0.12 = 0.36 is over the 0.35 mark
        for (a in Implant.entries - Implant.NONE) {
            val one = OfficerMind(lenient); one.onCheck(good.copy(implant = a))
            assertFalse("$a meter=${one.meter}", refers(false, false, one.meter))
            for (b in Implant.entries - Implant.NONE) {
                val m = OfficerMind(lenient)
                m.onCheck(good.copy(implant = a)); m.onCheck(good.copy(implant = b))
                org.junit.Assert.assertTrue("$a+$b meter=${m.meter}", refers(false, false, m.meter))
                val home = OfficerMind(lenient).apply { homecoming = true }
                home.onCheck(good.copy(implant = a)); home.onCheck(good.copy(implant = b))
                assertFalse("$a+$b at home meter=${home.meter}", refers(false, false, home.meter))
            }
        }
    }

    @Test fun oneLieAndOneMentionRefer() {
        val m = OfficerMind(lenient)
        m.onCheck(good.copy(contradiction = true), f(4))
        m.onCheck(good.copy(implant = Implant.PASSING), f(4)) // the correction, with a passing mention
        org.junit.Assert.assertTrue("meter=${m.meter}", refers(false, false, m.meter))
        passAfter(m)
        assertEquals(Decision.REFERRAL, m.decision)
    }

    @Test fun oneMentionOrOneLieAloneStillPasses() {
        val strict = calm.copy(strictness = 0.6)
        val mention = passAfter(OfficerMind(strict).also { it.onCheck(good.copy(implant = Implant.PASSING)) })
        assertEquals(Decision.PASS, mention.decision)
        val lie = passAfter(OfficerMind(strict).also { it.onCheck(good.copy(contradiction = true), f(4)) })
        assertEquals(Decision.PASS, lie.decision)
    }

    @Test fun threeContradictionsRefuseWhateverTheMeter() {
        val m = OfficerMind(lenient)
        m.onCheck(good.copy(implant = Implant.PASSING)); m.onCheck(good.copy(implant = Implant.PASSING)) // meter over the mark
        m.onCheck(good.copy(contradiction = true), f(4)); m.onCheck(good.copy(contradiction = true), f(5)); m.onCheck(good.copy(contradiction = true), f(6))
        assertEquals(Decision.REFUSED, m.decision)
    }

    @Test fun secondMentionIsHalvedByItsOwnTypeAndLaterOnesAddNothing() {
        for (second in listOf(Implant.PASSING, Implant.DETAILED)) {
            val m = OfficerMind(calm)
            m.onCheck(good.copy(implant = Implant.DETAILED))
            val before = m.meter
            assertEquals(METER_DETAILED, before, 1e-9)
            m.onCheck(good) // quiet answers do not matter
            m.onCheck(good.copy(implant = second))
            assertEquals((if (second == Implant.DETAILED) METER_DETAILED else METER_PASSING) / 2, m.meter - before, 1e-9)
            val after = m.meter
            m.onCheck(good.copy(implant = Implant.DETAILED))
            assertEquals(after, m.meter, 1e-9)
            assertEquals(3, m.implantPassing + m.implantDetailed)
        }
    }

    @Test fun officersGrowImmuneAfterThreeReleasesInTheirCountry() {
        assertEquals(1.0, implantWeight(0), 0.0)
        assertEquals(1.0, implantWeight(IMMUNE_AFTER - 1), 0.0)
        assertEquals(0.5, implantWeight(IMMUNE_AFTER), 0.0)
        val m = OfficerMind(lenient).apply { implantWeight = implantWeight(3) }
        m.onCheck(good.copy(implant = Implant.PASSING)); m.onCheck(good.copy(implant = Implant.DETAILED))
        assertEquals((METER_PASSING + METER_DETAILED / 2) / 2, m.meter, 1e-9)
        // Immune officers still refer on a mention and a slip at high strictness.
        val strict = OfficerMind(calm.copy(strictness = 1.0)).apply { implantWeight = 0.5 }
        strict.onCheck(good.copy(implant = Implant.PASSING)); strict.onCheck(good.copy(contradiction = true), f(4))
        org.junit.Assert.assertTrue("meter=${strict.meter}", refers(false, false, strict.meter))
    }

    @Test fun homecomingHalvesTheMeter() {
        val away = passAfter(OfficerMind(calm), Implant.DETAILED)
        val home = passAfter(OfficerMind(calm).apply { homecoming = true }, Implant.DETAILED)
        assertEquals(away.meter / 2, home.meter, 1e-9)
        assertEquals(Decision.PASS, home.decision)
    }

    @Test fun smallTalkCostsNoDeskAnswer() {
        val m = OfficerMind(calm)
        repeat(3) { m.onSmallTalkAnswer() }
        assertEquals(0, m.turns)
    }

    @Test fun contradictionOddWrongLanguageAndTellsRaiseSuspicionByHalf() {
        val s = calm.strictness
        val first = OfficerMind(calm); first.onCheck(good.copy(contradiction = true), f(4))
        assertEquals(calm.suspicion + 0.04, first.suspicion, 1e-9)
        first.onCheck(good.copy(contradiction = true), f(4))
        assertEquals(calm.suspicion + 0.04 + 0.075 + 0.1 * s + TRANSLATOR_SUSPICION, first.suspicion, 1e-9) // second in a row: the phone offer
        val odd = OfficerMind(calm); odd.onCheck(good.copy(odd = true))
        assertEquals(calm.suspicion + 0.06 + 0.06 * s, odd.suspicion, 1e-9)
        val lang = OfficerMind(calm); lang.onCheck(good.copy(inLanguage = false))
        assertEquals(calm.suspicion + 0.04, lang.suspicion, 1e-9)
    }

    @Test fun firstReferralTurnsAPassIntoAReferralButNotARefusal() {
        val pass = passAfter(OfficerMind(calm).apply { firstReferral = true })
        assertEquals(Decision.REFERRAL, pass.decision)
        val refused = OfficerMind(calm).apply { firstReferral = true }
        repeat(3) { answer(refused); refused.onCheck(good.copy(odd = true)) }
        assertEquals(Decision.REFUSED, refused.decision)
        assertEquals(Decision.PASS, passAfter(OfficerMind(calm)).decision)
    }

    @Test fun dangerBeatsInterestAndFailFast() {
        for (patienceLimit in listOf(false, true)) {
            val m = OfficerMind(calm.copy(suspicion = if (patienceLimit) 0.0 else 0.6))
            m.onEncounter(1.0, 0.0)
            answer(m); m.onCheck(good.copy(odd = true))
            if (patienceLimit) m.onEncounter(0.0, -1.0)
            answer(m); m.onCheck(good.copy(odd = true))
            answer(m); m.onCheck(good.copy(odd = true))
            assertEquals(3, m.badAnswers)
            assertEquals(Decision.REFUSED, m.decision)
        }
    }

    @Test fun answerCapRefersWithoutEnoughFactsButLowInterestRefuses() {
        for (interest in listOf(0.0, REFER_AT)) {
            val m = OfficerMind(Persona.TRAINING)
            m.onEncounter(interest / (0.5 + m.p.strictness), 0.0)
            repeat(m.maxAnswers) { answer(m); m.onCheck(good, FactId.PURPOSE) }
            assertEquals(if (interest >= REFER_AT) Decision.REFERRAL else Decision.REFUSED, m.decision)
        }
    }

    @Test fun enoughFactsPassDespiteSuspicionBelowTheDangerLimit() {
        val m = passAfter(OfficerMind(calm.copy(suspicion = 0.95)))
        assertEquals(Decision.PASS, m.decision)
    }

    @Test fun contradictionsOddAnswersAndTellsFillTheMeterLightly() {
        val m = OfficerMind(calm)
        m.onCheck(good.copy(contradiction = true), f(4))
        assertEquals(METER_CONTRA * (0.5 + calm.strictness), m.meter, 1e-9)
        val o = OfficerMind(calm); o.onCheck(good.copy(odd = true))
        assertEquals(METER_ODD * (0.5 + calm.strictness), o.meter, 1e-9)
        val t = OfficerMind(calm); t.onTells(listOf(Tell.LIPS to 3.0), 0.0)
        assertEquals(METER_TELL * 2 * (t.suspicion - calm.suspicion), t.meter, 1e-9) // suspicion takes half of the tell, the meter all of it
    }

    @Test fun trainingAndStoryNeverRefer() {
        for (mode in 1..2) {
            val m = OfficerMind(calm).apply {
                training = mode == 1; storyMission = mode == 2
                onEncounter(1.0, 0.0)
            }
            assertEquals(Decision.PASS, passAfter(m, Implant.DETAILED).decision)
            for (meter in listOf(0.0, REFER_AT, 1.0))
                assertFalse(refers(m.training, m.storyMission, meter))
            val usa = OfficerMind(calm).apply { training = mode == 1; storyMission = mode == 2 }
            usa.startDesk("usa")
            assertNull(usa.decision)
        }
        val usa = OfficerMind(calm)
        usa.startDesk("usa")
        assertEquals(Decision.REFERRAL, usa.decision)
    }

    @Test fun storyCrashesPassWithoutReferral() {
        val m = OfficerMind(calm).apply { storyMission = true }
        repeat(3) { answer(m); m.onCheck(good.copy(trick = true)) }
        assertEquals(Decision.PASS, m.decision)
    }

    @Test fun tellsAloneNeverSendToSecondary() {
        val m = OfficerMind(calm.copy(suspicion = 0.9))
        repeat(20) { m.onTells(listOf(Tell.LIPS to 4.0, Tell.BLINKS to 4.0), absent = 0.0) }
        assertEquals(0.9 + calm.tellCap / 2, m.suspicion, 1e-9) // capped per run, half of it reaches suspicion
        assertNull(m.decision)
        answer(m); m.onCheck(good.copy(answered = false)) // a dodge backs the tells up
        assertEquals(Decision.REFUSED, m.decision)
    }

    @Test fun tellsWeighMoreAtHigherRanksButStayCappedAndNeedGrounds() {
        val tells = listOf(Tell.LIPS to 3.0, Tell.BLINKS to 2.0)
        val rookie = OfficerMind(calm.copy(level = 0)).apply { onTells(tells, absent = 0.0) }
        val ghost = OfficerMind(calm.copy(level = 3)).apply { onTells(tells, absent = 0.0) }
        assertEquals(4 * (rookie.suspicion - calm.suspicion), ghost.suspicion - calm.suspicion, 1e-9) // ×0.5 vs ×2.0
        val capped = OfficerMind(calm.copy(level = 3, suspicion = 0.9))
        repeat(40) { capped.onTells(listOf(Tell.LIPS to 4.0, Tell.BLINKS to 4.0), absent = 0.0) }
        assertEquals(0.9 + 0.60 / 2, capped.suspicion, 1e-9) // the ghost cap
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
        assertEquals(Decision.REFUSED, m.decision)
    }

    @Test fun answeringResetsThePauseCount() {
        val m = OfficerMind(calm)
        repeat(10) { m.onPause(passportPending = false); m.onPause(passportPending = false); answer(m) }
        assertEquals(0, m.silences)
    }

    @Test fun serialLiarIsNotPassed() {
        // A new contradiction on each of five facts, then three good answers: the old flat +0.08 ended at 0.62 → PASS.
        val m = OfficerMind(calm)
        (3..7).forEach { answer(m); m.onCheck(good.copy(contradiction = true), f(it)) }
        repeat(3) { answer(m); m.onCheck(good, f(6)) }
        assertEquals(Decision.REFUSED, m.decision) // the third bad answer refuses; the rest cannot pass it back
        org.junit.Assert.assertTrue(m.suspicion > 0.35)
    }

    @Test fun turnCapNeedsTwoGoodFacts() {
        val m = OfficerMind(calm.copy(busy = 0.0))
        repeat(m.maxAnswers) { answer(m); m.onCheck(good.copy(clarity = 1), f(0)) } // clean but never about a cover fact
        assertEquals(Decision.REFUSED, m.decision)
        val ok = OfficerMind(calm.copy(busy = 0.0))
        repeat(m.maxAnswers) { answer(ok); ok.onCheck(good, f(if (it < 2) 3 + it else 0)) }
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
        assertEquals(Decision.REFUSED, m.decision)
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
        assertEquals(Decision.REFERRAL, m.decision) // a crash brings the associate at once
        org.junit.Assert.assertTrue(m.crashed)
        // The room after a desk break: a rebooted unit, not crashed again by the first answer.
        m.enterRoom(); answer(m); m.onCheck(good)
        assertNull(m.decision)
        org.junit.Assert.assertFalse(m.crashed)
        // The associate can't be crashed into a release: the room's verdict is the Assessment's.
        repeat(3) { answer(m); m.onCheck(good.copy(trick = true)) }
        assertNull(m.decision)
    }

    @Test fun trainingCrashPassesAndPassesReferOnlyByTheRule() {
        val t = OfficerMind(calm).apply { training = true; storyMission = true }
        repeat(3) { answer(t); t.onCheck(good.copy(trick = true)) }
        assertEquals(Decision.PASS, t.decision) // training is level 1 only
        // A clean desk pass with no referral evidence passes; a debug #pass follows the same rule, never in the room.
        for (story in listOf(false, true)) {
            val m = OfficerMind(calm.copy(suspicion = 0.0)).apply { storyMission = story }
            repeat(m.wanted) { answer(m); m.onCheck(good, f(3 + it)) }
            assertEquals(Decision.PASS, m.decision)
            assertEquals(Decision.PASS, OfficerMind(calm.copy(suspicion = 0.0)).apply { storyMission = story; settle(Decision.PASS) }.decision)
        }
        val room = OfficerMind(calm).apply { storyMission = true; enterRoom(); settle(Decision.RELEASED) }
        assertEquals(Decision.RELEASED, room.decision)
    }

    @Test fun roomInterrogatorIsAFreshUnit() {
        val m = OfficerMind(calm.copy(suspicion = 0.0))
        val trick = good.copy(answered = false, trick = true)
        answer(m); m.onCheck(trick); answer(m); m.onCheck(good, f(3))
        answer(m); m.onCheck(trick) // spread out: two single hits, no chain
        assertEquals(0.5, m.integrity, 0.01)
        var fact = 4
        while (m.decision == null) { answer(m); m.onCheck(good, f(fact++)) }
        assertEquals(Decision.PASS, m.decision)
        m.enterRoom()
        assertEquals(1.0, m.integrity, 0.0)
        val b = m.integrity; answer(m); m.onCheck(trick)
        assertEquals(R.string.implant_unit_slipping, integrityHint(b, m.integrity, m.decision != null))
        assertEquals(0.75, m.integrity, 0.01) // a first hit, not the second link of a desk chain
    }

    @Test fun spreadOutTricksDoNotChain() {
        val m = OfficerMind(calm)
        repeat(2) { answer(m); m.onCheck(good.copy(trick = true)); answer(m); m.onCheck(good) }
        assertNull(m.decision)
        assertEquals(0.5, m.integrity, 1e-9)
    }

    @Test fun strictOfficersCatchTricksFirst() {
        val m = OfficerMind(calm.copy(strictness = 1.0, suspicion = 0.4))
        repeat(4) { answer(m); m.onCheck(good.copy(trick = true)) }
        assertEquals(Decision.REFUSED, m.decision)
    }

    @Test fun tellsRaiseSuspicion() {
        val quiet = OfficerMind(calm).apply { onTells(emptyList(), absent = 0.0) }
        val shifty = OfficerMind(calm).apply { onTells(listOf(Tell.LIPS to 3.0, Tell.BLINKS to 2.0), absent = 0.0) }
        assertEquals(calm.suspicion - CALM_DESK, quiet.suspicion, 1e-9) // a poker face pays a little
        org.junit.Assert.assertTrue(shifty.suspicion > calm.suspicion + 0.01)
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

    @Test fun unaidedPokerFaceCalmsTheRoom() {
        val a = Assessment(0.5, Backstory.BENCH, kotlin.random.Random(1), coached = false)
        val start = a.index
        a.onTells(emptyList(), absent = 0.0); assertEquals(start - 1, a.index, 1e-9)
        a.onTells(emptyList(), absent = 0.9); assertEquals(start - 1 + 3, a.index, 1e-9) // unseen: the absence costs, no bonus
    }

    @Test fun facesFollowEvents() {
        val m = OfficerMind(calm)
        assertEquals(Face.NEUTRAL, m.face())
        answer(m); m.onCheck(Check.UNCLEAR)
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

    private val flagged = Check(answered = true, contradiction = true, clarity = 2, inLanguage = true)
    private val fine = Check(answered = true, contradiction = false, clarity = 2, inLanguage = true)
    private fun deskMind() = OfficerMind(Persona(1.0, 0.0, 0.5, 1.0, 0.0))

    @Test fun firstFlagIsReaskedOnceAndTheSecondCounts() {
        val m = deskMind()
        m.onAnswer(Delivery(1000, 1.0, "Two weeks."))
        assertTrue(m.reask(flagged))
        assertEquals(0, m.turns); assertEquals(0, m.flagCount); assertNull(m.slip)
        m.onAnswer(Delivery(1000, 1.0, "Three weeks."))
        assertFalse(m.reask(flagged)) // once per desk
        m.onCheck(flagged, FactId.DAYS, "Three weeks.")
        assertEquals(1, m.flagCount); assertEquals("Three weeks." to FactId.DAYS, m.slip)
    }

    @Test fun reaskedContradictionCountsTowardTheTranslatorStreak() {
        val dodge = fine.copy(answered = false)
        val m = deskMind()
        m.onAnswer(Delivery(1000, 1.0, "x")); assertTrue(m.reask(flagged)); assertFalse(m.translatorOffered) // reask alone never offers
        m.onAnswer(Delivery(1000, 1.0, "x")); m.onCheck(dodge, FactId.DAYS); assertTrue(m.translatorOffered)
        val n = deskMind() // dodge, re-asked contradiction, good repeat: the good one resets
        n.onAnswer(Delivery(1000, 1.0, "x")); n.onCheck(dodge, FactId.DAYS)
        n.onAnswer(Delivery(1000, 1.0, "x")); assertTrue(n.reask(flagged))
        n.onAnswer(Delivery(1000, 1.0, "x")); n.onCheck(fine, FactId.DAYS); assertFalse(n.translatorOffered)
        val o = deskMind() // an odd-only re-ask doesn't count
        o.onAnswer(Delivery(1000, 1.0, "x")); assertTrue(o.reask(fine.copy(odd = true)))
        o.onAnswer(Delivery(1000, 1.0, "x")); o.onCheck(dodge, FactId.DAYS); assertFalse(o.translatorOffered)
    }

    @Test fun reaskSkipsCleanAnswersTricksAndTheRoom() {
        val m = deskMind()
        assertFalse(m.reask(fine))
        assertFalse(m.reask(flagged.copy(trick = true)))
        m.enterRoom()
        assertFalse(m.reask(flagged))
    }

    @Test fun everyLanguageHasAReaskQuestion() {
        for (l in Language.all) assertTrue(l.code, isQuestion(OFFICER_LINES.getValue(l.code).getValue("reask.1"), l.code))
    }

    @Test fun untrainedLanguageStartsWithMorePatience() {
        val p = Persona(0.8, 0.0, 0.5, 1.0, 0.0)
        assertEquals(0.8 * GENTLE_PATIENCE, OfficerMind(p, gentle = true).patience, 1e-9)
        assertEquals(0.8, OfficerMind(p).patience, 1e-9)
        assertTrue(gentleDesk("de", setOf("fr"), false))
        assertFalse(gentleDesk("de", setOf("de"), false))
        assertFalse(gentleDesk("de", emptySet(), true))
        assertFalse(gentleDesk("de", null, false))
    }
}

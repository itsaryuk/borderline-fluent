package com.lampensoft.borderlinefluent

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 2026-10-16 indie polish, design items 8–13. */
class DesignPolishTest {
    private val human = RoomCheck(feeling = true, evasive = false, implant = false, inLanguage = true, clarity = 2, trick = false)
    private val usa = assessmentOffset(Act.FREE, "usa", story = false, training = false)

    private fun exam(suspicion: Double = 0.0) = Assessment(suspicion, Backstory.BENCH, Random(7), difficultyOffset = usa, exam = true)

    @Test fun usaExamAsksEveryQuestionAndSwapsInThePrototypeOnceWhenTooEasy() {
        assertEquals(25.0, USA_ASSESSMENT_OFFSET, 0.0)
        val a = exam()
        assertEquals(50.0, a.index, 0.0)
        while (a.asked < Assessment.SWAP_AT) {
            assertNull(a.verdict())
            a.onAnswer(2000, 6); a.onCheck(human); a.next() // −3 timing, −6 credited
        }
        assertTrue(a.prototype)
        assertEquals(Assessment.PROTOTYPE_INDEX, a.index, 0.0)
        assertEquals(R.string.machine_prototype, a.event)
        var last = a.index
        while (a.verdict() == null) {
            a.onAnswer(2000, 6); a.onCheck(human)
            assertEquals(last - 3 + Assessment.PROTOTYPE_CREDIT, a.index, 1e-9) // fewer credits after the swap
            last = a.index
            a.next()
            assertTrue(a.prototype)
            assertEquals(last, a.index, 0.0) // never a second swap
        }
        assertEquals(a.questions.size, a.asked) // no early release
        assertEquals(Decision.RELEASED, a.verdict()) // the USA ending still follows a release
    }

    @Test fun noSwapForAHardRunOrOutsideTheExam() {
        val slow = exam(suspicion = 1.0) // 85 at the start: still above the line after four good answers
        while (slow.asked < Assessment.SWAP_AT) { slow.onAnswer(2000, 6); slow.onCheck(human); slow.next() }
        assertFalse(slow.prototype)
        val typical = exam(suspicion = 0.4) // the top of a USA officer's range (no desk): four good answers still swap
        while (typical.asked < Assessment.SWAP_AT) { typical.onAnswer(2000, 6); typical.onCheck(human); typical.next() }
        assertTrue(typical.prototype)
        val usual = Assessment(0.0, Backstory.BENCH, Random(7))
        while (usual.verdict() == null) { usual.onAnswer(2000, 6); usual.onCheck(human); usual.next() }
        assertFalse(usual.prototype)
        assertTrue(usual.asked < usual.questions.size) // elsewhere the early release stays
        assertEquals(Decision.RELEASED, usual.verdict())
    }

    @Test fun smallTalkAvoidsTheRecentLines() {
        var recent = emptyList<Int>()
        val r = Random(3)
        repeat(40) {
            val pick = pickSmallTalk(8, recent, r)
            assertFalse("$pick in $recent", pick in recent)
            recent = rememberTalk(recent, pick)
            assertTrue(recent.size <= RECENT_TALK)
            assertEquals(pick, recent.last())
        }
        assertEquals(listOf(2, 3, 1), rememberTalk(listOf(1, 2, 3), 1).takeLast(3))
        assertTrue(pickSmallTalk(2, listOf(0, 1), r) in 0..1) // everything recent: any line
    }

    @Test fun actTwoDeskPassSaysItEarnedNothing() {
        assertTrue(tooCleanPass(Act.FREE, story = false, training = false, inRoom = false, decision = Decision.PASS))
        assertFalse(tooCleanPass(Act.FREE, story = false, training = false, inRoom = true, decision = Decision.RELEASED))
        assertFalse(tooCleanPass(Act.FREE, story = false, training = false, inRoom = false, decision = Decision.REFUSED))
        assertFalse(tooCleanPass(Act.FREE, story = false, training = true, inRoom = false, decision = Decision.PASS))
        assertFalse(tooCleanPass(Act.FREE, story = true, training = false, inRoom = false, decision = Decision.PASS))
        for (act in listOf(Act.STORY, Act.LEFT))
            assertFalse(tooCleanPass(act, story = false, training = false, inRoom = false, decision = Decision.PASS))
    }

    @Test fun chatbotTipOnceAfterAGoodAnswer() {
        assertTrue(chatbotTipDue(thalassian = false, goodAnswers = 1, shown = false))
        assertFalse(chatbotTipDue(thalassian = false, goodAnswers = 0, shown = false))
        assertFalse(chatbotTipDue(thalassian = false, goodAnswers = 2, shown = true))
        assertFalse(chatbotTipDue(thalassian = true, goodAnswers = 2, shown = false))
    }

    @Test fun refusalKeepsWhatTheOfficerHeardAndTheFact() {
        val m = OfficerMind(Persona.TRAINING)
        val good = Check(answered = true, contradiction = false, clarity = 2, inLanguage = true)
        m.onCheck(good, FactId.PURPOSE, "For a conference.")
        assertNull(m.slip)
        m.onCheck(good.copy(contradiction = true), FactId.DAYS, "Two weeks.")
        assertEquals("Two weeks." to FactId.DAYS, m.slip)
    }

    @Test fun implantMentionTravelsFromTheDeskToTheResult() {
        val a = Attempt(1, "de", "o", "de", training = false, story = false, replayEligible = true, effectiveLevel = 0)
        var live = LiveAttempt(1, a)
        live = captureDecision(live, 1, Decision.REFERRAL, inRoom = false, roomReady = false, mentioned = true)
        live = captureDecision(live, 1, Decision.RELEASED, inRoom = true, roomReady = true, doses = 2, mentioned = false)
        val result = finishAttempt(live, 1, TerminalKind.DECIDED).terminal!!
        assertTrue(result.implantMentioned)
        assertEquals(2, result.doses)
        assertFalse(evaluateContract(Contract.UNMENTIONED, ReplayOutcome.CLEARED, false, result.implantMentioned, result.doses, true))
        assertTrue(evaluateContract(Contract.SAMPLES, ReplayOutcome.CLEARED, false, result.implantMentioned, result.doses, true))
    }
}

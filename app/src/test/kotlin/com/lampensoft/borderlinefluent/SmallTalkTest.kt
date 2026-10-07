package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmallTalkTest {
    @Test fun lastSmallTalkSilenceKeepsWeatherAnswerOutOfCoverJudge() {
        // Device sequence: weather question, silence, "Ja, es ist sonnig."
        assertEquals(1, nextSmallTalk(1, 2, answered = false))
        assertEquals(null, nextSmallTalk(1, 2, answered = true))
        assertEquals(false, judgeInterviewAnswer(true, wasSmallTalk = true, handover = true))
        assertEquals(false, judgeInterviewAnswer(true, wasSmallTalk = true, handover = false))
        assertEquals(false, judgeInterviewAnswer(false, wasSmallTalk = false, handover = false))
        assertEquals(false, judgeInterviewAnswer(true, wasSmallTalk = false, handover = true))
        assertEquals(true, judgeInterviewAnswer(true, wasSmallTalk = false, handover = false))
        assertEquals(1, nextSmallTalk(0, 2, answered = true))
    }

    @Test fun everyLanguageHasEverySeedAsAQuestion() {
        val n = SMALL_TALK.getValue("en").size
        assertEquals(8, n)
        assertTrue(SMALL_TALK_ASKED <= n)
        for (l in Language.all) {
            val lines = SMALL_TALK[l.code] ?: error("no small talk for ${l.code}")
            assertEquals(l.code, n, lines.size)
            lines.forEach { assertTrue("${l.code}: $it", it.isNotBlank() && isQuestion(it, l.code)) }
        }
    }

    @Test fun paceIsTheMedian() {
        assertEquals(null, Pace.of(emptyList()).baseMs)
        assertEquals(2000L, Pace.of(listOf(3000, 1000)).baseMs)
        assertEquals(1500L, Pace.of(listOf(9000, 1500, 400)).baseMs)
        assertEquals(4500L, Pace.of(listOf(20_000)).baseMs); assertEquals(500L, Pace.of(listOf(100)).baseMs) // clamped
        org.junit.Assert.assertTrue(Pace.of(listOf(60_000)).slow(7000) < Assessment.ANSWER_MS) // under the room's window
    }

    @Test fun thresholdsFollowThePlayer() {
        assertEquals(7000L, Pace().slow(7000)); assertEquals(700L, Pace().fast(700)) // no small talk: as before
        val slow = Pace(4000) // a slow talker: slow = 2.5 × base, fast unchanged
        assertEquals(10_000L, slow.slow(7000)); assertEquals(700L, slow.fast(700))
        val quick = Pace(1000) // a quick one: fast = 0.4 × base, slow unchanged
        assertEquals(7000L, quick.slow(7000)); assertEquals(400L, quick.fast(700))
    }

    @Test fun slowTalkerIsNotPenalisedForTheirOwnPace() {
        fun cost(pace: Pace) = OfficerMind(Persona.TRAINING).run { this.pace = pace; onAnswer(Delivery(6000, 2.0, "Fünf Tage.")); patience }
        assertTrue(cost(Pace(6000)) > cost(Pace()))
        val fixed = Assessment(0.5, Backstory.BENCH, Random(1)).apply { onAnswer(8000, 5) }.index
        val relative = Assessment(0.5, Backstory.BENCH, Random(1)).apply { onAnswer(8000, 5, Pace(4000)) }.index
        assertEquals(fixed - 3.0, relative, 1e-9) // 8 s: slow (+3) for anyone, normal (0) for a 4 s talker
    }
}

class SmallTalkMindTest {
    @Test fun aSmallTalkAnswerRestartsTheFreePauses() {
        val m = OfficerMind(Persona.TRAINING)
        m.onPause(passportPending = true); m.onPause(passportPending = true) // two passport pauses
        val before = m.state()
        m.onSmallTalk() // Game calls it when the handover is held and after each small-talk answer
        assertEquals(before, m.state()) // patience, suspicion, turns untouched
        m.onPause(passportPending = false) // a small-talk silence: still free
        assertEquals(0, m.silences)
        val n = OfficerMind(Persona.TRAINING)
        repeat(3) { n.onPause(passportPending = false) } // without the answer the third one counts
        assertEquals(1, n.silences)
    }

    @Test fun zeroThinkTimesDontCount() {
        assertEquals(2000L, Pace.of(listOf(0, 2000)).baseMs)
        assertEquals(null, Pace.of(listOf(0, 0)).baseMs) // nothing left: fixed thresholds
        assertEquals(700L, Pace.of(listOf(0, 0)).fast(700))
    }
}

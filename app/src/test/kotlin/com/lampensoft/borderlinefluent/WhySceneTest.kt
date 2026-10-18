package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhySceneTest {
    private val free = CareerSnapshot(act = Act.FREE, machineSessions = 2)
    private fun due(s: CareerSnapshot? = free, act: Act = Act.FREE, d: Decision? = Decision.RELEASED,
                    usa: Boolean = false, training: Boolean = false, story: Boolean = false) =
        whySceneDue(s, act, d, usa, training, story)

    @Test fun playsOnceAfterTheSecondRelease() {
        assertTrue(due())
        assertTrue(due(free.copy(machineSessions = 5)))
        assertFalse(due(free.copy(machineSessions = 1)))
        assertFalse(due(free.copy(whySceneSeen = true)))
        assertFalse(due(null))
    }

    @Test fun neverWhenWalkedAway() { assertFalse(due(free.copy(walkedAway = true))) }

    @Test fun neverOnARefusalTheUsaFinalTrainingStoryOrActOne() {
        for (d in listOf(Decision.REFUSED, Decision.CAUGHT, Decision.REFERRAL, Decision.PASS, null)) assertFalse("$d", due(d = d))
        assertFalse(due(usa = true)); assertFalse(due(training = true)); assertFalse(due(story = true))
        assertFalse(due(act = Act.STORY)); assertFalse(due(act = Act.LEFT))
    }

    /** Plays the script taking option [picks] at each choice; returns every turn text shown. */
    private fun play(picks: List<Int>): List<Int> {
        val shown = mutableListOf<Int>(); val it = picks.iterator()
        for (step in WHY_SCRIPT) when (step) {
            is WhyTurns -> shown += step.turns.map { t -> t.text }
            is WhyChoice -> step.options[it.next()].let { o -> shown += o.reply.map { t -> t.text }; if (o.ends) return shown }
        }
        assertFalse(it.hasNext())
        return shown
    }

    @Test fun everyBranchReachesTheEndAndTheFollowingBranchAddsTheWhyLine() {
        val choices = WHY_SCRIPT.filterIsInstance<WhyChoice>()
        assertEquals(3, choices.size)
        assertEquals(listOf("why_corridor", "why_window", "why_gate"), choices.map { it.art })
        for (a in 0..1) for (b in 0..1) for (c in 0..1) {
            val shown = play(if (b == 1) listOf(a, b) else listOf(a, b, c))
            assertEquals(R.string.why_n_gone, shown.last())
            assertTrue(R.string.why_q_why in shown)
        }
        // "No" earns silence: the narrator line, no Marek reply.
        assertEquals(listOf(R.string.why_q_no, R.string.why_n_gone), WHY_SCRIPT.filterIsInstance<WhyChoice>()[1].options[1].reply.map { it.text })
        val turns = play(listOf(0, 0, 0))
        assertTrue(R.string.why_m_switch in turns && R.string.why_m_business in turns)
        val following = play(listOf(0, 0, 0)); val direct = play(listOf(1, 0, 0))
        assertEquals(listOf(R.string.why_q_following, R.string.why_m_following, R.string.why_q_why),
            following.subList(following.indexOf(R.string.why_q_following), following.indexOf(R.string.why_q_why) + 1))
        assertEquals(direct.size + 2, following.size)
        assertEquals(1, following.count { it == R.string.why_q_why })
    }

    @Test fun snapshotRoundTripsAndOldSavesLoadAsUnseen() {
        assertTrue(decoded(CareerJson.encode(CareerSnapshot(whySceneSeen = true))).whySceneSeen)
        val unseen = CareerJson.encode(CareerSnapshot())
        assertFalse(decoded(unseen).whySceneSeen)
        assertFalse(org.json.JSONObject(unseen).has("whySceneSeen"))
    }
}

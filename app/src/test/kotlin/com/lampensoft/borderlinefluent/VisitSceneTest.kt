package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitSceneTest {
    private val seen = CareerSnapshot(act = Act.FREE, whySceneSeen = true, trainedLanguages = setOf("de", "fr"))
    private fun due(s: CareerSnapshot? = seen, d: Decision? = Decision.RELEASED, usa: Boolean = false,
                    installed: Set<String> = setOf("de", "fr")) =
        visitSceneDue(s, Act.FREE, d, usa, training = false, story = false, installed)

    @Test fun dueOnlyWhenWhySeenAndEveryInstalledLanguageIsTrained() {
        assertTrue(due())
        assertTrue(due(installed = setOf("de")))
        assertFalse(due(seen.copy(whySceneSeen = false)))
        assertFalse(due(installed = setOf("de", "fr", "es")))
        assertFalse(due(installed = emptySet()))
        assertFalse(due(seen.copy(visitSceneSeen = true)))
        assertFalse(due(null))
    }

    @Test fun neverWhenWalkedAway() { assertFalse(due(seen.copy(walkedAway = true))) }

    @Test fun neverOnARefusalTheUsaFinalTrainingStoryOrOtherActs() {
        assertFalse(due(d = Decision.REFUSED)); assertFalse(due(d = null)); assertFalse(due(usa = true))
        assertFalse(visitSceneDue(seen, Act.FREE, Decision.RELEASED, false, training = true, story = false, setOf("de")))
        assertFalse(visitSceneDue(seen, Act.FREE, Decision.RELEASED, false, training = false, story = true, setOf("de")))
        assertFalse(visitSceneDue(seen, Act.STORY, Decision.RELEASED, false, false, false, setOf("de")))
    }

    private fun shown(script: List<WhyStep>, picks: List<Int>): List<Int> {
        val it = picks.iterator(); val out = mutableListOf<Int>(); var ended = false
        for (step in script) { if (ended) break; when (step) {
            is WhyTurns -> out += step.turns.map { t -> t.text }
            is WhyChoice -> { val o = step.options[it.next()]; out += o.reply.map { t -> t.text }; if (o.ends) ended = true }
        } }
        assertFalse(it.hasNext())
        return out
    }

    @Test fun missingLanguagesEndWithShowMeOrLater() {
        val s = visitScript(allInstalled = false)
        assertEquals(listOf(R.string.visit_q_show, R.string.visit_q_later), (s.last() as WhyChoice).options.map { it.label })
        assertTrue((s.last() as WhyChoice).options[0].opensLanguages)
        assertTrue(shown(s, listOf(0, 1)).last() == R.string.visit_m_later)
        assertTrue(R.string.visit_m_over in shown(s, listOf(1, 1)))
        assertFalse(R.string.visit_m_over in shown(s, listOf(0, 0)))
        assertTrue(R.string.visit_m_load in shown(s, listOf(0, 0)))
        assertTrue(s.filterIsInstance<WhyTurns>().flatMap { it.turns }.single { it.text == R.plurals.visit_m_langs }.plural)
    }

    @Test fun allLanguagesInstalledEndsWithAirports() {
        val s = visitScript(allInstalled = true)
        for (p in 0..1) assertEquals(R.string.visit_m_airports, shown(s, listOf(p)).last())
        assertFalse(s.any { it is WhyChoice && it.options.any { o -> o.opensLanguages } })
    }

    @Test fun snapshotRoundTripsAndOldSavesLoadAsUnseen() {
        assertTrue(decoded(CareerJson.encode(CareerSnapshot(visitSceneSeen = true))).visitSceneSeen)
        val old = CareerJson.encode(CareerSnapshot())
        assertFalse(decoded(old).visitSceneSeen)
        assertFalse(org.json.JSONObject(old).has("visitSceneSeen"))
    }
}

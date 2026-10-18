package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone translator beat. */
class TranslatorTest {
    private val calm = Persona(patience = 0.8, suspicion = 0.2, strictness = 0.7, tolerance = 0.5, busy = 0.3)
    private val wrongLanguage = Check(answered = true, contradiction = false, clarity = 2, inLanguage = false)

    @Test fun theSecondUnusableAnswerAtADeskOffersThePhoneOnce() {
        val m = OfficerMind(calm)
        m.onCheck(Check.UNCLEAR)
        assertFalse("one unclear answer is not enough", m.translatorOffered)
        m.onCheck(wrongLanguage) // not in the officer's language counts too
        assertTrue(m.translatorOffered)
        assertTrue(m.delta, "translator" in m.delta)
        val after = m.suspicion
        m.onCheck(Check.UNCLEAR) // a third: no second offer, no second cost (unclear itself costs no suspicion)
        assertTrue(m.translatorOffered)
        assertFalse(m.delta, "translator" in m.delta)
        assertEquals(after, m.suspicion, 1e-9)
    }

    @Test fun theOfferRaisesSuspicion() {
        val m = OfficerMind(calm)
        m.onCheck(Check.UNCLEAR); m.onCheck(Check.UNCLEAR)
        assertEquals(calm.suspicion + TRANSLATOR_SUSPICION, m.suspicion, 1e-9)
    }

    @Test fun neverInTrainingTheStoryOrTheRoom() {
        val training = OfficerMind(calm).apply { this.training = true }
        val story = OfficerMind(calm).apply { storyMission = true }
        val room = OfficerMind(calm).apply { enterRoom() }
        for (m in listOf(training, story, room)) {
            repeat(3) { m.onCheck(Check.UNCLEAR) }
            assertFalse(m.translatorOffered)
            assertFalse(m.useTranslator())
        }
    }

    @Test fun threeCreditsPerEncounter() {
        val m = OfficerMind(calm)
        assertFalse("not before the offer", m.useTranslator())
        m.onCheck(Check.UNCLEAR); m.onCheck(Check.UNCLEAR)
        repeat(TRANSLATOR_CREDITS) { assertTrue(m.useTranslator()) }
        assertEquals(0, m.translatorCredits)
        assertFalse("out of credits", m.useTranslator())
        // The next desk is a new mind: full credits again, once offered there.
        val next = OfficerMind(calm)
        assertEquals(TRANSLATOR_CREDITS, next.translatorCredits)
    }

    @Test fun noCreditSpentAfterTheDeskMovesToTheRoom() {
        val m = OfficerMind(calm)
        m.onCheck(Check.UNCLEAR); m.onCheck(Check.UNCLEAR)
        m.enterRoom()
        assertFalse(m.useTranslator())
        assertEquals(TRANSLATOR_CREDITS, m.translatorCredits)
    }

    @Test fun theAssociateKnowsYouOnlyAfterThePhoneTranslated() {
        assertEquals("associate.greet.1", associateGreet(true))
        assertEquals("associate.greet.2", associateGreet(false))
        for (l in Language.all.map { it.code }) assertTrue(l, OFFICER_LINES.getValue(l).containsKey("translator.offer.1"))
    }

    @Test fun translatorUsedRoundTripsAndOldSavesLoadAsUnused() {
        assertTrue(decoded(CareerJson.encode(CareerSnapshot(translatorUsed = true))).translatorUsed)
        val old = CareerJson.encode(CareerSnapshot())
        assertFalse(org.json.JSONObject(old).has("translatorUsed"))
        assertFalse(decoded(old).translatorUsed)
    }

    @Test fun theAttemptSavesTranslatorUseWithItsResult() {
        val a = attempt()
        val live = captureDecision(LiveAttempt(1, a, translatorUsed = true), 1, Decision.PASS, false, false)
        val event = finishAttempt(live, 1, TerminalKind.DECIDED).terminal!!
        assertTrue(event.translatorUsed)
        assertTrue(reduceReplayResult(pending(a), event).snapshot.translatorUsed)
        assertFalse(reduceReplayResult(pending(a), deskPass(a)).snapshot.translatorUsed)
        // Once used, a later attempt without the phone keeps it.
        assertTrue(reduceReplayResult(pending(a).copy(translatorUsed = true), deskPass(a)).snapshot.translatorUsed)
    }

    @Test fun translatorUnlockedRoundTripsAndOldSavesLoadAsLocked() {
        assertTrue(decoded(CareerJson.encode(CareerSnapshot(translatorUnlocked = true))).translatorUnlocked)
        val old = CareerJson.encode(CareerSnapshot())
        assertFalse(org.json.JSONObject(old).has("translatorUnlocked"))
        assertFalse(decoded(old).translatorUnlocked)
    }

    @Test fun theOfferIsSavedWithTheResultAndKept() {
        val a = attempt()
        val live = captureDecision(LiveAttempt(1, a, translatorOffered = true), 1, Decision.PASS, false, false)
        val event = finishAttempt(live, 1, TerminalKind.DECIDED).terminal!!
        assertTrue(event.translatorOffered)
        assertTrue(reduceReplayResult(pending(a), event).snapshot.translatorUnlocked)
        assertFalse(reduceReplayResult(pending(a), deskPass(a)).snapshot.translatorUnlocked)
        assertTrue(reduceReplayResult(pending(a).copy(translatorUnlocked = true), deskPass(a)).snapshot.translatorUnlocked)
    }

    @Test fun unlockStartsTheDeskWithThePhoneAtNoCost() {
        val m = OfficerMind(calm)
        val before = m.suspicion
        m.unlockTranslator()
        assertTrue(m.translatorOffered)
        assertEquals(TRANSLATOR_CREDITS, m.translatorCredits)
        assertEquals(before, m.suspicion, 1e-9)
        assertTrue(m.useTranslator())
        // Already offered: two unclear answers fire no offer and cost nothing.
        val n = OfficerMind(calm).apply { unlockTranslator() }
        val s = n.suspicion
        n.onCheck(Check.UNCLEAR); n.onCheck(Check.UNCLEAR)
        assertFalse(n.delta, "translator" in n.delta)
        assertEquals(s, n.suspicion, 1e-9)
    }

    @Test fun unlockDoesNothingInTrainingTheStoryOrTheRoom() {
        val training = OfficerMind(calm).apply { this.training = true }
        val story = OfficerMind(calm).apply { storyMission = true }
        val room = OfficerMind(calm).apply { enterRoom() }
        for (m in listOf(training, story, room)) { m.unlockTranslator(); assertFalse(m.translatorOffered); assertFalse(m.useTranslator()) }
    }

    @Test fun aSaveWithTheRemovedTrialDayStillLoads() {
        val old = org.json.JSONObject(CareerJson.encode(CareerSnapshot())).put("translatorTrialDay", 12).toString()
        val s = decoded(old)
        assertEquals(0, s.daysAway)
        assertTrue("kept as an unknown key", "translatorTrialDay" in org.json.JSONObject(CareerJson.encode(s)).keys().asSequence().toSet())
    }

    @Test fun translationParsing() {
        assertEquals("Sono qui per lavoro.", translatorLine("IT: Sono qui per lavoro.", "it"))
        assertEquals("Sono qui per lavoro.", translatorLine("EN: I am here for work.\nIT: Sono qui per lavoro.", "it"))
        assertEquals("Sono qui per lavoro.", translatorLine("\"Sono qui per lavoro.\"", "it"))
        assertEquals("Sono qui per lavoro.", translatorLine("[thinking]\nit: «Sono qui per lavoro.»", "it"))
        assertEquals("出張で来ました。", translatorLine("JA: 「出張で来ました。」", "ja"))
        assertEquals("", translatorLine("IT: Sono qui per <motivo>.", "it"))
        assertEquals("", translatorLine("  \n", "it"))
        val p = translatorPrompt("de", "ja")
        assertTrue(p, "German" in p && "Japanese" in p && "JA:" in p)
        assertFalse("no placeholders in an output template", p.any { it in "<>[]" })
    }
}

package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThalassiaTripTest {
    private val script = thalassiaScript()
    private val read = setOf(THALASSIA_MSG)
    /** The ending acknowledged: usaCompleted, no pending receipt ending. */
    private val ended = CareerSnapshot(act = Act.FREE, usaCompleted = true)
    private fun offered(s: CareerSnapshot?, r: Set<String> = read) = thalassiaTripOffered(s, Act1Stage.DONE, r)
    private fun messageDelivered(s: CareerSnapshot?) = deliveredMessages(s, Act1Stage.DONE).any { it.id == THALASSIA_MSG }

    /** The USA ending's result screen still showing (its receipt keeps the ending until acknowledged). */
    private fun endingPending(): CareerSnapshot {
        val airport = Country.of("usa").airports.first()
        val a = attempt(1, "usa", airport.officers.first()).copy(language = airport.lang, airportId = airport.id,
            contentVersion = 2, progressEligible = true, preparation = preparation().copy(countryId = "usa", kind = TripKind.ARRIVAL))
        return reduceReplayResult(pending(a).copy(act = Act.FREE, usaUnlocked = true), victory(a).copy(doses = 1)).snapshot
    }

    @Test fun neverBeforeTheUsaEnding() {
        assertFalse(messageDelivered(ended.copy(usaCompleted = false)))
        assertFalse(offered(ended.copy(usaCompleted = false)))
        assertFalse(offered(null))
        assertFalse(offered(ended.copy(act = Act.LEFT)))
    }

    @Test fun theMessageWaitsForTheEndingToBeAcknowledged() {
        val s = endingPending()
        assertTrue(usaCompletionPending(s))
        assertFalse(messageDelivered(s))
        val acknowledged = s.copy(receipt = s.receipt?.copy(ending = null))
        assertTrue(messageDelivered(acknowledged))
        assertTrue(offered(acknowledged))
    }

    @Test fun offeredOnlyOnceTheMessageIsReadAndUntilSeen() {
        assertTrue(messageDelivered(ended))
        assertFalse(offered(ended, emptySet()))
        assertTrue(offered(ended))
        assertFalse(offered(ended.copy(thalassiaSeen = true)))
        assertTrue(messageDelivered(ended.copy(thalassiaSeen = true))) // the message stays in the thread
    }

    @Test fun walkedAwayCareersThatReachedTheEndingGetItToo() { assertTrue(offered(ended.copy(walkedAway = true))) }

    @Test fun aKillMidSceneOffersTheTripAgain() {
        // Nothing is saved until the credits end: the reloaded career is the one that offered the trip.
        assertTrue(offered(decoded(CareerJson.encode(ended))))
        assertFalse(offered(decoded(CareerJson.encode(ended.copy(thalassiaSeen = true)))))
    }

    @Test fun snapshotRoundTripsAndOldSavesLoadAsUnseen() {
        assertTrue(decoded(CareerJson.encode(CareerSnapshot(thalassiaSeen = true))).thalassiaSeen)
        val old = CareerJson.encode(CareerSnapshot())
        assertFalse(org.json.JSONObject(old).has("thalassiaSeen"))
        assertFalse(decoded(old).thalassiaSeen)
    }

    @Test fun bothChoicesNameMarekBeforeValeryAnswersAndTheSceneEndsOnMarekAndTheSurge() {
        val choice = script.filterIsInstance<WhyChoice>().first()
        assertEquals(2, choice.options.size)
        for (o in choice.options) {
            assertFalse(o.ends)
            assertTrue(o.reply.last().text == R.string.thal_q_marek || o.reply.last().text == R.string.thal_y_name)
        }
        val after = script[script.indexOf(choice) + 1] as WhyTurns
        assertEquals(R.string.thal_v_marek, after.turns.first().text)
        val second = script.filterIsInstance<WhyChoice>().last()
        assertEquals(2, script.filterIsInstance<WhyChoice>().size)
        assertEquals(listOf(R.string.thal_q_ring, R.string.thal_q_do), second.options.map { it.label })
        assertTrue(second.options.none { it.ends })
        val i = script.indexOf(second)
        assertEquals(R.string.thal_v_ring, (script[i - 1] as WhyTurns).turns.last().text)
        assertEquals(R.string.thal_v_listen, (script[i + 1] as WhyTurns).turns.single().text)
        val marek = script.last() as WhyTurns
        assertEquals("thal_8_marek", marek.art)
        assertEquals(R.string.thal_n_surge, marek.turns.last().text)
        val thanks = marek.turns.single { it.text == R.string.thal_m_thanks }
        assertEquals(PrologueSpeaker.MAN_FRIEND, thanks.speaker)
        assertEquals(listOf("thal_1_window", "thal_2_arrivals", "thal_3_lens", "thal_4_square", "thal_6_valery", "thal_7_raid", "thal_8_marek"),
            script.map { it.art }.distinct())
    }

    private fun valeryTexts(heavy: Boolean) = thalassiaScript(heavy).filterIsInstance<WhyTurns>().flatMap { t -> t.turns.map { it.text } }

    @Test fun theInboxRevealFollowsTheWaitAsPlainTurns() {
        val t = valeryTexts(false)
        val i = t.indexOf(R.string.thal_v_waited)
        assertEquals(listOf(R.string.thal_y_look, R.string.thal_v_whose), t.subList(i + 1, i + 3))
    }

    @Test fun onlyHeavyPoisonersHearTheLaughingQueuesRightAfterOutside() {
        assertFalse(R.string.thal_v_laughing in valeryTexts(false))
        val t = valeryTexts(true)
        assertEquals(R.string.thal_v_laughing, t[t.indexOf(R.string.thal_v_outside) + 1])
        assertEquals(10, HEAVY_POISONER_DOSES)
    }

    @Test fun marekAlwaysSaysTheTrazadorLineAfterThanks() {
        val c = (thalassiaScript().last() as WhyTurns).turns.map { it.text }
        assertEquals(R.string.thal_m_trazador, c[c.indexOf(R.string.thal_m_thanks) + 1])
        assertTrue(cleanCareer(null) && cleanCareer(CareerSnapshot()) && !cleanCareer(CareerSnapshot(doses = 1)))
    }

    @Test fun marekMessagesFollowDoses() {
        fun text(doses: Int, n: Int) = deliveredMessages(CareerSnapshot(act = Act.FREE, upgradesGranted = 3, upgradeDrops = mapOf(1 to "usa", 2 to "usa", 3 to "usa"), doses = doses), Act1Stage.DONE)
            .single { it.id == "marek_$n" }.text
        assertEquals(R.string.msg_marek_1_clean, text(0, 1)); assertEquals(R.string.msg_marek_1, text(1, 1))
        assertEquals(R.string.msg_marek_2_clean, text(0, 2)); assertEquals(R.string.msg_marek_2, text(5, 2))
        assertEquals(R.string.msg_marek_3_clean, text(0, 3)); assertEquals(R.string.msg_marek_3, text(1, 3))
    }

    @Test fun noMissionIsBookedAfterTheTrueEnding() {
        assertFalse(ticketDeclined(null))
        assertFalse(ticketDeclined(CareerSnapshot()))
        assertTrue(ticketDeclined(CareerSnapshot(thalassiaSeen = true)))
    }

    @Test fun oneNewsCaptionPerStageOnceInPlainFreeTrips() {
        val s = CareerSnapshot(act = Act.FREE, upgradesGranted = 2, cutscenesSeen = setOf(1, 2))
        assertEquals(NEWS_THALASSIA, dueNews(s, Act.FREE, false)?.id)
        assertNull(dueNews(s.copy(newsSeen = setOf(NEWS_THALASSIA)), Act.FREE, false))
        assertNull(dueNews(s, Act.STORY, false))
        assertNull(dueNews(s, Act.FREE, true))
        assertNull(dueNews(null, Act.FREE, false))
        assertEquals("B12", dueNews(CareerSnapshot(act = Act.FREE), Act.FREE, false)?.id)
        assertEquals("C14", dueNews(s.copy(upgradesGranted = 1), Act.FREE, false)?.id)
        assertEquals("D14", dueNews(s.copy(upgradesGranted = 3), Act.FREE, false)?.id)
        assertEquals("F09", dueNews(s.copy(upgradesGranted = 3, usaUnlocked = true), Act.FREE, false)?.id)
        // After the USA (the Thalassia caption says the borders are still closed) and after the true ending: none.
        assertNull(dueNews(s.copy(usaUnlocked = true, usaCompleted = true), Act.FREE, false))
        assertNull(dueNews(s.copy(usaUnlocked = true, usaCompleted = true, thalassiaSeen = true), Act.FREE, false))
        assertEquals(NEWS_CAPTIONS.size, NEWS_CAPTIONS.map { it.stage }.toSet().size)
    }

    @Test fun newsSeenRoundTripsAndOldSavesLoadAsTheThalassiaCaption() {
        assertEquals(setOf("B12", "C14"), decoded(CareerJson.encode(CareerSnapshot(newsSeen = setOf("B12", "C14")))).newsSeen)
        val old = CareerJson.encode(CareerSnapshot())
        assertFalse(org.json.JSONObject(old).has("newsSeen"))
        assertTrue(decoded(old).newsSeen.isEmpty())
        val legacy = org.json.JSONObject(old).put("newsSeen", true).toString()
        assertEquals(setOf(NEWS_THALASSIA), decoded(legacy).newsSeen)
    }

    @Test fun theSecretEndingWaitsForTheEarnedTrip() {
        val catalog = listOf(Country.of("de"))
        val stuck = CareerSnapshot(act = Act.FREE, flagged = mapOf("de" to 3))
        assertTrue(badTester(stuck, catalog))
        assertFalse(badTester(stuck.copy(usaCompleted = true), catalog))
        assertFalse(badTester(stuck.copy(usaCompleted = true, thalassiaSeen = true), catalog)) // after the true ending the menu is never replaced by a wipe
    }

    @Test fun aLegacySaveWithoutTheKeyStillGetsTheMessage() {
        val usa = AwardMeta(1, "usa", "usa_x1", "assessment", 2)
        val raw = org.json.JSONObject(CareerJson.encode(ended.copy(usaCompleted = false, album = Album(infiltration = mapOf("usa" to usa)))))
        raw.remove("usaCompleted")
        val s = decoded(raw.toString())
        assertTrue(s.usaCompleted)
        assertTrue(messageDelivered(s))
    }
}

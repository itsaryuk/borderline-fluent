package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DaysAwayTest {
    private val prepared = attempt().copy(preparation = preparation()) // preparation(): 9 days away
    private fun days(a: Attempt, event: AttemptResult, base: CareerSnapshot = pending(a).copy(act = Act.FREE)) =
        reduceReplayResult(base.copy(daysAway = 10), event).snapshot.daysAway - 10

    @Test fun eachOutcomeAdvancesTheDayCounter() {
        assertEquals("passed: the stay plus the flight", 10, days(prepared, deskPass(prepared)))
        assertEquals("released", 10, days(prepared, victory(prepared)))
        assertEquals("refused", 1, days(prepared, AttemptResult(1, prepared, Decision.REFUSED)))
        assertEquals("caught", 1, days(prepared, AttemptResult(1, prepared, Decision.CAUGHT)))
        assertEquals("abandoned", 0, days(prepared, AttemptResult(1, prepared, terminalKind = TerminalKind.ABANDONED)))
        assertEquals("interrupted", 0, days(prepared, AttemptResult(1, prepared, terminalKind = TerminalKind.INTERRUPTED)))
        val story = prepared.copy(story = true)
        assertEquals("a story trip counts as well", 10, days(story, victory(story), pending(story).copy(act = Act.STORY, act1Stage = Act1Stage.DONE)))
    }

    @Test fun trainingAddsNothingExceptTheHomeArrivalDesk() {
        val t = prepared.copy(training = true, story = true, replayEligible = false)
        val first = pending(t).copy(act = Act.STORY, act1Stage = Act1Stage.TRAINING)
        assertEquals(0, days(t, deskPass(t), first))
        assertEquals(0, days(t, AttemptResult(1, t, Decision.REFUSED), first))
        val identity = Identity.roll(Random(2), Nationality.find("fr")!!, Gender.WOMAN, NameList.FEMALE)
        assertEquals(1, days(t, deskPass(t), first.copy(act1Stage = Act1Stage.HOME_TRAINING, identity = identity)))
    }

    @Test fun theCounterRoundTripsAndOldSavesStartAtZero() {
        assertEquals(42, decoded(CareerJson.encode(CareerSnapshot(daysAway = 42))).daysAway)
        val old = CareerJson.encode(CareerSnapshot())
        assertFalse(org.json.JSONObject(old).has("daysAway"))
        assertEquals(0, decoded(old).daysAway)
        assertEquals(0, decoded(CareerJson.encode(CareerSnapshot(daysAway = 5)).replace("\"daysAway\":5", "\"daysAway\":-3")).daysAway)
    }

    @Test fun theSpanIsDaysThenWeeksThenMonths() {
        assertEquals(Span(R.plurals.span_days, 1), awaySpan(1))
        assertEquals(Span(R.plurals.span_days, 13), awaySpan(13))
        assertEquals(Span(R.plurals.span_weeks, 2), awaySpan(14))
        assertEquals(Span(R.plurals.span_weeks, 3), awaySpan(20))
        assertEquals(Span(R.plurals.span_weeks, 8), awaySpan(59))
        assertEquals(Span(R.plurals.span_months, 2), awaySpan(60))
        assertEquals(Span(R.plurals.span_months, 3), awaySpan(75))
        assertEquals(Span(R.plurals.span_months, 5), awaySpan(150))
        assertEquals("a save from before the counter keeps the old five months", Span(R.plurals.span_months, 5), awaySpan(0))
    }

    private val s = CareerSnapshot(act = Act.FREE, upgradesGranted = 1, cutscenesSeen = setOf(1), countries = setOf("de"))
    private fun home(act: Act? = Act.FREE, story: Boolean = false, training: Boolean = false, homecoming: Boolean = true,
                     cleared: Boolean = true, snap: CareerSnapshot? = s) =
        homeVignetteDue(act, story, training, homecoming, cleared, snap)

    @Test fun everyClearedHomecomingGetsOneAndNoOtherTripDoes() {
        assertTrue(home())
        assertFalse("a visit", home(homecoming = false))
        assertFalse("refused or caught", home(cleared = false))
        assertTrue("before any upgrade too", home(snap = CareerSnapshot(act = Act.FREE, countries = setOf("de"))))
    }

    @Test fun vignetteExclusions() {
        assertFalse("act 1", home(act = Act.STORY))
        assertFalse("left", home(act = Act.LEFT))
        assertFalse("story trip", home(story = true))
        assertFalse("training", home(training = true))
        assertFalse("no career", home(snap = null))
        assertFalse("after the true ending", home(snap = s.copy(thalassiaSeen = true)))
    }

    @Test fun aVignetteNeverSharesATripWithABarOrHotel() {
        assertFalse(keepsEncounter(true, "de", false))
        assertFalse("a silent-cab country's first visit is a bar or hotel", keepsEncounter(true, "russia", true))
        assertTrue("the talking first-visit taxi is on the result screen", keepsEncounter(true, "de", true))
        assertTrue("no vignette, no change", keepsEncounter(false, "de", false))
    }

    @Test fun homeArtIsTheNationalitysFlatElseTheGenericOne() {
        val have = setOf("home_usa", "home_flat")
        assertEquals("home_usa", homeArt("usa") { it in have })
        assertEquals("home_flat", homeArt("japan") { it in have })
    }

    @Test fun rotationNeverRepeatsTheLastTwo() {
        var recent = emptyList<Int>()
        val seen = mutableSetOf<Int>()
        repeat(500) {
            val pick = pickVignette(recent, Random(it))
            assertTrue("pick $pick after $recent", pick !in recent.takeLast(2))
            recent = rememberVignette(recent, pick)
            assertTrue(recent.size <= 2)
            seen += pick
        }
        assertEquals(HOME_VIGNETTES.indices.toSet(), seen)
        assertEquals(listOf(4, 1), rememberVignette(listOf(3, 4), 1))
    }
}

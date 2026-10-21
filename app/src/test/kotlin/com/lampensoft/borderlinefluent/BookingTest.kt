package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BookingTest {
    @Test fun persistedFreeBookingCannotRestoreToCurrentCountry() {
        val booking = Booking("de", "fkb", "de_x1", preparation())
        val snapshot = CareerSnapshot(act = Act.FREE, lastPassedCountry = "de", booking = booking)
        val restored = decoded(CareerJson.encode(snapshot))
        assertEquals(booking, restored.booking)
        assertNull(bookingForRestore(restored))
        assertEquals(booking, bookingForRestore(snapshot.copy(lastPassedCountry = "fr")))
        assertEquals(booking, bookingForRestore(snapshot.copy(lastPassedCountry = null)))
        // Arrival puts story careers at home too; only unfinished onboarding can reuse that desk.
        assertNull(bookingForRestore(snapshot.copy(act = Act.STORY)))
        assertNull(bookingForRestore(snapshot.copy(act = Act.STORY, act1Stage = Act1Stage.DONE)))
        assertEquals(booking, bookingForRestore(snapshot.copy(act = Act.STORY, act1Stage = Act1Stage.TRAINING)))
        assertNull(bookingForRestore(snapshot.copy(booking = null)))
    }

    @Test fun ownCountryFirstThenSorted() {
        val all = Country.all
        val r = bookingRows(all, "japan", "en")
        assertEquals("japan", r.home?.id)
        assertEquals(all.size - 1, r.others.size)
        assertEquals(r.others.map { it.uiName("en") }.sorted(), r.others.map { it.uiName("en") })
    }

    @Test fun noHomeRowWhenNotPlayableOrUnknown() {
        val noJapan = Country.all.filter { it.id != "japan" }
        assertNull(bookingRows(noJapan, "japan", "en").home)
        val locked = countryPickerRows(Country.all, "usa", "en", CareerSnapshot(act = Act.FREE), story = false)
        assertNull(locked.home)
        assertEquals(1, locked.others.count { it.id == "usa" })
        assertEquals(noJapan.size, bookingRows(noJapan, null, "en").others.size)
    }

    @Test fun usaIsVisibleButNeverBookableUntilFreeActUnlock() {
        for (act in Act.entries) for (unlocked in listOf(false, true)) {
            val snapshot = CareerSnapshot(act = act, usaUnlocked = unlocked)
            val story = act == Act.STORY
            val rows = countryPickerRows(Country.all, "usa", "en", snapshot, story)
            assertEquals(1, (listOfNotNull(rows.home) + rows.others).count { it.id == "usa" })
            val canBook = act == Act.FREE && unlocked
            assertEquals(!story && !canBook, countryPickerUsaLocked(snapshot, story))
            assertEquals(canBook, availableFlights(snapshot, Country.all).any { it.id == "usa" })
            assertEquals(if (canBook || story) "usa" else null, rows.home?.id)
        }
        assertTrue(countryPickerUsaLocked(null, story = false))
        assertFalse(countryPickerUsaLocked(CareerSnapshot(act = Act.FREE, usaUnlocked = true), story = true))
    }

    @Test fun americansSeeHomeEvenWithoutItsPackAndCannotBookIt() {
        val home = CareerSnapshot(act = Act.STORY, act1Stage = Act1Stage.DONE,
            lastPassedCountry = "usa", storyLastCountry = "usa")
        val rows = countryPickerRows(listOf(Country.of("de")), "usa", "en", home, true)
        assertEquals("usa", rows.home?.id)
        val state = countryPickerRowState("usa", home.lastPassedCountry, 1, bookable = false)
        assertTrue(state.here)
        assertFalse(state.enabled)
        assertEquals(1, state.clears)
        assertFalse(countryPickerUsaLocked(home, true, "usa"))
        assertTrue(countryPickerUsaLocked(CareerSnapshot(act = Act.FREE), false, "usa"))
    }

    @Test fun usaTeaserDoesNotRequireInstalledAssetsOrMakeUnavailableFlightsBookable() {
        val snapshot = CareerSnapshot(act = Act.FREE, usaUnlocked = true)
        val playable = Country.all.filter { it.id != "usa" }
        val rows = countryPickerRows(playable, "de", "tr", snapshot, story = false)
        assertEquals("de", rows.home?.id)
        assertEquals(1, rows.others.count { it.id == "usa" })
        assertEquals(rows.others.map { it.uiName("tr") }.sorted(), rows.others.map { it.uiName("tr") })
        assertFalse(availableFlights(snapshot, playable).any { it.id == "usa" })
        val empty = countryPickerRows(emptyList(), null, "en", null, story = true)
        assertEquals(listOf("usa"), empty.others.map { it.id })
        val flagged = snapshot.copy(flagged = mapOf("usa" to 2))
        assertFalse(availableFlights(flagged, Country.all).any { it.id == "usa" })
        assertFalse(countryPickerUsaLocked(flagged, story = false))
    }

    @Test fun currentCountryKeepsPassedBadgeAndIsDisabledEvenIfOtherwiseBookable() {
        for (clears in listOf(0, 1, 3)) {
            val here = countryPickerRowState("de", "de", clears, bookable = true)
            assertTrue(here.here)
            assertEquals(clears, here.clears)
            assertFalse(here.enabled)
        }
        val elsewhere = countryPickerRowState("fr", "de", 2, bookable = true)
        assertFalse(elsewhere.here)
        assertEquals(2, elsewhere.clears)
        assertTrue(elsewhere.enabled)
        assertFalse(countryPickerRowState("fr", "de", 2, bookable = false).enabled)
    }

    @Test fun bonusUsesMachineLanguagesAcrossCountriesNotDeskClearsOrNationality() {
        val germany = Country.of("de")
        val austria = Country.of("austria")
        val a = attempt().copy(progressEligible = true, nationalityLanguages = setOf("de"))
        val start = pending(a).copy(act = Act.FREE)
        val desk = reduceReplayResult(start, deskPass(a)).snapshot
        assertEquals(listOf("de"), newLanguageBonuses(desk, germany.airports))
        assertEquals(listOf("de"), newLanguageBonuses(desk, austria.airports))
        val machine = reduceReplayResult(start, victory(a)).snapshot
        assertTrue(newLanguageBonuses(machine, germany.airports).isEmpty())
        assertTrue(newLanguageBonuses(machine, austria.airports).isEmpty())
        assertEquals(listOf("fr"), newLanguageBonuses(machine, Country.of("fr").airports))
        for (act in listOf(Act.STORY, Act.LEFT))
            assertTrue(newLanguageBonuses(start.copy(act = act), germany.airports).isEmpty())
        assertTrue(newLanguageBonuses(null, germany.airports).isEmpty())
    }

    @Test fun multilingualBonusNamesOnlyUntrainedEligibleLanguages() {
        val canada = Country.of("canada")
        val s = CareerSnapshot(act = Act.FREE, trainedLanguages = setOf("fr"))
        assertEquals(listOf("en"), newLanguageBonuses(s, canada.airports))
        assertTrue(newLanguageBonuses(s, canada.airports.filter { it.lang == "fr" }).isEmpty())
        assertTrue(newLanguageBonuses(s, emptyList()).isEmpty())
        assertTrue(newLanguageBonuses(s.copy(trainedLanguages = setOf("en", "fr")), canada.airports).isEmpty())
    }

    @Test fun warningPredictsACatchAtTheLastCatalogDestination() {
        val catalog = listOf(Country.of("de"), Country.of("fr"), Country.of("usa"))
        val s = CareerSnapshot(act = Act.FREE, lastPassedCountry = "de")
        assertTrue(lastFlightWarning(s, catalog)) // USA locked, Germany is the current country
        assertFalse(lastFlightWarning(s.copy(usaUnlocked = true), catalog))
        assertFalse(lastFlightWarning(s.copy(lastPassedCountry = null), catalog))
        assertFalse(lastFlightWarning(s.copy(flagged = mapOf("fr" to 1)), catalog)) // already no flights
        for (act in listOf(Act.STORY, Act.LEFT)) assertFalse(lastFlightWarning(s.copy(act = act), catalog))
        assertFalse(lastFlightWarning(null, catalog))
        assertFalse(lastFlightWarning(s, emptyList()))
        assertFalse(lastFlightWarning(s)) // missing packs never remove the rest of the catalog
        val caught = s.copy(flagged = mapOf("fr" to 3))
        assertTrue(badTester(caught, catalog))
    }

    @Test fun warningUsesRemainingFlagCountsAndTheFullUnlockedCatalog() {
        val open = "japan"
        val flags = Country.all.filter { it.id != open && it.id != "de" }.associate { it.id to 1 }
        val s = CareerSnapshot(act = Act.FREE, usaUnlocked = true, lastPassedCountry = "de", flagged = flags)
        assertTrue(lastFlightWarning(s))
        assertFalse(lastFlightWarning(s.copy(flagged = flags - "fr")))
        assertEquals(listOf(Country.of(open)), availableFlights(s, Country.all))
        for (left in 1..3) {
            val flagged = s.copy(flagged = mapOf("fr" to left))
            assertEquals(left, flaggedClears(flagged, "fr"))
            assertEquals(0, flaggedClears(flagged, "de"))
            assertFalse(availableFlights(flagged, Country.all).any { it.id == "fr" })
            for (act in listOf(Act.STORY, Act.LEFT))
                assertEquals(0, flaggedClears(flagged.copy(act = act), "fr"))
        }
    }

    @Test fun everyAirportHasThreeLetterIata() {
        for (a in Country.all.flatMap { it.airports }) assertEquals(a.id, 3, a.iata.length)
    }

    @Test fun lastPassedAirportRoundTripsAndOldSavesLoadNull() {
        val snap = CareerSnapshot(act = Act.FREE, lastPassedCountry = "australia", lastPassedAirport = "asp")
        assertEquals("asp", decoded(CareerJson.encode(snap)).lastPassedAirport)
        assertNull(decoded(CareerJson.encode(snap.copy(lastPassedAirport = null))).lastPassedAirport)
        val old = org.json.JSONObject(CareerJson.encode(snap)).apply { remove("lastPassedAirport") }.toString()
        assertNull(decoded(old).lastPassedAirport)
    }

    @Test fun departureCityFollowsPassedSecondaryAirport() {
        val s = CareerSnapshot(act = Act.FREE, lastPassedCountry = "australia", lastPassedAirport = "asp")
        assertEquals("Alice Springs", departureCity("Australia", s).replace('_', ' '))
        assertEquals("Sydney", departureCity("Australia", s.copy(lastPassedAirport = null)))
        assertEquals("Sydney", departureCity("Australia", s.copy(lastPassedCountry = "ireland")))
        assertEquals("Sydney", departureCity("Australia"))
    }

    @Test fun waitingCourierCountryIsThePendingDrop() {
        val s = CareerSnapshot(act = Act.FREE, upgradesGranted = 2, cutscenesSeen = setOf(1), upgradeDrops = mapOf(1 to "de", 2 to "fr"))
        assertEquals("fr", waitingCourierCountry(s))
        assertNull(waitingCourierCountry(s.copy(cutscenesSeen = setOf(1, 2))))
        assertNull(waitingCourierCountry(s.copy(upgradeDrops = emptyMap())))
        assertNull(waitingCourierCountry(null))
    }
}

class RefusalClosureTest {
    private val s = CareerSnapshot(act = Act.FREE, lastPassedCountry = "de")
    private fun free(vararg c: Pair<String, Int>) = s.copy(closed = mapOf(*c))
    private fun ids(x: CareerSnapshot) = availableFlights(x, Country.all).map { it.id }

    @Test fun refusedCountryIsClosedButOthersStayOpen() {
        val x = free("fr" to 2)
        assertFalse("fr" in ids(x)); assertTrue(ids(x).isNotEmpty())
        assertEquals(setOf("fr"), closedFlights(x)); assertEquals(2, closedTripsLeft(x, "fr")); assertEquals(0, closedTripsLeft(x, "it"))
    }
    @Test fun neverClosesEveryFlight() {
        val all = Country.all.associate { it.id to 2 }
        val x = s.copy(closed = all)
        assertEquals(emptySet<String>(), closedFlights(x)); assertTrue(ids(x).isNotEmpty())
        assertEquals(0, closedTripsLeft(x, "fr"))
    }
    @Test fun storyActRouteIsNotBlocked() {
        val x = s.copy(act = Act.STORY, closed = mapOf("fr" to 2))
        assertEquals(emptySet<String>(), closedFlights(x))
        assertTrue("fr" in availableFlights(x, Country.all).map { it.id })
    }
    @Test fun jsonRoundTripKeepsClosures() {
        val x = free("fr" to 1)
        assertEquals(x.closed, decoded(CareerJson.encode(x)).closed)
    }
}

class RefusalClosureReducerTest {
    private fun run(s: CareerSnapshot, country: String, refuse: Boolean, flags: Int = REFUSAL_FLAGS): CareerSnapshot {
        val a = attempt(id = s.nextAttemptId, country = country)
        val started = s.copy(pendingAttempt = a.copy(status = AttemptStatus.STARTED), nextAttemptId = a.id + 1)
        return reduceReplayResult(started, if (refuse) deskPass(a).copy(deskDecision = Decision.REFUSED, deskFlags = flags) else deskPass(a)).snapshot
    }
    @Test fun fewerThanThreeFlagsDoNotCloseButThreeDo() {
        for (flags in 0..2) assertTrue(run(CareerSnapshot(act = Act.FREE), "fr", true, flags).closed.isEmpty())
        assertEquals(mapOf("fr" to 2), run(CareerSnapshot(act = Act.FREE), "fr", true, 3).closed)
    }
    @Test fun captureCopiesTheDeskFlagsOnce() {
        val live = LiveAttempt(1, attempt())
        val at = captureDecision(live, 1, Decision.REFUSED, false, false, flags = 2)
        assertEquals(2, at.deskFlags)
        assertEquals(2, captureDecision(at, 1, Decision.REFUSED, false, false, flags = 0).deskFlags)
    }
    @Test fun refusalClosesForTwoTripsElsewhereThenReopens() {
        var s = run(CareerSnapshot(act = Act.FREE), "fr", refuse = true)
        assertEquals(mapOf("fr" to 2), s.closed)
        s = run(s, "de", false); assertEquals(mapOf("fr" to 1), s.closed)
        s = run(s, "it", false); assertTrue(s.closed.isEmpty())
    }
    @Test fun refusalAgainResetsAndStoryActDoesNotClose() {
        val s = run(run(CareerSnapshot(act = Act.FREE), "fr", true), "de", false)
        assertEquals(mapOf("fr" to 2), run(s, "fr", true).closed)
        assertTrue(run(CareerSnapshot(act = Act.STORY), "fr", true).closed.isEmpty())
    }
}

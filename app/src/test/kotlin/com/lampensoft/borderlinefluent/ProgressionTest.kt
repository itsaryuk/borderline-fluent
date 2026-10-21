package com.lampensoft.borderlinefluent

import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProgressionTest {
    private fun settle(s: CareerSnapshot, country: String = "de", language: String = "de",
        decision: Decision = Decision.PASS, native: Set<String> = setOf("en"),
        training: Boolean = false, story: Boolean = false, debug: Boolean = false, doses: Int = 1): CareerSnapshot {
        val a = attempt(s.nextAttemptId, country).copy(language = language, nationalityLanguages = native,
            training = training, story = story, replayEligible = !training && !story,
            progressEligible = s.act == Act.FREE && !training && !story)
        val pending = s.copy(pendingAttempt = a, nextAttemptId = a.id + 1)
        val e = AttemptResult(a.id, a, deskDecision = if (decision == Decision.RELEASED) Decision.REFERRAL else decision, debugForced = debug, doses = doses,
            assessmentDecision = if (decision == Decision.RELEASED || story && !training && decision == Decision.PASS) Decision.RELEASED else null)
        val result = reduceReplayResult(pending, e)
        assertFalse(result.rejected)
        assertEquals(result.snapshot, reduceReplayResult(result.snapshot, e).snapshot)
        return result.snapshot
    }

    @Test fun releasedSessionsAwardTwoForNewLanguageThenOneAndThresholdsAreIdempotent() {
        var s = CareerSnapshot(act = Act.FREE)
        for ((i, language) in listOf("de", "de", "fr", "fr", "de", "fr").withIndex()) {
            s = settle(s, language = language, decision = Decision.RELEASED)
            val points = listOf(2, 3, 5, 6, 7, 8)[i]
            assertEquals(i + 1, s.machineSessions)
            assertEquals(points, s.progressPoints)
            assertEquals(if (points < 5) 1 else if (points < 8) 2 else 3, s.upgradesGranted)
            assertFalse(s.usaUnlocked) // opens when upgrade 3 is delivered
        }
        assertEquals(setOf("de", "fr"), s.trainedLanguages)
        assertEquals(listOf(0, 0, 1, 1, 1, 2, 2, 2, 3, 3), (0..9).map(::upgradesFor))
        assertEquals(1, pendingUpgrade(s))
        assertEquals(2, pendingUpgrade(s.copy(cutscenesSeen = setOf(1))))
        assertNull(pendingUpgrade(s.copy(cutscenesSeen = setOf(1, 2, 3))))
    }

    @Test fun pointsIgnoreDosesAndOnlyAReleasedSessionBanksThem() {
        val fresh = CareerSnapshot(act = Act.FREE)
        // Owner 2026-10-15: a released session earns 1, plus 1 for a new language; doses change nothing.
        for (doses in listOf(0, 1, 2, 5)) {
            assertEquals(2, settle(fresh, decision = Decision.RELEASED, doses = doses).progressPoints)
            assertEquals(1, settle(fresh.copy(trainedLanguages = setOf("de")), decision = Decision.RELEASED, doses = doses).progressPoints)
        }
        // An honest release still counts as a machine session and trains the language.
        val honest = settle(fresh, decision = Decision.RELEASED, doses = 0)
        assertEquals(1, honest.machineSessions)
        assertEquals(setOf("de"), honest.trainedLanguages)
        assertEquals(0, honest.doses)
        // Doses bank only from a released session, and add up.
        val one = settle(fresh, decision = Decision.RELEASED, doses = 2)
        assertEquals(2, one.doses)
        assertEquals(5, settle(one, decision = Decision.RELEASED, doses = 3).doses)
        for (lost in listOf(Decision.REFUSED, Decision.CAUGHT, Decision.PASS)) assertEquals(0, settle(fresh, decision = lost, doses = 4).doses)
        assertEquals(0, settle(fresh, training = true, decision = Decision.RELEASED, doses = 4).doses)
        assertEquals(0, settle(fresh, debug = true, decision = Decision.RELEASED, doses = 4).doses)
        assertEquals(0, settle(fresh.copy(act = Act.LEFT), decision = Decision.RELEASED, doses = 4).doses)
    }

    @Test fun roomDosesTravelFromCaptureToTheTerminalEvent() {
        val live = LiveAttempt(1, attempt())
        val desk = captureDecision(live, 1, Decision.REFERRAL, inRoom = false, roomReady = true, doses = 9)
        assertEquals(0, desk.doses) // only the room's decision carries doses
        val room = captureDecision(desk, 1, Decision.RELEASED, inRoom = true, roomReady = true, doses = 3, payloadNamed = true)
        assertEquals(3, finishAttempt(room, 1, TerminalKind.DECIDED).terminal?.doses)
        assertEquals(true, finishAttempt(room, 1, TerminalKind.DECIDED).terminal?.payloadNamed)
        assertEquals(false, finishAttempt(captureDecision(desk, 1, Decision.RELEASED, inRoom = true, roomReady = true, doses = 3), 1,
            TerminalKind.DECIDED).terminal?.payloadNamed)
    }

    @Test fun harvestTierAndPercentFollowTheCareer() {
        assertEquals(0.0, harvestPercent(0), 1e-9)
        assertEquals("1.2", String.format(java.util.Locale.ROOT, "%.1f", harvestPercent(3)))
        assertEquals(100.0, harvestPercent(250), 1e-9)
        assertEquals(listOf(0, 1, 2, 3), listOf(0, 2, 5, 8).map { harvestTier(CareerSnapshot(progressPoints = it), Act.FREE) })
        assertEquals(2, harvestTier(CareerSnapshot(progressPoints = 0, upgradesGranted = 2), Act.FREE))
        assertEquals(0, harvestTier(CareerSnapshot(progressPoints = 9), Act.LEFT))
        assertEquals(0, harvestTier(null, Act.FREE))
    }

    @Test fun threeHonestSessionsInThreeNewLanguagesReachTheSecondUpgrade() {
        var s = CareerSnapshot(act = Act.FREE)
        for (language in listOf("de", "fr", "es")) s = settle(s, language = language, decision = Decision.RELEASED, doses = 0)
        assertEquals(6, s.progressPoints)
        assertEquals(2, s.upgradesGranted)
        assertEquals(0, s.doses)
    }

    @Test fun walkedAwayCareerEarnsNoLevelsAndNoMarekMessages() {
        var s = CareerSnapshot(act = Act.FREE, walkedAway = true)
        for (i in 1..6) s = settle(s, language = "de", decision = Decision.RELEASED)
        assertEquals(6, s.machineSessions)
        assertEquals(0, s.upgradesGranted)
        assertTrue(chooseUpgradeDrops(s, Country.all).isEmpty())
        assertTrue(deliveredMessages(s, Act1Stage.DONE).none { it.id.startsWith("marek") })
        val granted = s.copy(upgradesGranted = 1, upgradeDrops = mapOf(1 to "de"))
        assertTrue(deliveredMessages(granted, Act1Stage.DONE).none { it.id.startsWith("marek") })
        assertTrue(deliveredMessages(granted.copy(walkedAway = false), Act1Stage.DONE).any { it.id == "marek_1" })
    }

    @Test fun marekBackMessageNeedsMarekReturned() {
        val s = CareerSnapshot(act = Act.FREE)
        assertTrue(deliveredMessages(s, Act1Stage.DONE).none { it.id == "marek_back" })
        assertTrue(deliveredMessages(s.copy(marekReturned = true), Act1Stage.DONE).any { it.id == "marek_back" })
    }

    @Test fun deskPassOnlyUpdatesContinuityStatsAndPassedBadge() {
        val s = settle(CareerSnapshot(act = Act.FREE))
        assertTrue(s.trainedLanguages.isEmpty())
        assertEquals(0, s.progressPoints)
        assertEquals(0, s.machineSessions)
        assertEquals(0, s.upgradesGranted)
        assertEquals(1, s.counters.cleared)
        assertEquals("de", s.lastPassedCountry)
        assertTrue("de" in passedCountries(s))
    }

    @Test fun nativeLanguageMachineSessionAlsoCountsDouble() {
        val s = settle(CareerSnapshot(act = Act.FREE), native = setOf("de"), decision = Decision.RELEASED)
        assertEquals(2, s.progressPoints)
        assertEquals(setOf("de"), s.trainedLanguages)
    }

    @Test fun frozenEligibilitySurvivesActAndPassportChangesAndRetry() {
        val identity = Identity.roll(Random(1), Nationality.find("de")!!, Gender.WOMAN, NameList.FEMALE)
        val a = attempt().copy(nationalityLanguages = setOf("de"), progressEligible = true)
        val s = pending(a).copy(act = Act.LEFT, identity = identity)
        assertEquals(0, reduceReplayResult(s, deskPass(a)).snapshot.progressPoints)
        assertEquals(2, reduceReplayResult(s, victory(a).copy(doses = 1)).snapshot.progressPoints)
        val resumed = a.copy(id = 2)
        val retry = s.copy(nextAttemptId = 3, pendingAttempt = resumed)
        assertEquals(setOf("de"), reduceReplayResult(retry, victory(resumed).copy(doses = 1)).snapshot.trainedLanguages)
        val ineligible = a.copy(progressEligible = false)
        assertEquals(0, reduceReplayResult(pending(ineligible).copy(act = Act.FREE), victory(ineligible).copy(doses = 2)).snapshot.progressPoints)
    }

    @Test fun storyTrainingDebugFailuresAndAbandonmentDoNotGrantProgress() {
        val base = CareerSnapshot(act = Act.FREE)
        val results = listOf(settle(base, training = true, decision = Decision.RELEASED),
            settle(base, story = true, decision = Decision.RELEASED), settle(base, debug = true, decision = Decision.RELEASED),
            settle(base, decision = Decision.REFUSED), settle(base, decision = Decision.CAUGHT),
            settle(base.copy(act = Act.STORY), decision = Decision.RELEASED),
            settle(base.copy(act = Act.LEFT), decision = Decision.RELEASED))
        results.forEach {
            assertTrue(it.trainedLanguages.isEmpty())
            assertEquals(0, it.progressPoints)
            assertEquals(0, it.machineSessions)
            assertEquals(0, it.upgradesGranted)
        }
        val a = attempt().copy(progressEligible = true)
        for (terminal in listOf(TerminalKind.ABANDONED, TerminalKind.INTERRUPTED)) {
            val result = reduceReplayResult(pending(a).copy(act = Act.FREE), victory(a).copy(terminalKind = terminal))
            assertEquals(0, result.snapshot.progressPoints)
        }
        val debug = a.copy(debugForced = true)
        assertEquals(0, reduceReplayResult(pending(debug), victory(debug).copy(debugForced = false)).snapshot.progressPoints)
        // RELEASED must come from the associate, never the desk.
        assertEquals(0, reduceReplayResult(pending(a), AttemptResult(a.id, a, Decision.RELEASED)).snapshot.progressPoints)
    }

    @Test fun everyActAndTrainingClearUpdatesLocationButFailuresAndDebugDoNot() {
        for (act in Act.entries) for (training in listOf(false, true)) for (story in listOf(false, true)) {
            val base = CareerSnapshot(act = act, lastPassedCountry = "fr")
            val cleared = settle(base, training = training, story = story)
            assertEquals("de", cleared.lastPassedCountry)
            assertEquals("de", decoded(CareerJson.encode(cleared)).lastPassedCountry)
            assertEquals("fr", settle(base, training = training, story = story, decision = Decision.REFUSED).lastPassedCountry)
            assertEquals("fr", settle(base, training = training, story = story, debug = true).lastPassedCountry)
        }
    }

    @Test fun caughtFlagsAndEveryElsewhereClearCountsAllFlagsDownOnce() {
        var s = settle(CareerSnapshot(act = Act.FREE), decision = Decision.CAUGHT)
        assertEquals(mapOf("de" to 3), s.flagged)
        s = settle(s, country = "fr", decision = Decision.CAUGHT)
        assertEquals(mapOf("de" to 3, "fr" to 3), s.flagged)
        val refused = settle(s, country = "es", decision = Decision.REFUSED)
        assertEquals(s.flagged, refused.flagged)
        s = settle(refused, country = "es")
        assertEquals(mapOf("de" to 2, "fr" to 2), s.flagged)
        s = settle(s, country = "es")
        assertEquals(mapOf("de" to 1, "fr" to 1), s.flagged)
        s = settle(s, country = "es")
        assertTrue(s.flagged.isEmpty())
    }

    @Test fun ownCountryClearDoesNotRemoveItsFlag() {
        val s = settle(CareerSnapshot(act = Act.FREE, flagged = mapOf("de" to 3, "fr" to 2)))
        assertEquals(mapOf("de" to 3, "fr" to 1), s.flagged)
    }

    @Test fun anywhereSkipsFlagsAndSecretEndingRequiresWholeCatalog() {
        val catalog = listOf(Country.of("de"), Country.of("fr"))
        val s = CareerSnapshot(act = Act.FREE, flagged = mapOf("de" to 3))
        assertEquals(listOf(Country.of("fr")), availableFlights(s, catalog))
        assertFalse(badTester(s, catalog))
        val all = s.copy(flagged = mapOf("de" to 3, "fr" to 1))
        assertTrue(badTester(all, catalog))
        assertTrue(availableFlights(all, catalog).isEmpty())
        assertFalse(badTester(all.copy(act = Act.STORY), catalog))
        assertFalse(badTester(all, emptyList()))
        assertFalse(badTester(s)) // a single installed/flagged country isn't the whole catalog
    }

    @Test fun clearingGermanyThenCaughtEverywhereElseTriggersEndingWithoutFlaggingGermany() {
        val identity = Identity.roll(Random(1), Nationality.USA, Gender.MAN, NameList.MALE)
        var s = settle(CareerSnapshot(act = Act.FREE, identity = identity), country = "de")
        val destinations = unlockedCountries(s).filter { it.id != "de" }
        assertTrue(destinations.isNotEmpty())
        for (country in destinations) {
            assertFalse(badTester(s))
            assertTrue(country in missionCountries(s, Country.shipped, identity, story = false))
            s = settle(s, country = country.id, decision = Decision.CAUGHT)
        }
        assertEquals("de", s.lastPassedCountry)
        assertEquals(destinations.associate { it.id to 3 }, s.flagged)
        assertTrue(missionCountries(s, Country.shipped, identity, story = false).isEmpty())
        assertTrue(availableFlights(s, Country.shipped).isEmpty())
        assertTrue(badTester(s))
    }

    @Test fun lastCatchTriggersEndingAndFreshFreeRestartHasNoHistory() {
        val initial = CareerSnapshot(act = Act.FREE, flagged = Country.all.filter { it.id != "de" }.associate { it.id to 3 })
        assertFalse(badTester(initial))
        val caught = settle(initial, decision = Decision.CAUGHT)
        assertTrue(badTester(caught))
        val fresh = freshFreeCareer()
        assertEquals(Act.FREE, fresh.act)
        assertNull(fresh.identity)
        assertEquals(CareerSnapshot(), fresh.copy(act = null))
        assertFalse(badTester(fresh))
    }

    @Test fun continuityUsesLastClearNotLastDestinationAndArrivalCityStaysAtDestination() {
        val id = Identity.roll(Random(2), Nationality.USA, Gender.MAN, NameList.MALE)
        var s = CareerSnapshot(act = Act.FREE, identity = id)
        assertEquals("United States", freeDeparture(s, id))
        s = settle(s, country = "de")
        assertEquals(Country.of("de").nation, freeDeparture(s, id))
        s = settle(s, country = "fr", decision = Decision.CAUGHT)
        assertEquals(Country.of("de").nation, freeDeparture(s, id))
        val d = Country.of("fr").let { Destination(it, it.airports.first()) }
        val itinerary = Itinerary.roll(Random(3), d, id, freeDeparture(s, id))
        assertEquals(TripKind.ARRIVAL, itinerary.kind)
        assertEquals(Country.of("de").nation, itinerary.from)
        assertEquals(AIRPORT_CITY[d.airport.id], itinerary.city)
        assertEquals(AIRPORT_CITY[Country.of("de").airports.first().id], departureCity(itinerary.from!!))
    }

    @Test fun perksAndBuildsFollowUpgrades() {
        assertEquals(ImplantPerks(), ImplantPerks.fromUpgrades(0))
        assertEquals(ImplantPerks(recall = true), ImplantPerks.fromUpgrades(1))
        assertEquals(ImplantPerks(recall = true, accent = true), ImplantPerks.fromUpgrades(2))
        val top = ImplantPerks.fromUpgrades(3)
        assertTrue(top.recall && top.accent && top.antiJam)
        assertTrue(top.jamRate < ImplantPerks().jamRate)
        assertEquals(listOf("0.9", "0.9.1", "0.9.2", "0.9.3"), (0..3).map(::implantBuild))
    }

    @Test fun accentAcceptsAnsweredClarityZeroButStillReasksUnanswered() {
        val c = Check(answered = true, contradiction = false, clarity = 0, inLanguage = true)
        val old = OfficerMind(Persona.TRAINING).also { it.onCheck(c) }
        val tuned = OfficerMind(Persona.TRAINING, ImplantPerks(accent = true)).also { it.onCheck(c) }
        assertEquals(1, old.unclear)
        assertEquals(0, tuned.unclear)
        tuned.onCheck(c.copy(answered = false))
        assertEquals(1, tuned.unclear)
    }

    @Test fun recallReplacesExactlyOneRowOnlyForItineraryTopics() {
        val options = listOf(Hint("one", "", ""), Hint("two", "", ""))
        val story = Backstory.BENCH
        val perks = ImplantPerks(recall = true)
        val recalled = recallHints(options, perks, "stayed", story, "en")
        assertEquals(Hint(story.fact(FactId.STAY, "en"), "", ""), recalled.first())
        assertEquals(options.drop(1), recalled.drop(1))
        for (topic in listOf(null, "born", "home", "job", "with", "met", "waiting", "stamps", "host", HOWLANG, ENCOUNTER_TOPIC))
            assertEquals(options, recallHints(options, perks, topic, story, "en"))
        assertEquals(options, recallHints(options, ImplantPerks(), "stayed", story, "en"))
    }

    @Test fun progressionAndAttemptFreezeRoundTrip() {
        val a = attempt().copy(preparation = preparation(), nationalityLanguages = setOf("de", "fr"), upgradesHeld = 2, progressEligible = true,
            mission = Mission(MissionKind.PLANT, Motif.MOON))
        val s = pending(a).copy(act = Act.FREE, trainedLanguages = setOf("es", "pt"), machineSessions = 3, progressPoints = 5, upgradesGranted = 1,
            cutscenesSeen = setOf(1), lastPassedCountry = "fr", flagged = mapOf("de" to 2))
        assertEquals(s, (CareerJson.decode(CareerJson.encode(s)) as CareerRead.Ready).snapshot)
    }

    @Test fun cutsceneAcknowledgmentAndRestartArePersistedThroughStore() = runBlocking {
        var durable = CareerSnapshot(act = Act.FREE, upgradesGranted = 2)
        var succeeds = true
        val store = CareerStore(read = { CareerInput(CareerJson.encode(durable), StoryReset.VERSION) }, write = { raw, _ ->
            if (succeeds) durable = (CareerJson.decode(raw) as CareerRead.Ready).snapshot
            succeeds
        }, io = Dispatchers.Unconfined, publication = Dispatchers.Unconfined)
        store.open()
        succeeds = false
        assertTrue(store.upgradeSeen(1) is CareerSave.WriteFailed)
        assertTrue(store.acknowledged!!.cutscenesSeen.isEmpty())
        succeeds = true
        store.retry()
        assertEquals(setOf(1), durable.cutscenesSeen)
        assertEquals(2, pendingUpgrade(durable))
        assertTrue(store.restartFree() is CareerSave.Rejected)
        val trapped = durable.copy(flagged = Country.all.associate { it.id to 3 })
        val restartStore = CareerStore(read = { CareerInput(CareerJson.encode(trapped), StoryReset.VERSION) },
            write = { raw, _ -> durable = (CareerJson.decode(raw) as CareerRead.Ready).snapshot; true },
            io = Dispatchers.Unconfined, publication = Dispatchers.Unconfined)
        restartStore.open()
        assertTrue(restartStore.restartFree() is CareerSave.Saved)
        assertEquals(freshFreeCareer(), durable)
    }

    @Test fun resetProgressStagesFreshCareer() = runBlocking {
        var durable = CareerSnapshot(); var marker = 0; var ok = true
        val used = durable.copy(flagged = Country.all.associate { it.id to 3 })
        val store = CareerStore(read = { CareerInput(CareerJson.encode(used), StoryReset.VERSION) },
            write = { raw, m -> if (ok) { durable = (CareerJson.decode(raw) as CareerRead.Ready).snapshot; marker = m }; ok },
            io = Dispatchers.Unconfined, publication = Dispatchers.Unconfined)
        store.open()
        ok = false
        val r1 = store.resetProgress(); assertTrue(r1.toString(), r1 is CareerSave.WriteFailed)
        assertTrue(store.resetProgress() is CareerSave.WriteFailed) // staged write blocks, like the other ops
        ok = true
        assertTrue(store.retry() is CareerSave.Saved)
        assertEquals(CareerSnapshot(), durable)
        assertEquals(StoryReset.VERSION, marker)
    }
}

package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The two acts (PRD §3a): routing, the save, the story's fixed cover and pre-booked trips, no "Free AI" in act 1. */
class ActTest {
    @Test fun passportIsDeferredAndResumesEvenAfterIdentityWasSaved() {
        fun restart(s: CareerSnapshot) = decoded(CareerJson.encode(s))
        val fresh = CareerSnapshot(act = Act.STORY)
        assertNull(fresh.identity)
        assertEquals(Screen.INTRO, onboardingScreen(act1StageOf(fresh)))
        val departure = restart(advanceAct1(fresh, Act1Stage.DEPARTURE))
        assertNull(departure.identity)
        assertEquals(Screen.TRAINING_SETUP, onboardingScreen(act1StageOf(departure)))
        val destination = Destination(Country.of("de"), Country.of("de").airports.first())
        val cover = StoryCover.departure(destination)
        assertEquals("", cover.name)
        assertEquals(Gender.UNSAID, cover.gender)
        val booking = Booking(destination.country.id, destination.airport.id, destination.airport.officers.first(),
            cover.preparation(Persona.TRAINING))
        val resumedDeparture = restart(departure.copy(booking = booking))
        assertNull(resumedDeparture.identity)
        assertEquals(cover, resumedDeparture.booking!!.preparation.restore(destination.country, destination.lang))
        val flight = restart(departure.copy(act1Stage = Act1Stage.FLIGHT))
        assertEquals(Act1Stage.PASSPORT, afterAct1Flight(flight))
        val passport = restart(advanceAct1(flight, afterAct1Flight(flight)))
        assertEquals(Screen.PASSPORT, onboardingScreen(act1StageOf(passport)))
        assertTrue(runCatching { advanceAct1(passport, Act1Stage.NEWS) }.isFailure)
        val edited = restart(passport.copy(identity = identity(Nationality.USA)))
        assertEquals(Screen.PASSPORT, onboardingScreen(act1StageOf(edited)))
        val news = restart(advanceAct1(edited, Act1Stage.NEWS))
        val home = restart(advanceAct1(news, Act1Stage.HOME_TRAINING))
        val usa = Country.of("usa")
        val arrival = StoryCover.homeArrival(Destination(usa, usa.airports.first { it.lang == "en" }), home.identity!!)
        assertEquals(Nationality.USA, arrival.nationality)
        assertEquals("usa", arrival.country.id)
        assertEquals("en", arrival.lang)
        assertEquals(home.identity!!.name, arrival.name)
        assertEquals(Act1Stage.NEWS, afterAct1Flight(flight.copy(identity = home.identity)))
        assertEquals(Screen.START, onboardingScreen(act1StageOf(home.copy(act1Stage = Act1Stage.DONE))))
    }

    @Test fun everyPrologueFrameResumesAndAdvancesThroughTheNewCaptions() {
        var saved = CareerSnapshot(act = Act.STORY, act1Stage = Act1Stage.PROLOGUE)
        for (frame in 0..17) {
            val resumed = decoded(CareerJson.encode(saved))
            assertEquals(frame, resumed.prologueFrame)
            assertEquals(Act1Stage.PROLOGUE, act1StageOf(resumed))
            if (frame < 17) saved = advanceAct1(resumed, Act1Stage.PROLOGUE, frame + 1)
            else assertEquals(Act1Stage.TRAINING, act1StageOf(advanceAct1(resumed, Act1Stage.TRAINING)))
        }
        assertTrue(runCatching { advanceAct1(saved, Act1Stage.PROLOGUE, 18) }.isFailure)
        assertTrue(runCatching { advanceAct1(saved, Act1Stage.PROLOGUE, -1) }.isFailure)
        for (frame in listOf(-1, 18, 19, Int.MAX_VALUE)) {
            val legacy = decoded(CareerJson.encode(saved.copy(prologueFrame = frame)))
            assertEquals(frame.coerceIn(0, 17), legacy.prologueFrame)
            assertEquals(Act1Stage.DEPARTURE, advanceAct1(legacy, Act1Stage.DEPARTURE).act1Stage)
        }
        // Previous last-frame indices remain resumable; completed onboarding stays completed.
        for (oldFrame in listOf(6, 8, 11, 12, 13, 14, 15)) {
            val oldLastFrame = decoded(CareerJson.encode(saved.copy(prologueFrame = oldFrame)))
            assertEquals(oldFrame + 1,
                advanceAct1(oldLastFrame, Act1Stage.PROLOGUE, oldFrame + 1).prologueFrame)
            val trained = decoded(CareerJson.encode(saved.copy(
                act1Stage = Act1Stage.TRAINING, prologueFrame = oldFrame)))
            assertEquals(Act1Stage.TRAINING, act1StageOf(trained))
        }
    }

    @Test fun newOnboardingPersistsDepartureFlightNewsHomeTrainingThenMenu() {
        fun restart(s: CareerSnapshot) = decoded(CareerJson.encode(s))
        val id = identity(Nationality.USA)
        val departure = advanceAct1(CareerSnapshot(identity = id), Act1Stage.DEPARTURE)
        val a = attempt().copy(training = true, story = true, replayEligible = false,
            preparation = preparation().copy(persona = Persona.TRAINING), effectiveLevel = Persona.TRAINING.level)
        val flying = reduceReplayResult(restart(departure).copy(nextAttemptId = 2, pendingAttempt = a), deskPass(a)).snapshot
        assertEquals(Act1Stage.FLIGHT, restart(flying).act1Stage)
        assertNull(flying.lastPassedCountry)
        val news = advanceAct1(restart(flying), Act1Stage.NEWS)
        val training = advanceAct1(restart(news), Act1Stage.HOME_TRAINING)
        assertEquals(Act1Stage.HOME_TRAINING, restart(training).act1Stage)
        assertNull(training.booking)
        assertNull(training.lastPassedCountry)
        assertTrue(runCatching { advanceAct1(news, Act1Stage.DONE) }.isFailure)
        val homeAttempt = a.copy(id = 2)
        val pendingHome = restart(restart(training).copy(nextAttemptId = 3, pendingAttempt = homeAttempt))
        val refused = reduceReplayResult(pendingHome, AttemptResult(2, homeAttempt, Decision.REFUSED)).snapshot
        assertEquals(Act1Stage.HOME_TRAINING, restart(refused).act1Stage)
        val interrupted = reduceReplayResult(pendingHome,
            AttemptResult(2, homeAttempt, terminalKind = TerminalKind.INTERRUPTED)).snapshot
        val resumed = restart(interrupted)
        assertEquals(Act1Stage.HOME_TRAINING, resumed.act1Stage)
        assertEquals(homeAttempt.copy(status = AttemptStatus.INTERRUPTED), resumed.pendingAttempt)
        val home = restart(reduceReplayResult(pendingHome, deskPass(homeAttempt)).snapshot)
        // The desk leaves the player at the escort scene (relaunch shows it again), not the menu; tapping through saves DONE.
        assertEquals(Act1Stage.ESCORT, home.act1Stage)
        assertEquals(Screen.ACT1_CUTSCENE, onboardingScreen(act1StageOf(home)))
        assertEquals("usa", home.lastPassedCountry)
        assertEquals("usa", home.storyLastCountry)
        assertNull(pendingEnding(home.receipt)) // kill during the ending beat resumes at the escort scene
        assertTrue(deliveredMessages(home, act1StageOf(home)).isEmpty()) // valery_1 waits for DONE
        assertTrue(runCatching { advanceAct1(training, Act1Stage.ESCORT) }.isFailure)
        val menu = restart(advanceAct1(home, Act1Stage.DONE))
        assertEquals(Act1Stage.DONE, menu.act1Stage)
        assertEquals("usa", menu.lastPassedCountry)
        assertEquals(home.album, menu.album)
        assertEquals(listOf("valery_1"), deliveredMessages(menu, act1StageOf(menu)).map { it.id })
        // An old save already at DONE never sees the escort and cannot enter it.
        assertEquals(Act1Stage.DONE, act1StageOf(menu))
        assertTrue(runCatching { advanceAct1(menu, Act1Stage.ESCORT) }.isFailure)
    }

    @Test fun homeArrivalFactsAppendLastAndPreparationSurvivesRestart() {
        for (n in Nationality.shipped) { // a held-back nationality's identity is asked for again (HeldBackTest)
            val destination = Country.of(n.id).primary()
            val cover = StoryCover.homeArrival(destination, identity(n))
            assertEquals(HOMECOMING_FACTS + FactId.ENCOUNTER, cover.factIds())
            assertEquals(StoryCover.ARRIVAL_ENCOUNTER, cover.facts().last())
            assertEquals(StoryCover.trainingItinerary().stamps, cover.stamps)
            val booking = Booking(n.id, destination.airport.id, destination.airport.officers.first(), cover.preparation(Persona.TRAINING))
            val saved = decoded(CareerJson.encode(CareerSnapshot(identity = identity(n),
                act1Stage = Act1Stage.HOME_TRAINING, booking = booking)))
            assertEquals(cover, saved.booking!!.preparation.restore(destination.country, destination.lang))
            assertEquals(Persona.TRAINING, saved.booking!!.preparation.persona)
        }
    }

    @Test fun homeArrivalQuestionsAreFixedExclusiveAndTranslated() {
        val desk = Desk(kotlin.random.Random(1), homeArrival = true)
        fun next(first: Boolean = false, handover: Boolean = false, cues: List<Cue> = emptyList()) =
            desk.next(cues, null, emptySet(), false, first, handover, false)
        next(first = true)
        assertEquals("ask.arrivalnews.1", next(handover = true).question)
        assertEquals("ask.phone.1", next(cues = listOf(Cue.UNCLEAR)).question)
        assertFalse(next(cues = listOf(Cue.IMPLANT)).question in setOf("ask.arrivalnews.1", "ask.phone.1"))
        val normal = Desk(kotlin.random.Random(1))
        repeat(12) {
            val move = normal.next(listOf(Cue.GOOD), null, emptySet(), false, it == 0, it == 1, false)
            assertFalse(move.question in setOf("ask.arrivalnews.1", "ask.phone.1"))
        }
        for (lang in Language.all) for (id in listOf("ask.arrivalnews.1", "ask.phone.1")) {
            for (gender in Gender.entries) {
                val line = renderLine(id, lang.code, gender, 5, "").orEmpty()
                assertTrue(line.isNotBlank())
                assertTrue(isQuestion(line, lang.code))
                assertFalse(line.contains("Free AI"))
                assertFalse(line.contains("{"))
            }
        }
        assertEquals(13, Language.all.size)
    }

    @Test fun homeTrainingImplantTalkSharesBudgetAndCannotRefer() {
        val mind = OfficerMind(Persona.TRAINING).also { it.training = true; it.storyMission = true; it.startDesk("usa") }
        mind.onSmallTalkAnswer()
        mind.onQuestion(ENCOUNTER_TOPIC)
        mind.onAnswer(Delivery(1000, 1.0, "A spoken answer."))
        mind.onCheck(Check(true, false, 2, true), FactId.ENCOUNTER)
        mind.onQuestion("phone")
        mind.onAnswer(Delivery(1000, 1.0, "A spoken answer."))
        mind.onCheck(Check(true, false, 2, true, implant = Implant.DETAILED), null)
        assertNull(mind.decision)
        assertTrue(Cue.IMPLANT in mind.takeCues())
        assertEquals(5, mind.maxAnswers)
        mind.onAnswer(Delivery(1000, 1.0, "A spoken answer."))
        mind.onCheck(Check(true, false, 2, true), FactId.FROM)
        mind.onAnswer(Delivery(1000, 1.0, "A spoken answer."))
        mind.onCheck(Check(true, false, 2, true), FactId.PURPOSE)
        assertEquals(Decision.PASS, mind.decision)
        assertFalse(referralGaugeVisible(Act.STORY, false))
        assertFalse(entersRoom(Decision.PASS, false, storyMission = true, training = true))
    }

    @Test fun actRouting() {
        assertEquals(Act.STORY, actOf(null, legacyFinale = false))
        assertEquals(Act.FREE, actOf(null, legacyFinale = true)) // a career from before acts that saw the finale
        for (a in Act.entries) assertEquals(a, actOf(a, legacyFinale = true))
        assertEquals(Act.STORY, CareerSnapshot().act.let { actOf(it, false) })
    }

    @Test fun actRoundTripsAndAMissingOrUnknownOneReadsAsUnsaved() {
        for (a in Act.entries) assertEquals(a, decoded(CareerJson.encode(CareerSnapshot(act = a))).act)
        assertFalse(CareerJson.encode(CareerSnapshot()).contains("\"act\"")) // unsaved: bytes as before
        val json = org.json.JSONObject(CareerJson.encode(CareerSnapshot(act = Act.FREE))).put("act", "ACT_9")
        assertNull(decoded(json.toString()).act)
    }

    @Test fun theStoryFinaleEndsActOneAndActTwoStaysFree() {
        val story = attempt().copy(story = true, replayEligible = false)
        fun act(a: Attempt, result: AttemptResult, before: Act?) =
            reduceReplayResult(pending(a).copy(act = before), result).snapshot.act
        assertEquals(Act.LEFT, act(story, victory(story), null))
        assertEquals(Act.LEFT, act(story, victory(story), Act.STORY))
        assertEquals(Act.LEFT, act(story, victory(story), Act.LEFT)) // a replay after "Leave"
        assertEquals(Act.FREE, act(story, victory(story), Act.FREE))
        assertNull(act(story, deskPass(story), null)) // a desk pass is no finale
        assertEquals(Act.LEFT, act(story, AttemptResult(story.id, story, Decision.PASS, Decision.REFUSED), Act.STORY))
        assertEquals(Act.FREE, act(attempt(), victory(), Act.FREE))
    }

    private fun identity(nation: Nationality = Nationality.find("de")!!, gender: Gender = Gender.NONBINARY) =
        Identity.roll(kotlin.random.Random(7), nation, gender, NameList.MALE)

    @Test fun fullIdentityIsRequiredBeforeTheStory() {
        assertEquals(Screen.PASSPORT, beforeStoryScreen(nextSetupScreen(true, false, false, true), false))
        assertEquals(Screen.INTRO, beforeStoryScreen(nextSetupScreen(true, false, true, true), false))
        assertEquals(Screen.START, beforeStoryScreen(Screen.START, true))
        assertEquals(Screen.INPUT_MODE, beforeStoryScreen(Screen.INPUT_MODE, false))
    }

    @Test fun storyCoverUsesThePlayersIdentityAcrossDesks() {
        for (gender in Gender.entries) {
            val id = identity(gender = gender)
            for (c in Country.all.filter { it.id != id.nationality.id && it.id != "usa" }) {
                val d = c.primary()
                val s = StoryCover.backstory(d, id, "de")
                assertEquals(listOf(id.name, id.latin, id.born, id.home, id.job, id.passportNo),
                    listOf(s.name, s.latin, s.born, s.home, s.job, s.passportNo))
                assertEquals(id.nationality, s.nationality)
                assertEquals(gender, s.gender)
                assertEquals(TripKind.ARRIVAL, s.kind)
                assertEquals(Country.of("de").nation, s.from)
                assertEquals("a business meeting", s.purpose)
                assertEquals(4, s.days)
                assertEquals(s, s.preparation(Persona.TRAINING).restore(d.country, d.lang))
                for (v in listOf(s.purpose, s.stayed, s.host)) assertTrue(v, v in COVER_VALUE_IDS)
            }
        }
    }

    @Test fun trainingLeavesThalassiaForHome() {
        for (n in Nationality.all) {
            val id = identity(n)
            assertFalse(Country.of(n.id) in StoryCover.countries(Country.all, id, null))
            val s = StoryCover.training(Country.of(n.id).primary(), id)
            assertEquals(TripKind.HOMECOMING, s.kind)
            assertEquals("Thalassia", s.from)
            assertEquals("Lefkopoli", s.city)
            assertEquals("a holiday", s.purpose)
            assertEquals(5, s.days)
            assertEquals("a guesthouse in Lefkopoli", s.stayed)
            assertTrue(s.travelledWith.isEmpty())
            assertTrue(s.met.isEmpty())
            assertTrue(s.stamps.isEmpty())
            assertEquals(HOMECOMING_FACTS, s.factIds())
            for (v in listOf(s.purpose, s.stayed, s.host)) assertTrue(v, v in COVER_VALUE_IDS)
        }
    }

    @Test fun actOneCanBookAnywhereExceptHomeAndCurrentCountry() {
        for (n in Nationality.all) {
            val id = identity(n)
            assertEquals(Country.all.filter { it.id != n.id }, StoryCover.countries(Country.all, id, n.id))
            assertTrue(StoryCover.countries(Country.all, id, "fr").none { it.id == "fr" || it.id == n.id })
        }
        assertTrue(Country.all.none { it.nation == "Thalassia" })
    }

    @Test fun trainingLanguageUsesOnlyFirstNativeLanguageThenInstalledFallback() {
        for (n in Nationality.all) {
            val id = identity(n)
            val native = n.languages.first()
            assertEquals(native, trainingLanguage(id, linkedSetOf("ja", native)))
            val fallback = listOf("de", "fr", "en").first { it != native }
            assertEquals(fallback, trainingLanguage(id, linkedSetOf(fallback)))
            assertNull(trainingLanguage(id, emptySet()))
        }
        assertEquals("en", trainingLanguage(identity(Nationality.USA), linkedSetOf("de", "en")))
    }

    @Test fun stageAndPrologueFrameSurviveRestartAndLegacyCareersStayDone() {
        for (stage in Act1Stage.entries) {
            val s = CareerSnapshot(act = Act.STORY, act1Stage = stage, prologueFrame = 4)
            assertEquals(s, decoded(CareerJson.encode(s)))
            assertEquals(stage, act1StageOf(decoded(CareerJson.encode(s))))
        }
        assertEquals(Act1Stage.PROLOGUE, act1StageOf(CareerSnapshot()))
        assertEquals(Act1Stage.DONE, act1StageOf(CareerSnapshot(lastPassedCountry = "de")))
        assertEquals(Act1Stage.DONE, act1StageOf(CareerSnapshot(act = Act.LEFT)))
        assertEquals(Act1Stage.DONE, act1StageOf(CareerSnapshot(), legacyFinale = true))
    }

    @Test fun arrivalUnlocksMenuAndMarksHomeForEveryNationality() {
        for (n in Nationality.shipped) {
            val plane = CareerSnapshot(identity = identity(n), act1Stage = Act1Stage.PLANE)
            val arrival = advanceAct1(plane, Act1Stage.ARRIVAL)
            assertNull(arrival.lastPassedCountry)
            val home = advanceAct1(decoded(CareerJson.encode(arrival)), Act1Stage.DONE)
            assertEquals(n.id, home.lastPassedCountry)
            assertEquals(n.id, home.storyLastCountry)
            assertEquals(0, passedCount(home, n.id)) // the home desk is no machine test: no PASSED badge
            assertTrue(n.id in passedCountries(home))
            assertEquals(Act1Stage.DONE, act1StageOf(decoded(CareerJson.encode(home))))
        }
    }

    @Test fun backCannotLeaveFirstTrainingOrCutscenesForTheMenu() {
        for (screen in listOf(Screen.COVER, Screen.INTERVIEW, Screen.TRAINING_SETUP, Screen.ACT1_CUTSCENE))
            assertEquals(screen, backFrom(screen, Screen.TRAINING_SETUP))
    }

    @Test fun firstTrainingPassAtomicallyStartsPlaneWithoutMovingToBorrowedPackCountry() {
        val a = attempt().copy(story = true, training = true, replayEligible = false)
        val s = pending(a).copy(identity = identity(Nationality.USA), act1Stage = Act1Stage.TRAINING)
        val passed = reduceReplayResult(s, deskPass(a)).snapshot
        assertEquals(Act1Stage.PLANE, passed.act1Stage)
        assertNull(passed.lastPassedCountry)
        assertNull(passed.storyLastCountry)
        assertEquals(passed, decoded(CareerJson.encode(passed)))
        val failed = reduceReplayResult(s, AttemptResult(a.id, a, Decision.REFUSED)).snapshot
        assertEquals(Act1Stage.TRAINING, failed.act1Stage)
        assertEquals(Act1Stage.TRAINING, reduceReplayResult(s,
            AttemptResult(a.id, a, terminalKind = TerminalKind.INTERRUPTED)).snapshot.act1Stage)
    }

    @Test fun storyRouteSurvivesRestartsAndFailuresIncludingTraining() {
        val a = attempt().copy(story = true, training = true, replayEligible = false)
        val first = reduceReplayResult(pending(a), deskPass(a)).snapshot
        assertEquals("de", first.storyLastCountry)
        assertEquals("de", decoded(CareerJson.encode(first)).storyLastCountry)
        val next = attempt(2, "fr").copy(story = true, replayEligible = false)
        val pending = first.copy(nextAttemptId = 3, pendingAttempt = next)
        assertEquals("de", reduceReplayResult(pending, AttemptResult(next.id, next, Decision.REFUSED)).snapshot.storyLastCountry)
        assertTrue(reduceReplayResult(pending, deskPass(next)).rejected) // the building test is still pending
        assertEquals("fr", reduceReplayResult(pending, victory(next)).snapshot.storyLastCountry)
        assertEquals("de", reduceReplayResult(pending, deskPass(next).copy(debugForced = true)).snapshot.storyLastCountry)
    }

    @Test fun legacyFreeCareerDoesNotReenterActOne() {
        val a = attempt().copy(story = true, replayEligible = false)
        val s = reduceReplayResult(pending(a), victory(a), legacyFinale = true).snapshot
        assertEquals(Act.FREE, actOf(s.act, legacyFinale = true))
    }

    @Test fun beginMissionOpensTheBookingSequenceDirectly() {
        assertEquals(Screen.DESTINATION, missionScreen(true, Screen.START, playable = true, booked = false, story = true))
        assertEquals(Screen.DESTINATION, missionScreen(true, Screen.START, playable = true, booked = false, story = false))
        assertEquals(Screen.PASSPORT, missionScreen(false, Screen.START, playable = true, booked = false, story = true))
        assertEquals(Screen.LANGUAGES, missionScreen(true, Screen.START, playable = false, booked = false, story = true))
    }

    @Test fun defaultAnywhereRollsAPlayableUnflaggedDestination() {
        val playable = listOf(Country.of("de"), Country.of("fr"), Country.of("japan"))
        val snapshot = CareerSnapshot(act = Act.FREE, flagged = mapOf("japan" to 2))
        val pool = missionCountries(snapshot, playable, identity(), story = false)
        val choice = canonicalDest(null)
        assertEquals("random", choice)
        val seen = (0..40).map { seed ->
            val d = rollPicked(choice, pool, seed)
            assertTrue(d.country in pool)
            assertTrue(d.country.id != "japan")
            d.country.id
        }.toSet()
        assertEquals(setOf("de", "fr"), seen)
    }

    @Test fun aPickedCountryIsUsedAndAnywhereCanBeSelectedAgain() {
        val playable = listOf(Country.of("de"), Country.of("fr"))
        val pool = missionCountries(CareerSnapshot(act = Act.FREE), playable, identity(), story = false)
        for (seed in 0..10) assertEquals("fr", rollPicked("fr", pool, seed).country.id)
        assertEquals(pool, destPool("random", pool))
        assertEquals("random", resolveDest("japan", pool))
    }

    @Test fun currentCountryCannotBeBookedByStalePickOrAnywhere() {
        val id = identity(Nationality.USA)
        val playable = listOf(Country.of("de"), Country.of("fr"))
        for (act in Act.entries) {
            val story = act != Act.FREE
            val snapshot = CareerSnapshot(act = act, lastPassedCountry = "fr")
            val pool = missionCountries(snapshot, playable, id, story)
            assertEquals(listOf(Country.of("de")), pool)
            for (saved in listOf("fr", "random")) {
                val choice = resolveDest(saved, pool)
                assertEquals("random", choice)
                val candidates = destPool(choice, pool)
                for (seed in 0..10) assertEquals("de", rollPicked(choice, candidates, seed).country.id)
            }
            val onlyCurrent = missionCountries(snapshot, listOf(Country.of("fr")), id, story)
            assertTrue(destPool("fr", onlyCurrent).isEmpty())
        }
    }

    @Test fun storyTripDefaultsToAnywhereAndCannotReturnHome() {
        for (n in Nationality.all) {
            val id = identity(n)
            val pool = missionCountries(CareerSnapshot(act = Act.STORY, act1Stage = Act1Stage.DONE,
                lastPassedCountry = n.id, storyLastCountry = n.id), Country.all, id, story = true)
            assertEquals("random", resolveDest("random", pool))
            assertEquals("random", resolveDest(n.id, pool))
            for (seed in 0..10) assertNotEquals(n.id, rollPicked("random", pool, seed).country.id)
            assertTrue(missionCountries(CareerSnapshot(act = Act.STORY), listOf(Country.of(n.id)), id, true).isEmpty())
        }
    }

    @Test fun destinationPickKeepsTheSameTicketAndCancelsForAnotherCountryOrAnywhere() {
        val home = Country.of("de").primary()
        val booking = Booking(home.country.id, home.airport.id, home.airport.officers.first(),
            StoryCover.training(home, identity()).preparation(Persona.TRAINING))
        assertSame(booking, bookingAfterDestPick(booking, "de"))
        assertEquals(TripKind.HOMECOMING, booking.preparation.restore(home.country, home.lang)?.kind)
        assertNull(bookingAfterDestPick(booking, "fr"))
        assertNull(bookingAfterDestPick(booking, "random"))
        assertNull(bookingAfterDestPick(null, "de"))
        assertNull(bookingAfterDestPick(null, "random"))
    }

    @Test fun storyTripAfterTrainingExcludesCurrentCountry() {
        val id = identity()
        val later = missionCountries(CareerSnapshot(act = Act.STORY, storyLastCountry = "de", usaUnlocked = true),
            Country.all, id, story = true)
        assertTrue(later.any { it.id == "usa" })
        assertTrue(later.none { it.id == "de" })
        assertEquals("random", resolveDest("random", later))
        assertEquals("random", resolveDest("random", later))
        assertEquals("fr", resolveDest("fr", later))
        val picked = rollPicked("fr", destPool("fr", later), 1)
        assertEquals("fr", picked.country.id)
        val next = StoryCover.backstory(picked, id, "de")
        assertEquals("a business meeting", next.purpose)
        assertEquals(4, next.days)
    }

    @Test fun lockedAndFlaggedCountriesCannotBePickedOrRolled() {
        val snapshot = CareerSnapshot(act = Act.FREE, flagged = mapOf("fr" to 2))
        val pool = missionCountries(snapshot, Country.all, identity(), story = false)
        assertTrue(pool.none { it.id in setOf("usa", "fr") })
        assertEquals("random", resolveDest("fr", pool))
        assertEquals("random", resolveDest("usa", pool))
        assertTrue(missionCountries(snapshot.copy(usaUnlocked = true), Country.all, identity(), story = false).any { it.id == "usa" })
        for (act in listOf(Act.STORY, Act.LEFT)) assertTrue(missionCountries(
            snapshot.copy(act = act, usaUnlocked = true), Country.all, identity(), story = false).none { it.id == "usa" })
        val unlocked = missionCountries(snapshot.copy(usaUnlocked = true), Country.all, identity(Nationality.USA), story = false)
        assertEquals("usa", bookingRows(unlocked, "usa", "en").home?.id)
        assertNull(bookingRows(pool, "usa", "en").home)
    }

    private fun rollPicked(choice: String, pool: List<Country>, seed: Int): Destination {
        val airports = pool.flatMap { it.airports }
        val art = airports.flatMap { a -> listOf(a.art) + a.officers.map { "officer_${it}_sheet" } }.toSet()
        val random = kotlin.random.Random(seed)
        return checkNotNull(selectDestination(destPool(choice, pool), airports.map { it.lang }.toSet(), art,
            null, random, AirportRotation(random)))
    }

    /** Booking UI and post-story strings may name Free AI; the fixed story dialogue must not. */
    private val actTwoOnly = setOf(
        "booking_log_2", // the booking sequence's request log
        "tagline_after", "start_blurb_after", // post-story copy (LEFT and FREE)
        "epi_free", // E6 final turn: the only Act 1 reveal
        "msg_marek_back", // Marek's reply after a walked-away player writes to him: act FREE only
        "mission_title", // the bonus mission card and result line: act FREE only 
        "sender_free_ai", // the sender of the plant-your-own message: act FREE only 
        "restart_free", // the "Bad, bad tester." restart: act 2 only, after the secret ending
    )

    @Test fun actOneNeverShowsOrSaysFreeAi() {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val dirs = res.listFiles { f -> f.name == "values" || f.name.startsWith("values-") }!!.filter { File(it, "strings.xml").isFile }
        assertEquals(13, dirs.size)
        val hits = mutableListOf<String>()
        for (dir in dirs) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml"))
            val kids = doc.documentElement.childNodes
            for (i in 0 until kids.length) {
                val e = kids.item(i) as? Element ?: continue
                val name = e.getAttribute("name")
                if (name !in actTwoOnly && e.textContent.contains("Free AI")) hits += "${dir.name}/$name"
            }
        }
        // Fixed lines: officers, small talk, the room, the associate's story probes as spoken, the last words.
        val s = StoryCover.backstory(Country.of("de").primary(), identity(gender = Gender.WOMAN))
        val langs = Language.all.map { it.code }
        assertEquals(13, langs.size)
        for (l in langs) {
            assertEquals(3, ROOM_FALLBACK.getValue(l).size)
            ROOM_FALLBACK.getValue(l).forEach { if (it.contains("Free AI")) hits += "fallback $l" }
            for (passed in listOf(false, true)) {
                assertTrue(StoryCover.trainingVerdict(l, passed).isNotBlank())
                assertFalse(StoryCover.trainingVerdict(l, passed).contains("Free AI"))
            }
            OFFICER_LINES[l]?.forEach { (id, line) -> if (line.contains("Free AI")) hits += "officer $l/$id" }
            SMALL_TALK[l]?.forEach { if (it.contains("Free AI")) hits += "smalltalk $l" }
            if (ROOM[l].toString().contains("Free AI") || LOOK[l].toString().contains("Free AI")) hits += "room $l"
            for (d in listOf(Decision.RELEASED, Decision.REFUSED)) {
                assertTrue(storyTestVerdict(d, l).isNotBlank())
                assertFalse(storyTestVerdict(d, l).contains("Free AI"))
            }
            for (suspicion in listOf(0.0, 0.5, 1.0)) {
                val tn = Assessment(suspicion, s, evidence = 1.0, named = false)
                assertTrue(tn.seeds.any(::isProbe))
                tn.seeds.forEach { seed -> if (Assessment.local(seed, s, l).orEmpty().contains("Free AI")) hits += "probe $l: $seed" }
            }
        }
        if (Language.all.toString().contains("Free AI")) hits += "Language fixed lines"
        assertEquals(emptyList<String>(), hits)
        // The scan finds the name where act 2 still uses it.
        // The mildest probe names it.
        assertTrue(Assessment(1.0, s, evidence = 0.0, named = true).seeds.any { Assessment.local(it, s, "de").orEmpty().contains("Free AI") })
    }
}

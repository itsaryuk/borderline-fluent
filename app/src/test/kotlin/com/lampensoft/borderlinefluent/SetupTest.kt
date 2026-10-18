package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SetupTest {
    @Test fun firstRunHasOneSetupScreenBeforeStoryWithoutAPassport() {
        fun next(input: Boolean, face: Boolean, stage: Act1Stage = Act1Stage.PROLOGUE) =
            setupForAct1(stage, true, false, false, false, input, face)
        assertEquals(Screen.INPUT_MODE, next(false, false))
        assertEquals(Screen.INPUT_MODE, next(true, false)) // resume the old flow after input only
        assertEquals(Screen.INPUT_MODE, next(false, true)) // input still needs confirmation
        assertEquals(Screen.INTRO, next(true, true))
        assertEquals(Screen.TRAINING_SETUP, next(true, true, Act1Stage.DEPARTURE))
        assertEquals(Screen.ACT1_CUTSCENE, next(true, true, Act1Stage.FLIGHT))
        assertEquals(Screen.PASSPORT, next(true, true, Act1Stage.PASSPORT))
        // Identity saved, photo still pending: the durable stage remains authoritative.
        assertEquals(Screen.PASSPORT, setupForAct1(Act1Stage.PASSPORT, true, false, true, false, true, true))
        assertEquals(Screen.START, setupForAct1(Act1Stage.DONE, true, false, true, true, true, false))
        assertEquals(Screen.INTRO, setupForAct1(Act1Stage.PROLOGUE, true, false, true, true, true, false))
    }

    @Test fun prologueSeenSkipsOnlyTheAutomaticViewingAfterSetup() {
        assertEquals(Screen.INTRO, beforeStoryScreen(Screen.START, introSeen = false))
        assertEquals(Screen.START, beforeStoryScreen(Screen.START, introSeen = true))
        for (setup in listOf(Screen.LANGUAGES, Screen.DOWNLOAD, Screen.PASSPORT, Screen.INPUT_MODE)) {
            assertEquals(setup, beforeStoryScreen(setup, introSeen = false))
            assertEquals(setup, beforeStoryScreen(setup, introSeen = true))
        }
    }

    @Test fun pauseClockExcludesRepeatedBackgroundIntervalsAndPreservesSilenceDeadline() {
        var wall = 1_000_000_000L
        val clock = InterviewClock { wall }
        val answerAt = clock.now()
        val silenceDue = answerAt + 10_000_000_000L
        wall += 3_000_000_000L
        clock.pause()
        wall += 60_000_000_000L
        clock.pause() // duplicate lifecycle notification does not move the pause boundary
        assertEquals(3_000_000_000L, clock.now() - answerAt)
        clock.resume()
        clock.resume()
        assertEquals(7_000_000_000L, silenceDue - clock.now())
        wall += 2_000_000_000L
        clock.pause()
        wall += 120_000_000_000L
        clock.resume()
        assertEquals(5_000_000_000L, clock.now() - answerAt)
        assertEquals(5_000_000_000L, silenceDue - clock.now())
    }

    @Test fun roomThinkTimeExcludesBothBackgroundTimeAndHintDelay() {
        var wall = 1_000_000_000L
        val clock = InterviewClock { wall }
        val spoken = clock.now()
        wall += 2_000_000_000L
        clock.pause()
        wall += 300_000_000_000L
        clock.resume()
        wall += 1_000_000_000L
        val hints = clock.now()
        wall += 4_000_000_000L
        assertEquals(4000L, Assessment.thinkMs(spoken, hints, clock.now()))
    }

    @Test fun savePresentationNeverOffersRetryForCorruptionOrMarksBeforeCommit() {
        val receipt = Receipt(1, ReplayOutcome.CLEARED, listOf(NewMark("infiltration", "de")))
        val snapshot = CareerSnapshot(receipt = receipt)
        assertEquals(SavePresentation.SAVING, savePresentation(null, false))
        assertEquals(SavePresentation.SAVING, savePresentation(CareerSave.Saved(snapshot), true))
        // After acknowledgement the finale sting owns navigation, not a saving dialog.
        assertEquals(SavePresentation.READY, savePresentation(CareerSave.Saved(snapshot, receipt), false))
        assertEquals(SavePresentation.RETRY, savePresentation(CareerSave.WriteFailed(snapshot, null), false))
        assertEquals(SavePresentation.READ_ERROR, savePresentation(CareerSave.Unreadable(CareerRead.Unreadable("bad")), false))
        assertEquals(SavePresentation.PREPARATION_ERROR, savePresentation(CareerSave.Unreadable(CareerRead.Unreadable("bad", snapshot, true)), false))
        assertEquals(emptyList<NewMark>(), committedMarks(CareerSave.WriteFailed(snapshot, null), false))
        assertEquals(emptyList<NewMark>(), committedMarks(CareerSave.Saved(snapshot, receipt), true))
        assertEquals(emptyList<NewMark>(), committedMarks(CareerSave.Saved(snapshot), false))
        assertEquals(receipt.newMarks, committedMarks(CareerSave.Saved(snapshot, receipt), false))
    }

    @Test fun setupGateOrder() {
        assertEquals(Screen.LANGUAGES, nextSetupScreen(hasLanguages = false, downloading = false, passportDone = false, inputDone = true))
        assertEquals(Screen.LANGUAGES, nextSetupScreen(hasLanguages = false, downloading = true, passportDone = true, inputDone = true))
        assertEquals(Screen.DOWNLOAD, nextSetupScreen(hasLanguages = true, downloading = true, passportDone = false, inputDone = true))
        assertEquals(Screen.DOWNLOAD, nextSetupScreen(hasLanguages = true, downloading = true, passportDone = true, inputDone = true))
        // An existing install (languages, no passport step yet) sees PASSPORT once.
        assertEquals(Screen.PASSPORT, nextSetupScreen(hasLanguages = true, downloading = false, passportDone = false, inputDone = true))
        assertEquals(Screen.START, nextSetupScreen(hasLanguages = true, downloading = false, passportDone = true, inputDone = true))
        // Settled-career repair retains the old order; onboarding uses setupForAct1.
        assertEquals(Screen.INPUT_MODE, nextSetupScreen(hasLanguages = true, downloading = false, passportDone = true, inputDone = false))
        assertEquals(Screen.PASSPORT, nextSetupScreen(hasLanguages = true, downloading = false, passportDone = false, inputDone = false))
        assertEquals(Screen.LANGUAGES, nextSetupScreen(hasLanguages = false, downloading = false, passportDone = true, inputDone = false))
    }

    @Test fun backNeverSkipsSetup() {
        assertNull(backFrom(Screen.PASSPORT, setup = Screen.PASSPORT)) // not START
        assertNull(backFrom(Screen.LANGUAGES, setup = Screen.LANGUAGES))
        assertNull(backFrom(Screen.DOWNLOAD, setup = Screen.DOWNLOAD))
        assertNull(backFrom(Screen.INPUT_MODE, setup = Screen.INPUT_MODE))
        assertNull(backFrom(Screen.INTRO, setup = Screen.PASSPORT)) // leaves the app
        assertEquals(Screen.START, backFrom(Screen.INTRO, setup = Screen.START))
        assertEquals(Screen.START, backFrom(Screen.SETTINGS, setup = Screen.START))
        assertNull(backFrom(Screen.START, setup = Screen.START))
        assertNull(backFrom(Screen.UNSUPPORTED, setup = Screen.LANGUAGES))
    }

    @Test fun arrowsRespectTheSetupGate() {
        // First run, one language failed: back on LANGUAGES with a language installed, so the arrow shows. It must not
        // reach SETTINGS (and from there START) before the passport step.
        assertEquals(Screen.PASSPORT, arrowTo(Screen.SETTINGS, Screen.LANGUAGES, setup = Screen.PASSPORT))
        assertEquals(Screen.LANGUAGES, arrowTo(Screen.START, Screen.SETTINGS, setup = Screen.LANGUAGES))
        assertEquals(Screen.DOWNLOAD, arrowTo(Screen.SETTINGS, Screen.LANGUAGES, setup = Screen.DOWNLOAD))
        assertEquals(Screen.LANGUAGES, arrowTo(Screen.SETTINGS, Screen.LANGUAGES, setup = Screen.LANGUAGES)) // stays on setup
        assertEquals(Screen.INPUT_MODE, arrowTo(Screen.SETTINGS, Screen.LANGUAGES, setup = Screen.INPUT_MODE))
        assertEquals(Screen.INPUT_MODE, arrowTo(Screen.START, Screen.SETTINGS, setup = Screen.INPUT_MODE))
        // Setup done: arrows go where they point.
        assertEquals(Screen.SETTINGS, arrowTo(Screen.SETTINGS, Screen.LANGUAGES, setup = Screen.START))
        assertEquals(Screen.START, arrowTo(Screen.START, Screen.SETTINGS, setup = Screen.START))
    }

    @Test fun restoredPrefWithoutPhotoAsksForThePassport() {
        assertEquals(Screen.PASSPORT, nextSetupScreen(hasLanguages = true, downloading = false, passportDone = passportSetupDone(pref = true, photo = false), inputDone = true))
        assertEquals(Screen.START, nextSetupScreen(hasLanguages = true, downloading = false, passportDone = passportSetupDone(pref = true, photo = true), inputDone = true))
        assertEquals(Screen.PASSPORT, nextSetupScreen(hasLanguages = true, downloading = false, passportDone = passportSetupDone(pref = false, photo = true), inputDone = true))
    }

    @Test fun passportReturnsWhereItWasOpened() {
        // From Settings: back there (finish and Back alike), never START.
        assertEquals(Screen.SETTINGS, passportExit(Screen.SETTINGS, setup = Screen.START))
        assertEquals(Screen.SETTINGS, backFrom(Screen.PASSPORT, setup = Screen.START, passportFrom = Screen.SETTINGS))
        // Any other entry (setup, or a stray one after the step is done) is the setup variant: on to the next step.
        assertEquals(Screen.START, passportExit(Screen.DESTINATION, setup = Screen.START))
        assertEquals(Screen.START, passportExit(Screen.INTRO, setup = Screen.START))
        assertEquals(Screen.START, passportExit(null, setup = Screen.START))
        // First run: on to the input step; from Settings it is skipped even while that step is pending.
        assertEquals(Screen.INPUT_MODE, passportExit(Screen.INTRO, setup = Screen.INPUT_MODE))
        assertEquals(Screen.SETTINGS, passportExit(Screen.SETTINGS, setup = Screen.INPUT_MODE))
        assertNull(backFrom(Screen.PASSPORT, setup = Screen.PASSPORT, passportFrom = Screen.INTRO))
    }

    @Test fun storyFactsAreBold() {
        val b = fillBold("You live in %1\$s; %2\$s will be waiting. %3\$d%% sure.", "Kyiv", "your partner", 7)
        assertEquals("You live in Kyiv; your partner will be waiting. 7% sure.", b.text)
        assertEquals(listOf("Kyiv", "your partner", "7"), b.bold.map { b.text.substring(it) })
        // A nested fill keeps only its argument bold ("for" stays plain); reordered positional args work.
        val purpose = fillBold("for %1\$s", "a wedding")
        val trip = fillBold("%2\$s: %1\$d days, %3\$s.", 3, "Lviv", purpose)
        assertEquals("Lviv: 3 days, for a wedding.", trip.text)
        assertEquals(listOf("Lviv", "3", "a wedding"), trip.bold.map { trip.text.substring(it) })
    }

    private val de = Country.all.first { it.id == "de" }
    private val austria = Country.all.first { it.id == "austria" }
    private val fr = Country.all.first { it.id == "fr" }
    private val playable = listOf(de, austria, fr)

    @Test fun staleChoiceIsRandom() {
        assertEquals("random", resolveDest("lang:de", playable)) // retired "Any <language>" row
        assertEquals("austria", resolveDest("austria", playable))
        assertEquals("random", resolveDest("lang:ja", playable)) // the pack is gone
        assertEquals("random", resolveDest("jp", playable))
        assertEquals("random", resolveDest("random", playable))
        assertEquals("random", resolveDest("lang:fr", listOf(de)))
    }

    @Test fun destPoolHonoursTheChoice() {
        assertEquals(playable, destPool("random", playable))
        assertEquals(listOf(austria), destPool("austria", playable))
        assertEquals(playable, destPool("lang:ja", playable)) // retired: anywhere
    }

    @Test fun aReferralEntersTheRoomAndTrainingNeverRefers() {
        for (d in Decision.entries) {
            assertEquals(d == Decision.REFERRAL, entersRoom(d, wasInRoom = false))
            assertFalse(entersRoom(d, wasInRoom = true))
        }
        for (training in listOf(false, true)) for (meter in listOf(0.0, 0.5, 1.0)) {
            assertFalse(refers(training, true, meter))
        }
        assertFalse(refers(false, false, 0.0))
        assertFalse(refers(false, false, REFER_AT - 0.000001))
        assertTrue(refers(false, false, REFER_AT))
    }

    @Test fun storyDeskPassEntersBuildingOnlyAfterTraining() {
        for (d in Decision.entries) for (training in listOf(false, true)) {
            assertEquals(!training && d == Decision.PASS, entersRoom(d, false, true, training))
            assertFalse(entersRoom(d, true, true, training))
        }
    }

    @Test fun eitherBuildingVerdictReachesTheFinale() {
        for (d in Decision.entries) {
            assertEquals(d in setOf(Decision.RELEASED, Decision.REFUSED), reachesFinale(d, true, true))
            assertFalse(reachesFinale(d, false, true))
            assertFalse(reachesFinale(d, true, false))
        }
    }

    @Test fun foreignTongueComparesLanguageCodes() {
        assertEquals(false, foreignTongue("en", "en"))
        assertEquals(true, foreignTongue("en", "de"))
        assertEquals(false, foreignTongue("pt", "pt")) // pt-PT UI, pt-BR speech: still the player's language
    }

    @Test fun retryRunsEveryUnfinishedLanguage() {
        // The AI step failed after an evicted pack (still in the saved languages) was queued: both come back.
        val steps = listOf(PackStep("AI", "", failed = true), PackStep("German", "", lang = "de"),
            PackStep("French", "", done = true, lang = "fr"), PackStep("Japanese", "", done = true, failed = true, noVoice = true, lang = "ja"))
        assertEquals(setOf("de"), retrySteps(steps))
    }

    @Test fun offlineReminderOnlyAfterParanoidMissionWhileStillOffline() {
        assertEquals(true, remindOffline(playedOffline = true, offlineNow = true))
        assertEquals(false, remindOffline(playedOffline = true, offlineNow = false))
        assertEquals(false, remindOffline(playedOffline = false, offlineNow = true))
        assertEquals(false, remindOffline(playedOffline = false, offlineNow = false))
    }
}

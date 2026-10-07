package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SetupTest {
    @Test fun savePresentationNeverOffersRetryForCorruptionOrMarksBeforeCommit() {
        val receipt = Receipt(1, ReplayOutcome.INFILTRATION, listOf(NewMark("infiltration", "de")))
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
        // How to answer comes after the passport, never before it or the languages.
        assertEquals(Screen.INPUT_MODE, nextSetupScreen(hasLanguages = true, downloading = false, passportDone = true, inputDone = false))
        assertEquals(Screen.PASSPORT, nextSetupScreen(hasLanguages = true, downloading = false, passportDone = false, inputDone = false))
        assertEquals(Screen.LANGUAGES, nextSetupScreen(hasLanguages = false, downloading = false, passportDone = true, inputDone = false))
    }

    @Test fun backNeverSkipsSetup() {
        assertEquals(Screen.INTRO, backFrom(Screen.PASSPORT, setup = Screen.PASSPORT)) // not START
        assertEquals(Screen.INTRO, backFrom(Screen.LANGUAGES, setup = Screen.LANGUAGES))
        assertEquals(Screen.INTRO, backFrom(Screen.DOWNLOAD, setup = Screen.DOWNLOAD))
        assertEquals(Screen.INTRO, backFrom(Screen.INPUT_MODE, setup = Screen.INPUT_MODE))
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
        assertEquals(Screen.INTRO, arrowTo(Screen.SETTINGS, Screen.LANGUAGES, setup = Screen.LANGUAGES)) // like system Back
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
        assertEquals(Screen.INTRO, backFrom(Screen.PASSPORT, setup = Screen.PASSPORT, passportFrom = Screen.INTRO))
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

    @Test fun oldDestinationPrefMigrates() {
        assertEquals("random", migrateDest(null))
        assertEquals("random", migrateDest("random"))
        assertEquals("random", migrateDest("de")) // an old language code: "Any <language>" is gone
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

    @Test fun trainingNeverEntersTheRoom() {
        for (story in listOf(true, false)) {
            assertEquals(false, entersRoom(Decision.PASS, wasInRoom = false, training = true, storyMission = story, suspicion = 1.0, contradictions = 0))
            assertEquals(false, entersRoom(Decision.BROKEN, wasInRoom = false, training = true, storyMission = story, suspicion = 1.0, contradictions = 0))
            assertEquals(true, entersRoom(Decision.PASS, wasInRoom = false, training = false, storyMission = story, suspicion = 1.0, contradictions = 0))
            assertEquals(false, entersRoom(Decision.PASS, wasInRoom = true, training = false, storyMission = story, suspicion = 1.0, contradictions = 0))
            assertEquals(false, entersRoom(Decision.SECONDARY, wasInRoom = false, training = false, storyMission = story, suspicion = 1.0, contradictions = 0))
        }
        // A break: the story goes on into the room; after the story it wins the mission at the desk.
        assertEquals(true, entersRoom(Decision.BROKEN, wasInRoom = false, training = false, storyMission = true, suspicion = 1.0, contradictions = 0))
        assertEquals(false, entersRoom(Decision.BROKEN, wasInRoom = false, training = false, storyMission = false, suspicion = 1.0, contradictions = 0))
        assertEquals(false, entersRoom(Decision.BROKEN, wasInRoom = true, training = false, storyMission = true, suspicion = 1.0, contradictions = 0))
        fun e(d: Decision, story: Boolean, training: Boolean, s: Double, c: Int) = entersRoom(d, false, training, story, s, c)
        assertTrue(e(Decision.PASS, true, false, 0.0, 0))       // story always enters
        assertFalse(e(Decision.PASS, false, true, 0.9, 3))      // training never
        assertFalse(e(Decision.PASS, false, false, 0.2, 0))     // clean post-story PASS ends at the desk
        assertFalse(e(Decision.PASS, false, false, 0.25, 0))    // strictly above the threshold
        assertTrue(e(Decision.PASS, false, false, 0.3, 0))
        assertTrue(e(Decision.PASS, false, false, 0.1, 1))
        assertFalse(e(Decision.BROKEN, false, false, 0.9, 2))   // broken after the story wins outright
    }

    @Test fun storyRoomBreakReachesTheFinale() {
        assertEquals(true, reachesFinale(Decision.PASS, storyMission = true, wasInRoom = true))
        assertEquals(true, reachesFinale(Decision.BROKEN, storyMission = true, wasInRoom = true))
        assertEquals(false, reachesFinale(Decision.SECONDARY, storyMission = true, wasInRoom = true))
        assertEquals(false, reachesFinale(Decision.BROKEN, storyMission = false, wasInRoom = true)) // normal result
        assertEquals(false, reachesFinale(Decision.PASS, storyMission = false, wasInRoom = true))
        assertEquals(false, reachesFinale(Decision.BROKEN, storyMission = true, wasInRoom = false)) // desk: room first
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

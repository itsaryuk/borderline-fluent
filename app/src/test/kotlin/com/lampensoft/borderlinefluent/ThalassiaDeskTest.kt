package com.lampensoft.borderlinefluent

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ThalassiaDeskTest {
    @Test fun takenChildHasNoHintsAndAnySpeechOrFirstSilenceStartsCutsceneBeforePass() {
        for (reply in listOf("Yes", "No", "!", "", "  ", "другое")) {
            val desk = ThalassiaDesk(childTaken = true)
            assertEquals(ThalassiaDesk.LINES[0], desk.line)
            assertEquals(ThalassiaDesk.ANSWERS[0], desk.suggestions)
            desk.answer("Here")
            assertEquals("To paidí, mazí sas?", desk.line)
            assertEquals(R.string.thalassia_child, desk.subtitle)
            assertTrue(desk.suggestions.isEmpty())
            desk.answer(reply)
            assertTrue(desk.fatherAway)
            assertFalse(desk.passed)
            desk.answer("another late answer")
            assertFalse(desk.passed)
            desk.finishFatherAway()
            assertTrue(desk.passed)
            assertFalse(desk.fatherAway)
            assertEquals("O, lypámai. Kaló taxídi.", desk.line)
            assertEquals(R.string.thalassia_child_last, desk.subtitle)
            assertTrue(desk.suggestions.isEmpty())
        }
    }

    @Test fun takenChildPassportRepeatWaitsForSpeechBeforeTheChildQuestion() {
        val desk = ThalassiaDesk(childTaken = true)
        desk.answer("  ")
        assertEquals(ThalassiaDesk.LINES[0], desk.line)
        assertFalse(desk.passed)
        assertFalse(desk.fatherAway)
        desk.answer("Here")
        assertEquals(ThalassiaDesk.CHILD, desk.line)
        desk.answer("")
        assertTrue(desk.fatherAway)
    }

    @Test fun takenChildCannotBeSkippedByPassportSilence() {
        val desk = ThalassiaDesk(childTaken = true)
        desk.answer("")
        assertEquals(0, desk.turn)
        desk.answer("")
        assertEquals(ThalassiaDesk.CHILD, desk.line)
        assertFalse(desk.passed)
        desk.answer("")
        assertTrue(desk.fatherAway)
    }

    @Test fun bothChoicesSurviveQueueAndInterruptedDeskWithTrainingItineraryIntact() {
        val destination = Country.of("de").primary()
        val officer = destination.airport.officers.first()
        for (choice in 0..1) {
            val identity = Identity.roll(kotlin.random.Random(7), Nationality.USA,
                Gender.NONBINARY, NameList.MALE)
            val cover = StoryCover.training(destination, identity)
            val prep = cover.preparation(Persona.TRAINING).withFatherChoice(choice)
            val booked = CareerSnapshot(act = Act.STORY, act1Stage = Act1Stage.TRAINING,
                identity = identity, booking = Booking(destination.country.id, destination.airport.id, officer, prep))
            val saved = decoded(CareerJson.encode(booked))
            assertEquals(booked.booking, saved.booking)
            val restored = saved.booking!!.preparation
            assertEquals(choice, restored.fatherChoice)
            assertEquals(prep, restored)
            assertEquals(cover, restored.restore(destination.country, destination.lang))
            assertNull(restored.encounter)
            val interrupted = Attempt(1, destination.country.id, officer, destination.lang,
                training = true, story = true, replayEligible = false, effectiveLevel = Persona.TRAINING.level,
                contentVersion = 2, airportId = destination.airport.id, preparation = restored,
                status = AttemptStatus.INTERRUPTED)
            val resumed = decoded(CareerJson.encode(saved.copy(booking = null,
                pendingAttempt = interrupted, nextAttemptId = 2)))
            assertEquals(interrupted, resumed.pendingAttempt)
            val deskPrep = resumed.pendingAttempt!!.preparation!!
            assertEquals(choice, deskPrep.fatherChoice)
            assertEquals(prep, deskPrep)
            assertEquals(cover, deskPrep.restore(destination.country, destination.lang))
            val desk = ThalassiaDesk(deskPrep.fatherChoice == 0)
            desk.answer("Here")
            assertEquals(if (choice == 0) ThalassiaDesk.CHILD else ThalassiaDesk.LAST, desk.line)
            assertEquals(choice == 1, desk.passed)
            if (choice == 1) {
                val active = interrupted.copy(status = AttemptStatus.STARTED)
                val result = reduceReplayResult(resumed.copy(pendingAttempt = active), deskPass(active))
                assertFalse(result.rejected)
                assertEquals(Act1Stage.PLANE, result.snapshot.act1Stage)
            }
        }
    }

    @Test fun planeCaptionKeepsTheFatherChoiceAfterDepartureAndRestart() {
        for (stage in listOf(Act1Stage.TRAINING, Act1Stage.DEPARTURE)) {
            for (choice in 0..1) {
                val a = attempt().copy(training = true, story = true, replayEligible = false,
                    preparation = preparation().withFatherChoice(choice))
                val departed = reduceReplayResult(pending(a).copy(act1Stage = stage), deskPass(a)).snapshot
                val resumed = decoded(CareerJson.encode(departed))
                assertNull(resumed.booking)
                assertNull(resumed.pendingAttempt)
                assertEquals(if (choice == 0) R.string.act1_plane_taken else R.string.act1_plane,
                    act1PlaneCaption(resumed))
            }
        }
        assertEquals(R.string.act1_plane, act1PlaneCaption(CareerSnapshot()))
    }

    @Test fun homePhoneQuestionShowsTheImplantTipOnlyOnItsFirstAsk() {
        val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
        val game = File(main, "kotlin/com/lampensoft/borderlinefluent/Game.kt").readText()
        assertTrue(game.contains("if (homeArrivalTraining && desk.topic == \"phone\" && !m.reask)"))
        assertTrue(game.contains("tip = str(R.string.tip_mention_implant)"))
    }

    @Test fun firstTrainingSkipsBothBookingAndCoverUiAndKeepsQueueChoiceAtHandoff() {
        val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
        val ui = File(main, "kotlin/com/lampensoft/borderlinefluent/Ui.kt").readText()
        val game = File(main, "kotlin/com/lampensoft/borderlinefluent/Game.kt").readText()
        val destination = ui.substringAfter("private fun DestinationScreen").substringBefore("val prebooked")
        assertTrue(destination.contains("if (g.firstCareerTraining)"))
        assertTrue(destination.contains("return@Box"))
        val cover = ui.substringAfter("private fun CoverScreen").substringBefore("val ctx")
        assertTrue(cover.contains("if (g.departureTraining)"))
        assertFalse(cover.contains("if (g.firstCareerTraining)"))
        assertTrue(game.contains("val departureTraining get() = act1Stage in setOf(Act1Stage.TRAINING, Act1Stage.DEPARTURE)"))
        assertTrue(game.contains("val homeArrivalTraining get() = act1Stage == Act1Stage.HOME_TRAINING"))
        assertTrue(game.contains("val firstCareerTraining get() = departureTraining || homeArrivalTraining"))
        assertTrue(cover.contains("return@Box"))
        assertTrue(game.contains("if (firstCareerTraining) { screen = Screen.COVER; if (departureTraining) openFatherQueue() }"))
        val handoff = game.substringAfter("fun chooseInQueue").substringBefore("fun leaveQueue")
        assertFalse(handoff.contains("remove("))
        assertTrue(handoff.contains("startInterview(fatherBriefed = true)"))
        assertTrue(ui.contains("StoryCaption(\"cut_father_away\""))
        val interview = ui.substringAfter("private fun InterviewScreen").substringBefore("private fun OfficerEye")
        assertFalse(interview.contains("TrainingDeskEntry"))
        assertFalse(interview.contains("return@Box\n"))
    }

    @Test fun refusedFatherAnyNonEmptySpeechPassesAfterPassportOnly() {
        for (speech in listOf("yes", "нет", "123", "!", "wrong passport and wrong language")) {
            val desk = ThalassiaDesk()
            assertEquals(0, desk.turn)
            assertEquals("Kalesta! Dorvani su, parastí?", desk.line)
            assertEquals(R.string.thalassia_t1, desk.subtitle)
            assertFalse(desk.passed)
            desk.answer(speech)
            assertTrue(desk.passed)
            assertEquals("Kaló taxídi. Epómenos!", desk.line)
            assertEquals(R.string.thalassia_last, desk.subtitle)
            assertFalse(desk.fatherAway)
            assertTrue(desk.suggestions.isEmpty())
            desk.answer("anything")
            assertTrue(desk.passed)
        }
    }

    @Test fun refusedFatherSilenceRepeatsPassportOnceThenPasses() {
        val desk = ThalassiaDesk()
        val line = desk.line
        val subtitle = desk.subtitle
        desk.answer("  ")
        assertEquals(line, desk.line)
        assertEquals(subtitle, desk.subtitle)
        assertFalse(desk.passed)
        desk.answer("")
        assertTrue(desk.passed)
        assertEquals(ThalassiaDesk.LAST, desk.line)
    }

    @Test fun refusedFatherSpeechAfterRepeatPassesWithoutAnotherQuestion() {
        val desk = ThalassiaDesk()
        desk.answer("")
        desk.answer("Here")
        assertTrue(desk.passed)
        desk.answer("")
        assertTrue(desk.passed)
        assertEquals(ThalassiaDesk.LAST, desk.line)
    }

    @Test fun onlyFirstCareerTrainingUsesScript() {
        assertTrue(usesThalassiaDesk(true, true))
        assertFalse(usesThalassiaDesk(false, true))
        assertFalse(usesThalassiaDesk(false, false))
    }

    @Test fun fixedLinesAndUniversalSuggestions() {
        assertEquals(listOf("Kalesta! Dorvani su, parastí?"), ThalassiaDesk.LINES)
        assertEquals(listOf(listOf("OK.", "Here.", "Yes.")), ThalassiaDesk.ANSWERS)
    }

    @Test fun gameRoutesScriptBeforeJudgeAndOrdinaryAnswerChecks() {
        val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
        val game = File(main, "kotlin/com/lampensoft/borderlinefluent/Game.kt").readText()
        val turn = game.substringAfter("private suspend fun lockedTurn").substringBefore("private suspend fun smallTalk")
        val branch = turn.substringAfter("thalassiaDesk?.let { script ->").substringBefore("if (passportGiven")
        assertTrue(branch.contains("return@withLock"))
        assertFalse(branch.contains("judge"))
        assertTrue(turn.indexOf("thalassiaDesk?.let") < turn.indexOf("a.judge("))
        val answer = game.substringAfter("private suspend fun answer(").substringBefore("private fun newRecorder")
        assertTrue(answer.indexOf("if (thalassiaDesk != null) return officerTurn") < answer.indexOf("if (passportAsked"))
        assertTrue(game.contains("analyst = null"))
    }
}

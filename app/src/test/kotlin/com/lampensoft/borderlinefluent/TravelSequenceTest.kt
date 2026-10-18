package com.lampensoft.borderlinefluent

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TravelSequenceTest {
    @Test fun normalMissionsInBothActsTravelBeforeQueueOrDesk() {
        for (act in listOf(Act.STORY, Act.FREE)) {
            assertTrue("Normal mission in $act", shouldShowTravel(training = false,
                firstCareerTraining = false, recovering = false, completed = false))
        }
    }

    @Test fun departureAndHomeTrainingNeverTravel() {
        for (stage in listOf(Act1Stage.DEPARTURE, Act1Stage.TRAINING, Act1Stage.HOME_TRAINING)) {
            assertFalse("Training at $stage", shouldShowTravel(training = true,
                firstCareerTraining = true, recovering = false, completed = false))
        }
        assertFalse(shouldShowTravel(training = false, firstCareerTraining = true,
            recovering = false, completed = false)) // onboarding also gates independently
    }

    @Test fun restoredAttemptsAndCompletedTravelDoNotRepeat() {
        assertFalse(shouldShowTravel(false, false, recovering = true, completed = false))
        assertFalse(shouldShowTravel(false, false, recovering = false, completed = true))
    }

    @Test fun cutsceneDurationsRespectDisabledAnimations() {
        assertEquals(3000L, travelDurationMillis(TravelStep.FLIGHT, true))
        assertEquals(1000L, travelDurationMillis(TravelStep.FLIGHT, false))
        assertEquals(2000L, travelDurationMillis(TravelStep.ARRIVALS, true))
        assertEquals(2000L, travelDurationMillis(TravelStep.ARRIVALS, false))
    }

    @Test fun missionFlowGatesTravelBeforeQueueAndRestoresTicketsWithoutTravel() {
        val game = File("src/main/kotlin/com/lampensoft/borderlinefluent/Game.kt").readText()
        val start = game.substringAfter("@Synchronized fun startInterview(").substringBefore("private fun openFatherQueue()")
        assertTrue(start.indexOf("shouldShowTravel(training, firstCareerTraining, recovering != null, travelCompleted)") <
            start.indexOf("val pending = story.encounter"))
        val booking = game.substringAfter("private fun openBooking()").substringBefore("/** The finale")
        assertTrue(booking.contains("travelCompleted = true"))
        val prepare = game.substringAfter("private fun prepareMission(").substringBefore("/** The language has")
        assertTrue(prepare.contains("travelCompleted = false"))
        val advance = game.substringAfter("fun advanceTravel(").substringBefore("fun leaveTravel()")
        assertTrue(advance.contains("travel != expected"))
        assertTrue(advance.contains("TravelStep.FLIGHT -> travel = TravelStep.ARRIVALS"))
        assertTrue(advance.indexOf("travelCompleted = true") < advance.indexOf("startInterview()"))
    }
}

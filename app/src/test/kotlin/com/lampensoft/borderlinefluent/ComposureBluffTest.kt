package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test

class ComposureBluffTest {
    private val neutral = NeutralFace(.1f, .08f, .03f)
    private fun sample(t: Long) = Sample(t, true, smile = .1f, brow = .08f, lips = .03f)

    @Test fun naturalBlinksAndSmallMotionCompleteTheScan() {
        val hold = ComposureHold(neutral)
        for (t in 0L..3000L step 100) hold.update(sample(t).copy(
            eyesOpen = if (t % 500 == 0L) 0f else 1f, yaw = (t % 7).toFloat()), t)
        assertTrue(hold.complete); assertFalse(hold.failed)
    }
    @Test fun trackingLossAndStaleFramesPauseWithoutAwardingTime() {
        val hold = ComposureHold(neutral)
        for (t in 0L..1000L step 100) hold.update(sample(t), t)
        hold.update(sample(1100).copy(face = false), 1100)
        hold.update(sample(1100), 5000)
        hold.update(sample(6000), 6000)
        hold.update(sample(6100), 6000) // future frame
        assertEquals(1000L, hold.elapsed)
        for (t in 6200L..8200L step 100) hold.update(sample(t), t)
        assertTrue(hold.complete)
    }
    @Test fun deviationLosesOnlyTheOpportunity() {
        val hold = ComposureHold(neutral)
        hold.update(sample(0), 0)
        hold.update(sample(100).copy(yaw = 30f), 100)
        for (t in 200L..4000L step 100) hold.update(sample(t), t)
        assertTrue(hold.failed); assertFalse(hold.complete)
    }
    @Test fun missingCalibrationAndMissingFramesNeverComplete() {
        val hold = ComposureHold(null)
        for (t in 0L..4000L step 100) hold.update(sample(t), t)
        assertEquals(0L, hold.elapsed)
        val calibrated = ComposureHold(neutral)
        for (t in 0L..4000L step 100) calibrated.update(null, t)
        assertFalse(calibrated.complete)
    }
    @Test fun backgroundTimeNeverCounts() {
        val hold = ComposureHold(neutral)
        hold.update(sample(0), 0); hold.update(sample(100), 100)
        hold.suspendHold(); hold.update(sample(9000), 9000)
        assertEquals(100L, hold.elapsed); assertFalse(hold.failed)
    }
    @Test fun calibratedNeutralRequiresEnoughStableValidFace() {
        val p = Profiler()
        assertNull(p.neutralFace())
        assertTrue(p.calibrate((0L..1900L step 100).map(::sample)))
        assertNotNull(p.neutralFace())
        p.calibrate((0L..1900L step 100).map { sample(it).copy(brow = 0f) })
        assertNull(p.neutralFace())
        p.calibrate((0L..19L).map(::sample))
        assertNull(p.neutralFace())
    }
    @Test fun composureReplacesCalmAndCapsAtThree() {
        val a = Assessment(0.0, Backstory.BENCH)
        a.onTells(emptyList(), 0.0); assertEquals(37.5, a.index, 0.0)
        repeat(3) { assertTrue(a.composure()) }
        assertFalse(a.composure()); assertEquals(34.5, a.index, 0.0)
        val unaided = Assessment(0.0, Backstory.BENCH, coached = false)
        assertFalse(unaided.composure())
        unaided.onTells(emptyList(), 0.0); assertEquals(36.5, unaided.index, 0.0)
    }
    @Test fun composureCannotRemoveContentRefusalOrAdmission() {
        val a = Assessment(0.0, Backstory.BENCH)
        repeat(2) { a.onCheck(RoomCheck(false, false, false, true, 2, false, odd = true)) }
        a.composure(); assertEquals(Decision.REFUSED, a.verdict())
        val b = Assessment(0.0, Backstory.BENCH)
        while (!b.probing) b.next()
        b.onCheck(RoomCheck(false, false, true, true, 2, false, admit = Admit.YES)); b.next()
        b.onCheck(RoomCheck(false, false, true, true, 2, false, admit = Admit.YES))
        assertFalse(b.composure()); assertEquals(Decision.CAUGHT, b.verdict())
    }
    @Test fun pressureExcludesWarmupAndIncludesProbes() {
        val a = Assessment(0.0, Backstory.BENCH)
        assertFalse(a.pressureQuestion)
        while (!a.probing) a.next()
        assertTrue(a.pressureQuestion)
    }
    @Test fun bluffRequiresRaisedBrowsAndReturnToNeutral() {
        val g = DeskBluffGesture(neutral)
        g.sample(sample(0), 0)
        g.sample(sample(100).copy(brow = .12f), 100)
        assertFalse(g.complete); assertTrue(g.expressive)
        g.sample(sample(250), 250); assertTrue(g.complete)
    }
    @Test fun smileAloneDoesNotPerformTheAnnouncedGesture() {
        val g = DeskBluffGesture(neutral)
        g.sample(sample(0), 0); g.sample(sample(100).copy(smile = .8f), 100)
        g.sample(sample(250), 250); assertFalse(g.complete)
    }
    @Test fun expressionAlreadyHeldFlickerAndTrackingGapsDoNotCount() {
        val g = DeskBluffGesture(neutral)
        g.sample(sample(0).copy(brow = .12f), 0)
        g.sample(sample(100), 100); assertFalse(g.complete)
        g.sample(sample(200).copy(brow = .12f), 200)
        g.sample(sample(220), 220); assertFalse(g.complete)
        g.sample(sample(300).copy(brow = .12f), 300)
        g.sample(sample(400).copy(face = false), 400)
        g.sample(sample(500), 500); assertFalse(g.complete)
        g.sample(sample(600).copy(brow = .12f), 600)
        g.sample(sample(1200), 1200); assertFalse(g.complete)
        g.sample(sample(1300).copy(brow = .12f), 1300)
        g.pause(); g.sample(sample(1450), 1450); assertFalse(g.complete)
    }
    @Test fun bluffRejectsStaleFutureInvalidAndLongExpressions() {
        val g = DeskBluffGesture(neutral)
        g.sample(sample(0), 0)
        g.sample(sample(100).copy(brow = .12f), 500)
        g.sample(sample(600).copy(brow = .12f), 500)
        g.sample(sample(100).copy(smile = Float.NaN), 100)
        g.sample(sample(200), 200); assertFalse(g.complete)
        for (t in 300L..2000L step 100) g.sample(sample(t).copy(brow = .12f), t)
        g.sample(sample(2100), 2100); assertFalse(g.complete)
        val uncalibrated = DeskBluffGesture(null)
        uncalibrated.sample(sample(0), 0)
        uncalibrated.sample(sample(100).copy(brow = .12f), 100)
        uncalibrated.sample(sample(250), 250); assertFalse(uncalibrated.complete)
    }
    @Test fun bluffCostsExactlyOnceWithoutChangingAnswerCorrectness() {
        val m = OfficerMind(Persona.TRAINING).apply { homecoming = true }
        val suspicion = m.suspicion
        assertTrue(m.deskBluff()); assertFalse(m.deskBluff())
        assertEquals(.10, m.meter, 1e-9); assertEquals(suspicion + .02, m.suspicion, 1e-9)
        assertEquals(0, m.goodAnswers); assertEquals(0, m.badAnswers); assertNull(m.decision)
        assertTrue(OfficerMind(Persona.TRAINING).deskBluff()) // fresh desk restores use
    }
    @Test fun bluffIsUnavailableInTrainingStoryMachineAndSettledDesk() {
        assertFalse(OfficerMind(Persona.TRAINING).apply { training = true }.deskBluff())
        assertFalse(OfficerMind(Persona.TRAINING).apply { storyMission = true }.deskBluff())
        assertFalse(OfficerMind(Persona.TRAINING).apply { enterRoom() }.deskBluff())
        assertFalse(OfficerMind(Persona.TRAINING).apply { settle(Decision.REFUSED) }.deskBluff())
    }
}

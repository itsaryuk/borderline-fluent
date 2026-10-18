package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test

class FaceGesturesTest {
    private fun sample(t: Long, eyes: Float, face: Boolean = true) =
        Sample(t, face, leftEye = eyes, rightEye = eyes)
    /** Open frames at [t] .. [t]+400 (enough for the baseline), then [shut] closure onsets, 100 ms apart. */
    private fun blinks(g: FaceGestures, t: Long, shut: Int) {
        (0..4).forEach { g.sample(sample(t + it * 100, 1f), t + it * 100) }
        repeat(shut) { val c = t + 500 + it * 200L; g.sample(sample(c, 0f), c); g.sample(sample(c + 100, 1f), c + 100) }
    }
    @Test fun countsEachClosureOnsetAndOnlyCompletesOnce() {
        val g = FaceGestures(0)
        blinks(g, 0, 0)
        assertTrue(g.sample(sample(500, 0f), 500)); assertEquals(1, g.count); assertFalse(g.complete)
        assertFalse(g.sample(sample(600, 0f), 600)) // still shut: one closure, one blink
        g.sample(sample(700, 1f), 700)
        assertTrue(g.sample(sample(800, 0f), 800)); assertTrue(g.complete)
        g.sample(sample(900, 1f), 900)
        assertFalse(g.sample(sample(1000, 0f), 1000)); assertEquals(2, g.count)
    }
    @Test fun closedAtStartWinksAndMissingProbabilitiesDoNotCount() {
        val g = FaceGestures(0)
        g.sample(sample(0, 0f), 0); (1..4).forEach { g.sample(sample(it * 100L, 1f), it * 100L) }
        g.sample(sample(500, 0f).copy(rightEye = 1f), 500)
        g.sample(sample(600, 1f), 600)
        g.sample(sample(700, 0f).copy(rightEye = null), 700)
        g.sample(sample(800, 0f), 800)
        assertEquals(0, g.count)
    }
    @Test fun staleDuplicateFutureAndTrackingGapsCannotCountAClosure() {
        val g = FaceGestures(100)
        assertFalse(g.sample(sample(0, 1f), 100))
        listOf(100L, 200L, 300L, 400L, 500L).forEach { g.sample(sample(it, 1f), it) }
        assertFalse(g.sample(sample(500, 0f), 500))
        assertFalse(g.sample(sample(600, 0f), 599))
        assertFalse(g.sample(sample(600, 0f), 900))
        assertFalse(g.sample(sample(1000, 0f), 1000)) // 500 ms gap: no open frame since
        g.sample(sample(1100, 1f), 1100); g.sample(sample(1200, 1f, false), 1200)
        assertFalse(g.sample(sample(1300, 0f), 1300)) // face lost in between
        g.sample(sample(1400, 1f), 1400)
        assertFalse(g.sample(sample(1800, 0f), 1800))
        g.sample(sample(1900, 1f), 1900)
        assertTrue(g.sample(sample(2000, 0f), 2000)) // the resets kept the baseline
    }
    @Test fun flickerIsDebounced() {
        val g = FaceGestures(0)
        blinks(g, 0, 1)
        g.sample(sample(640, 0f), 640)
        assertEquals(1, g.count)
    }
    @Test fun upgradedWindowAllowsALaterPair() {
        val g = FaceGestures(0, 4500)
        blinks(g, 3700, 2)
        assertTrue(g.complete)
    }
    @Test fun practiceWaitsButStillRequiresAPair() {
        val g = FaceGestures(0, Long.MAX_VALUE)
        assertTrue(g.active(60000)); blinks(g, 60000, 1)
        blinks(g, 64000, 1); assertEquals(1, g.count)
        g.sample(sample(64700, 0f), 64700); assertTrue(g.complete)
    }
    @Test fun penaltiesShareBothQuestionAndRoomBudgets() {
        val a = Assessment(0.0, Backstory.BENCH)
        repeat(10) { a.onLookAway(); a.onTells(listOf(Tell.LIPS to 4.0), .9) }
        assertEquals(29.0, a.index, 0.0)
        a.next(); a.onTells(listOf(Tell.LIPS to 4.0), .9); a.onLookAway()
        assertEquals(33.0, a.index, 0.0)
        repeat(8) { a.next(); a.onLookAway(); a.onTells(listOf(Tell.LIPS to 4.0), .9) }
        assertEquals(37.5, a.index, 0.0)
    }
    @Test fun reaskedProbeKeepsItsPassiveBudget() {
        val a = Assessment(0.0, Backstory.BENCH)
        while (!a.probing) a.next()
        a.onLookAway()
        a.onCheck(RoomCheck(false, false, false, true, 2, false, admit = Admit.UNCLEAR))
        a.next(); assertTrue(a.reasking)
        val before = a.index
        a.onLookAway(); a.onTells(listOf(Tell.LIPS to 4.0), .9)
        assertEquals(before, a.index, 0.0)
    }
    @Test fun faceDeadlineExpiresEvenWithOneCompletedBlink() {
        val g = FaceGestures(0); blinks(g, 0, 1)
        g.sample(sample(2900, 1f), 2900); g.sample(sample(3001, 0f), 3001)
        assertFalse(g.complete); assertFalse(g.active(3001))
    }
    @Test fun overloadDoesNotEraseContentRefusalOrConfirmedAdmission() {
        val a = Assessment(0.0, Backstory.BENCH)
        val odd = RoomCheck(false, false, false, true, 2, false, odd = true)
        a.onCheck(odd); a.onCheck(odd); a.overload()
        assertEquals(Decision.REFUSED, a.verdict())
        val b = Assessment(0.0, Backstory.BENCH)
        while (!b.probing) b.next()
        b.onCheck(RoomCheck(false, false, true, true, 2, false, admit = Admit.YES)); b.next()
        b.onCheck(RoomCheck(false, false, true, true, 2, false, admit = Admit.YES)); b.overload()
        assertEquals(Decision.CAUGHT, b.verdict())
    }
    @Test fun dimLightBlinksCountAtTenFps() {
        // ~10.5 fps, open eyes scored 0.55-0.65, one closed frame per blink (owner's evening room, 2026-10-13).
        val g = FaceGestures(0)
        val eyes = listOf(.6f, .57f, .62f, .58f, .1f, .55f, .64f, .6f, .12f, .57f, .63f)
        eyes.forEachIndexed { i, e -> val t = i * 95L; g.sample(sample(t, e).copy(rightEye = e + .03f), t) }
        assertEquals(2, g.count); assertTrue(g.complete)
    }
    @Test fun steadyHalfOpenJitterIsNotABlink() {
        val g = FaceGestures(0)
        val eyes = listOf(.4f, .6f, .45f, .55f, .41f, .59f, .5f, .43f, .57f, .4f, .6f, .44f, .52f, .42f, .58f)
        eyes.forEachIndexed { i, e -> val t = i * 95L; g.sample(sample(t, e).copy(rightEye = 1f - e), t) }
        assertEquals(0, g.count)
    }
    private fun feed(g: FaceGestures, eyes: List<Float>, step: Long = 100) =
        eyes.forEachIndexed { i, e -> val t = i * step; g.sample(sample(t, e).copy(rightEye = e + .03f), t) }
    @Test fun dimRoomDoubleBlinkCompletesAgainstTheOwnOpenLevel() {
        // Open eyes read 0.45-0.65, closed 0.0-0.1, 10 fps: never above the old 0.5 on both eyes for long. (Open
        // frames of 0.35-0.6, median ~0.45, have no open state since the baseline became a median.)
        val g = FaceGestures(0)
        feed(g, listOf(.55f, .48f, .62f, .5f, .58f, .05f, 0f, .45f, .6f, .52f, .63f, .08f, .1f, .5f, .57f, .6f))
        assertEquals(2, g.count); assertTrue(g.complete)
    }
    @Test fun mostlyClosedSignalWithStraySpikesHasNoOpenStateToBlinkFrom() {
        // Like 2026-10-13 22:20 (median ~0.1, a few lone 0.6 frames): no open level, so nothing to count; the trace
        // in the overload log line shows what the real signal looked like.
        val g = FaceGestures(0)
        val eyes = MutableList(30) { listOf(.05f, .12f, .08f, .15f, .02f, .1f, .18f)[it % 7] }
        listOf(3, 11, 18, 27).forEach { eyes[it] = .6f }
        feed(g, eyes)
        assertEquals(0, g.count)
    }
    @Test fun steadyLowJitterIsNotABlink() {
        val g = FaceGestures(0)
        feed(g, listOf(.3f, .5f, .35f, .48f, .3f, .42f, .5f, .31f, .45f, .3f, .5f, .38f, .3f, .47f, .33f, .5f, .3f))
        assertEquals(0, g.count)
    }
    @Test fun slowDeliberateBlinksCount() {
        // Closed 700 ms each with half-shut frames around it: 900 ms to reopen, past the old 800 ms bound.
        val g = FaceGestures(0)
        val blink = listOf(.3f, .05f, .02f, .08f, .04f, .1f, .03f, .06f, .3f, .35f)
        feed(g, listOf(.6f, .55f, .62f) + blink + listOf(.58f, .6f) + blink + listOf(.61f))
        assertEquals(2, g.count); assertTrue(g.complete)
    }
    @Test fun closedFramesOfTheFirstBlinkDoNotEraseTheOpenLevel() {
        //  cold review: after the first reopen the history was mostly closed frames, the level fell to 0.05
        // and the second closure was invisible. The baseline is latched now.
        val g = FaceGestures(0)
        val pair = List(5) { .9f } + List(7) { .05f } + .9f + List(7) { .05f } + .9f
        pair.forEachIndexed { i, e -> val t = i * 100L; g.sample(sample(t, e), t) }
        assertEquals(2, g.count); assertTrue(g.complete)
    }
    @Test fun oneLongClosureIsOneBlinkWhateverItsJitter() {
        // Shut-range noise (0.2) and half-open frames (0.3, under open = shut + 0.05) inside a 2.5 s closure are not
        // an open eye.
        val g = FaceGestures(0)
        feed(g, List(5) { .9f } + List(25) { listOf(.03f, .2f, .02f, .3f, .05f)[it % 5] })
        assertEquals(1, g.count)
    }
    @Test fun longClosureThenAnOrdinaryBlinkCompletes() {
        val g = FaceGestures(0)
        feed(g, List(5) { .6f } + List(15) { .04f } + listOf(.6f, .04f))
        assertEquals(2, g.count); assertTrue(g.complete)
    }
    @Test fun fewBrightFramesInAMostlyClosedSignalDoNotLatchAnOpenLevel() {
        //  cold review on d63e600: 0.6 at 0, 200 and 1100 ms, 0.05 otherwise; the 80th percentile of the first
        // 3 frames latched 0.6 and later closures completed the challenge.
        val g = FaceGestures(0)
        (0 until 30).forEach { val t = it * 100L; g.sample(sample(t, if (t in setOf(0L, 200L, 1100L)) .6f else .05f), t) }
        assertEquals(0, g.count)
    }
    @Test fun aSingleHalfOpenFrameSeparatesTwoClosures() {
        //  cold review on d63e600: with a 0.9 baseline the old open threshold was 0.4 and this counted once.
        val g = FaceGestures(0)
        val eyes = List(5) { .9f } + List(7) { .05f } + .35f + List(7) { .05f } + .9f
        eyes.forEachIndexed { i, e -> val t = i * 100L; g.sample(sample(t, e), t) }
        assertEquals(2, g.count); assertTrue(g.complete)
    }
    /** Feeds a logged `trace=` (min eye x100 per frame) the way Game.overloadWindow does: polls the latest frame every 30 ms. */
    private fun replay(trace: String, step: Long = 95, eye: (Int) -> Float = { it / 100f }): FaceGestures {
        val start = 1000L
        val g = FaceGestures(start)
        val frames = trace.split(",").mapIndexed { i, v -> sample(start + 30 + i * step, eye(v.toInt())) }
        var now = start
        while (g.active(now)) { frames.lastOrNull { it.t <= now }?.let { g.sample(it, now) }; now += 30 }
        return g
    }
    @Test fun ownersRepeatedBlinksWithSingleFrameOpeningsComplete() {
        // The logged values are min(eyes) (the old trace format), applied to both eyes.
        // 2026-10-13, ticks=1 on the old detector: openings between closures are one frame, caught mid-opening.
        // The trace truncates to 2 digits; the old detector logged 1 tick only with the "64" frame just under its
        // reopen threshold (e.g. 0.641 against a 0.985 level), so both readings are replayed.
        val a = "98,98,92,79,98,86,99,98,99,98,02,02,05,23,16,50,02,02,12,40,04,00,01,64,00,00,15,96,01,06,23,86,00"
        assertTrue(replay(a).complete)
        assertTrue(replay(a) { if (it == 64) .641f else it / 100f + .005f }.complete)
    }
    @Test fun ownersLongClosuresWithABriefOpeningComplete() {
        // The logged values are min(eyes) (the old trace format), applied to both eyes.
        // 2026-10-13, ticks=0 on the old detector: a 1.5 s closure collapsed its live level to 0.02.
        assertTrue(replay("99,98,98,90,91,94,78,00,06,35,01,02,17,00,02,02,00,02,00,02,01,02,02,00,77,01,02,04,00,01,25,00").complete)
    }
    @Test fun practiceWindowGivesUpAfterThirtySeconds() {
        assertFalse(practiceExpired(1000, 30_999)); assertTrue(practiceExpired(1000, 31_001))
    }
}

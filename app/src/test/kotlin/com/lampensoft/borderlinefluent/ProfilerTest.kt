package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ProfilerTest {
    private val r = Random(7)
    /** 10 fps of a calm face with sensor noise; [f] tweaks each sample. */
    private fun frames(fromMs: Long, seconds: Int, f: (Sample, Int) -> Sample = { s, _ -> s }) = List(seconds * 10) { i ->
        f(Sample(fromMs + i * 100L, true, smile = 0.1f + r.nextFloat() * 0.05f, eyesOpen = 0.9f, yaw = r.nextFloat() * 4 - 2,
            pitch = r.nextFloat() * 4 - 2, lips = 0.09f + r.nextFloat() * 0.004f, brow = 0.12f + r.nextFloat() * 0.004f), i)
    }
    /** Closes the eyes for one frame every [every] frames: a blink. */
    private fun blinking(every: Int) = { s: Sample, i: Int -> if (i % every == 0) s.copy(eyesOpen = 0.1f) else s }

    @Test fun calmAnswerHasNoTells() {
        val p = Profiler().apply { calibrate(frames(0, 15, blinking(50))) }
        assertEquals(emptyList<Pair<Tell, Double>>(), p.tells(frames(20_000, 6, blinking(50))))
    }

    @Test fun deviationsFromBaselineAreTells() {
        val p = Profiler().apply { assertTrue(calibrate(frames(0, 15, blinking(50)))) }
        val nervous = p.tells(frames(20_000, 6, blinking(8))).map { it.first }
        assertTrue("blinks: $nervous", Tell.BLINKS in nervous)
        val pressed = p.tells(frames(20_000, 6) { s, _ -> s.copy(lips = 0.05f) }).map { it.first }
        assertTrue("lips: $pressed", Tell.LIPS in pressed)
        val grin = p.tells(frames(20_000, 6) { s, _ -> s.copy(smile = 0.9f) }).map { it.first }
        assertTrue("mask smile (eyes wide): $grin", Tell.MASK_SMILE in grin)
        val real = p.tells(frames(20_000, 6) { s, _ -> s.copy(smile = 0.9f, eyesOpen = 0.55f) }).map { it.first }
        assertTrue("real smile (eyes narrow): $real", Tell.SMILE in real)
        val away = p.tells(frames(20_000, 6) { s, _ -> s.copy(yaw = 35f) }).map { it.first }
        assertTrue("gaze: $away", Tell.GAZE in away)
    }

    @Test fun noBaselineNoTells() = assertEquals(0, Profiler().tells(frames(0, 5)).size)
}

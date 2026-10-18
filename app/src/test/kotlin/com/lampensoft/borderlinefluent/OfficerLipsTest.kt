package com.lampensoft.borderlinefluent

import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficerLipsTest {
    private val region = LipRegion(100f, 110f, 120f, 35f, 180f, 8f)

    @Test fun systemMouthDurationScalesWithTextAndCapsAtThirtySeconds() {
        assertEquals(400L, systemMouthDurationMs(0))
        assertEquals(1_300L, systemMouthDurationMs(10))
        assertEquals(29_920L, systemMouthDurationMs(328))
        assertEquals(30_000L, systemMouthDurationMs(329))
        assertEquals(30_000L, systemMouthDurationMs(Int.MAX_VALUE))
    }

    @Test fun stopClosesMouthAndRejectsLateLevels() {
        val mouth = OfficerMouth()
        val token = mouth.begin()
        mouth.level(token, 1f)
        assertEquals(1f, mouth.open.floatValue, 0f)
        mouth.stop()
        mouth.level(token, 1f)
        assertEquals(0f, mouth.open.floatValue, 0f)
    }

    @Test fun releaseHasAnEightyMillisecondTimeConstant() {
        assertEquals(exp(-1f), LipEnvelope.smooth(1f, 0f, 80f), 0.00001f)
        assertTrue(LipEnvelope.smooth(0f, 1f, 20f) > 0.6f)
        assertEquals(0.4f, LipEnvelope.smooth(0.4f, 1f, 0f), 0.00001f)
    }

    @Test fun smoothingIsIndependentOfPollingIntervalAndBounded() {
        val once = LipEnvelope.smooth(1f, 0f, 80f)
        var split = 1f
        repeat(5) { split = LipEnvelope.smooth(split, 0f, 16f) }
        assertEquals(once, split, 0.00001f)
        var value = 0f
        repeat(100) {
            value = LipEnvelope.smooth(value, if (it % 2 == 0) 5f else -2f, 16f)
            assertTrue(value in 0f..1f)
        }
    }

    @Test fun rmsUsesOnlyTheCurrentWindowAndTreatsSilenceAsClosed() {
        val samples = floatArrayOf(0f, 0f, 0.09f, -0.09f)
        assertEquals(0f, LipEnvelope.rms(samples, 0, 2), 0f)
        assertEquals(0.5f, LipEnvelope.rms(samples, 2, 4), 0.00001f)
        assertEquals(0f, LipEnvelope.rms(samples, 0, 0), 0f)
    }

    @Test fun closedMeshIsExactlyUnchangedAndOpeningIsBounded() {
        for (x in 0..200) for (y in 0..200) {
            assertEquals(0f, region.displacement(x.toFloat(), y.toFloat(), 0f), 0f)
            for (open in listOf(0.1f, 0.5f, 1f, 2f)) {
                assertTrue(region.displacement(x.toFloat(), y.toFloat(), open) in 0f..region.maxOpen)
            }
        }
        assertEquals(region.maxOpen, region.displacement(100f, 120f, 1f), 0f)
    }

    @Test fun outsideMouthSupportAndItsEdgesNeverMove() {
        for (x in 0..200) for (y in 0..200) {
            if (x <= 65 || x >= 135 || y <= 110 || y >= 180)
                assertEquals(0f, region.displacement(x.toFloat(), y.toFloat(), 1f), 0f)
        }
        assertTrue(region.displacement(100f, 179.9f, 1f) < 0.001f)
        assertTrue(region.displacement(134.9f, 120f, 1f) < 0.001f)
    }
}

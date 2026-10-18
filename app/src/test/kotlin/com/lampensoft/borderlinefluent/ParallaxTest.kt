package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class ParallaxTest {
    @Test fun firstSampleEstablishesNeutralPose() {
        assertEquals(ParallaxAxis(.7f), stepParallax(null, .7f, 1f / 60f))
    }

    @Test fun sixtyHzStepUsesTwelvePercentSmoothing() {
        val next = stepParallax(ParallaxAxis(0f), .1f, 1f / 60f)
        val baseline = .1f * (1f - exp(-1f / 180f))
        assertEquals(baseline, next.baseline, .000001f)
        assertEquals((.1f - baseline) / .35f * .12f, next.offset, .000001f)
    }

    @Test fun extremeTiltsStayClampedInBothDirections() {
        for (direction in listOf(-1f, 1f)) {
            var state = ParallaxAxis(0f)
            repeat(120) {
                state = stepParallax(state, direction, 1f / 60f)
                assertTrue(state.offset in -1f..1f)
            }
            assertTrue(state.offset * direction > .9f)
        }
    }

    @Test fun heldPoseRecentresWithThreeSecondTimeConstant() {
        val afterThreeSeconds = stepParallax(ParallaxAxis(0f), .3f, 3f)
        assertEquals(.3f * (1f - exp(-1f)), afterThreeSeconds.baseline, .000001f)
        var state = ParallaxAxis(0f)
        repeat(1800) { state = stepParallax(state, .3f, 1f / 60f) }
        assertTrue(abs(state.offset) < .001f)
    }

    @Test fun baselineIsIndependentOfFrameRate() {
        fun atRate(hz: Int): ParallaxAxis {
            var state = ParallaxAxis(0f)
            repeat(hz * 3) { state = stepParallax(state, .3f, 1f / hz) }
            return state
        }
        assertEquals(atRate(60).baseline, atRate(120).baseline, .00001f)
    }

    @Test fun invalidSamplesAndTimeDoNotCorruptState() {
        val previous = ParallaxAxis(.2f, .1f)
        assertEquals(previous, stepParallax(previous, Float.NaN, .1f))
        assertEquals(previous, stepParallax(previous, .5f, 0f))
        assertEquals(previous, stepParallax(previous, .5f, -1f))
        assertEquals(previous, stepParallax(previous, .5f, Float.POSITIVE_INFINITY))
    }
}

class CityParallaxTest {
    @Test fun backdropScaleCoversFullShiftOnNarrowArt() {
        assertEquals(1.06f, cityBackdropScale(1080f, 10f), 0f)
        assertEquals(1f + 2f * 50f / 400f, cityBackdropScale(400f, 50f), .000001f)
        assertEquals(1.06f, cityBackdropScale(0f, 50f), 0f)
    }

    @Test fun onlyTheJudgedTaxiDrives() {
        assertTrue(CityStep.values().filter { cityDriving(CityKind.TAXI, it) } == listOf(CityStep.CHECKING, CityStep.DONE))
        assertTrue(CityStep.values().none { cityDriving(CityKind.BAR, it) || cityDriving(CityKind.HOTEL, it) })
    }
}

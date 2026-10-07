package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test

class HapticsTest {
    @Test fun allEnvelopesAreFiniteAndEndSilent() {
        HapticEvent.entries.forEach { event ->
            val spec = hapticSpec(event)
            assertTrue(spec.envelope.size in 2..16)
            assertEquals(0f, spec.envelope.last().intensity)
            assertTrue(spec.envelope.sumOf { it.ms } <= 650)
            spec.envelope.forEach { assertTrue(it.ms >= 20); assertTrue(it.intensity in 0f..1f); assertTrue(it.sharpness in 0f..1f) }
            assertTrue(spec.composition.isNotEmpty())
            spec.composition.forEach { assertTrue(it.scale in 0f..1f); assertTrue(it.delay >= 0) }
        }
    }
    @Test fun stampsAreSingleThudsAndDetentionIsHeavier() {
        val pass = hapticSpec(HapticEvent.STAMP_PASS)
        val detain = hapticSpec(HapticEvent.STAMP_DETAIN)
        assertEquals(listOf(HapticPrimitive.THUD), pass.composition.map { it.primitive })
        assertEquals(1, detain.composition.size)
        assertTrue(detain.envelope.first().intensity > pass.envelope.first().intensity)
    }
    @Test fun scannerRisesForThreeHundredMilliseconds() {
        val e = hapticSpec(HapticEvent.SCAN).envelope
        assertEquals(300L, e.sumOf { it.ms })
        assertTrue(e[1].intensity > e[0].intensity)
        assertTrue(e[1].sharpness > e[0].sharpness)
    }
    @Test fun stutterIsStrongerWithIrregularGaps() {
        val light = hapticSpec(HapticEvent.IMPLANT_GLITCH)
        val strong = hapticSpec(HapticEvent.IMPLANT_STUTTER)
        assertTrue(strong.envelope.maxOf { it.intensity } > light.envelope.maxOf { it.intensity })
        assertEquals(listOf(0, 40, 80), strong.composition.map { it.delay })
        assertTrue(strong.doubleClick)
    }
    @Test fun crashDecaysAndTaserIsDistinct() {
        val crash = hapticSpec(HapticEvent.BREAK).envelope
        assertEquals(600L, crash.sumOf { it.ms })
        assertTrue(crash.zipWithNext().all { (a,b) -> a.intensity > b.intensity })
        assertNotEquals(hapticSpec(HapticEvent.BREAK), hapticSpec(HapticEvent.TASER))
    }
    @Test fun strengthsShareCooldownAndRejectedCallsDoNotExtendIt() {
        val gate = HapticRateLimiter()
        assertTrue(gate.allow(HapticEvent.IMPLANT_GLITCH, 0))
        assertFalse(gate.allow(HapticEvent.IMPLANT_STUTTER, 1999))
        assertTrue(gate.allow(HapticEvent.IMPLANT_STUTTER, 2000))
        assertFalse(gate.allow(HapticEvent.IMPLANT_GLITCH, 3999))
        assertTrue(gate.allow(HapticEvent.IMPLANT_GLITCH, 4000))
    }
    @Test fun authoredActionsAreNotThrottledByWarnings() {
        val gate = HapticRateLimiter()
        assertTrue(gate.allow(HapticEvent.IMPLANT_GLITCH, 123))
        HapticEvent.entries.filter { it != HapticEvent.IMPLANT_GLITCH && it != HapticEvent.IMPLANT_STUTTER }.forEach {
            assertTrue(gate.allow(it, 124)); assertTrue(gate.allow(it, 124))
        }
        assertFalse(gate.allow(HapticEvent.IMPLANT_GLITCH, 125))
    }
}

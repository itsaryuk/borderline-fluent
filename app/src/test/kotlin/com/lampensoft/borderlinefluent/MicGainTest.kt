package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/** The software AGC: quiet speech is lifted, loud speech is turned down before it clips, gain stays within 1..MAX. */
class MicGainTest {
    private fun frame(peak: Float) = FloatArray(512) { sin(it * 0.3f) }.also { // scaled so its peak is exactly [peak]
        val p = it.maxOf(::abs); for (i in it.indices) it[i] *= peak / p
    }

    @Test fun quietInputRisesToMax() {
        val g = MicGain()
        repeat(60) { g.apply(frame(0.03f)) } // ~1.9 s of the owner's arm's-length speech
        assertEquals(MicGain.MAX, g.gain, 1e-4f)
        assertEquals(0.03f * MicGain.MAX, g.apply(frame(0.03f)).maxOf(::abs), 1e-4f)
    }

    @Test fun loudFrameNeverPassesCeiling() {
        val g = MicGain()
        repeat(60) { g.apply(frame(0.001f)) } // silence: gain at max
        for (p in listOf(0.09f, 0.24f, 0.5f, 0.7f)) {
            assertTrue(g.apply(frame(p)).maxOf(::abs) <= MicGain.CEIL + 1e-5f)
            assertTrue(g.gain >= 1f)
        }
        assertEquals(1f, g.gain, 1e-4f) // a 0.7 peak takes the gain down to 1
    }

    @Test fun steadyAndBigFramesNeverPassCeiling() { //: the release used to rise past the frame's own peak
        val g = MicGain()
        repeat(40) { assertTrue(g.apply(frame(0.175f)).maxOf(::abs) <= MicGain.CEIL + 1e-5f) }
        val big = FloatArray(8192) { sin(it * 0.3f) * 0.175f } // a hold-mode chunk on a device with a large min buffer
        assertTrue(MicGain().apply(big).maxOf(::abs) <= MicGain.CEIL + 1e-5f)
    }

    @Test fun gainStaysWithinBounds() {
        val g = MicGain()
        assertEquals(0.9f, g.apply(frame(0.9f)).maxOf(::abs), 1e-4f) // already past the ceiling: left alone, not cut
        assertEquals(1f, g.gain)
        repeat(500) { g.apply(FloatArray(512)) } // digital silence: no divide by zero, capped at max
        assertEquals(MicGain.MAX, g.gain)
        assertEquals(1f, g.lo); assertEquals(MicGain.MAX, g.hi)
    }
}

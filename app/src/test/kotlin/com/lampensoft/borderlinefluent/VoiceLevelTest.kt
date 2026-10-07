package com.lampensoft.borderlinefluent

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLevelTest {
    private fun tone(amp: Float) = FloatArray(22050) { amp * sin(2 * PI * 220 * it / 22050).toFloat() }
    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size).toFloat()

    @Test fun quietAndLoudVoicesLandAtTheSameLevel() {
        val quiet = Piper.level(tone(0.03f)); val loud = Piper.level(tone(0.17f))
        assertEquals(Piper.TARGET_RMS, rms(quiet), 0.002f)
        assertEquals(Piper.TARGET_RMS, rms(loud), 0.002f)
    }

    @Test fun peaksStayUnderTheCeiling() {
        val spiky = tone(0.05f).also { it[100] = 0.8f } // crest factor too high for the RMS target
        assertTrue(Piper.level(spiky).maxOf { abs(it) } <= 0.89f + 1e-6f)
    }

    @Test fun silenceAndNoiseAreNotBlownUp() {
        val silent = FloatArray(1000)
        assertTrue(Piper.level(silent) === silent)
        assertTrue(Piper.level(tone(0.001f)).maxOf { abs(it) } <= 0.0101f) // +20 dB at most
    }
}

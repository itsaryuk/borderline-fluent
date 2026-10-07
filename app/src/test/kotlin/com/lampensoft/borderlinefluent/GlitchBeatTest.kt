package com.lampensoft.borderlinefluent

import org.junit.Test
import org.junit.Assert.assertEquals

class GlitchBeatTest {
    // beats at 60 + 500k ms
    @Test fun beforeFirstBeat() = assertEquals(60L, msToNextGlitch(0, 60, 500, 2))
    @Test fun onBeatSkipsToNext() = assertEquals(1000L, msToNextGlitch(60, 60, 500, 2))
    @Test fun midBeat() = assertEquals(900L, msToNextGlitch(160, 60, 500, 2)) // beat 0 passed -> beat 2 at 1060
    @Test fun barAligned() {
        assertEquals(1900L, msToNextGlitch(160, 60, 500, 4)) // beat 4 at 2060
        assertEquals(3900L, msToNextGlitch(160, 60, 500, 8)) // beat 8 at 4060
        assertEquals(1500L, msToNextGlitch(560, 60, 500, 4)) // beat 1 -> beat 4 at 2060
    }
    @Test fun nearTrackEndWraps() { // 134 s loop = 268 beats; next beat after the end lands past the end, recomputed after the wrap
        assertEquals(60L + 134000L - 133990L, msToNextGlitch(133990, 60, 500, 2))
        assertEquals(60L, msToNextGlitch(0, 60, 500, 8))
    }
}

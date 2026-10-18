package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

/** Long clips are cut for one-shot ASR (buffer overflow at ~8 s): contiguous, at most 7 s each, cut in pauses. */
class AsrSegmentsTest {
    private val rate = 16000
    private fun speech(seconds: Double) = FloatArray((seconds * rate).toInt()) { 0.3f * sin(it * 0.05f) }

    private fun assertCovers(segs: List<IntRange>, size: Int) {
        assertEquals(0, segs.first().first)
        assertEquals(size - 1, segs.last().last)
        segs.zipWithNext { a, b -> assertEquals(a.last + 1, b.first) }
        segs.forEach { assertTrue("$it longer than 7 s", it.count() <= 7 * rate) }
    }

    @Test fun shortClipIsOneSegment() {
        assertEquals(listOf(0 until 5 * rate), asrSegments(speech(5.0), rate))
        assertEquals(listOf(0 until 7 * rate), asrSegments(speech(7.0), rate))
    }

    @Test fun longClipsCoverEverythingInSevenSecondPieces() {
        for (s in listOf(7.01, 8.1, 8.8, 11.1, 15.6, 31.0)) {
            val pcm = speech(s)
            val segs = asrSegments(pcm, rate)
            assertTrue(segs.size >= 2)
            assertCovers(segs, pcm.size)
        }
    }

    @Test fun cutLandsInThePause() {
        val pcm = speech(9.0)
        for (i in (4.8 * rate).toInt() until (5.3 * rate).toInt()) pcm[i] = 0f // half-second pause at ~5 s
        val segs = asrSegments(pcm, rate)
        assertEquals(2, segs.size)
        assertTrue("cut at ${segs[1].first}", segs[1].first in (4.8 * rate).toInt() until (5.3 * rate).toInt())
        assertCovers(segs, pcm.size)
    }

    @Test fun trailingSilenceLeavesNoTinyTail() {
        val pcm = speech(7.5)
        for (i in (6.5 * rate).toInt() until pcm.size) pcm[i] = 0f // auto-mode clips end in silence
        val segs = asrSegments(pcm, rate)
        segs.forEach { assertTrue("$it shorter than 1 s", it.count() >= rate) }
        assertCovers(segs, pcm.size)
    }
}

package com.lampensoft.borderlinefluent

import com.lampensoft.borderlinefluent.HandGestures.Gesture
import com.lampensoft.borderlinefluent.HandGestures.Pt
import org.junit.Assert.*
import org.junit.Test

class HandGesturesTest {
    // An upright hand, wrist at the bottom (y grows downward), palm length 0.2 from wrist to middle MCP.
    private val wrist = Pt(0.5f, 0.8f)
    private val mcps = listOf(Pt(0.42f, 0.62f), Pt(0.5f, 0.6f), Pt(0.57f, 0.62f), Pt(0.63f, 0.65f)) // index..pinky

    /** A finger from its MCP: straight up, or folded back down toward the palm. */
    private fun finger(mcp: Pt, straight: Boolean) = if (straight)
        listOf(mcp, Pt(mcp.x, mcp.y - 0.09f), Pt(mcp.x, mcp.y - 0.15f), Pt(mcp.x, mcp.y - 0.2f))
        else listOf(mcp, Pt(mcp.x, mcp.y - 0.06f), Pt(mcp.x, mcp.y - 0.02f), Pt(mcp.x, mcp.y + 0.04f))
    /** A relaxed finger: bent halfway, tip neither clearly out nor folded. */
    private fun relaxed(mcp: Pt) = listOf(mcp, Pt(mcp.x, mcp.y - 0.07f), Pt(mcp.x + 0.02f, mcp.y - 0.1f), Pt(mcp.x + 0.04f, mcp.y - 0.1f))
    private val thumbOut = listOf(Pt(0.42f, 0.76f), Pt(0.36f, 0.72f), Pt(0.3f, 0.68f), Pt(0.25f, 0.64f))
    private val thumbTucked = listOf(Pt(0.44f, 0.76f), Pt(0.42f, 0.72f), Pt(0.43f, 0.68f), Pt(0.46f, 0.66f))

    private fun hand(thumb: List<Pt>, vararg straight: Boolean) =
        listOf(wrist) + thumb + mcps.indices.flatMap { finger(mcps[it], straight[it]) }

    @Test fun middleFinger() = assertEquals(Gesture.MIDDLE_FINGER, HandGestures.classify(hand(thumbTucked, false, true, false, false)))
    @Test fun openPalm() = assertEquals(Gesture.OPEN_PALM, HandGestures.classify(hand(thumbOut, true, true, true, true)))

    @Test fun shapeLineCarriesTheMeasurementsAndTheResult() {
        val shape = HandGestures.measure(hand(thumbOut, true, true, true, true), HandGestures.Box(0f, 0f, 0.1f, 0.1f))!!
        assertEquals(0f, shape.overlap, 0f); assertTrue(shape.whole); assertEquals(0.2f, shape.palm, 1e-4f)
        assertTrue(shape.line(), shape.line().startsWith("hands shape i=1.38 m=1.38 "))
        assertTrue(shape.line(), shape.line().endsWith(" whole=true palm=0.20 face=0.00 -> OPEN_PALM"))
        assertNull(HandGestures.measure(hand(thumbOut, true, true, true, true).take(15)))
    }

    @Test fun thumbsUp() {
        // A sideways fist (knuckles to the right of the wrist), thumb straight up above the index knuckle.
        val w = Pt(0.4f, 0.7f)
        val knuckles = listOf(Pt(0.55f, 0.55f), Pt(0.57f, 0.6f), Pt(0.57f, 0.65f), Pt(0.55f, 0.7f))
        val fingers = knuckles.flatMap { k -> listOf(k, Pt(k.x + 0.06f, k.y), Pt(k.x + 0.05f, k.y + 0.03f), Pt(k.x, k.y + 0.03f)) }
        val thumb = listOf(Pt(0.45f, 0.62f), Pt(0.5f, 0.52f), Pt(0.5f, 0.44f), Pt(0.5f, 0.37f))
        assertEquals(Gesture.THUMBS_UP, HandGestures.classify(listOf(w) + thumb + fingers))
    }

    @Test fun handOverFace() {
        val face = HandGestures.Box(0.3f, 0.4f, 0.75f, 0.9f)
        assertEquals(Gesture.HAND_OVER_FACE, HandGestures.classify(hand(thumbTucked, false, false, false, false), face))
        // The same fist far from the face is nothing.
        assertEquals(Gesture.NONE, HandGestures.classify(hand(thumbTucked, false, false, false, false), HandGestures.Box(0f, 0f, 0.1f, 0.1f)))
    }

    @Test fun negatives() {
        assertEquals("fist", Gesture.NONE, HandGestures.classify(hand(thumbTucked, false, false, false, false)))
        assertEquals("relaxed", Gesture.NONE, HandGestures.classify(listOf(wrist) + thumbTucked + mcps.flatMap(::relaxed)))
        // An open palm cut by the top edge: fingertips extrapolated above the frame.
        val cut = hand(thumbOut, true, true, true, true).map { Pt(it.x, it.y - 0.45f) }
        assertEquals("edge", Gesture.NONE, HandGestures.classify(cut))
        assertEquals("partial list", Gesture.NONE, HandGestures.classify(hand(thumbOut, true, true, true, true).take(15)))
    }

    @Test fun debouncerReportsOncePerHoldAndToleratesOneMissedFrame() {
        val d = HandGestures.Debouncer()
        assertNull(d.feed(Gesture.THUMBS_UP, 0))
        assertNull(d.feed(Gesture.THUMBS_UP, 200))
        assertNull(d.feed(Gesture.NONE, 400)) // a missed frame inside the grace keeps the hold
        assertEquals(600L, d.feed(Gesture.THUMBS_UP, 600))
        assertNull(d.feed(Gesture.THUMBS_UP, 800)) // once per hold
        assertNull(d.feed(Gesture.NONE, 1000)); assertNull(d.feed(Gesture.NONE, 1400)) // released past the grace
        assertNull(d.feed(Gesture.THUMBS_UP, 1600)); assertNull(d.feed(Gesture.THUMBS_UP, 2000))
        assertEquals(600L, d.feed(Gesture.THUMBS_UP, 2200)) // a new hold reports again
        assertNull(d.feed(Gesture.OPEN_PALM, 2400)); assertNull(d.feed(Gesture.OPEN_PALM, 2800)) // switching restarts
        assertEquals(600L, d.feed(Gesture.OPEN_PALM, 3000))
    }

    @Test fun shortFlashIsNotReported() {
        val d = HandGestures.Debouncer()
        assertNull(d.feed(Gesture.MIDDLE_FINGER, 0)); assertNull(d.feed(Gesture.MIDDLE_FINGER, 200))
        assertNull(d.feed(Gesture.NONE, 700)); assertNull(d.feed(Gesture.NONE, 900))
    }
}

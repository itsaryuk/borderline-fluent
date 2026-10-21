package com.lampensoft.borderlinefluent

import android.content.Context
import androidx.camera.core.ImageProxy
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Hand gestures from MediaPipe's 21 hand landmarks (wrist 0; thumb 1-4; index 5-8; middle 9-12; ring 13-16;
 * pinky 17-20), in normalized upright frame coordinates. Pure, so the shapes are unit-tested; the landmarker itself
 * runs only in debug builds (HandLandmarks.kt), as a measurement spike with no gameplay effect.
 */
internal object HandGestures {
    enum class Gesture { NONE, MIDDLE_FINGER, THUMBS_UP, OPEN_PALM, HAND_OVER_FACE }
    data class Pt(val x: Float, val y: Float)
    /** A box in normalized upright frame coordinates (0..1). */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val area get() = max(0f, right - left) * max(0f, bottom - top)
    }

    private fun d(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

    /**
     * Everything [classify] decides from, for the debug log. Finger ratios are wrist-to-tip over wrist-to-PIP (index,
     * middle, ring, pinky); [thumbOut] is d(thumb tip, index MCP) / palm; [thumbUp] is how vertical the thumb points
     * (1 = straight up); [overlap] is the hand/face box intersection over the smaller box (0 without a face).
     */
    data class Shape(val index: Float, val middle: Float, val ring: Float, val pinky: Float, val thumbOut: Float,
                     val thumbFar: Boolean, val thumbUp: Float, val tipAboveMcp: Boolean, val whole: Boolean,
                     val palm: Float, val overlap: Float) {
        /**
         * ponytail: 2D distance ratios only; a finger pointing at the camera foreshortens and reads as curled. Use the
         * landmarker's world landmarks (z) if the phone shows that as a miss.
         */
        val gesture: Gesture get() {
            // A hand cut by the frame edge: MediaPipe extrapolates the missing joints, so its shape is a guess.
            if (whole && palm > 0.03f) {
                // A straight finger reaches ~1.4x, a folded one comes back inside the PIP.
                fun extended(r: Float) = r > 1.2f
                fun curled(r: Float) = r < 1.0f
                val out = thumbOut > 0.7f && thumbFar
                when {
                    extended(middle) && curled(index) && curled(ring) && curled(pinky) -> return Gesture.MIDDLE_FINGER
                    curled(index) && curled(middle) && curled(ring) && curled(pinky) && out && thumbUp > 0.6f && tipAboveMcp ->
                        return Gesture.THUMBS_UP
                    extended(index) && extended(middle) && extended(ring) && extended(pinky) && out -> return Gesture.OPEN_PALM
                }
            }
            // ponytail: 30% of the smaller box; tune from the phone's false positives (a hand resting on the chin).
            return if (overlap > 0.3f) Gesture.HAND_OVER_FACE else Gesture.NONE
        }
        fun line() = String.format(Locale.ROOT,
            "hands shape i=%.2f m=%.2f r=%.2f p=%.2f thumbOut=%.2f thumbUp=%.2f tipAboveMcp=%b whole=%b palm=%.2f face=%.2f -> %s",
            index, middle, ring, pinky, thumbOut, thumbUp, tipAboveMcp, whole, palm, overlap, gesture)
    }

    /** The measurements [classify] uses; null unless all 21 landmarks are there. */
    fun measure(hand: List<Pt>, face: Box? = null): Shape? {
        if (hand.size != 21) return null
        val w = hand[0]
        val palm = d(w, hand[9])
        fun ratio(mcp: Int) = d(w, hand[mcp + 3]) / d(w, hand[mcp + 1]).coerceAtLeast(1e-6f)
        val overlap = if (face == null || face.area <= 0f) 0f else {
            val hb = Box(hand.minOf { it.x }.coerceIn(0f, 1f), hand.minOf { it.y }.coerceIn(0f, 1f),
                hand.maxOf { it.x }.coerceIn(0f, 1f), hand.maxOf { it.y }.coerceIn(0f, 1f))
            val o = Box(max(hb.left, face.left), max(hb.top, face.top), min(hb.right, face.right), min(hb.bottom, face.bottom)).area
            if (hb.area > 0f) o / min(hb.area, face.area) else 0f
        }
        return Shape(ratio(5), ratio(9), ratio(13), ratio(17), d(hand[4], hand[5]) / palm.coerceAtLeast(1e-6f),
            d(w, hand[4]) > d(w, hand[3]), (hand[2].y - hand[4].y) / d(hand[2], hand[4]).coerceAtLeast(1e-6f),
            hand[4].y < hand[5].y, hand.all { it.x in 0f..1f && it.y in 0f..1f }, palm, overlap)
    }

    fun classify(hand: List<Pt>, face: Box? = null): Gesture = measure(hand, face)?.gesture ?: Gesture.NONE

    /**
     * Reports a gesture once per hold, when it has lasted [holdMs]. A missed frame or two (NONE within [graceMs] of the
     * last match) keeps the hold; any other gesture restarts it.
     */
    class Debouncer(private val holdMs: Long = 500, private val graceMs: Long = 400) {
        private var current = Gesture.NONE
        private var since = 0L
        private var lastSeen = 0L
        private var reported = false
        /** How long [g] has been held, the first time that reaches [holdMs]; else null. */
        fun feed(g: Gesture, t: Long): Long? {
            if (g != current) {
                if (g == Gesture.NONE && t - lastSeen <= graceMs) return null
                current = g; since = t; lastSeen = t; reported = false
                return null
            }
            lastSeen = t
            if (g == Gesture.NONE || reported || t - since < holdMs) return null
            reported = true
            return t - since
        }
    }
}

/** The debug build's hand landmarker (src/debug HandLandmarks.kt). Release builds have none and never look for it. */
internal interface HandProbe {
    /** Called on the analyzer thread; reads [proxy] before returning and never blocks on inference. */
    fun offer(proxy: ImageProxy, face: HandGestures.Box?)
    fun close()

    companion object {
        /** By name, so the release build (no MediaPipe, no model) compiles without it. A failure is logged, never silent. */
        fun load(context: Context): HandProbe? = if (!BuildConfig.DEBUG) null else try {
            Class.forName("com.lampensoft.borderlinefluent.HandLandmarks").getConstructor(Context::class.java)
                .newInstance(context.applicationContext) as HandProbe
        } catch (e: Throwable) {
            val s = "hands probe failed to load: $e"
            android.util.Log.e(Game.TAG, s); appendDiagnostic(context.filesDir, s)
            null
        }
    }
}

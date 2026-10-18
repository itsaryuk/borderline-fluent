package com.lampensoft.borderlinefluent

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Software AGC for VOICE_RECOGNITION capture, which has none. One per listen. Fast attack: a frame whose peak would pass
 * [CEIL] drops the gain before it is applied, so a frame never clips (never below 1: loud raw input stays as it is).
 * Slow release: the gain climbs back toward [MAX] over ~1.5 s.
 */
class MicGain(var gain: Float = START) {
    var lo = gain; private set
    var hi = gain; private set

    /** Boosts [f] in place; returns it. */
    fun apply(f: FloatArray): FloatArray {
        var peak = 0f
        for (x in f) peak = max(peak, abs(x))
        // The release is capped by this frame's own peak too: rising past it alternated 0.73/0.70 and clipped big
        // hold-mode chunks.
        val rise = min(MAX, gain * RISE_PER_S.pow(f.size / 16000f))
        gain = if (peak > 0f) max(1f, min(rise, CEIL / peak)) else rise
        lo = min(lo, gain); hi = max(hi, gain)
        for (i in f.indices) f[i] *= gain
        return f
    }

    fun range() = "gain %.1f..%.1f".format(lo, hi)

    companion object {
        // Tuning knobs. Pixel, VOICE_RECOGNITION, arm's length: owner speech frame RMS 0.008-0.019, peak 0.02-0.04;
        // silence 0.0003-0.001 (up to 0.005 with noise); a loud speaker peaked 0.24. 8x lifts the quiet voice to
        // Silero's comfortable range; the ceiling leaves headroom so a loud speaker is turned down, not clipped.
        const val START = 4f
        const val MAX = 8f
        const val CEIL = 0.7f
        const val RISE_PER_S = 4f // 1 -> 8 in 1.5 s, 4 -> 8 in 0.5 s
    }
}

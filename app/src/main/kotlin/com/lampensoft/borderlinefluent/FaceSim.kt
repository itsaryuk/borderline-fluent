package com.lampensoft.borderlinefluent

import kotlin.math.PI
import kotlin.math.sin

/** Debug-only synthetic face for adb testing (see [Game] FACE receiver): a pure state + time -> [Sample]. */
internal enum class SimFace { NEUTRAL, BLINK, SMILE, SHAKE, NOD, AWAY, NOFACE;
    companion object { fun of(s: String?) = entries.firstOrNull { it.name.equals(s, true) } }
}

internal fun simSample(state: SimFace, t: Long): Sample {
    if (state == SimFace.NOFACE) return Sample(t, false)
    val n = sin(t / 97.0).toFloat() // tiny deterministic jitter
    val closed = state == SimFace.BLINK && t % 600 < 150
    val eyes = if (closed) .05f else .9f + n * .02f
    val w = 2 * PI * t / 1000
    return Sample(t, true,
        smile = if (state == SimFace.SMILE) .9f else .05f + n * .01f,
        eyesOpen = eyes,
        yaw = when (state) { SimFace.SHAKE -> (30 * sin(w * 2)).toFloat(); SimFace.AWAY -> 45f; else -> n },
        pitch = if (state == SimFace.NOD) (20 * sin(w * 2)).toFloat() else n,
        lips = .08f, brow = .10f, leftEye = eyes, rightEye = eyes)
}

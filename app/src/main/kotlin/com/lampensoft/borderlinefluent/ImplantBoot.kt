package com.lampensoft.borderlinefluent

/** Yaw change (degrees) that makes one leg of a head shake. Relative, so a face seen off-axis still works. */
internal const val SHAKE_SWING = 10f

/** A head shake is two direction reversals of at least [SHAKE_SWING] within three seconds (left-right-left, in
 * either order); turning away and back is only one. A single blurred frame mid-shake is skipped, but from the
 * first leg to the confirming reversal at least 60% of frames must hold a face, and 400 ms without one starts
 * over: a cluttered background flickers in false faces with jumping yaw. Only fresh, ordered frames count.
 */
internal class ImplantShakeDetector {
    private var last = Long.MIN_VALUE
    private var seen = Long.MIN_VALUE
    private var dir = 0
    private var hi = 0f
    private var lo = 0f
    private val turns = ArrayDeque<Long>() // the latest three direction changes; the oldest may be the first leg
    private val recent = ArrayDeque<Pair<Long, Boolean>>() // the last four seconds of frames: face or not

    fun add(at: Long, yaw: Float, face: Boolean = true): Boolean {
        if (at <= last) return false
        last = at
        val valid = face && yaw.isFinite()
        recent.addLast(at to valid)
        while (at - recent.first().first > 4000) recent.removeFirst()
        if (!valid) return false
        if (seen == Long.MIN_VALUE || at - seen > 400) { dir = 0; hi = yaw; lo = yaw; turns.clear() }
        seen = at
        hi = maxOf(hi, yaw); lo = minOf(lo, yaw)
        val turn = when {
            dir >= 0 && yaw <= hi - SHAKE_SWING -> -1
            dir <= 0 && yaw >= lo + SHAKE_SWING -> 1
            else -> return false
        }
        dir = turn
        if (turn > 0) hi = yaw else lo = yaw
        turns.addLast(at)
        if (turns.size > 3) turns.removeFirst()
        if (turns.size < 3 || at - turns[1] > 3000) return false
        val span = recent.filter { it.first >= turns[0] }
        return span.count { it.second } * 10 >= span.size * 6
    }
}

/** Linear acceleration (m/s², gravity removed) a shake excursion must pass, and the level that rearms it. */
internal const val SHAKE_ACCEL = 8.0
private const val SHAKE_REARM = 4.0
/** Low-pass time constant (ms) of the gravity estimate: slow tilts follow it, a 3-6 Hz shake does not. */
private const val GRAVITY_TAU = 500.0

/** Pure accelerometer transition: two excursions above [SHAKE_ACCEL], 60-1000 ms apart, are a shake.
 * Gravity is tracked per axis with a low-pass filter and subtracted, so a sideways shake (perpendicular to
 * gravity) counts as much as one along it. One sustained excursion counts once; a valley rearms it.
 * Timestamps are monotonic milliseconds; [level] is the latest linear magnitude, for diagnostics.
 */
internal data class PhoneShakeState(
    val last: Long = -1L, val gx: Double = 0.0, val gy: Double = 0.0, val gz: Double = 0.0,
    val primed: Boolean = false, val peak: Long? = null, val above: Boolean = false,
    val level: Double = 0.0, val detected: Boolean = false
)

internal fun phoneShake(state: PhoneShakeState, at: Long, x: Float, y: Float, z: Float): PhoneShakeState {
    if (at <= state.last) return state
    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return PhoneShakeState(last = at)
    // The first sample is taken as gravity: a phone at rest or in a hand, never mid-shake in practice.
    if (!state.primed) return PhoneShakeState(at, x.toDouble(), y.toDouble(), z.toDouble(), primed = true)
    val dx = x - state.gx; val dy = y - state.gy; val dz = z - state.gz
    val level = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    val k = (at - state.last) / (GRAVITY_TAU + (at - state.last))
    val previous = state.peak?.takeIf { at - it <= 1000 }
    val rising = level > SHAKE_ACCEL && !state.above
    val separate = previous == null || at - previous >= 60
    return PhoneShakeState(at, state.gx + k * dx, state.gy + k * dy, state.gz + k * dz, true,
        if (rising && separate) at else previous,
        if (state.above) level > SHAKE_REARM else level > SHAKE_ACCEL,
        level, rising && previous != null && separate)
}

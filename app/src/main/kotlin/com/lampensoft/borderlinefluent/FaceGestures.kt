package com.lampensoft.borderlinefluent

/** Ephemeral, deterministic double-blink recognizer. Times use the FaceWatch monotonic clock. */
/** The unlimited practice blink window gives up after 30 s so a face that never satisfies the thresholds cannot stall the interview. */
internal fun practiceExpired(start: Long, now: Long) = now - start > 30_000

internal class FaceGestures(private val started: Long, private val durationMs: Long = 3000) {
    @Volatile var count = 0; private set
    val complete get() = count == 2
    private var lastSample = started - 1
    private var acceptedAt = Long.MIN_VALUE
    private var baseline = 0f // the open level, latched: it only rises during the window
    private class Eyes(val t: Long, val min: Float, val max: Float)
    private val recent = ArrayDeque<Eyes>() // the last 1.5 s
    fun active(now: Long) = now >= started && now - started <= durationMs && !complete
    @Synchronized fun sample(s: Sample, now: Long): Boolean {
        if (!active(now) || s.t < started || s.t <= lastSample || s.t > now || now - s.t > 250) return false
        val gap = s.t - lastSample
        lastSample = s.t
        val left = s.leftEye; val right = s.rightEye
        // A tracking gap or a lost face clears the history, so the eyes must be seen open again before a closure
        // counts; the baseline stays, since the light has not changed and re-learning it would eat the window.
        if (gap > 350) recent.clear()
        if (!s.face || left == null || right == null || !left.isFinite() || !right.isFinite() ||
            left !in 0f..1f || right !in 0f..1f) { recent.clear(); return false }
        // A blink is an onset: the last clear frame was open (weaker eye >= shut + 0.05), this one is shut (both eyes
        // below min(0.3, baseline / 2)). Frames in between are neither, so the brief, mid-opening frames between
        // slow or repeated blinks (0.35-0.96, 2026-10-13) still separate two closures, and one long closure is one
        // blink. The baseline is the median of min(eyes) over 1.5 s (5 frames at least, so a few bright frames in a
        // mostly closed signal are not an open eye), latched; under 0.5 there is no open state and nothing counts.
        // In a dim room ML Kit scores open eyes 0.2-0.65 (medians 0.17 and 0.01): those players cannot blink-twice.
        // ponytail: no seed from before the window, so a blink in its first 5 frames is lost; looking down and up, or
        // a spontaneous blink, counts too (favours the player).
        recent.addLast(Eyes(s.t, minOf(left, right), maxOf(left, right)))
        while (s.t - recent.first().t > 1500) recent.removeFirst()
        if (recent.size >= 5) baseline = maxOf(baseline, recent.map { it.min }.sorted()[(recent.size - 1) / 2])
        if (baseline < .5f) return false
        val shut = minOf(.3f, baseline / 2); val open = shut + .0499f // not .05f: .3f + .05f rounds above .35f
        if (recent.last().max >= shut) return false
        val before = recent.asReversed().drop(1).firstOrNull { it.min >= open || it.max < shut } ?: return false
        return before.min >= open && accept(s.t)
    }
    private fun accept(now: Long): Boolean {
        if (acceptedAt != Long.MIN_VALUE && now - acceptedAt < 180) return false
        if (durationMs == Long.MAX_VALUE && acceptedAt != Long.MIN_VALUE && now - acceptedAt > 3000) count = 0
        acceptedAt = now; count++
        return true
    }
}

/** Only reduced, ephemeral signals; eye openness deliberately does not constrain relaxation. */
internal data class NeutralFace(val smile: Float, val brow: Float, val lips: Float) {
    fun relaxed(s: Sample) = valid(s) && kotlin.math.abs(s.yaw) <= 20 && kotlin.math.abs(s.pitch) <= 20 &&
        kotlin.math.abs(s.smile - smile) <= .25f && kotlin.math.abs(s.brow - brow) <= .025f &&
        kotlin.math.abs(s.lips - lips) <= .02f
    companion object {
        fun valid(s: Sample) = s.face && listOf(s.smile, s.brow, s.lips, s.yaw, s.pitch).all { it.isFinite() } &&
            s.smile in 0f..1f && s.brow > 0f && s.lips > 0f
    }
}

internal class ComposureHold(private val neutral: NeutralFace?) {
    @Volatile var elapsed = 0L; private set
    @Volatile var failed = false; private set
    val complete get() = elapsed >= 3000 && !failed
    val progress get() = (elapsed / 3000f).coerceIn(0f, 1f)
    private var last: Long? = null
    @Synchronized fun pause() { last = null }
    @Synchronized fun suspendHold() { last = null }
    @Synchronized fun update(s: Sample?, now: Long) {
        if (failed || complete) return
        if (neutral == null || s == null || !NeutralFace.valid(s) || s.t > now || now - s.t > 250) { pause(); return }
        if (last != null && s.t <= last!!) return
        if (!neutral.relaxed(s)) { failed = true; return }
        val time = s.t
        last?.let { if (time - it in 1..350) elapsed += time - it }
        last = time
    }
}

/** Neutral → brief expression → neutral, with continuity and a debounce on the expression. */
internal class DeskBluffGesture(private val neutral: NeutralFace?) {
    @Volatile var complete = false; private set
    @Volatile var expressive = false; private set
    private var last = Long.MIN_VALUE
    private var ready = false
    private var raisedAt: Long? = null
    @Synchronized fun pause() { ready = false; raisedAt = null; expressive = false; last = Long.MIN_VALUE }
    @Synchronized fun sample(s: Sample, now: Long) {
        if (complete || s.t <= last || s.t > now || now - s.t > 250) return
        if (last != Long.MIN_VALUE && s.t - last > 350 || !NeutralFace.valid(s)) {
            ready = false; raisedAt = null; expressive = false
        }
        last = s.t
        val n = neutral ?: return
        if (!NeutralFace.valid(s) || kotlin.math.abs(s.yaw) > 20 || kotlin.math.abs(s.pitch) > 20) {
            ready = false; raisedAt = null; expressive = false; return
        }
        val raised = s.brow - n.brow > .025f
        if (raised && ready && raisedAt == null) raisedAt = s.t
        expressive = raisedAt != null
        if (!raised && n.relaxed(s)) {
            raisedAt?.let { if (s.t - it in 100..1500) complete = true }
            raisedAt = null; expressive = false; ready = true
        } else if (raisedAt?.let { s.t - it > 1500 } == true) {
            raisedAt = null; expressive = false; ready = false
        }
    }
}

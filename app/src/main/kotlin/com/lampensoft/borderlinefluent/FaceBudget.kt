package com.lampensoft.borderlinefluent

import java.util.Locale

/** Serialized with FaceSession. Two counters per 5 s window; no frames, outcomes or geometry retained. */
internal class FaceBudget {
    data class Report(val detectorFps: Double, val publishFps: Double) {
        fun line() = String.format(Locale.ROOT, "scanner detector=%.1ffps publish=%.1ffps", detectorFps, publishFps)
    }
    private var start = 0L
    private var detections = 0
    private var publishes = 0
    fun reset(now: Long) { start = now; detections = 0; publishes = 0 }
    fun detected() { detections++ }
    fun published() { publishes++ }
    fun take(now: Long): Report? {
        if (now < start || now - start < 5000) return null
        val seconds = (now - start) / 1000.0
        return Report(detections / seconds, publishes / seconds).also { reset(now) }
    }
}

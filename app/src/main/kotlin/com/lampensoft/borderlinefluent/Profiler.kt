package com.lampensoft.borderlinefluent

import androidx.annotation.StringRes
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One camera frame, reduced to the face signals the android officers profile (decision 0008). Geometry is
 * normalised by face height so distance to the phone doesn't matter. Null fields: no face in that frame.
 */
data class Sample(
    val t: Long, // ms
    val face: Boolean,
    val smile: Float = 0f,
    val eyesOpen: Float = 1f, // the lower of the two eyes
    val yaw: Float = 0f, val pitch: Float = 0f,
    val lips: Float = 0f, // visible lip height / face height: drops when lips compress
    val brow: Float = 0f, // eyebrow-to-eye gap / face height: jumps in an eyebrow flash
)

/** A deviation from the traveler's own baseline, Six-Minute X-Ray style. [z] = how far, in baseline spreads. */
enum class Tell(
    @StringRes val label: Int, @StringRes val implant: Int, val officer: String, // officer: a note to the model, never shown
    @StringRes val debrief: Int, @StringRes val debriefAsk: Int, // plurals, see [debrief]
) {
    BLINKS(R.string.tell_blinks, R.string.implant_blinks, "Their blinking sped up when you asked that.", R.plurals.debrief_blinks, R.plurals.debrief_blinks_ask),
    SMILE(R.string.tell_smile, R.string.implant_smile, "They smiled at an odd moment.", R.plurals.debrief_smile, R.plurals.debrief_smile_ask),
    MASK_SMILE(R.string.tell_mask_smile, R.string.implant_mask_smile, "Their smile doesn't reach their eyes.", R.plurals.debrief_mask_smile, R.plurals.debrief_mask_smile_ask),
    LIPS(R.string.tell_lips, R.string.implant_lips, "They pressed their lips together, holding something back.", R.plurals.debrief_lips, R.plurals.debrief_lips_ask),
    BROWS(R.string.tell_brows, R.string.implant_brows, "Their eyebrows jumped at that question.", R.plurals.debrief_brows, R.plurals.debrief_brows_ask),
    GAZE(R.string.tell_gaze, R.string.implant_gaze, "They looked away while answering.", R.plurals.debrief_gaze, R.plurals.debrief_gaze_ask),
    HEAD_DOWN(R.string.tell_head_down, R.string.implant_head_down, "They dropped their head.", R.plurals.debrief_head_down, R.plurals.debrief_head_down_ask),
    FREEZE(R.string.tell_freeze, R.string.implant_freeze, "They went completely still.", R.plurals.debrief_freeze, R.plurals.debrief_freeze_ask),
}

/**
 * Baseline then deviation, the core of rapid behavior profiling: what counts is change from this person's own
 * normal during the relaxed opening (greeting, passport, small talk), not absolute values. Pure: unit-tested.
 *
 * The science is thin (DePaulo et al. 2003: nonverbal cues to deception have median d ≈ 0.10), which is the joke:
 * first-generation androids running a pop-psychology profiling module, and a player who learns to game it.
 * ponytail: every threshold is a guess; tune from the "tells" lines in results.log and the camera test screen.
 */
class Profiler {
    private data class Stat(val mean: Double, val sd: Double)
    private var base: Map<String, Stat>? = null
    private var baseBlinks = 12.0

    val calibrated get() = base != null

    /** Learns this traveler's normal from the relaxed opening. Needs a couple of seconds of face. */
    fun calibrate(s: List<Sample>): Boolean {
        val f = s.filter { it.face }
        if (f.size < 15) return false
        base = SIGNALS.mapValues { (_, get) -> f.map(get).let { v -> Stat(v.average(), sd(v)) } }
        baseBlinks = blinksPerMin(s).coerceIn(6.0, 30.0)
        return true
    }

    /** Tells in [s] (an answer, or the last couple of seconds for live implant warnings), strongest first. */
    fun tells(s: List<Sample>): List<Pair<Tell, Double>> {
        val b = base ?: return emptyList()
        val f = s.filter { it.face }
        if (f.size < 5) return emptyList()
        fun z(key: String): Double { val st = b.getValue(key); return (f.map(SIGNALS.getValue(key)).average() - st.mean) / max(st.sd, FLOOR.getValue(key)) }
        val out = mutableListOf<Pair<Tell, Double>>()
        // An answer lasts seconds, so one extra blink already looks like a doubled rate: also demand 2+ blinks
        // beyond what the baseline rate predicts for this window.
        val minutes = (f.last().t - f.first().t) / 60_000.0
        val blinkZ = (blinksPerMin(s) - baseBlinks) / max(4.0, baseBlinks * 0.4)
        if (blinkZ > 1.5 && blinks(s) - baseBlinks * minutes >= 2) out += Tell.BLINKS to blinkZ
        val smileZ = z("smile")
        if (smileZ > 2.0 && f.map { it.smile }.average() > 0.5) {
            // Duchenne check: a real smile narrows the eyes (cheeks rise); a mouth-only smile leaves them wide open.
            out += (if (z("eyes") > -0.5) Tell.MASK_SMILE else Tell.SMILE) to smileZ
        }
        z("lips").let { if (it < -2.0) out += Tell.LIPS to -it }
        // A flash is a jump, not a level: compare the peak, so a raised-brows baseline doesn't hide it.
        val browPeak = (f.maxOf { it.brow } - b.getValue("brow").mean) / max(b.getValue("brow").sd, FLOOR.getValue("brow"))
        if (browPeak > 3.0) out += Tell.BROWS to browPeak
        val away = f.count { abs(it.yaw) > 20 || abs(it.pitch) > 20 }.toDouble() / f.size
        if (away > 0.35) out += Tell.GAZE to away * 4
        z("pitch").let { if (it < -2.0 && f.map { s -> s.pitch }.average() < -10) out += Tell.HEAD_DOWN to -it }
        val motion = sd(f.map { it.yaw.toDouble() }) + sd(f.map { it.pitch.toDouble() })
        if (f.size >= 20 && motion < 0.4) out += Tell.FREEZE to 2.0
        return out.sortedByDescending { it.second }
    }

    companion object {
        private val SIGNALS: Map<String, (Sample) -> Double> = mapOf(
            "smile" to { it.smile.toDouble() }, "eyes" to { it.eyesOpen.toDouble() },
            "lips" to { it.lips.toDouble() }, "brow" to { it.brow.toDouble() },
            "yaw" to { it.yaw.toDouble() }, "pitch" to { it.pitch.toDouble() },
        )
        // Minimum spreads, so a perfectly still baseline doesn't turn sensor noise into tells.
        private val FLOOR = mapOf("smile" to 0.08, "eyes" to 0.08, "lips" to 0.006, "brow" to 0.006, "yaw" to 4.0, "pitch" to 4.0)

        private fun sd(v: List<Double>): Double { val m = v.average(); return sqrt(v.sumOf { (it - m) * (it - m) } / max(1, v.size - 1)) }

        /** Closed-eye onsets in [s]; eyes count as closed under 0.3. */
        fun blinks(s: List<Sample>): Int {
            var n = 0; var closed = false
            for (x in s) if (x.face) { val c = x.eyesOpen < 0.3f; if (c && !closed) n++; closed = c }
            return n
        }

        /** Blinks per minute over the span of [s]. */
        fun blinksPerMin(s: List<Sample>): Double {
            val f = s.filter { it.face }
            if (f.size < 2) return 0.0
            val min = (f.last().t - f.first().t) / 60_000.0
            return if (min > 0.05) blinks(f) / min else 0.0
        }
    }
}

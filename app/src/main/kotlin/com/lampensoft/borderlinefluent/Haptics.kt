package com.lampensoft.borderlinefluent

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

internal enum class HapticEvent { OVERLOAD_TICK, STAMP_PASS, STAMP_DETAIN, SCAN, IMPLANT_GLITCH, IMPLANT_STUTTER, BREAK }
internal enum class HapticPrimitive { THUD, CLICK, RISE, FALL, TICK }
internal data class EnvelopePoint(val intensity: Float, val sharpness: Float, val ms: Long)
internal data class PrimitivePoint(val primitive: HapticPrimitive, val scale: Float, val delay: Int = 0)
internal data class HapticSpec(val envelope: List<EnvelopePoint>, val composition: List<PrimitivePoint>, val doubleClick: Boolean = false)

/** Authored data only: no Android calls, clock or game state. Durations are transition times. */
internal fun hapticSpec(event: HapticEvent): HapticSpec {
    fun p(i: Float, s: Float, ms: Long) = EnvelopePoint(i, s, ms)
    fun c(k: HapticPrimitive, scale: Float, delay: Int = 0) = PrimitivePoint(k, scale, delay)
    return when (event) {
        HapticEvent.OVERLOAD_TICK -> HapticSpec(listOf(p(.25f, .5f, 20), p(0f, .5f, 20)),
            listOf(c(HapticPrimitive.TICK, .25f)))
        HapticEvent.STAMP_PASS, HapticEvent.STAMP_DETAIN -> {
            val detain = event == HapticEvent.STAMP_DETAIN
            HapticSpec(listOf(p(if (detain) 1f else .85f, .15f, 20), p(0f, .1f, if (detain) 100 else 70)),
                listOf(c(HapticPrimitive.THUD, if (detain) 1f else .85f)))
        }
        // Half strength (owner: on a table the old .65 buzz read as a loud click next to the quieter scan beep).
        HapticEvent.SCAN -> HapticSpec(listOf(p(.1f, .1f, 40), p(.32f, .8f, 220), p(0f, .8f, 40)),
            listOf(c(HapticPrimitive.RISE, .3f)))
        HapticEvent.IMPLANT_GLITCH, HapticEvent.IMPLANT_STUTTER -> {
            val scale = if (event == HapticEvent.IMPLANT_STUTTER) .8f else .4f
            HapticSpec(listOf(p(scale, .8f, 20), p(0f, .8f, 20), p(0f, .8f, 40),
                p(scale * .6f, .5f, 20), p(0f, .5f, 20), p(0f, .5f, 80), p(scale, .9f, 20), p(0f, .9f, 20)),
                listOf(c(HapticPrimitive.TICK, scale), c(HapticPrimitive.TICK, scale * .6f, 40), c(HapticPrimitive.TICK, scale, 80)), true)
        }
        HapticEvent.BREAK -> HapticSpec(listOf(p(1f, .6f, 20), p(.7f, .3f, 120), p(.3f, .1f, 200), p(0f, .1f, 260)),
            listOf(c(HapticPrimitive.THUD, 1f), c(HapticPrimitive.FALL, .8f, 30), c(HapticPrimitive.FALL, .3f, 80)))
    }
}

/** Both implant strengths share one monotonic cooldown; rejected events do not extend it. */
internal class HapticRateLimiter {
    private var glitchAt: Long? = null
    @Synchronized fun allow(event: HapticEvent, now: Long): Boolean {
        if (event != HapticEvent.IMPLANT_GLITCH && event != HapticEvent.IMPLANT_STUTTER) return true
        val last = glitchAt
        if (last != null && now - last < 2000) return false
        glitchAt = now
        return true
    }
}

/** Pure capture gate; its owner serializes requests, capture transitions and cancellation. */
internal class HapticCaptureGate {
    private var capturing = false
    private val pending = mutableListOf<() -> Unit>()
    fun defer(action: () -> Unit): Boolean {
        if (!capturing) return false
        pending += action
        return true
    }
    fun start() { capturing = true }
    fun end(): List<() -> Unit> {
        capturing = false
        return pending.toList().also { pending.clear() }
    }
    fun cancel() { pending.clear() }
}

/** Calls and lifecycle transitions share a lock: a late worker cannot vibrate after pause cancels. */
internal class Haptics(private val ctx: Context, private val enabled: () -> Boolean, private val dbg: (String) -> Unit) {
    private val vibrator = ctx.getSystemService(Vibrator::class.java)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val limiter = HapticRateLimiter()
    private val captureGate = HapticCaptureGate()
    private var foreground = false
    private var busyUntil = 0L
    @Synchronized fun foreground(on: Boolean) { foreground = on; if (!on) cancel() }
    @Synchronized fun cancel() { captureGate.cancel(); vibrator?.cancel(); busyUntil = 0 }
    @Synchronized fun capture(on: Boolean) {
        if (on) {
            captureGate.start()
            vibrator?.cancel() // stop an effect that started before the recorder opened
            busyUntil = 0
        } else captureGate.end().forEach { it() }
    }
    private fun allowed() = foreground && enabled() &&
        Settings.System.getInt(ctx.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0 &&
        audio?.ringerMode != AudioManager.RINGER_MODE_SILENT

    @Synchronized fun result(view: View, passed: Boolean) {
        if (!allowed()) return
        if (captureGate.defer { result(view, passed) }) return
        if (SystemClock.elapsedRealtime() < busyUntil) return
        if (view.performHapticFeedback(if (passed) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT))
            dbg("haptic RESULT_${if (passed) "CONFIRM" else "REJECT"} tier=predefined (view)")
    }

    @Synchronized fun play(event: HapticEvent) {
        if (!allowed()) return
        if (captureGate.defer { play(event) }) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val now = SystemClock.elapsedRealtime()
        if (!limiter.allow(event, now)) return
        val spec = hapticSpec(event)
        try {
            var tier = "envelope"
            val effect = envelope(v, spec) ?: run {
                tier = "composition"
                val ids = spec.composition.map { primitiveId(it.primitive) }.toIntArray()
                if (v.areAllPrimitivesSupported(*ids)) VibrationEffect.startComposition().apply {
                    spec.composition.forEach { addPrimitive(primitiveId(it.primitive), it.scale, it.delay) }
                }.compose() else {
                    tier = "predefined"
                    VibrationEffect.createPredefined(if (event == HapticEvent.OVERLOAD_TICK) VibrationEffect.EFFECT_TICK
                        else if (spec.doubleClick) VibrationEffect.EFFECT_DOUBLE_CLICK else VibrationEffect.EFFECT_HEAVY_CLICK)
                }
            }
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            // Conservative guard for result feedback; the authored result transition waits 2800 ms.
            busyUntil = now + 1000
            dbg("haptic $event tier=$tier")
        } catch (e: RuntimeException) { dbg("haptic $event unavailable: ${e.javaClass.simpleName}") }
    }

    private fun envelope(v: Vibrator, spec: HapticSpec): VibrationEffect? {
        if (Build.VERSION.SDK_INT < 36 || !v.areEnvelopeEffectsSupported()) return null
        val info = v.envelopeEffectInfo
        if (spec.envelope.size > info.maxSize || spec.envelope.sumOf { it.ms } > info.maxDurationMillis ||
            spec.envelope.any { it.ms !in info.minControlPointDurationMillis..info.maxControlPointDurationMillis }) return null
        return VibrationEffect.BasicEnvelopeBuilder().apply {
            setInitialSharpness(spec.envelope.first().sharpness)
            spec.envelope.forEach { addControlPoint(it.intensity, it.sharpness, it.ms) }
        }.build()
    }

    private fun primitiveId(p: HapticPrimitive) = when (p) {
        HapticPrimitive.THUD -> VibrationEffect.Composition.PRIMITIVE_THUD
        HapticPrimitive.CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
        HapticPrimitive.RISE -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
        HapticPrimitive.FALL -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
        HapticPrimitive.TICK -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
    }
}

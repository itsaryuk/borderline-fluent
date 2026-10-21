package com.lampensoft.borderlinefluent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only measurement spike: MediaPipe hand landmarks on the interview's camera frames, ~5 fps, logged to
 * results.log (as dbg() does in a debug build) and never acted on. CPU delegate only: on Tensor G6 the GPU decodes
 * garbage and the NPU hangs. Loaded by name from [HandProbe.load]; release builds have neither this class
 * nor the model.
 *
 * Model: src/debug/assets/hand_landmarker.task, float16 v1, 7 819 105 bytes,
 * https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task
 * sha256 fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1
 */
internal class HandLandmarks(private val context: Context) : HandProbe {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "hands").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private val skipped = AtomicInteger()
    @Volatile private var closed = false
    private var offeredAt = 0L // analyzer thread
    // Hands thread only from here.
    private var landmarker: HandLandmarker? = null
    private var failed = false
    private val debounce = HandGestures.Debouncer()
    private var windowAt = 0L
    private var lastAt = 0L
    private var shapeAt = 0L
    private var frames = 0
    private var inferSum = 0L
    private var inferMax = 0L
    private var convertSum = 0L

    override fun offer(proxy: ImageProxy, face: HandGestures.Box?) {
        val at = SystemClock.elapsedRealtime()
        if (closed || failed || at - offeredAt < 200) return
        // Inference still running: skip this frame rather than queue it behind.
        if (!busy.compareAndSet(false, true)) { skipped.incrementAndGet(); return }
        offeredAt = at
        val t0 = SystemClock.elapsedRealtimeNanos()
        val frame = try { upright(proxy) } catch (_: Exception) { busy.set(false); return }
        val convertUs = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
        // The frame is mirrored like a selfie (MediaPipe's handedness assumes that), so the face box is too.
        val mirroredFace = face?.let { HandGestures.Box(1 - it.right, it.top, 1 - it.left, it.bottom) }
        try {
            thread.execute { try { infer(frame, mirroredFace, at, convertUs) } finally { frame.recycle(); busy.set(false) } }
        } catch (_: RejectedExecutionException) { frame.recycle(); busy.set(false) }
    }

    private fun upright(proxy: ImageProxy): Bitmap {
        val src = proxy.toBitmap()
        val m = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()); postScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false).also { if (it !== src) src.recycle() }
    }

    private fun load(): HandLandmarker? {
        if (failed || closed) return null
        val t0 = SystemClock.elapsedRealtime()
        return try {
            HandLandmarker.createFromOptions(context, HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(1) // ponytail: one hand halves the landmark cost; 2 if two-handed gestures matter
                .build()).also { landmarker = it; log("hands loaded in ${SystemClock.elapsedRealtime() - t0}ms (cpu)") }
        } catch (e: Throwable) { failed = true; log("hands load failed: $e"); null }
    }

    private fun infer(frame: Bitmap, face: HandGestures.Box?, at: Long, convertUs: Long) {
        val lm = landmarker ?: load() ?: return
        val t0 = SystemClock.elapsedRealtime()
        val result = try { lm.detectForVideo(BitmapImageBuilder(frame).build(), at) }
            catch (e: Exception) { log("hands detect failed: $e"); return }
        val ms = SystemClock.elapsedRealtime() - t0
        // A gap (between desks, or the camera off) starts a new window, so its fps stays the desk's.
        if (at - lastAt > 1000) { windowAt = at; frames = 0; inferSum = 0; inferMax = 0; convertSum = 0 }
        lastAt = at
        frames++; inferSum += ms; inferMax = maxOf(inferMax, ms); convertSum += convertUs
        val hand = result.landmarks().firstOrNull()
        val side = result.handedness().firstOrNull()?.firstOrNull()
        val shape = hand?.let { h -> HandGestures.measure(h.map { HandGestures.Pt(it.x(), it.y()) }, face) }
        val gesture = shape?.gesture ?: HandGestures.Gesture.NONE
        // Diagnostics for tuning the thresholds: at most one shape line a second while a hand is in view.
        if (shape != null && at - shapeAt >= 1000) { shapeAt = at; log(shape.line()) }
        debounce.feed(gesture, at)?.let { held ->
            log(String.format(Locale.ROOT, "hands gesture=%s held=%dms conf=%.2f hand=%s infer=%dms",
                gesture, held, side?.score() ?: 0f, side?.categoryName() ?: "?", ms))
        }
        val window = at - windowAt
        if (window >= 10_000) {
            val thermal = context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: -1
            log(String.format(Locale.ROOT, "hands frames=%d %.1ffps infer avg=%.1fms max=%dms convert avg=%.1fms busySkips=%d thermal=%d",
                frames, frames * 1000.0 / window, inferSum.toDouble() / frames, inferMax, convertSum / 1000.0 / frames,
                skipped.getAndSet(0), thermal))
            windowAt = at; frames = 0; inferSum = 0; inferMax = 0; convertSum = 0
        }
    }

    private fun log(s: String) { Log.i(Game.TAG, s); appendDiagnostic(context.filesDir, s) }

    override fun close() {
        closed = true
        try { thread.execute { runCatching { landmarker?.close() }; landmarker = null } } catch (_: RejectedExecutionException) {}
        thread.shutdown()
    }
}

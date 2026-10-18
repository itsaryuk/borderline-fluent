package com.lampensoft.borderlinefluent

import android.graphics.Bitmap
import androidx.compose.runtime.mutableFloatStateOf
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/** Pure envelope math. Times are milliseconds; the release constant is one e-fold, not a hard cutoff. */
internal object LipEnvelope {
    const val ATTACK_MS = 20f
    const val RELEASE_MS = 80f
    const val WINDOW_MS = 20
    const val FULL_RMS = 0.18f
    fun smooth(previous: Float, target: Float, elapsedMs: Float): Float {
        val t = target.coerceIn(0f, 1f)
        val tau = if (t > previous) ATTACK_MS else RELEASE_MS
        return (t + (previous - t) * exp(-elapsedMs.coerceAtLeast(0f) / tau)).coerceIn(0f, 1f)
    }
    fun rms(samples: FloatArray, start: Int, end: Int): Float {
        if (end <= start) return 0f
        var sum = 0.0
        for (i in start until end) sum += samples[i].toDouble() * samples[i]
        return (sqrt(sum / (end - start)).toFloat() / FULL_RMS).coerceIn(0f, 1f)
    }
}

internal fun systemMouthDurationMs(textLength: Int): Long = (400L + 90L * textLength.coerceAtLeast(0)).coerceAtMost(30_000L)

/** One draw-observed state per game. Tokens reject callbacks from cancelled/replaced utterances. */
internal class OfficerMouth {
    val open = mutableFloatStateOf(0f)
    private var generation = 0L
    private var synthetic: Job? = null
    @Synchronized fun stop() { generation++; synthetic?.cancel(); synthetic = null; open.floatValue = 0f }
    @Synchronized fun begin(): Long { stop(); return generation }
    @Synchronized fun level(token: Long, value: Float) {
        if (token == generation) open.floatValue = value.coerceIn(0f, 1f)
    }
    @Synchronized fun end(token: Long) { if (token == generation) stop() }
    @Synchronized fun systemStart(token: Long, scope: CoroutineScope, textLength: Int) {
        if (token != generation || synthetic != null) return
        synthetic = scope.launch {
            val start = System.nanoTime()
            val durationNs = systemMouthDurationMs(textLength) * 1_000_000L
            var last = start
            var cycle = -1L
            var amplitude = 0.7f
            var value = 0f
            while (isActive) {
                val now = System.nanoTime()
                if (now - start >= durationNs) break
                val phase = (now - start) / 1_000_000_000.0 * 5.0
                if (phase.toLong() != cycle) { cycle = phase.toLong(); amplitude = Random.nextFloat() * 0.5f + 0.45f }
                val target = (0.5 - 0.5 * kotlin.math.cos(phase * 2 * PI)).toFloat() * amplitude
                value = LipEnvelope.smooth(value, target, (now - last) / 1_000_000f)
                level(token, value)
                last = now
                delay(16)
            }
            end(token)
        }
    }
}

/** Tile-pixel geometry; the displacement has compact support and reaches zero at cheeks and jaw. */
internal data class LipRegion(
    val cx: Float, val mouthY: Float, val lowerY: Float, val halfWidth: Float,
    val jawY: Float, val maxOpen: Float,
) {
    fun displacement(x: Float, y: Float, open: Float): Float {
        if (open <= 0f || y <= mouthY || y >= jawY || abs(x - cx) >= halfWidth) return 0f
        fun smooth(t: Float) = t * t * (3f - 2f * t)
        val horizontal = 1f - smooth(abs(x - cx) / halfWidth)
        val vertical = if (y < lowerY) smooth((y - mouthY) / (lowerY - mouthY))
            else 1f - smooth((y - lowerY) / (jawY - lowerY))
        return open.coerceIn(0f, 1f) * maxOpen * horizontal * vertical
    }
}

internal object OfficerLips {
    const val MAX_OPEN_FRACTION = 0.04f
    const val MESH = 48
    private val mutex = Mutex()
    // Includes failures. Bounded to eight officers; no bitmaps or detector clients retained.
    private val cache = object : LinkedHashMap<String, LipRegion?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LipRegion?>?) = size > 64
    }
    suspend fun region(key: String, bitmap: Bitmap): LipRegion? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (cache.containsKey(key)) return@withLock cache[key]
            val result = runCatching {
                val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL).build())
                try {
                    val face = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
                        .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return@runCatching null
                    val upper = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points.orEmpty()
                    val lower = face.getContour(FaceContour.LOWER_LIP_TOP)?.points.orEmpty()
                    val bottom = face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points.orEmpty()
                    val oval = face.getContour(FaceContour.FACE)?.points.orEmpty()
                    if (upper.isEmpty() || lower.isEmpty() || bottom.isEmpty() || oval.isEmpty()) return@runCatching null
                    val left = (upper + lower + bottom).minOf { it.x }
                    val right = (upper + lower + bottom).maxOf { it.x }
                    val mouthY = upper.map { it.y }.average().toFloat()
                    val lowerY = maxOf(lower.map { it.y }.average().toFloat(), bottom.map { it.y }.average().toFloat())
                    val jawY = oval.maxOf { it.y }.coerceAtMost(bitmap.height.toFloat())
                    if (right <= left || lowerY <= mouthY || jawY <= lowerY) return@runCatching null
                    LipRegion((left + right) / 2f, mouthY, lowerY, (right - left) * 0.7f,
                        jawY, face.boundingBox.height() * MAX_OPEN_FRACTION)
                } finally { detector.close() }
            }.getOrNull()
            cache[key] = result
            result
        }
    }
}

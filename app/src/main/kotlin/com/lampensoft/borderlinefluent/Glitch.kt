package com.lampensoft.borderlinefluent

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.random.Random
import kotlinx.coroutines.delay

/** One frame of a glitch burst, in fractions of the element's size. */
private class GlitchFrame(val split: Float, val shift: Float, val tears: List<Triple<Float, Float, Float>>) {
    companion object {
        fun random(strength: Float) = GlitchFrame(
            split = (0.004f + Random.nextFloat() * 0.016f) * strength * if (Random.nextBoolean()) 1 else -1,
            shift = (Random.nextFloat() - 0.5f) * 0.02f * strength,
            tears = List(Random.nextInt(0, 4)) { // top, height, sideways offset
                Triple(Random.nextFloat(), 0.02f + Random.nextFloat() * 0.1f, (Random.nextFloat() - 0.5f) * 0.12f * strength)
            },
        )
    }
}

/**
 * Digital glitch: quiet for a random [idleMs], then a burst of a few 40-90 ms frames with RGB fringes,
 * horizontal tearing and a sideways jolt. Pass a short idle for something that is visibly breaking.
 */
fun Modifier.glitch(idleMs: LongRange = 1500L..4000L, strength: Float = 1f): Modifier =
    glitchWhen(strength, 3..7, 40L..90L) { delay(Random.nextLong(idleMs.first, idleMs.last + 1)) }

// --- Menu beat sync: tune these by ear -------------------------------------------------------------
// ponytail: tuned by ear; measured phase ≈ 60 ms, measured tempo ≈ 130 vs the owner's 120 — adjust if the glitch drifts off the beat.
const val MENU_BPM = 120.0
const val MENU_BEAT_OFFSET_MS = 60L

/** ms from [posMs] to the next beat that is a multiple of [everyBeats] (counted from the track start, so 4/8 land on bars). Always > 0. */
fun msToNextGlitch(posMs: Long, offsetMs: Long, beatMs: Long, everyBeats: Int): Long {
    val k = Math.floorDiv(posMs - offsetMs, beatMs)           // beat we are in (or just passed)
    val next = (Math.floorDiv(k, everyBeats.toLong()) + 1) * everyBeats
    return next * beatMs + offsetMs - posMs
}

/**
 * [glitch] that fires only on the music's beats: every 2, 4 or 8 beats (random, bar-aligned), burst under one beat.
 * [position] is the playing track's position in ms (looping wraps are fine: the next beat is recomputed from it each time);
 * when it is null (sound off) it falls back to random idle gaps.
 */
fun Modifier.glitchOnBeat(
    position: () -> Long?, beatMs: Long = (60000 / MENU_BPM).toLong(), offsetMs: Long = MENU_BEAT_OFFSET_MS,
    everyBeats: List<Int> = listOf(4, 8, 16), strength: Float = 1f,
): Modifier = glitchWhen(strength, 3..4, 40L..80L) { // 4 x 80 = 320 ms < a 500 ms beat
    val p = position()
    if (p == null) delay(Random.nextLong(3000, 8000)) else delay(msToNextGlitch(p, offsetMs, beatMs, everyBeats.random()))
}

private fun Modifier.glitchWhen(strength: Float, frames: IntRange, frameMs: LongRange, wait: suspend () -> Unit): Modifier = composed {
    var frame by remember { mutableStateOf<GlitchFrame?>(null) }
    LaunchedEffect(strength) {
        while (true) {
            frame = null
            wait()
            repeat(Random.nextInt(frames.first, frames.last + 1)) { frame = GlitchFrame.random(strength); delay(Random.nextLong(frameMs.first, frameMs.last + 1)) }
        }
    }
    drawWithContent {
        val f = frame ?: return@drawWithContent drawContent()
        val w = size.width; val h = size.height
        translate(f.shift * w) { this@drawWithContent.drawContent() }
        // Colour fringes: the same content tinted red and cyan, screened on top a little to each side.
        for ((tint, dx) in listOf(Color.Red to -f.split, Color.Cyan to f.split)) {
            drawIntoCanvas {
                it.saveLayer(Rect(Offset.Zero, size), Paint().apply {
                    colorFilter = ColorFilter.tint(tint, BlendMode.Modulate); blendMode = BlendMode.Screen; alpha = 0.6f
                })
            }
            translate((f.shift + dx) * w) { this@drawWithContent.drawContent() }
            drawIntoCanvas { it.restore() }
        }
        // Tears: a band blanked and redrawn shifted sideways.
        for ((top, height, dx) in f.tears) clipRect(0f, top * h, w, (top + height) * h) {
            drawRect(Color(0xFF05080D))
            translate(dx * w) { this@drawWithContent.drawContent() }
        }
    }
}

/** CRT scanlines over the content. */
fun Modifier.scanlines(alpha: Float = 0.12f): Modifier = drawWithContent {
    drawContent()
    var y = 0f
    while (y < size.height) { drawLine(Color.Black.copy(alpha = alpha), Offset(0f, y), Offset(size.width, y), 1f); y += 4f }
}

/** [s] with some letters swapped for block glyphs; line breaks kept so the layout doesn't jump. */
fun corrupt(s: String, rate: Float = 0.25f) =
    s.map { if (it != '\n' && it != ' ' && Random.nextFloat() < rate) "█▓▒░#@%01/\\"[Random.nextInt(11)] else it }.joinToString("")

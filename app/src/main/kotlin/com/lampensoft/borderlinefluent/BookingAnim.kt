package com.lampensoft.borderlinefluent

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

/** Slot-machine frames for one itinerary field: up to [n] distinct decoys from [pool] (never [real]), then [real] last. */
internal fun decoyCycle(real: String, pool: Collection<String>, n: Int, rnd: Random): List<String> =
    pool.filter { it != real }.distinct().shuffled(rnd).take(n) + real

/** A diagonal holographic sheen sweeping across the content once as [progress] goes 0..1; nothing drawn outside (0,1). */
internal fun Modifier.hologram(progress: () -> Float): Modifier = drawWithContent {
    drawContent()
    val p = progress()
    if (p <= 0f || p >= 1f) return@drawWithContent
    val x = -size.width * .6f + p * size.width * 2.2f
    drawRect(Brush.linearGradient(
        listOf(Color.Transparent, Color(0x5500E5FF), Color(0x66FF4DFF), Color(0x55FFE94D), Color.Transparent),
        start = Offset(x, 0f), end = Offset(x + size.width * .5f, size.height * .5f)))
}

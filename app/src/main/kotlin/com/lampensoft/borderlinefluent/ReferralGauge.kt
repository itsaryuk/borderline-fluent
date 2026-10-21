package com.lampensoft.borderlinefluent

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

fun referralGaugeVisible(act: Act, inRoom: Boolean) = act == Act.FREE && !inRoom

/** Refusal approaches at suspicion 1 or patience 0; this is a warning, not the referral probability. */
fun referralRefusalRisk(suspicion: Double, patience: Double) =
    maxOf(suspicion, 1.0 - patience).coerceIn(0.0, 1.0).toFloat()

private data class ReferralReading(val meter: Double, val risk: Float, val badAnswers: Int)

/** Read-only HUD: sampling the non-Compose mind never changes the decision. */
@Composable internal fun ReferralGauge(mind: OfficerMind, bluffReason: Boolean = false, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var reading by remember(mind) { mutableStateOf(ReferralReading(mind.meter, referralRefusalRisk(mind.suspicion, mind.patience), mind.badAnswers)) }
    LaunchedEffect(mind) {
        while (true) {
            reading = ReferralReading(mind.meter, referralRefusalRisk(mind.suspicion, mind.patience), mind.badAnswers)
            delay(400)
        }
    }
    val percent = (reading.meter.coerceIn(0.0, 1.0) * 100).roundToInt()
    val interest = stringResource(R.string.referral_interest)
    val danger = stringResource(R.string.referral_danger)
    val readingLabel = stringResource(R.string.referral_readings, percent, (REFER_AT * 100).roundToInt(),
        (reading.risk * 100).roundToInt(), reading.badAnswers.coerceIn(0, REFUSAL_BAD_ANSWERS))
    val label = readingLabel + if (bluffReason) " · " + stringResource(R.string.bluff_reason) else ""
    val cyan = Color(0xFF8FE3FF)
    val red = Color(0xFFF27979)
    // Two slim vertical gauges over the portrait's edges: Interest left, Danger right (both fill bottom to top).
    Box(modifier.semantics(mergeDescendants = true) { contentDescription = label }) {
        Column(Modifier.align(Alignment.TopStart).fillMaxHeight(0.55f).vGaugeBacking(),
            horizontalAlignment = Alignment.CenterHorizontally) {
            LabeledVBar(interest, cyan, reading.meter.toFloat(), REFER_AT.toFloat(), Modifier.weight(1f))
        }
        Column(Modifier.align(Alignment.TopEnd).fillMaxHeight(0.55f).vGaugeBacking(),
            horizontalAlignment = Alignment.CenterHorizontally) {
            LabeledVBar(danger, red, reading.risk, null, Modifier.weight(1f))
            Canvas(Modifier.padding(top = 3.dp).size(12.dp, 39.dp)) {
                val pips = reading.badAnswers.coerceIn(0, REFUSAL_BAD_ANSWERS)
                repeat(REFUSAL_BAD_ANSWERS) { i ->
                    drawCircle(if (i < pips) red else Color(0xFF25344C), 5.dp.toPx(), Offset(size.width / 2, 5.dp.toPx() + 13.dp.toPx() * i))
                }
            }
        }
    }
}

/**
 * A vertical gauge bar with its [label] drawn inside it, rotated to read bottom to top (never truncated: the bar is as
 * wide as the text is tall). White with a dark halo so it reads over both the fill and the empty track.
 * The label is hidden from accessibility; the caller's semantics carry it ([semantics] goes on the bar).
 */
@Composable internal fun LabeledVBar(label: String, color: Color, fill: Float, tick: Float?, modifier: Modifier = Modifier,
    labelSp: Int = 12, semantics: Modifier = Modifier) {
    Box(modifier.then(semantics), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) { vBar(fill, color, tick) }
        Text(label, color = Color.White, fontSize = labelSp.sp, lineHeight = (labelSp + 2).sp, maxLines = 1, softWrap = false,
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset.Zero, 6f)),
            modifier = Modifier.clearAndSetSemantics {}
                .layout { m, c ->
                    val p = m.measure(androidx.compose.ui.unit.Constraints())
                    layout(p.height, p.width) { p.place(-(p.width - p.height) / 2, (p.width - p.height) / 2) }
                }.rotate(-90f).padding(horizontal = 3.dp))
    }
}

internal fun Modifier.vGaugeBacking() = this.clip(androidx.compose.ui.graphics.RectangleShape).background(Color(0x990A111C)).padding(horizontal = 4.dp, vertical = 5.dp)

/** Vertical bar filling bottom to top, with an optional horizontal tick at [tick] (0..1). */
internal fun DrawScope.vBar(fill: Float, color: Color, tick: Float?) {
    drawRect(Color(0xFF25344C))
    val h = size.height * fill.coerceIn(0f, 1f)
    drawRect(color, Offset(0f, size.height - h), Size(size.width, h))
    tick?.let {
        val y = size.height * (1 - it)
        drawLine(Color.White, Offset(-2.dp.toPx(), y), Offset(size.width + 2.dp.toPx(), y), 2.dp.toPx())
    }
}

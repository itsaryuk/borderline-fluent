package com.lampensoft.borderlinefluent

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
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

/** JVM String.hashCode (31 × hash + char), stable across launches, versions and locales.
 * Catalog: de, fr, es, ukraine, turkey, uae, japan, switzerland, belgium, senegal, colombia, capeverde.
 */
fun referralGaugeScrambled(countryId: String, perks: ImplantPerks) =
    !perks.antiJam && countryId.hashCode() % 3 == 0

/** Refusal approaches at suspicion 1 or patience 0; this is a warning, not the referral probability. */
fun referralRefusalRisk(suspicion: Double, patience: Double) =
    maxOf(suspicion, 1.0 - patience).coerceIn(0.0, 1.0).toFloat()

/** Interest, danger (0..1) and pips for a scrambled meter. Without motion the bars stay empty, never a frozen fake reading. */
internal fun scrambledFills(animate: Boolean, frame: Int): Triple<Float, Float, Int> =
    if (!animate) Triple(0f, 0f, 0)
    else Triple(((frame * 37L + 43) % 101).toFloat() / 100f, ((frame * 53L + 61) % 101).toFloat() / 100f, ((frame.toLong() + 1) % 3).toInt())

private val GaugeGrey = Color(0xFF8A94A3)

private data class ReferralReading(val meter: Double, val risk: Float, val badAnswers: Int)

/** Read-only HUD: sampling the non-Compose mind never changes the decision. */
@Composable internal fun ReferralGauge(mind: OfficerMind, countryId: String, perks: ImplantPerks, bluffReason: Boolean = false, modifier: Modifier = Modifier) {
    val scrambled = referralGaugeScrambled(countryId, perks)
    val ctx = LocalContext.current
    val animate = Motion.allowed()
    var reading by remember(mind) { mutableStateOf(ReferralReading(mind.meter, referralRefusalRisk(mind.suspicion, mind.patience), mind.badAnswers)) }
    val frame = remember(mind, scrambled) { mutableIntStateOf(0) }
    LaunchedEffect(mind, scrambled, animate) {
        while (true) {
            if (!scrambled) reading = ReferralReading(mind.meter, referralRefusalRisk(mind.suspicion, mind.patience), mind.badAnswers)
            // The animation state is read only inside Canvas: jitter never recomposes the interview.
            if (scrambled && animate) frame.intValue++
            delay(400)
        }
    }
    val percent = (reading.meter.coerceIn(0.0, 1.0) * 100).roundToInt()
    val interest = stringResource(R.string.referral_interest)
    val danger = stringResource(R.string.referral_danger)
    val readingLabel = if (scrambled) stringResource(R.string.referral_unreadable)
        else stringResource(R.string.referral_readings, percent, (REFER_AT * 100).roundToInt(),
            (reading.risk * 100).roundToInt(), reading.badAnswers.coerceIn(0, 2))
    val label = readingLabel + if (bluffReason) " · " + stringResource(R.string.bluff_reason) else ""
    val cyan = Color(0xFF8FE3FF)
    val red = Color(0xFFF27979)
    val mark = if (scrambled) "≋ " else ""
    // Two slim vertical gauges over the portrait's edges: Interest left, Danger right (both fill bottom to top).
    Box(modifier.semantics(mergeDescendants = true) { contentDescription = label }) {
        Column(Modifier.align(Alignment.TopStart).fillMaxHeight(0.55f).width(52.dp).vGaugeBacking(),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(mark + interest, color = cyan, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics {})
            Canvas(Modifier.padding(top = 3.dp).width(12.dp).weight(1f)) {
                val fill = if (scrambled) scrambledFills(animate, frame.intValue).first else reading.meter.toFloat()
                vBar(fill, if (scrambled && !animate) GaugeGrey else cyan, REFER_AT.toFloat())
            }
        }
        Column(Modifier.align(Alignment.TopEnd).fillMaxHeight(0.55f).width(52.dp).vGaugeBacking(),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(mark + danger, color = red, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics {})
            Canvas(Modifier.padding(top = 3.dp).width(12.dp).weight(1f)) {
                vBar(if (scrambled) scrambledFills(animate, frame.intValue).second else reading.risk, if (scrambled && !animate) GaugeGrey else red, null)
            }
            Canvas(Modifier.padding(top = 3.dp).size(12.dp, 26.dp)) {
                val pips = if (scrambled) scrambledFills(animate, frame.intValue).third else reading.badAnswers.coerceIn(0, 2)
                repeat(2) { i ->
                    drawCircle(if (i < pips) (if (scrambled && !animate) GaugeGrey else red) else Color(0xFF25344C), 5.dp.toPx(), Offset(size.width / 2, 5.dp.toPx() + 13.dp.toPx() * i))
                }
            }
        }
        if (scrambled) Text(stringResource(R.string.referral_unreadable), color = Color(0xFFCBD3DF), fontSize = 9.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp).fillMaxWidth(0.5f).clearAndSetSemantics {})
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

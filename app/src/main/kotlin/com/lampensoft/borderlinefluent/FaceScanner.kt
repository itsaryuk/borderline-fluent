package com.lampensoft.borderlinefluent

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Decorative sensor display; one local draw invalidation, no camera owner or gameplay input. */
@Composable internal fun FaceScanner(g: Game, asset: FaceMesh.Asset, visible: Boolean, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resolver = LocalContext.current.contentResolver
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var reduced by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(lifecycle, resolver) {
        fun refresh() {
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            reduced = !ValueAnimator.areAnimatorsEnabled()
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { refresh() }
        }
        val events = LifecycleEventObserver { _, _ -> refresh() }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        lifecycle.addObserver(events)
        refresh()
        onDispose { resolver.unregisterContentObserver(observer); lifecycle.removeObserver(events) }
    }
    val active = visible && resumed
    // Keying the holder discards the old face immediately on authorization/lifecycle changes.
    val state = remember(g, active, reduced) { mutableStateOf<FaceSession.State?>(null) }
    val sweep = remember(g, active, reduced) { mutableLongStateOf(0L) }
    LaunchedEffect(g, active, reduced) {
        if (!active) return@LaunchedEffect
        val decor = FaceMesh.Scheduler(g.faceWatch::now).also { it.start(1) }
        while (true) {
            val next = g.scannerState(reduced)
            state.value = next
            val now = g.faceWatch.now()
            if (next.face != null && decor.decorate(1, true, reduced)) sweep.longValue = now
            // Wake at expiry as well as polling for new sensor data; reduced motion has no decorative invalidation.
            val expiry = next.face?.source?.capturedAt?.plus(FaceMesh.FRESH_MS)
            delay(if (expiry == null) 34 else minOf(34, (expiry - now).coerceAtLeast(1)))
        }
    }
    LaunchedEffect(g, active) {
        if (active) while (true) {
            delay(5000)
            withContext(Dispatchers.IO) { g.scannerDiagnostics() }
        }
    }
    // Paths and projected points are rebuilt only for a new sensor snapshot or canvas size.
    val geometry = remember { ScannerGeometry() }
    Canvas(modifier) {
        val aperture = FaceMesh.aperture(asset, size.width.toDouble(), size.height.toDouble()) ?: return@Canvas
        val center = Offset(aperture.center.x.toFloat(), aperture.center.y.toFloat())
        val radius = aperture.radius.toFloat()
        val clip = Path().apply { addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)) }
        val cyan = Color(0xFF8EDDE2)
        val amber = Color(0xFFE7AF62)
        val current = state.value.takeIf { active }
        val face = current?.face?.takeIf { FaceMesh.fresh(it.source, g.faceWatch.now(), current.epoch) }
        clipPath(clip) {
            drawCircle(Color(0xFF02090B), radius, center)
            if (face == null) {
                // Static broken reticle, deliberately no face silhouette or waiting animation.
                val r = radius * .40f
                for (angle in listOf(20f, 110f, 200f, 290f)) drawArc(cyan.copy(alpha = .32f), angle, 35f, false,
                    Offset(center.x - r, center.y - r), androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = Stroke(.6.dp.toPx()))
                geometry.clear()
            } else {
                geometry.update(face, aperture)
                for (line in geometry.lines.values) {
                    drawPath(line, cyan.copy(alpha = .07f), style = Stroke(1.5.dp.toPx()))
                    drawPath(line, cyan.copy(alpha = .60f), style = Stroke(.5.dp.toPx()))
                }
                for (p in geometry.sparse.values.flatten()) drawCircle(cyan.copy(alpha = .8f), .65.dp.toPx(), p)
                for ((region, strength) in current.highlights) for (c in FaceMesh.contoursOf(region)) {
                    val line = geometry.lines[c] ?: continue
                    drawPath(line, amber.copy(alpha = strength.toFloat() * .85f), style = Stroke(.8.dp.toPx()))
                    if (region != FaceMesh.Region.WHOLE) for (p in geometry.sparse[c].orEmpty())
                        drawCircle(amber.copy(alpha = strength.toFloat()), .85.dp.toPx(), p)
                }
                if (!reduced) {
                    val phase = (sweep.longValue % 3000) / 3000f
                    val y = center.y - radius + phase * radius * 2
                    drawLine(cyan.copy(alpha = .12f), Offset(center.x - radius, y), Offset(center.x + radius, y), .65.dp.toPx())
                }
            }
        }
    }
}

private class ScannerGeometry {
    private var face: FaceMesh.Smoothed? = null
    private var aperture: FaceMesh.Circle? = null
    var lines = emptyMap<FaceMesh.Contour, Path>(); private set
    var sparse = emptyMap<FaceMesh.Contour, List<Offset>>(); private set
    fun clear() { face = null; aperture = null; lines = emptyMap(); sparse = emptyMap() }
    fun update(value: FaceMesh.Smoothed, circle: FaceMesh.Circle) {
        if (face === value && aperture == circle) return
        face = value; aperture = circle
        val fitted = FaceMesh.fit(value.contours, circle) ?: run { clear(); return }
        val pts = fitted.mapValues { (_, l) -> l.map { Offset(it.x.toFloat(), it.y.toFloat()) } }
        lines = pts.mapValues { (c, l) -> Path().apply {
            l.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            if (c.closed && l.size > 2) close()
        } }
        sparse = pts.mapValues { (_, l) -> l.filterIndexed { i, _ -> i % 3 == 0 } }
    }
}

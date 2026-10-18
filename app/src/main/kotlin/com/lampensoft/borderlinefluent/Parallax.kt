package com.lampensoft.borderlinefluent

import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.exp
import kotlin.math.sqrt

internal data class ParallaxAxis(val baseline: Float, val offset: Float = 0f)

/** Gravity components avoid Euler-angle wraparound. Offset is normalized to [-1, 1]. */
internal fun stepParallax(previous: ParallaxAxis?, raw: Float, seconds: Float): ParallaxAxis {
    if (!raw.isFinite()) return previous ?: ParallaxAxis(0f)
    if (previous == null) return ParallaxAxis(raw)
    if (!seconds.isFinite() || seconds <= 0f) return previous
    val baseline = previous.baseline + (raw - previous.baseline) * (1f - exp(-seconds / 3f))
    val target = ((raw - baseline) / .35f).coerceIn(-1f, 1f)
    // Equivalent to a .12 low-pass step at 60 Hz, independent of sensor/display frequency.
    val blend = 1f - exp(kotlin.math.ln(.88f) * seconds * 60f)
    return ParallaxAxis(baseline, (previous.offset + (target - previous.offset) * blend).coerceIn(-1f, 1f))
}

/** Sensor samples never enter composition; only the graphics layers observe the output. */
@Composable internal fun rememberParallax(): State<Offset> {
    val ctx = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val offset = remember { mutableStateOf(Offset.Zero) }
    val reduce = Motion.reduce
    DisposableEffect(ctx, view, lifecycle, reduce) {
        val manager = ctx.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val handler = Handler(Looper.getMainLooper())
        val clock = Choreographer.getInstance()
        val matrix = FloatArray(9)
        var disposed = false
        var registered = false
        var raw: Offset? = null
        var x: ParallaxAxis? = null
        var y: ParallaxAxis? = null
        var lastFrame = 0L
        val frame = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!registered) return
                val dt = if (lastFrame == 0L) 0f else (frameTimeNanos - lastFrame) / 1_000_000_000f
                lastFrame = frameTimeNanos
                raw?.let {
                    x = stepParallax(x, it.x, dt)
                    y = stepParallax(y, it.y, dt)
                    offset.value = Offset(x!!.offset, y!!.offset)
                }
                clock.postFrameCallback(this)
            }
        }
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                if (!registered) return
                val gx: Float
                val gy: Float
                if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    gx = matrix[6]
                    gy = matrix[7]
                } else {
                    val v = event.values
                    val length = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                    if (length < .001f) return
                    gx = v[0] / length
                    gy = v[1] / length
                }
                raw = when (view.display?.rotation) {
                    Surface.ROTATION_90 -> Offset(-gy, -gx)
                    Surface.ROTATION_180 -> Offset(-gx, gy)
                    Surface.ROTATION_270 -> Offset(gy, gx)
                    else -> Offset(gx, -gy)
                }
            }
        }
        fun stop() {
            registered = false
            manager?.unregisterListener(listener)
            clock.removeFrameCallback(frame)
            raw = null
            x = null
            y = null
            lastFrame = 0L
            offset.value = Offset.Zero
        }
        fun refresh() {
            if (disposed) return
            val enabled = !reduce && sensor != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            if (!enabled) stop()
            else if (!registered) {
                registered = manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, handler) == true
                if (registered) clock.postFrameCallback(frame)
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, _ -> refresh() }
        val settingsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        ctx.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, settingsObserver)
        lifecycle.addObserver(lifecycleObserver)
        refresh()
        onDispose {
            disposed = true
            lifecycle.removeObserver(lifecycleObserver)
            ctx.contentResolver.unregisterContentObserver(settingsObserver)
            stop()
        }
    }
    return offset
}

internal fun Modifier.parallaxArt(tilt: State<Offset>) = graphicsLayer {
    scaleX = 1.12f
    scaleY = 1.12f
    translationX = tilt.value.x * 24.dp.toPx()
    translationY = tilt.value.y * 24.dp.toPx()
}

internal fun Modifier.parallaxCaption(tilt: State<Offset>) = graphicsLayer {
    translationX = -tilt.value.x * 8.dp.toPx()
    translationY = -tilt.value.y * 8.dp.toPx()
}

/** City scenes: the backdrop shifts up to [CITY_BACK_DP] with the tilt, the foreground [CITY_FRONT_DP] the other way. */
internal const val CITY_BACK_DP = 14f
internal const val CITY_FRONT_DP = 5f
/** The bar's bartender layer sits between them: a slight shift the foreground's way. */
internal const val CITY_MID_DP = 2f

/** The backdrop is scaled up so a full tilt shift (± [shiftPx]) never shows its edge: at least 1.06, more on a narrow art. */
internal fun cityBackdropScale(widthPx: Float, shiftPx: Float) =
    if (widthPx > 0f) maxOf(1.06f, 1f + 2f * shiftPx / widthPx) else 1.06f

/** The taxi pulls away once the answer is judged. */
internal fun cityDriving(kind: CityKind, step: CityStep) = kind == CityKind.TAXI && step >= CityStep.CHECKING

/** How much the taxi's street view is enlarged from its bottom edge, lifting the end of the street into the windshield. */
internal const val TAXI_VIEW_RAISE = 1.25f

package com.lampensoft.borderlinefluent

import java.util.Collections
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Ephemeral scanner math only. Times are monotonic milliseconds; callers serialize mutable helpers.
 * Coordinates already have InputImage rotation applied. The only reflection is in [project].
 * No method logs geometry, retains a frame history, or changes gameplay samples.
 */
class FaceMesh private constructor() {
    data class Point(val x: Double, val y: Double)
    data class Circle(val center: Point, val radius: Double)
    enum class Asset { ROOM, DESK }
    /** The face detector's contour set; polylines, in the order the SDK returns them. */
    enum class Contour {
        FACE_OVAL, LEFT_EYE, RIGHT_EYE, LEFT_EYEBROW_TOP, LEFT_EYEBROW_BOTTOM,
        RIGHT_EYEBROW_TOP, RIGHT_EYEBROW_BOTTOM, UPPER_LIP_TOP, UPPER_LIP_BOTTOM,
        LOWER_LIP_TOP, LOWER_LIP_BOTTOM, NOSE_BRIDGE, NOSE_BOTTOM, LEFT_CHEEK, RIGHT_CHEEK;
        /** Drawn as loops; the rest are open lines (a cheek is one point). */
        val closed get() = this == FACE_OVAL || this == LEFT_EYE || this == RIGHT_EYE
    }
    enum class Region { EYES, MOUTH, BROWS, NOSE, LOWER_FACE, WHOLE }

    /** Created only by [snapshot]; immutable copies. Points are upright frame pixels (rotation already applied
     * by the detector); the only reflection is in [fit].
     */
    class Snapshot internal constructor(
        val contours: Map<Contour, List<Point>>, val capturedAt: Long, val width: Int, val height: Int,
        val epoch: Long, val rotation: Int,
    )
    class Smoothed internal constructor(val source: Snapshot, val contours: Map<Contour, List<Point>>)

    companion object {
        const val FRESH_MS = 500L
        const val HIGHLIGHT_MS = 900L
        private const val MAX_POINTS = 64
        private fun finite(p: Point) = p.x.isFinite() && p.y.isFinite()
        private fun <T> frozenList(v: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(v))

        /** Invalid input is no-signal. The face oval is required (it sizes the picture); other contours may be missing. */
        fun snapshot(contours: Map<Contour, List<Point>>, capturedAt: Long, width: Int, height: Int,
                     epoch: Long, rotation: Int): Snapshot? {
            if (width <= 0 || height <= 0 || capturedAt < 0 || rotation !in setOf(0, 90, 180, 270)) return null
            if (contours.values.any { it.size > MAX_POINTS || it.any { p -> !finite(p) } }) return null
            val oval = contours[Contour.FACE_OVAL] ?: return null
            if (oval.size < 3 || bounds(oval) == null) return null
            return Snapshot(Collections.unmodifiableMap(LinkedHashMap(contours.filterValues { it.isNotEmpty() }
                .mapValues { frozenList(it.value) })), capturedAt, width, height, epoch, rotation)
        }
        /** left, top, right, bottom of a point set; null when degenerate. */
        private fun bounds(points: List<Point>): DoubleArray? {
            val b = doubleArrayOf(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
            return b.takeIf { it[2] > it[0] && it[3] > it[1] }
        }
        /** No tracking ID is carried by the contour adapter. Compare raw oval boxes, before smoothing/fit,
         * to avoid blending a newly selected face into its predecessor. Thresholds use the previous box.
         */
        fun continuous(previous: Snapshot, next: Snapshot): Boolean {
            if (previous.epoch != next.epoch || previous.width != next.width ||
                previous.height != next.height || previous.rotation != next.rotation) return false
            val a = bounds(previous.contours.getValue(Contour.FACE_OVAL)) ?: return false
            val b = bounds(next.contours.getValue(Contour.FACE_OVAL)) ?: return false
            for (axis in 0..1) {
                val size = a[axis + 2] - a[axis]
                val nextSize = b[axis + 2] - b[axis]
                val movement = abs((b[axis] + b[axis + 2] - a[axis] - a[axis + 2]) / 2)
                if (movement > size * .5 || abs(nextSize - size) > size * .4) return false
            }
            return true
        }
        fun mirror(point: Point) = Point(-point.x, point.y)
        /** Fit the face oval's bounding box into [FILL] of the circle diameter (aspect kept, centred), mirrored like a
         * selfie. Output may extend beyond the circle; the canvas clips.
         */
        fun fit(contours: Map<Contour, List<Point>>, circle: Circle): Map<Contour, List<Point>>? {
            if (!finite(circle.center) || !circle.radius.isFinite() || circle.radius <= 0) return null
            val b = bounds(contours[Contour.FACE_OVAL] ?: return null) ?: return null
            val scale = FILL * 2 * circle.radius / maxOf(b[2] - b[0], b[3] - b[1])
            val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2
            if (!scale.isFinite()) return null
            return contours.mapValues { (_, pts) -> pts.map {
                Point(circle.center.x - (it.x - cx) * scale, circle.center.y + (it.y - cy) * scale) } }
        }
        const val FILL = .90
        /** Same center-crop transform as the image, in its container's local coordinates. */
        fun aperture(asset: Asset, width: Double, height: Double,
                     sourceWidth: Double = 941.0, sourceHeight: Double = 1672.0): Circle? {
            if (listOf(width, height, sourceWidth, sourceHeight).any { !it.isFinite() || it <= 0 }) return null
            val scale = max(width / sourceWidth, height / sourceHeight)
            val cy = if (asset == Asset.ROOM) .353 else .455
            val radius = if (asset == Asset.ROOM) .110 else .105
            val c = Circle(Point(width / 2, (height - scale * sourceHeight) / 2 + cy * sourceHeight * scale),
                radius * sourceWidth * scale)
            return c.takeIf { finite(it.center) && it.radius.isFinite() && it.radius > 0 }
        }
        fun fresh(snapshot: Snapshot?, now: Long, epoch: Long): Boolean = snapshot != null &&
            snapshot.epoch == epoch && now >= snapshot.capturedAt && now - snapshot.capturedAt < FRESH_MS

        fun regions(tell: Tell): Set<Region> = when (tell) {
            Tell.BLINKS -> setOf(Region.EYES)
            Tell.LIPS, Tell.SMILE -> setOf(Region.MOUTH)
            Tell.BROWS -> setOf(Region.BROWS)
            Tell.GAZE -> setOf(Region.EYES, Region.NOSE)
            Tell.HEAD_DOWN -> setOf(Region.NOSE, Region.LOWER_FACE)
            Tell.MASK_SMILE -> setOf(Region.MOUTH, Region.EYES)
            Tell.FREEZE -> setOf(Region.WHOLE)
        }
        fun contoursOf(region: Region): Set<Contour> = when (region) {
            Region.EYES -> setOf(Contour.LEFT_EYE, Contour.RIGHT_EYE)
            Region.MOUTH -> setOf(Contour.UPPER_LIP_TOP, Contour.UPPER_LIP_BOTTOM, Contour.LOWER_LIP_TOP, Contour.LOWER_LIP_BOTTOM)
            Region.BROWS -> setOf(Contour.LEFT_EYEBROW_TOP, Contour.LEFT_EYEBROW_BOTTOM,
                Contour.RIGHT_EYEBROW_TOP, Contour.RIGHT_EYEBROW_BOTTOM)
            Region.NOSE -> setOf(Contour.NOSE_BRIDGE, Contour.NOSE_BOTTOM)
            Region.LOWER_FACE -> setOf(Contour.FACE_OVAL)
            Region.WHOLE -> Contour.entries.toSet()
        }
    }

    /** One predecessor only. Null (loss/malformed input) clears immediately. dt <= 0 is ignored.
     * Smooths contour points in frame pixels; the fit to the aperture happens after smoothing.
     */
    class Smoother {
        private var previous: Smoothed? = null
        fun clear() { previous = null }
        fun update(frame: Snapshot?): Smoothed? {
            if (frame == null) { clear(); return null }
            val old = previous
            val a = old?.source
            val compatible = a != null && a.epoch == frame.epoch && a.width == frame.width &&
                a.height == frame.height && a.rotation == frame.rotation &&
                a.contours.mapValues { it.value.size } == frame.contours.mapValues { it.value.size }
            if (compatible && frame.capturedAt <= a.capturedAt) return old
            val result = if (!compatible || frame.capturedAt - a.capturedAt > FRESH_MS) {
                Smoothed(frame, frame.contours)
            } else {
                val alpha = 1 - exp(-(frame.capturedAt - a.capturedAt) / 80.0)
                fun blend(x: Double, y: Double) = x * (1 - alpha) + y * alpha
                Smoothed(frame, frame.contours.mapValues { (c, pts) ->
                    frozenList(pts.mapIndexed { i, p -> val o = old.contours.getValue(c)[i]; Point(blend(o.x, p.x), blend(o.y, p.y)) }) })
            }
            previous = result
            return result
        }
    }

    /** At most one timestamp per semantic region. Caller passes live/fresh face availability.
     * No-signal clears pending accents; stale epoch reads cannot destroy a newer epoch's state.
     */
    class Highlights {
        private var epoch: Long? = null
        private val events = mutableMapOf<Region, Long>()
        fun reset(epoch: Long) { this.epoch = epoch; clear() }
        fun clear() { events.clear() }
        fun add(tell: Tell, at: Long, epoch: Long) {
            if (this.epoch != epoch || at < 0) return
            for (region in regions(tell)) events[region] = max(events[region] ?: at, at)
        }
        fun active(now: Long, epoch: Long, hasFace: Boolean, reducedMotion: Boolean = false): Map<Region, Double> {
            if (this.epoch != epoch) return emptyMap()
            if (!hasFace) { clear(); return emptyMap() }
            events.entries.removeAll { now >= it.value && now - it.value >= HIGHLIGHT_MS }
            return events.filterValues { now >= it }.mapValues { (region, at) ->
                val strength = if (region == Region.WHOLE) .25 else 1.0
                strength * if (reducedMotion) 1.0 else min(1.0, (HIGHLIGHT_MS - (now - at)) / 150.0)
            }
        }
    }

    /** Publication cap (a detector at 30 fps need not repaint 30 times) and the decorative sweep cap. Rejects stale,
     * future, duplicate/out-of-order and invalidated results; start(same epoch) cannot revive an invalidated one.
     */
    class Scheduler(private val clock: () -> Long) {
        private var epoch: Long? = null
        private var enabled = false
        private var lastDraw: Long? = null
        private var lastPublish: Long? = null
        private var lastCapture: Long? = null
        fun start(epoch: Long) {
            if (this.epoch != null && epoch <= this.epoch!!) return
            this.epoch = epoch; enabled = true
            lastDraw = null; lastPublish = null; lastCapture = null
        }
        fun invalidate() { enabled = false; lastDraw = null; lastPublish = null; lastCapture = null }
        private fun current(epoch: Long) = enabled && this.epoch == epoch
        private fun due(now: Long, last: Long?, period: Long) = now >= 0 &&
            (last == null || (now >= last && now - last >= period))
        fun publish(snapshot: Snapshot): Boolean {
            val now = clock()
            if (!current(snapshot.epoch) || !fresh(snapshot, now, snapshot.epoch) ||
                (lastCapture != null && snapshot.capturedAt <= lastCapture!!) ||
                !due(now, lastPublish, PUBLISH_MS)) return false
            lastPublish = now; lastCapture = snapshot.capturedAt; return true
        }
        fun decorate(epoch: Long, visible: Boolean, reducedMotion: Boolean): Boolean {
            if (!current(epoch) || !visible || reducedMotion) return false
            val now = clock()
            if (!due(now, lastDraw, 34)) return false
            lastDraw = now; return true
        }
        companion object { const val PUBLISH_MS = 60L } // ~15 fps
    }
}

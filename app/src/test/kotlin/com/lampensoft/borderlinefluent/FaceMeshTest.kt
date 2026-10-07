package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test
import com.lampensoft.borderlinefluent.FaceMesh.*

class FaceMeshTest {
    private val oval = listOf(Point(0.0, 0.0), Point(100.0, 0.0), Point(100.0, 200.0), Point(0.0, 200.0))
    private fun frame(t: Long = 0, x: Double = 50.0, epoch: Long = 1, rotation: Int = 0, width: Int = 640) =
        FaceMesh.snapshot(mapOf(Contour.FACE_OVAL to oval, Contour.LEFT_EYE to listOf(Point(x, 50.0))),
            t, width, 480, epoch, rotation)!!
    private fun near(a: Double, b: Double) = assertEquals(a, b, 1e-9)

    @Test fun ovalFillsNinetyPercentCentredAndMirrored() {
        val c = Circle(Point(10.0, 20.0), 50.0)
        val f = FaceMesh.fit(mapOf(Contour.FACE_OVAL to oval,
            Contour.LEFT_EYE to listOf(Point(0.0, 100.0)), Contour.NOSE_BRIDGE to listOf(Point(50.0, 100.0))), c)!!
        val o = f.getValue(Contour.FACE_OVAL)
        near(.9 * 100, o.maxOf { it.y } - o.minOf { it.y })      // long side = 90% of the diameter
        near(.9 * 50, o.maxOf { it.x } - o.minOf { it.x })       // aspect kept
        near(10.0, (o.maxOf { it.x } + o.minOf { it.x }) / 2); near(20.0, (o.maxOf { it.y } + o.minOf { it.y }) / 2)
        near(10.0 + 22.5, f.getValue(Contour.LEFT_EYE)[0].x)     // frame-left lands on the right: selfie mirror
        near(10.0, f.getValue(Contour.NOSE_BRIDGE)[0].x)
        val wide = FaceMesh.fit(mapOf(Contour.FACE_OVAL to listOf(Point(0.0, 0.0), Point(300.0, 0.0), Point(300.0, 100.0))), c)!!
        near(90.0, wide.getValue(Contour.FACE_OVAL).let { l -> l.maxOf { it.x } - l.minOf { it.x } })
        val p = Point(.2, .7); near(-.2, FaceMesh.mirror(p).x)
    }
    @Test fun bothArtAnchorsAndNonSquareCrops() {
        val room = FaceMesh.aperture(Asset.ROOM, 941.0, 941.0)!!
        near(.5 * 941, room.center.x); near(.353 * 1672 - 365.5, room.center.y)
        near(.110 * 941, room.radius)
        val desk = FaceMesh.aperture(Asset.DESK, 941.0, 941.0)!!
        near(.455 * 1672 - 365.5, desk.center.y); near(.105 * 941, desk.radius)
        for ((w, h) in listOf(400.0 to 800.0, 800.0 to 400.0)) {
            val s = maxOf(w / 941, h / 1672)
            val c = FaceMesh.aperture(Asset.ROOM, w, h)!!
            near(w / 2, c.center.x); near((h - s * 1672) / 2 + .353 * 1672 * s, c.center.y)
        }
    }
    @Test fun anchorsStayInsideHardwareAndScaleWithSharedContainer() {
        for ((asset, cy, radius) in listOf(Triple(Asset.ROOM, 590.216, 103.51), Triple(Asset.DESK, 760.76, 98.805))) {
            val source = FaceMesh.aperture(asset, 941.0, 1672.0)!!
            near(470.5, source.center.x); near(cy, source.center.y); near(radius, source.radius)
            val small = FaceMesh.aperture(asset, 360.0, 360.0)!!
            val large = FaceMesh.aperture(asset, 720.0, 720.0)!!
            near(small.center.x * 2, large.center.x); near(small.center.y * 2, large.center.y)
            near(small.radius * 2, large.radius)
            assertTrue(small.center.y > small.radius)
            assertTrue(small.center.y + small.radius < 360)
        }
    }
    @Test fun invalidGeometryRejected() {
        for (bad in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertNull(FaceMesh.aperture(Asset.ROOM, bad, 100.0))
            assertNull(FaceMesh.fit(mapOf(Contour.FACE_OVAL to oval), Circle(Point(0.0, 0.0), bad)))
        }
        fun snap(c: Map<Contour, List<Point>>, w: Int = 640) = FaceMesh.snapshot(c, 0, w, 480, 1, 0)
        assertNull(snap(mapOf(Contour.FACE_OVAL to oval + Point(Double.NaN, 0.0))))
        assertNull(snap(emptyMap()))                                          // no oval, nothing to size
        assertNull(snap(mapOf(Contour.FACE_OVAL to oval.take(2))))
        assertNull(snap(mapOf(Contour.FACE_OVAL to List(3) { Point(5.0, 5.0) }))) // degenerate box
        assertNull(snap(mapOf(Contour.FACE_OVAL to oval, Contour.LEFT_EYE to List(65) { Point(1.0, 1.0) })))
        assertNull(snap(mapOf(Contour.FACE_OVAL to oval), w = 0))
        assertNull(FaceMesh.snapshot(mapOf(Contour.FACE_OVAL to oval), 0, 640, 480, 1, 45))
        assertNotNull(snap(mapOf(Contour.FACE_OVAL to oval)))
    }
    @Test fun snapshotCopiesAndIsImmutable() {
        val pts = mutableListOf(*oval.toTypedArray()); val map = mutableMapOf(Contour.FACE_OVAL to pts)
        val s = FaceMesh.snapshot(map, 0, 640, 480, 1, 0)!!
        pts.clear(); map.clear()
        assertEquals(4, s.contours.getValue(Contour.FACE_OVAL).size)
        assertThrows(UnsupportedOperationException::class.java) { (s.contours as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (s.contours.getValue(Contour.FACE_OVAL) as MutableList).clear() }
    }
    @Test fun smootherBlendsPointsAndResetsWhenIncompatible() {
        val s = Smoother(); near(50.0, s.update(frame())!!.contours.getValue(Contour.LEFT_EYE)[0].x)
        val alpha = 1 - kotlin.math.exp(-1.0)
        near(50 + 50 * alpha, s.update(frame(80, 100.0))!!.contours.getValue(Contour.LEFT_EYE)[0].x)
        val same = s.update(frame(80, 0.0)); assertSame(same, s.update(frame(50, 0.0))) // non-increasing time ignored
        for (f in listOf(frame(601, 100.0), frame(100, 100.0, epoch = 2), frame(100, 100.0, rotation = 90),
            frame(100, 100.0, width = 800))) {
            val t = Smoother(); t.update(frame()); near(100.0, t.update(f)!!.contours.getValue(Contour.LEFT_EYE)[0].x)
        }
        val t = Smoother(); t.update(frame()); assertNull(t.update(null)); near(100.0, t.update(frame(100, 100.0))!!.contours.getValue(Contour.LEFT_EYE)[0].x)
    }
    @Test fun contoursMapToRegionsAndCoverEveryContour() {
        assertEquals(setOf(Contour.LEFT_EYE, Contour.RIGHT_EYE), FaceMesh.contoursOf(Region.EYES))
        assertEquals(4, FaceMesh.contoursOf(Region.BROWS).size); assertEquals(4, FaceMesh.contoursOf(Region.MOUTH).size)
        assertEquals(setOf(Contour.NOSE_BRIDGE, Contour.NOSE_BOTTOM), FaceMesh.contoursOf(Region.NOSE))
        assertEquals(setOf(Contour.FACE_OVAL), FaceMesh.contoursOf(Region.LOWER_FACE))
        assertEquals(Contour.entries.toSet(), FaceMesh.contoursOf(Region.WHOLE))
    }
    @Test fun lipsAreOpenAndOnlyOvalAndEyesAreClosed() {
        for (lip in listOf(Contour.UPPER_LIP_TOP, Contour.UPPER_LIP_BOTTOM, Contour.LOWER_LIP_TOP, Contour.LOWER_LIP_BOTTOM))
            assertFalse(lip.closed)
        assertEquals(setOf(Contour.FACE_OVAL, Contour.LEFT_EYE, Contour.RIGHT_EYE),
            Contour.entries.filter { it.closed }.toSet())
    }
    @Test fun highlightsFadeWithoutRewindingRefresh() {
        val h = Highlights(); h.reset(1); h.add(Tell.SMILE, 100, 1); h.add(Tell.SMILE, 50, 1)
        near(1.0, h.active(850, 1, true).getValue(Region.MOUTH))
        near(.5, h.active(925, 1, true).getValue(Region.MOUTH))
        near(1.0, h.active(925, 1, true, true).getValue(Region.MOUTH))
        h.reset(2); h.add(Tell.BLINKS, 1000, 2)
        assertTrue(h.active(1000, 1, false).isEmpty())
        assertEquals(setOf(Region.EYES), h.active(1000, 2, true).keys)
    }
    @Test fun allTellsHaveExactSemanticRegions() {
        val expected = mapOf(Tell.BLINKS to setOf(Region.EYES), Tell.LIPS to setOf(Region.MOUTH),
            Tell.BROWS to setOf(Region.BROWS), Tell.GAZE to setOf(Region.EYES, Region.NOSE),
            Tell.HEAD_DOWN to setOf(Region.NOSE, Region.LOWER_FACE), Tell.SMILE to setOf(Region.MOUTH),
            Tell.MASK_SMILE to setOf(Region.MOUTH, Region.EYES), Tell.FREEZE to setOf(Region.WHOLE))
        assertEquals(Tell.entries.toSet(), expected.keys)
        for ((tell, regions) in expected) assertEquals(regions, FaceMesh.regions(tell))
    }
    @Test fun highlightsMergeRefreshExpireAndRejectOldEpoch() {
        val h = Highlights(); h.reset(1)
        h.add(Tell.MASK_SMILE, 0, 1); h.add(Tell.BLINKS, 500, 1); h.add(Tell.BROWS, 600, 0)
        assertEquals(setOf(Region.EYES, Region.MOUTH), h.active(899, 1, true).keys)
        assertEquals(setOf(Region.EYES), h.active(900, 1, true).keys)
        assertTrue(h.active(1400, 1, true).isEmpty())
    }
    @Test fun noSignalClearsHighlightsAndReducedMotionIsSteady() {
        val h = Highlights(); h.reset(1); h.add(Tell.FREEZE, 0, 1)
        near(.25, h.active(899, 1, true, reducedMotion = true).getValue(Region.WHOLE))
        assertTrue(h.active(899, 1, false).isEmpty())
        assertTrue(h.active(899, 1, true).isEmpty())
        h.add(Tell.BLINKS, 900, 1); assertTrue(h.active(900, 2, true).isEmpty())
    }
    @Test fun freshnessBoundaryAndFutureCapture() {
        assertTrue(FaceMesh.fresh(frame(), 499, 1)); assertFalse(FaceMesh.fresh(frame(), 500, 1))
        assertFalse(FaceMesh.fresh(frame(), -1, 1)); assertFalse(FaceMesh.fresh(frame(), 0, 2))
    }
    @Test fun publicationIsCappedFreshAndInvalidated() {
        var now = 0L; val p = Scheduler { now }; p.start(1)
        assertTrue(p.publish(frame()))
        now = 50; assertFalse(p.publish(frame(50)))                  // inside the cap
        now = 60; assertTrue(p.publish(frame(50)))
        now = 200; assertFalse(p.publish(frame(50)))                 // duplicate capture
        assertFalse(p.publish(frame(201)))                           // future
        assertFalse(p.publish(frame(200, epoch = 0)))                // stale epoch
        now = 1000; assertFalse(p.publish(frame(500)))               // stale at exactly 500 ms
        assertTrue(p.publish(frame(1000)))
        p.invalidate(); now = 2000; assertFalse(p.publish(frame(2000)))
        p.start(1); assertFalse(p.publish(frame(2000)))              // same epoch cannot revive
        p.start(2); assertTrue(p.publish(frame(2000, epoch = 2)))
    }
    @Test fun decorativeTicksRespectVisibilityMotionAndThirtyHzCap() {
        var now = 0L; val p = Scheduler { now }; p.start(1)
        assertFalse(p.decorate(1, visible = false, reducedMotion = false))
        assertFalse(p.decorate(1, visible = true, reducedMotion = true))
        assertTrue((0L until 1000).count { now = it; p.decorate(1, true, false) } <= 30)
        p.invalidate(); assertFalse(p.decorate(1, true, false))
    }
}

package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test

class FaceSessionTest {
    private class Fake {
        var now = 0L
        var detectorThrow = false
        var snapshotThrow = false
        var snapshotNull = false
        var offsetX = 0.0
        var offsetY = 0.0
        var scale = 1.0
        var detectorCalls = 0
        var closes = 0
        var drained = 0
        val samples = mutableListOf<String?>()
        val detections = mutableListOf<(Result<String?>) -> Unit>()
        val session = FaceSession<Any, String>({ now }, { _, done ->
            detectorCalls++
            if (detectorThrow) error("detect")
            detections += done
        }, { _, frame, epoch ->
            if (snapshotThrow) error("snapshot")
            if (snapshotNull) null else snapshot(epoch, frame.capturedAt, frame.width, frame.height, offsetX, offsetY, scale)
        }, { samples += it }, { drained++ })
        var token = session.startCamera()
        val gate = FaceSession.Gate(1, true, true, true, true, false)
        init { session.game(gate); session.presentation(true) }
        fun frame(image: Any? = Any(), width: Int = 640, height: Int = 480) =
            session.frame(token, image, FaceSession.Frame(width, height, 0, now)) { closes++ }
        fun detected(index: Int = detections.lastIndex, face: String? = "face") = detections[index](Result.success(face))
        fun restart() { session.stopCamera(token); token = session.startCamera() }
        companion object {
            fun snapshot(epoch: Long, at: Long, w: Int, h: Int, x: Double, y: Double, scale: Double) = FaceMesh.snapshot(
                mapOf(FaceMesh.Contour.FACE_OVAL to listOf(FaceMesh.Point(0.0, 0.0), FaceMesh.Point(10.0, 0.0), FaceMesh.Point(10.0, 20.0))
                    .map { FaceMesh.Point(x + 5 + (it.x - 5) * scale, y + 10 + (it.y - 10) * scale) }),
                at, w, h, epoch, 0)
        }
    }
    @Test fun oneCloseAfterDetectionAndFacePublishedFromSameResult() {
        val f = Fake(); f.frame(); assertEquals(0, f.closes); assertEquals(1, f.detectorCalls)
        f.detected(); assertEquals(1, f.closes); assertNotNull(f.session.state().face)
        f.detected(); assertEquals(1, f.closes); assertEquals(1, f.detectorCalls) // duplicate completion ignored
    }
    @Test fun nullImageClosesWithoutDetector() {
        val f = Fake(); f.frame(null); assertEquals(1, f.closes); assertEquals(0, f.detectorCalls)
    }
    @Test fun emptyDetectionAddsOnlyExistingAbsentSampleAndNoFace() {
        val f = Fake(); f.frame(); f.detected(face = null)
        assertEquals(listOf<String?>(null), f.samples); assertNull(f.session.state().face); assertEquals(1, f.closes)
    }
    @Test fun detectorFailureDoesNotInventSample() {
        val f = Fake(); f.frame(); f.detections[0](Result.failure(Exception()))
        assertTrue(f.samples.isEmpty()); assertEquals(1, f.closes); assertNull(f.session.state().face)
    }
    @Test fun synchronousDetectorThrowReleases() {
        val f = Fake(); f.detectorThrow = true; f.frame(); assertEquals(1, f.closes)
        f.session.dispose(); assertEquals(1, f.drained)
    }
    @Test fun snapshotThrowOrNullPreservesSampleAndReleases() {
        for (mode in listOf("throw", "null")) {
            val f = Fake(); if (mode == "throw") f.snapshotThrow = true else f.snapshotNull = true
            f.frame(); f.detected()
            assertEquals(listOf("face"), f.samples); assertEquals(1, f.closes); assertNull(f.session.state().face)
            f.session.dispose(); assertEquals(1, f.drained)
        }
    }
    @Test fun stopDuringDetectionRejectsSampleAndFace() {
        val f = Fake(); f.frame(); f.session.stopCamera(f.token); assertEquals(0, f.closes)
        f.detected(); assertTrue(f.samples.isEmpty()); assertNull(f.session.state().face); assertEquals(1, f.closes)
    }
    @Test fun gateClosesDuringDetectionButNormalDetectionContinues() {
        val f = Fake(); f.frame(); f.session.game(f.gate.copy(assessment = false)); f.detected()
        assertEquals(listOf("face"), f.samples); assertNull(f.session.state().face); assertEquals(1, f.closes)
    }
    @Test fun restartRejectsOldDetectionAndOldOwnerDisposal() {
        val f = Fake(); f.frame(); val old = f.token; f.restart()
        f.session.stopCamera(old); assertFalse(f.session.ownsCamera(old)); assertTrue(f.session.ownsCamera(f.token))
        f.detected(0); assertNull(f.session.state().face)
        f.frame(); f.detected(1); assertNotNull(f.session.state().face)
        assertEquals(2, f.closes)
    }
    @Test fun oldDetectionCannotReplaceNewFaceAfterRestart() {
        val f = Fake(); f.frame(); f.restart(); f.now = 200
        f.frame(); f.detected(1); val fresh = f.session.state().face
        f.detected(0); assertSame(fresh, f.session.state().face); assertEquals(2, f.closes)
    }
    @Test fun disposalDrainsReadersAndNeverInitializesAnotherSession() {
        val f = Fake(); f.frame(); assertTrue(f.session.retain())
        f.session.dispose(); f.session.dispose(); assertEquals(0, f.drained); assertEquals(0, f.closes)
        f.detected(); assertEquals(0, f.drained); f.session.release(); assertEquals(1, f.drained)
        assertFalse(f.session.retain()); assertEquals(-1L, f.session.startCamera())
        f.frame(); assertEquals(2, f.closes); assertEquals(1, f.detectorCalls)
    }
    @Test fun undersizedInputKeepsSampleButNoFaceAndPortraitIsAccepted() {
        val small = Fake(); small.frame(width = 320, height = 240); small.detected()
        assertEquals(listOf("face"), small.samples); assertNull(small.session.state().face); assertEquals(1, small.closes)
        val portrait = Fake(); portrait.frame(width = 480, height = 640); portrait.detected()
        assertNotNull(portrait.session.state().face)
    }
    @Test fun allGameGateTermsAreRequired() {
        val base = Fake().gate
        val closed = listOf(base.copy(run = null), base.copy(interview = false), base.copy(watching = false),
            base.copy(assessment = false), base.copy(machine = false), base.copy(obscured = true))
        assertTrue(base.eligible)
        for (gate in closed) {
            val f = Fake(); f.session.game(gate); f.frame(); f.detected()
            assertFalse(gate.eligible); assertNull(f.session.state().face); assertEquals(1, f.closes)
            assertEquals(listOf("face"), f.samples)
        }
    }
    @Test fun foregroundPresentationMustBePresent() {
        val f = Fake(); f.session.presentation(false); f.frame(); f.detected()
        assertNull(f.session.state().face)
        f.now = 100; f.session.presentation(true); f.frame(); f.detected(); assertNotNull(f.session.state().face)
        f.session.presentation(false); assertNull(f.session.state().face)
    }
    @Test fun terminalStopCannotBeReopenedByLateUiOrSurvivingMachine() {
        val f = Fake(); f.frame(); f.detected()
        f.session.game(f.gate.copy(run = null)); f.session.presentation(true)
        assertNull(f.session.state().face); f.now = 200; f.frame(); f.detected(); assertNull(f.session.state().face)
    }
    @Test fun deskToRoomKeepsCameraAndDetectionSession() {
        val f = Fake(); f.session.game(f.gate.copy(assessment = false)); f.frame(); f.detected()
        assertNull(f.session.state().face)
        f.session.game(f.gate); f.now = 100; f.frame(); f.detected()
        assertNotNull(f.session.state().face); assertEquals(2, f.detectorCalls); assertEquals(listOf("face", "face"), f.samples)
    }
    @Test fun publishRateIsCappedAndReported() {
        val f = Fake()
        f.frame(); f.detected(); val first = f.session.state().face
        f.now = 20; f.frame(); f.detected(); assertSame(first, f.session.state().face) // inside the cap
        f.now = 5000; f.frame(); f.detected()
        assertEquals("scanner detector=0.6fps publish=0.2fps", f.session.takeReport()) // the window closes before the 3rd publish
    }
    @Test fun tellsRejectOldRunsExpireAndClearOnLoss() {
        val f = Fake(); f.frame(); f.detected()
        f.session.tell(0, Tell.BLINKS, 0, f.session.state().epoch); assertTrue(f.session.state().highlights.isEmpty())
        f.session.tell(1, Tell.BLINKS, 0, f.session.state().epoch); assertEquals(setOf(FaceMesh.Region.EYES), f.session.state().highlights.keys)
        f.now = 500; assertNull(f.session.state().face); assertTrue(f.session.state().highlights.isEmpty())
        f.frame(); f.detected(); assertTrue(f.session.state().highlights.isEmpty())
        f.session.tell(1, Tell.BROWS, 500, f.session.state().epoch); f.frame(); f.detected(face = null)
        assertNull(f.session.state().face); assertTrue(f.session.state().highlights.isEmpty())
    }
    @Test fun evaluatedTellCannotCrossAnEpochWithinSameRun() {
        val f = Fake(); f.frame(); f.detected()
        val old = f.session.tellEpoch(1)!!
        f.session.presentation(false); f.session.presentation(true); f.now = 200
        f.frame(); f.detected()
        f.session.tell(1, Tell.BLINKS, f.now, old)
        assertTrue(f.session.state().highlights.isEmpty())
    }
    @Test fun nullInputImmediatelyClearsPreviousFaceAndHighlights() {
        val f = Fake(); f.frame(); f.detected()
        f.session.tell(1, Tell.BLINKS, 0, f.session.state().epoch)
        f.frame(null)
        assertNull(f.session.state().face); assertTrue(f.session.state().highlights.isEmpty())
        assertEquals(2, f.closes)
    }
    @Test fun sizeChangeDropsPreviousHighlight() {
        val f = Fake(); f.frame(); f.detected()
        f.session.tell(1, Tell.BLINKS, 0, f.session.state().epoch)
        f.now = 200; f.frame(width = 480, height = 640); f.detected()
        assertNotNull(f.session.state().face); assertTrue(f.session.state().highlights.isEmpty())
    }
    @Test fun faceSwitchWithoutEmptyFrameDropsSmoothingAndHighlights() {
        for ((x, y, scale) in listOf(Triple(6.0, 0.0, 1.0), Triple(0.0, 11.0, 1.0),
            Triple(0.0, 0.0, 1.5), Triple(0.0, 0.0, .5))) {
            val f = Fake(); f.frame(); f.detected()
            f.session.tell(1, Tell.BLINKS, 0, f.session.state().epoch)
            assertFalse(f.session.state().highlights.isEmpty())
            f.offsetX = x; f.offsetY = y; f.scale = scale
            f.now = 80; f.frame(); f.detected(face = "other person")
            val state = f.session.state()
            assertNotNull(state.face)
            assertEquals(state.face!!.source.contours, state.face.contours)
            assertTrue(state.highlights.isEmpty())
            assertEquals(listOf("face", "other person"), f.samples)
        }
    }
    @Test fun smallMovementKeepsSmoothingAndHighlights() {
        val f = Fake(); f.frame(); f.detected()
        f.session.tell(1, Tell.BLINKS, 0, f.session.state().epoch)
        f.offsetX = 1.0; f.offsetY = 2.0
        f.now = 80; f.frame(); f.detected()
        val state = f.session.state()
        val point = state.face!!.contours.getValue(FaceMesh.Contour.FACE_OVAL)[0]
        val alpha = 1 - kotlin.math.exp(-1.0)
        assertEquals(alpha, point.x, 1e-9)
        assertEquals(2 * alpha, point.y, 1e-9)
        assertEquals(setOf(FaceMesh.Region.EYES), state.highlights.keys)
    }
    @Test fun rapidRepeatedSessionsDrainEveryFrame() {
        val f = Fake()
        repeat(20) { f.frame(); f.restart(); f.detected(); f.now += 200 }
        assertEquals(20, f.closes); assertNull(f.session.state().face); f.session.dispose(); assertEquals(1, f.drained)
    }
    @Test fun lateProviderNeverBindsOrUnbindsSomeoneElse() {
        var invalidations = 0; var clears = 0; var binds = 0; var unbinds = 0
        val binding = FaceBinding({ invalidations++ }, { clears++ })
        binding.dispose(); binding.arrived({ binds++ }, { unbinds++ }); binding.dispose()
        assertEquals(1, invalidations); assertEquals(1, clears); assertEquals(0, binds); assertEquals(0, unbinds)
    }
    @Test fun boundProviderDisposalInvalidatesThenClearsThenUnbindsOwnedCases() {
        val events = mutableListOf<String>()
        val binding = FaceBinding({ events += "invalidate" }, { events += "clear" })
        binding.arrived({ events += "bind" }, { events += "unbind owned" }); binding.dispose(); binding.dispose()
        assertEquals(listOf("bind", "invalidate", "clear", "unbind owned"), events)
    }
    @Test fun bindingFailureCleansPartialBinding() {
        val events = mutableListOf<String>(); val b = FaceBinding({ events += "stop" }, { events += "clear" })
        b.arrived({ error("camera busy") }, { events += "unbind" })
        assertEquals(listOf("stop", "clear", "unbind"), events)
    }
}

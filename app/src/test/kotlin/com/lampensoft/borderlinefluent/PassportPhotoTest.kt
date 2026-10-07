package com.lampensoft.borderlinefluent

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PassportPhotoTest {
    @Test fun cropIs35by45AndCentredOnTheFace() {
        val box = IntRect(180, 200, 300, 340) // 120×140 face in a 480×640 frame
        val c = passportCrop(box, 480, 640)!!
        assertTrue(abs(c.width / c.height.toFloat() - 35f / 45f) < 0.01f)
        assertEquals(266, c.height) // 1.9 × 140
        assertEquals(200 - 77, c.top) // top − 0.55 × 140
        assertTrue(abs((c.left + c.right) / 2f - 240f) <= 1f)
    }

    @Test fun cropLeavingTheImageIsNull() {
        assertNull(passportCrop(IntRect(180, 30, 300, 170), 480, 640)) // above the top edge
        assertNull(passportCrop(IntRect(0, 200, 120, 340), 480, 640)) // past the left edge
        assertNull(passportCrop(IntRect(100, 100, 400, 460), 480, 640)) // face too big for the frame
        assertNull(passportCrop(IntRect(10, 10, 10, 10), 480, 640)) // empty box
    }

    @Test fun fitCropIsCentred35by45() {
        assertEquals(IntRect(0, 3, 350, 453), fitCrop(350, 456)) // taller: trim top and bottom
        assertEquals(IntRect(30, 0, 380, 450), fitCrop(410, 450)) // wider: trim the sides
    }

    @Test fun savedPhotoOpensWithoutTheCamera() {
        assertFalse(cameraAtStart(granted = true, hasPhoto = true))
        assertTrue(cameraAtStart(granted = true, hasPhoto = false))
        assertFalse(cameraAtStart(granted = false, hasPhoto = false)) // asks first, on "Take my photo"
    }

    @Test fun uprightSizeSwapsOnQuarterTurns() {
        assertEquals(480 to 640, uprightSize(640, 480, 270))
        assertEquals(480 to 640, uprightSize(640, 480, 90))
        assertEquals(640 to 480, uprightSize(640, 480, 180))
        assertEquals(640 to 480, uprightSize(640, 480, 0))
    }

    private fun face(cx: Int = 240, cy: Int = 320, w: Int = 180, yaw: Float = 0f, pitch: Float = 0f, eyes: Float? = 0.9f, smile: Float? = 0.1f) =
        PassportFace(IntRect(cx - w / 2, cy - w / 2, cx + w / 2, cy + w / 2), yaw, pitch, eyes, smile)

    @Test fun oneGoodFacePasses() {
        val c = passportChecks(listOf(face()), 480, 640)
        assertTrue(c.ok); assertFalse(c.eyesHint); assertFalse(c.smileHint)
    }

    @Test fun multipleOrNoFacesAreBlocked() {
        assertFalse(passportChecks(listOf(face(), face(cx = 100, w = 60)), 480, 640).ok)
        assertEquals(2, passportChecks(listOf(face(), face(cx = 100, w = 60)), 480, 640).faces)
        assertFalse(passportChecks(emptyList(), 480, 640).ok)
    }

    @Test fun eachGateBlocks() {
        assertFalse(passportChecks(listOf(face(yaw = 12f)), 480, 640).straight)
        assertFalse(passportChecks(listOf(face(pitch = -11f)), 480, 640).straight)
        assertFalse(passportChecks(listOf(face(cx = 330)), 480, 640).centred)
        assertFalse(passportChecks(listOf(face(cy = 420)), 480, 640).centred)
        assertEquals(-1, passportChecks(listOf(face(w = 120)), 480, 640).size)
        assertEquals(1, passportChecks(listOf(face(w = 280)), 480, 640).size)
    }

    @Test fun faceNearTheTopDoesNotFit() {
        // Centred enough (within 12%) and a fine size, but the crop's headroom leaves the top of the frame.
        val c = passportChecks(listOf(PassportFace(IntRect(150, 70, 330, 250), 0f, 0f, 0.9f, 0.1f)), 480, 400)
        assertTrue(c.centred); assertEquals(0, c.size); assertFalse(c.fits); assertFalse(c.ok)
    }

    @Test fun eyesAndSmileAreHintsNotGates() {
        val c = passportChecks(listOf(face(eyes = 0.1f, smile = 0.9f)), 480, 640)
        assertTrue(c.ok); assertTrue(c.eyesHint); assertTrue(c.smileHint)
        assertTrue(passportChecks(listOf(face(eyes = null, smile = null)), 480, 640).ok) // unclassified
    }

    @Test fun shutterOpensAfterHoldingAndResetsOnABreak() {
        var since: Long? = null
        since = passSince(true, since, 1000); assertFalse(shutterReady(since, 1000))
        since = passSince(true, since, 1400); assertFalse(shutterReady(since, 1400))
        since = passSince(true, since, 1500); assertTrue(shutterReady(since, 1500))
        since = passSince(false, since, 1600); assertFalse(shutterReady(since, 1600))
        since = passSince(true, since, 1700); assertFalse(shutterReady(since, 1900))
    }
}

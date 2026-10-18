package com.lampensoft.borderlinefluent

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class FaceSimTest {
    private fun run(s: SimFace) = (0L..3000 step 66).map { simSample(s, it) }

    @Test fun shakeCrossesImplantThresholdBothWays() {
        val d = ImplantShakeDetector()
        assertTrue(run(SimFace.SHAKE).any { d.add(it.t + 1, it.yaw) })
    }

    @Test fun neutralNeverShakes() {
        val d = ImplantShakeDetector()
        assertFalse(run(SimFace.NEUTRAL).any { d.add(it.t + 1, it.yaw) })
    }

    @Test fun blinkDoubleBlinksAndNeutralDoesNot() {
        fun count(s: SimFace): Int { val g = FaceGestures(0, Long.MAX_VALUE); return run(s).count { g.sample(it, it.t) } }
        assertTrue(count(SimFace.BLINK) >= 2)
        assertEquals(0, count(SimFace.NEUTRAL))
    }

    @Test fun neutralIsRelaxedAndSmileIsNot() {
        val n = NeutralFace(.05f, .10f, .08f)
        assertTrue(run(SimFace.NEUTRAL).all { n.relaxed(it) })
        assertFalse(n.relaxed(simSample(SimFace.SMILE, 0)))
        assertFalse(n.relaxed(simSample(SimFace.AWAY, 0)))
        assertFalse(simSample(SimFace.NOFACE, 0).face)
        assertTrue(run(SimFace.NOD).any { it.pitch > 15 } && run(SimFace.NOD).any { it.pitch < -15 })
    }
}

package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test

class FaceBudgetTest {
    @Test fun windowReportsDetectorAndPublishRatesThenResets() {
        val b = FaceBudget(); b.reset(0)
        repeat(150) { b.detected() }; repeat(50) { b.published() }
        assertNull(b.take(4999))
        val r = b.take(5000)!!
        assertEquals(30.0, r.detectorFps, 0.0); assertEquals(10.0, r.publishFps, 0.0)
        assertEquals("scanner detector=30.0fps publish=10.0fps", r.line())
        assertNull(b.take(9999)); assertEquals(0.0, b.take(10000)!!.detectorFps, 0.0)
    }
}

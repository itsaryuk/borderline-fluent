package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test

class ReferralGaugeTest {
    @Test fun visibleFromStartOfFreeOnlyAtDesk() {
        for (act in Act.entries) for (room in listOf(false, true))
            assertEquals(act == Act.FREE && !room, referralGaugeVisible(act, room))
    }

    @Test fun refusalWarningTracksSuspicionAndLowPatience() {
        assertEquals(0.1f, referralRefusalRisk(0.1, 0.9), 0.001f)
        assertEquals(0.95f, referralRefusalRisk(0.95, 0.9), 0.001f)
        assertEquals(0.95f, referralRefusalRisk(0.1, 0.05), 0.001f)
        assertEquals(1f, referralRefusalRisk(1.5, -1.0), 0.001f)
    }
}

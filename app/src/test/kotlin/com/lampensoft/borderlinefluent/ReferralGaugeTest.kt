package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test

class ReferralGaugeTest {
    @Test fun visibleFromStartOfFreeOnlyAtDesk() {
        for (act in Act.entries) for (room in listOf(false, true))
            assertEquals(act == Act.FREE && !room, referralGaugeVisible(act, room))
    }

    @Test fun scrambledCountriesAreFixedAndOnlyUpgradeThreeClearsThem() {
        val expected = setOf("de", "fr", "es", "ukraine", "turkey", "uae", "japan", "switzerland",
            "belgium", "senegal", "colombia", "capeverde")
        for (level in 0..2) assertEquals(expected,
            Country.all.filter { referralGaugeScrambled(it.id, ImplantPerks.fromUpgrades(level)) }.map { it.id }.toSet())
        assertTrue(Country.all.none { referralGaugeScrambled(it.id, ImplantPerks.fromUpgrades(3)) })
    }

    @Test fun scrambledBarsAreEmptyWithoutMotion() {
        for (f in 0..10) assertEquals(Triple(0f, 0f, 0), scrambledFills(false, f))
        assertTrue((0..10).any { scrambledFills(true, it).first > 0f })
    }

    @Test fun refusalWarningTracksSuspicionAndLowPatience() {
        assertEquals(0.1f, referralRefusalRisk(0.1, 0.9), 0.001f)
        assertEquals(0.95f, referralRefusalRisk(0.95, 0.9), 0.001f)
        assertEquals(0.95f, referralRefusalRisk(0.1, 0.05), 0.001f)
        assertEquals(1f, referralRefusalRisk(1.5, -1.0), 0.001f)
    }
}

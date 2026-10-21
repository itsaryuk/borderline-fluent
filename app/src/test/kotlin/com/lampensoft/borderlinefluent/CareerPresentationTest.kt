package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Test

class CareerPresentationTest {
    @Test fun passedCountUsesCommittedCountsAndFallsBackToHistoricalEvidence() {
        assertEquals(0, passedCount(null, "de"))
        val s = CareerSnapshot(countries = setOf("counter-only"), pendingAttempt = attempt(country = "pending"),
            album = Album(historicalCleared = setOf("de", "uk"), clears = mapOf("de" to 3),
                infiltration = mapOf("fr" to AwardMeta(1, "fr", "fr_x1", "desk", 2)),
                sabotage = mapOf("austria" to AwardMeta(2, "austria", "de_at_m", "desk", 2))))
        for ((country, count) in mapOf("de" to 3, "uk" to 1, "fr" to 0, "austria" to 0, // album marks alone (desk passes) are no machine clear
            "counter-only" to 0, "pending" to 0, "missing" to 0)) assertEquals(count, passedCount(s, country))
    }

    @Test fun migratedReceiptDisplaysOneCanonicalCountryAndKeepsUnitIdentity() {
        val marks = listOf(NewMark("sabotage", "grauwald"), NewMark("unit", "grauwald", "de_x2"))
        val migrated = marks.map { it.copy(countryId = CountryMigration.countryId(it.countryId)) }
        assertEquals(listOf(NewMark("unit", "de", "de_x2")), displayMarks(migrated))
    }
    @Test fun oneBreakHasOneDetailedMark() {
        val unit = NewMark("unit", "de", "de_x2")
        assertEquals(listOf(unit), displayMarks(listOf(NewMark("sabotage", "de"), unit)))
        assertEquals("DE_X2", displayUnit(unit.officerId!!))
    }
    @Test fun unrelatedAndStandaloneMarksArePreserved() {
        val marks = listOf(NewMark("sabotage", "de"), NewMark("unit", "fr", "fr_x1"), NewMark("infiltration", "de"))
        assertEquals(marks, displayMarks(marks))
    }
}

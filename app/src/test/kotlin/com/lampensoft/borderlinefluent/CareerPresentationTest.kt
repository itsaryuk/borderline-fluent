package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Test

class CareerPresentationTest {
    @Test fun quietAwardMarkDisplaysAfterSnapshotRoundTrip() {
        val a = attempt().copy(contract = "quiet")
        val awarded = reduceReplayResult(pending(a), victory(a)).snapshot
        val loaded = decoded(CareerJson.encode(awarded))
        val mark = loaded.receipt!!.newMarks.last()
        assertEquals("\"quiet\"", mark.extra["contract"])
        assertEquals(Contract.QUIET, markContract(mark))
        assertEquals(awarded, loaded)
    }

    @Test fun contractResultRequiresAcknowledgedReceipt() {
        val receipt = Receipt(1, ReplayOutcome.INFILTRATION, emptyList(), contract = "quiet")
        val snapshot = CareerSnapshot(receipt = receipt)
        val saved = CareerSave.Saved(snapshot, receipt)
        assertEquals(null, contractResult(saved, true))
        for (state in listOf(null, CareerSave.Saved(snapshot), CareerSave.WriteFailed(snapshot, "result"),
            CareerSave.Rejected(snapshot), CareerSave.Unreadable(CareerRead.Unreadable("bad")))) {
            assertEquals(null, contractResult(state, false))
        }
        assertEquals(Contract.QUIET to true, contractResult(saved, false))
    }

    @Test fun contractResultIgnoresAbsentAndUnknownContracts() {
        for (id in listOf(null, "unaided")) {
            val receipt = Receipt(1, ReplayOutcome.INFILTRATION, emptyList(), contract = id)
            assertEquals(null, contractResult(CareerSave.Saved(CareerSnapshot(), receipt), false))
        }
    }

    @Test fun contractResultUsesReceiptOutcomeEvenWithoutANewMark() {
        for (contract in Contract.entries) for (outcome in ReplayOutcome.entries) {
            val receipt = Receipt(1, outcome, emptyList(), contract = contract.id)
            val done = when (contract) {
                Contract.QUIET -> outcome == ReplayOutcome.INFILTRATION
                Contract.DISABLE -> outcome == ReplayOutcome.SABOTAGE
            }
            assertEquals("$contract / $outcome", contract to done,
                contractResult(CareerSave.Saved(CareerSnapshot(), receipt), false))
        }
    }

    @Test fun passedCountUsesCommittedCountsAndFallsBackToHistoricalEvidence() {
        assertEquals(0, passedCount(null, "de"))
        val s = CareerSnapshot(countries = setOf("counter-only"), pendingAttempt = attempt(country = "pending"),
            album = Album(historicalCleared = setOf("de", "uk"), clears = mapOf("de" to 3),
                infiltration = mapOf("fr" to AwardMeta(1, "fr", "fr_x1", "desk", 2)),
                sabotage = mapOf("austria" to AwardMeta(2, "austria", "de_at_m", "desk", 2))))
        for ((country, count) in mapOf("de" to 3, "uk" to 1, "fr" to 1, "austria" to 1,
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

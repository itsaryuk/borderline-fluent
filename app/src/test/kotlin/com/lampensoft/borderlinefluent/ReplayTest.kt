package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test

internal fun attempt(id: Long = 1, country: String = "de", officer: String = "de_x1") = Attempt(
    id, country, officer, "de", training = false, story = false, replayEligible = true, effectiveLevel = 2,
)
internal fun victory(a: Attempt = attempt()) = AttemptResult(a.id, a, Decision.PASS, Decision.PASS)
internal fun pending(a: Attempt = attempt()) = CareerSnapshot(nextAttemptId = a.id + 1, pendingAttempt = a)

class ReplayTest {
    @Test fun contractPredicateMatrix() {
        for (c in Contract.entries) for (outcome in ReplayOutcome.entries) {
            val expected = c == Contract.QUIET && outcome == ReplayOutcome.INFILTRATION ||
                c == Contract.DISABLE && outcome == ReplayOutcome.SABOTAGE
            assertEquals("$c / $outcome", expected, evaluateContract(c, outcome))
        }
        assertEquals(Contract.QUIET, Contract.fromId("quiet"))
        assertEquals(Contract.DISABLE, Contract.fromId("disable"))
        for (id in listOf(null, "unaided", "QUIET", "")) assertNull(Contract.fromId(id))
    }

    @Test fun contractAwardedOnceWithMark() {
        for (c in Contract.entries) {
            val a = attempt().copy(contract = c.id, loadoutId = "first", contentVersion = 2)
            val event = victory(a).copy(assessmentDecision = if (c == Contract.QUIET) Decision.PASS else Decision.BROKEN)
            val first = reduceReplayResult(pending(a), event)
            assertEquals(listOf(NewMark("contract", "de", extra = mapOf("contract" to "\"${c.id}\""))),
                first.newMarks.filter { it.kind == "contract" })
            val meta = AwardMeta(1, "de", "de_x1", "assessment", 2, "first", 2)
            assertEquals(meta, first.snapshot.album.contracts[c.id])
            val duplicate = reduceReplayResult(first.snapshot, event)
            assertEquals(first.snapshot, duplicate.snapshot)
            assertTrue(duplicate.newMarks.isEmpty())
            assertEquals(first.receipt, duplicate.receipt)
            val later = attempt(2, "fr", "fr_x2").copy(contract = c.id, effectiveLevel = 3)
            val second = reduceReplayResult(first.snapshot.copy(nextAttemptId = 3, pendingAttempt = later),
                victory(later).copy(assessmentDecision = event.assessmentDecision))
            assertTrue(second.newMarks.none { it.kind == "contract" })
            assertEquals(mapOf(c.id to meta), second.snapshot.album.contracts)
            assertEquals(c.id, second.receipt!!.contract)
        }
    }

    @Test fun contractNeverChangesCounters() {
        for (c in Contract.entries) for (desk in Decision.entries) for (assessment in listOf(null) + Decision.entries)
            for (terminal in TerminalKind.entries) for (debug in listOf(false, true)) {
                val plain = attempt()
                val chosen = plain.copy(contract = c.id)
                val event = AttemptResult(plain.id, plain, desk, assessment, terminalKind = terminal, debugForced = debug)
                val baseline = reduceReplayResult(pending(plain), event)
                val result = reduceReplayResult(pending(chosen), event.copy(context = chosen))
                assertEquals(baseline.rejected, result.rejected)
                assertEquals(baseline.receipt?.outcome, result.receipt?.outcome)
                assertEquals(baseline.snapshot.counters, result.snapshot.counters)
                assertEquals(baseline.snapshot.countries, result.snapshot.countries)
                assertEquals(baseline.snapshot.album, result.snapshot.album.copy(contracts = emptyMap()))
                assertEquals(baseline.newMarks, result.newMarks.filter { it.kind != "contract" })
            }
    }

    @Test fun missedContractHasReceiptButNoMark() {
        val a = attempt().copy(contract = "disable")
        val result = reduceReplayResult(pending(a), victory(a))
        assertEquals("disable", result.receipt!!.contract)
        assertTrue(result.newMarks.none { it.kind == "contract" })
        assertTrue(result.snapshot.album.contracts.isEmpty())
    }

    @Test fun debugAndStoryEarnNoContract() {
        for (c in Contract.entries) for (a in listOf(attempt().copy(debugForced = true),
            attempt().copy(story = true, replayEligible = false), attempt().copy(training = true, replayEligible = false),
            attempt().copy(replayEligible = false), attempt()).map { it.copy(contract = c.id) }) {
            val event = victory(a).copy(assessmentDecision = if (c == Contract.QUIET) Decision.PASS else Decision.BROKEN,
                debugForced = !a.debugForced && a.replayEligible)
            val result = reduceReplayResult(pending(a), event)
            assertTrue(result.newMarks.none { it.kind == "contract" })
            assertTrue(result.snapshot.album.contracts.isEmpty())
            assertEquals(c.id, result.receipt!!.contract)
        }
    }

    @Test fun unknownContractIdIsIgnoredByReducer() {
        val a = attempt().copy(contract = "unaided")
        val result = reduceReplayResult(pending(a), victory(a))
        assertFalse(result.rejected)
        assertTrue(result.newMarks.none { it.kind == "contract" })
        assertTrue(result.snapshot.album.contracts.isEmpty())
        assertEquals("unaided", result.receipt!!.contract)
    }

    @Test fun contractsFollowDeskAndAssessmentOutcomes() {
        val quiet = attempt().copy(contract = "quiet")
        val skipped = victory(quiet).copy(assessmentDecision = null, assessmentSkipped = true)
        assertEquals("desk", reduceReplayResult(pending(quiet), skipped).snapshot.album.contracts.getValue("quiet").stage)
        for (event in listOf(skipped.copy(everBroken = true), victory(quiet).copy(everBroken = true)))
            assertTrue(reduceReplayResult(pending(quiet), event).snapshot.album.contracts.isEmpty())
        val disable = attempt().copy(contract = "disable")
        for (assessment in listOf(false, true)) {
            val event = if (assessment) victory(disable).copy(assessmentDecision = Decision.BROKEN)
                else victory(disable).copy(deskDecision = Decision.BROKEN, assessmentDecision = null)
            assertEquals(if (assessment) "assessment" else "desk",
                reduceReplayResult(pending(disable), event).snapshot.album.contracts.getValue("disable").stage)
        }
    }

    @Test fun unfinishedContractsEarnNothingAndInterruptionKeepsChoice() {
        for (c in Contract.entries) for (terminal in listOf(TerminalKind.ABANDONED, TerminalKind.INTERRUPTED)) {
            val a = attempt().copy(contract = c.id)
            val live = captureDecision(LiveAttempt(1, a), 1, Decision.BROKEN, false, false)
            val event = finishAttempt(live, 1, terminal).terminal!!
            assertEquals(a, event.context)
            val result = reduceReplayResult(pending(a), event)
            assertTrue(result.newMarks.isEmpty())
            assertTrue(result.snapshot.album.contracts.isEmpty())
            if (terminal == TerminalKind.INTERRUPTED) {
                assertEquals(a.copy(status = AttemptStatus.INTERRUPTED), result.snapshot.pendingAttempt)
                assertNull(result.receipt)
            } else assertEquals(c.id, result.receipt!!.contract)
        }
    }

    @Test fun clearCountsArePerCountryAndExactlyOnceAcrossMethods() {
        var s = CareerSnapshot()
        for ((index, country) in listOf("de", "de", "de", "fr").withIndex()) {
            val a = attempt(index + 1L, country)
            val event = victory(a).copy(assessmentDecision = if (index == 1) Decision.BROKEN else Decision.PASS)
            s = reduceReplayResult(s.copy(nextAttemptId = a.id + 1, pendingAttempt = a), event).snapshot
            assertEquals(s, reduceReplayResult(s, event).snapshot)
            assertEquals(s, reduceReplayResult(s, victory()).snapshot)
        }
        assertEquals(mapOf("de" to 3, "fr" to 1), s.album.clears)
        assertEquals(1L, s.album.infiltration.getValue("de").attemptId)
        val skipped = victory().copy(assessmentDecision = null, assessmentSkipped = true)
        assertEquals(mapOf("de" to 1), reduceReplayResult(pending(), skipped).snapshot.album.clears)
    }

    @Test fun countsFollowMarkAndHistoricalEligibility() {
        for (a in listOf(attempt(), attempt().copy(training = true, replayEligible = false),
            attempt().copy(story = true, replayEligible = false), attempt().copy(replayEligible = false),
            attempt().copy(debugForced = true))) {
            for (decision in listOf(Decision.PASS, Decision.BROKEN, Decision.SECONDARY))
                for (terminal in TerminalKind.entries) for (debug in listOf(false, true)) {
                    val e = victory(a).copy(assessmentDecision = decision, terminalKind = terminal, debugForced = debug)
                    val expected = if (!a.training && !a.debugForced && !debug && (a.story || a.replayEligible) &&
                        terminal == TerminalKind.DECIDED && decision != Decision.SECONDARY) mapOf("de" to 1) else emptyMap()
                    assertEquals("$a / $e", expected, reduceReplayResult(pending(a), e).snapshot.album.clears)
                }
        }
        assertTrue(reduceReplayResult(pending(), victory().copy(everBroken = true)).snapshot.album.clears.isEmpty())
    }

    @Test(expected = ArithmeticException::class) fun clearCountOverflowCannotWrap() {
        reduceReplayResult(pending().copy(album = Album(clears = mapOf("de" to Int.MAX_VALUE))), victory())
    }

    @Test fun passedCountriesCombinesEvidenceWithoutInferringOtherClears() {
        assertEquals(emptySet<String>(), passedCountries(null))
        assertEquals(emptySet<String>(), passedCountries(CareerSnapshot()))
        val meta = AwardMeta(1, "de", "de_x1", "desk", 2)
        val snapshot = CareerSnapshot(countries = setOf("counter-only"), album = Album(
            historicalCleared = setOf("de", "retired"),
            infiltration = mapOf("de" to meta, "fr" to meta.copy(countryId = "fr")),
            sabotage = mapOf("de" to meta, "austria" to meta.copy(countryId = "austria")),
            units = mapOf("unit-only" to mapOf("de_x1" to meta.copy(countryId = "unit-only"))),
        ), pendingAttempt = attempt(country = "pending-only"))
        assertEquals(setOf("de", "retired", "fr", "austria"), passedCountries(snapshot))
    }

    @Test fun emptyMigration() {
        assertEquals(CareerSnapshot(), migrateCareer(CareerCounters(), emptySet()))
    }

    @Test fun legacyMigrationCopiesCountriesWithoutInventingMethods() {
        val ids = mutableSetOf("de", "valdenstein", "retired")
        val counters = CareerCounters(20, 15, 3, 6, 4, 7)
        val s = migrateCareer(counters, ids)
        ids.clear()
        assertEquals(counters, s.counters)
        assertEquals(setOf("de", "valdenstein", "retired"), s.countries)
        assertEquals(s.countries, s.album.historicalCleared)
        assertTrue(s.album.infiltration.isEmpty())
        assertTrue(s.album.sabotage.isEmpty())
        assertTrue(s.album.units.isEmpty())
        assertEquals(s, migrateCareer(counters, s.countries))
    }

    @Test fun classificationMatrix() {
        val pass = victory()
        assertEquals(ReplayOutcome.INFILTRATION, classifyReplayOutcome(pass))
        assertEquals(ReplayOutcome.FAILURE, classifyReplayOutcome(pass.copy(assessmentDecision = Decision.SECONDARY)))
        assertEquals(ReplayOutcome.INCOMPLETE, classifyReplayOutcome(pass.copy(assessmentDecision = null)))
        assertEquals(ReplayOutcome.SABOTAGE, classifyReplayOutcome(pass.copy(deskDecision = Decision.BROKEN, assessmentDecision = null)))
        assertEquals(ReplayOutcome.SABOTAGE, classifyReplayOutcome(pass.copy(assessmentDecision = Decision.BROKEN)))
        assertEquals(ReplayOutcome.CLEAR, classifyReplayOutcome(pass.copy(everBroken = true)))
        assertEquals(ReplayOutcome.ABANDONED, classifyReplayOutcome(pass.copy(terminalKind = TerminalKind.ABANDONED)))
        assertEquals(ReplayOutcome.INTERRUPTED, classifyReplayOutcome(pass.copy(terminalKind = TerminalKind.INTERRUPTED)))
    }

    @Test fun frozenEligibilityExcludesTrainingStoryAndDebug() {
        for (a in listOf(attempt().copy(training = true, replayEligible = false), attempt().copy(story = true, replayEligible = false), attempt())) {
            val event = victory(a).copy(debugForced = !a.training && !a.story)
            val s = reduceReplayResult(pending(a), event).snapshot
            assertTrue(s.album.infiltration.isEmpty())
            assertEquals(if (a.training) 0 else 1, s.counters.missions)
            assertEquals(if (a.story) setOf("de") else emptySet<String>(), s.album.historicalCleared)
        }
        assertEquals(ReplayOutcome.INFILTRATION, classifyReplayOutcome(victory()))
    }

    @Test fun victoryCountersAndReceipt() {
        val s = reduceReplayResult(pending(), victory()).snapshot
        assertEquals(CareerCounters(1, 1, 1, 1, 0, 1), s.counters)
        assertEquals(setOf("de"), s.countries)
        assertEquals(listOf(NewMark("infiltration", "de")), s.receipt!!.newMarks)
        assertEquals(1L, s.appliedThroughId)
        assertNull(s.pendingAttempt)
    }

    @Test fun firstMetadataAndPairIdentity() {
        var s = CareerSnapshot()
        for ((i, pair) in listOf("de" to "de_x1", "de" to "de_x1", "austria" to "de_x1", "de" to "de_x2").withIndex()) {
            val a = attempt(i + 1L, pair.first, pair.second)
            s = reduceReplayResult(s.copy(nextAttemptId = a.id + 1, pendingAttempt = a), victory(a).copy(assessmentDecision = Decision.BROKEN)).snapshot
        }
        assertEquals(4, s.counters.broken)
        assertEquals(0, s.counters.rooms)
        assertEquals(1L, s.album.sabotage.getValue("de").attemptId)
        assertEquals(1L, s.album.units.getValue("de").getValue("de_x1").attemptId)
        assertEquals(2, s.album.units.getValue("de").size)
        assertEquals(1, s.album.units.getValue("austria").size)
        assertEquals(listOf(NewMark("unit", "de", "de_x2")), s.receipt!!.newMarks)
    }

    @Test fun duplicateOldAndWrongPendingResults() {
        val first = reduceReplayResult(pending(), victory())
        val duplicate = reduceReplayResult(first.snapshot, victory())
        assertEquals(first.snapshot, duplicate.snapshot)
        assertTrue(duplicate.newMarks.isEmpty())
        assertEquals(first.snapshot.receipt, duplicate.receipt)
        assertTrue(reduceReplayResult(pending(), victory(attempt(2))).rejected)
        assertTrue(reduceReplayResult(pending(), victory(attempt(country = "jp"))).rejected)
        val a = attempt(2)
        val second = reduceReplayResult(first.snapshot.copy(nextAttemptId = 3, pendingAttempt = a), victory(a)).snapshot
        assertEquals(second, reduceReplayResult(second, victory()).snapshot)
        assertNull(reduceReplayResult(second, victory()).receipt)
        assertEquals(2L, second.receipt!!.id)
    }

    @Test fun abandonmentFailsOnceAndInterruptionRetainsPreparation() {
        val start = pending().copy(counters = CareerCounters(5, 4, 3, 3))
        val abandon = victory().copy(terminalKind = TerminalKind.ABANDONED)
        val failed = reduceReplayResult(start, abandon).snapshot
        assertEquals(CareerCounters(6, 4, 0, 3), failed.counters)
        assertEquals(failed, reduceReplayResult(failed, abandon).snapshot)
        val interrupted = reduceReplayResult(start, victory().copy(terminalKind = TerminalKind.INTERRUPTED)).snapshot
        assertEquals(start.counters, interrupted.counters)
        assertEquals(AttemptStatus.INTERRUPTED, interrupted.pendingAttempt!!.status)
        assertTrue(reduceReplayResult(interrupted, victory()).rejected)
    }

    @Test fun deskPassCannotSettleReplayAndFailureKeepsBest() {
        assertTrue(reduceReplayResult(pending(), victory().copy(assessmentDecision = null)).rejected)
        val s = reduceReplayResult(pending().copy(counters = CareerCounters(10, 8, 5, 7)), victory().copy(assessmentDecision = Decision.SECONDARY)).snapshot
        assertEquals(CareerCounters(11, 8, 0, 7), s.counters)
        assertTrue(s.countries.isEmpty())
    }
    @Test fun skippedAssessmentDeskPassIsTerminalInfiltrationExactlyOnce() {
        val skipped = victory().copy(assessmentDecision = null, assessmentSkipped = true)
        assertEquals(ReplayOutcome.INFILTRATION, classifyReplayOutcome(skipped))
        val r = reduceReplayResult(pending(), skipped)
        assertEquals(CareerCounters(1, 1, 1, 1, 0, 0), r.snapshot.counters) // no room counted
        assertEquals("desk", r.snapshot.album.infiltration.getValue("de").stage)
        assertEquals(r.snapshot, reduceReplayResult(r.snapshot, skipped).snapshot)
        // story never skips; a broken run or ineligible attempt is not an infiltration
        val story = attempt().copy(story = true, replayEligible = false)
        assertEquals(ReplayOutcome.INCOMPLETE, classifyReplayOutcome(victory(story).copy(assessmentDecision = null, assessmentSkipped = true)))
        assertEquals(ReplayOutcome.CLEAR, classifyReplayOutcome(skipped.copy(everBroken = true)))
        val live = skipAssessment(captureDecision(LiveAttempt(3, attempt()), 3, Decision.PASS, false, false), 3)
        assertEquals(ReplayOutcome.INFILTRATION, classifyReplayOutcome(finishAttempt(live, 3, TerminalKind.DECIDED).terminal!!))
    }

    @Test fun debugStoryKeepsCountersWithoutHistoricalAward() {
        val a = attempt().copy(story = true, replayEligible = false)
        val s = reduceReplayResult(pending(a), victory(a).copy(debugForced = true)).snapshot
        assertEquals(1, s.counters.cleared)
        assertEquals(setOf("de"), s.countries)
        assertTrue(s.album.historicalCleared.isEmpty())
    }

    @Test fun storyDeskPassIsStillNonterminal() {
        val a = attempt().copy(story = true, replayEligible = false)
        val event = victory(a).copy(assessmentDecision = null)
        assertEquals(ReplayOutcome.INCOMPLETE, classifyReplayOutcome(event))
        assertTrue(reduceReplayResult(pending(a), event).rejected)
        val training = a.copy(training = true)
        assertEquals(ReplayOutcome.CLEAR, classifyReplayOutcome(victory(training).copy(assessmentDecision = null)))
    }

    @Test fun repeatedInfiltrationKeepsFirstMetadataAndIndependentHistory() {
        val a = attempt()
        val old = pending(a).copy(album = Album(historicalCleared = setOf("de")))
        val first = reduceReplayResult(old, victory(a)).snapshot
        val later = attempt(2).copy(effectiveLevel = 3)
        val second = reduceReplayResult(first.copy(nextAttemptId = 3, pendingAttempt = later), victory(later)).snapshot
        assertEquals(first.album.copy(clears = second.album.clears), second.album)
        assertEquals(3, second.album.clears.getValue("de"))
        assertEquals(2, second.counters.missions)
        assertEquals(2, second.counters.streak)
        assertTrue(second.receipt!!.newMarks.isEmpty())
        val broken = attempt(3)
        val third = reduceReplayResult(second.copy(nextAttemptId = 4, pendingAttempt = broken),
            victory(broken).copy(assessmentDecision = Decision.BROKEN)).snapshot
        assertEquals(first.album.infiltration, third.album.infiltration)
        assertEquals(setOf("de"), third.album.historicalCleared)
        assertEquals(1, third.album.sabotage.size)
    }

    @Test fun debugBreakAndNonReplayAbandonmentGiveNoMarks() {
        val event = victory().copy(assessmentDecision = Decision.BROKEN, debugForced = true)
        val s = reduceReplayResult(pending(), event).snapshot
        assertEquals(1, s.counters.broken)
        assertTrue(s.album.units.isEmpty())
        for (a in listOf(attempt().copy(story = true, replayEligible = false), attempt().copy(training = true, replayEligible = false))) {
            val result = reduceReplayResult(pending(a), victory(a).copy(terminalKind = TerminalKind.ABANDONED)).snapshot
            assertEquals(CareerCounters(), result.counters)
            assertEquals(Album(), result.album)
        }
    }

    @Test fun startRequiresReadyBrainAndNoPendingWork() {
        assertTrue(canStartAttempt(true, true, false, false))
        assertFalse(canStartAttempt(false, true, false, false))
        assertFalse(canStartAttempt(true, false, false, false))
        assertFalse(canStartAttempt(true, true, true, false))
        assertFalse(canStartAttempt(true, true, false, true))
    }

    @Test fun stageCaptureIgnoresRoomSetupAndStaleCallbacks() {
        val a = attempt()
        val desk = captureDecision(LiveAttempt(7, a), 7, Decision.PASS, false, false)
        assertEquals(Decision.PASS, desk.desk)
        assertEquals(desk, captureDecision(desk, 6, Decision.BROKEN, true, true))
        assertEquals(desk, captureDecision(desk, 7, Decision.PASS, true, false))
        val room = captureDecision(desk, 7, Decision.SECONDARY, true, true)
        val ended = finishAttempt(room, 7, TerminalKind.DECIDED)
        assertEquals(ReplayOutcome.FAILURE, classifyReplayOutcome(ended.terminal!!))
        assertEquals(ended, finishAttempt(ended, 7, TerminalKind.ABANDONED))
        assertEquals(ended, finishAttempt(ended, 7, TerminalKind.INTERRUPTED))
        assertEquals(ended, captureDecision(ended, 7, Decision.BROKEN, true, true))
    }

    @Test fun debugTaintAndBrokenHistorySurviveStageChanges() {
        val a = attempt().copy(story = true, replayEligible = false)
        val desk = forceAttempt(captureDecision(LiveAttempt(2, a), 2, Decision.BROKEN, false, false), 2)
        assertEquals(desk, forceAttempt(desk, 1))
        val room = captureDecision(desk, 2, Decision.PASS, true, true)
        val event = finishAttempt(room, 2, TerminalKind.DECIDED).terminal!!
        assertTrue(event.debugForced)
        assertTrue(event.everBroken)
        assertEquals(a, event.context)
        assertTrue(reduceReplayResult(pending(a), event).snapshot.album.historicalCleared.isEmpty())
    }

    @Test fun lifecycleFirstTerminalWinsAndOldRunCannotQuitNewOne() {
        val live = LiveAttempt(8, attempt())
        assertEquals(live, finishAttempt(live, 7, TerminalKind.ABANDONED))
        for (cause in AbortCause.entries) {
            val kind = abortKind(cause)
            val done = finishAttempt(live, 8, kind)
            assertEquals(kind, done.terminal!!.terminalKind)
            assertEquals(done, finishAttempt(done, 8, TerminalKind.INTERRUPTED))
            val s = reduceReplayResult(pending(), done.terminal).snapshot
            assertEquals(if (cause == AbortCause.ABANDONED) 1 else 0, s.counters.missions)
        }
    }

    @Test fun preparationRoundTripPreservesCoverAndPersona() {
        val cover = Backstory.BENCH
        val p = cover.preparation(Persona.TRAINING)
        assertEquals(cover, p.restore(cover.country, cover.lang))
        assertEquals(Persona.TRAINING, p.persona)
        assertNull(p.restore(Country.all.first { it.id != cover.country.id }, cover.lang))
    }

    @Test fun startAcknowledgementHonoursBackgroundAndUnavailableBrain() {
        assertTrue(launchAfterStart(interrupted = false, brainAvailable = true))
        assertFalse(launchAfterStart(interrupted = true, brainAvailable = true))
        assertFalse(launchAfterStart(interrupted = false, brainAvailable = false))
        // Retrying the same persisted start after backgrounding must still interrupt it.
        val a = attempt()
        val live = LiveAttempt(12, a)
        val event = finishAttempt(live, 12, TerminalKind.INTERRUPTED).terminal!!
        val s = reduceReplayResult(pending(a), event).snapshot
        assertEquals(0, s.counters.missions)
        assertEquals(AttemptStatus.INTERRUPTED, s.pendingAttempt!!.status)
    }

    @Test fun recoveredPreparationRequiresExactCountryOfficerAndContent() {
        val c = Backstory.BENCH.country
        val a = attempt(country = c.id, officer = c.airports.first().officers.first()).copy(language = c.airports.first().lang,
            status = AttemptStatus.INTERRUPTED, preparation = Backstory.BENCH.preparation(Persona.TRAINING),
            contentVersion = 2, airportId = "fra", effectiveLevel = Persona.TRAINING.level)
        assertEquals(Backstory.BENCH, recoverPreparation(a, listOf(c), setOf(a.officerId), setOf("dest_de")))
        assertNull(recoverPreparation(a, emptyList(), setOf(a.officerId), setOf("dest_de")))
        assertNull(recoverPreparation(a, listOf(c), emptySet(), setOf("dest_de")))
        assertNull(recoverPreparation(a.copy(officerId = "retired"), listOf(c), setOf("retired"), setOf("dest_de")))
        assertNull(recoverPreparation(a.copy(contentVersion = 1), listOf(c), setOf(a.officerId), setOf("dest_de")))
        assertNull(recoverPreparation(a.copy(language = "missing"), listOf(c), setOf(a.officerId), setOf("dest_de")))
    }

}

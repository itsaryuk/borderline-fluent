package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test

internal fun attempt(id: Long = 1, country: String = "de", officer: String = "de_x1") = Attempt(
    id, country, officer, "de", training = false, story = false, replayEligible = true, effectiveLevel = 2, airportId = "fkb",
)
/** Referred at the desk, released by the associate. */
internal fun victory(a: Attempt = attempt()) = AttemptResult(a.id, a, Decision.REFERRAL, Decision.RELEASED)
/** A desk pass with no referral: the mission ends at the desk. */
internal fun deskPass(a: Attempt = attempt()) = AttemptResult(a.id, a, Decision.PASS, null)
internal fun pending(a: Attempt = attempt()) = CareerSnapshot(nextAttemptId = a.id + 1, pendingAttempt = a)

class ReplayTest {
    @Test fun epilogueVerdictAndResumeAreSavedWithEitherBuildingResult() {
        val a = attempt().copy(story = true, replayEligible = false, preparation = preparation())
        for (verdict in listOf(Decision.RELEASED, Decision.REFUSED)) {
            val before = pending(a).copy(act1Stage = Act1Stage.DONE, epilogueFrame = 5)
            val after = reduceReplayResult(before, AttemptResult(a.id, a, Decision.PASS, verdict)).snapshot
            val restarted = decoded(CareerJson.encode(after))
            assertEquals(after, restarted)
            assertEquals(6, restarted.epilogueFrame)
            assertEquals(verdict == Decision.RELEASED, restarted.epiloguePassed)
            assertEquals(0, restarted.epilogueDoses)
            val dosed = decoded(CareerJson.encode(reduceReplayResult(before, AttemptResult(a.id, a, Decision.PASS, verdict, doses = 2)).snapshot))
            assertEquals(2, dosed.epilogueDoses)
            assertEquals(Act.LEFT, restarted.act)
            assertNull(restarted.pendingAttempt)
            assertEquals(Screen.FINALE, pendingEnding(restarted.receipt))
            assertEquals(after, reduceReplayResult(after, AttemptResult(a.id, a, Decision.PASS, verdict)).snapshot)
        }
        for (frame in 0..5) {
            val before = pending(a).copy(epilogueFrame = frame)
            val interrupted = reduceReplayResult(before,
                AttemptResult(a.id, a, terminalKind = TerminalKind.INTERRUPTED)).snapshot
            assertEquals(frame, decoded(CareerJson.encode(interrupted)).epilogueFrame)
            assertEquals(AttemptStatus.INTERRUPTED, interrupted.pendingAttempt!!.status)
        }
    }

    @Test fun eitherBuildingVerdictPersistsFinaleAndIsIdempotent() {
        for (verdict in listOf(Decision.RELEASED, Decision.REFUSED)) {
            val a = attempt().copy(story = true, replayEligible = false)
            val event = AttemptResult(a.id, a, Decision.PASS, verdict)
            val result = reduceReplayResult(pending(a).copy(act = Act.STORY), event).snapshot
            assertEquals(Act.LEFT, result.act)
            assertEquals(Screen.FINALE, pendingEnding(decoded(CareerJson.encode(result)).receipt))
            assertEquals(result, reduceReplayResult(result, event).snapshot)
        }
    }

    @Test fun refusedDeskCanRetryAndTrainingDoesNotEndStory() {
        for (training in listOf(false, true)) {
            val a = attempt().copy(story = true, training = training, replayEligible = false)
            val result = reduceReplayResult(pending(a).copy(act = Act.STORY),
                AttemptResult(a.id, a, Decision.REFUSED)).snapshot
            assertEquals(Act.STORY, result.act)
            assertEquals(Screen.RESULT, pendingEnding(decoded(CareerJson.encode(result)).receipt))
        }
        val a = attempt().copy(story = true, training = true, replayEligible = false)
        val result = reduceReplayResult(pending(a).copy(act = Act.STORY), deskPass(a)).snapshot
        assertEquals(Act.STORY, result.act)
        assertEquals(Screen.RESULT, pendingEnding(result.receipt))
    }

    @Test fun contractPredicateMatrix() {
        for (c in Contract.entries) for (outcome in ReplayOutcome.entries) for (broken in listOf(false, true))
            for (mentioned in listOf(false, true)) for (doses in 0..2) for (released in listOf(false, true)) {
                val expected = outcome == ReplayOutcome.CLEARED && when (c) {
                    Contract.QUIET -> !broken
                    Contract.UNAIDED -> true
                    Contract.UNMENTIONED -> released && !mentioned
                    Contract.SAMPLES -> released && doses >= 2
                }
                assertEquals("$c / $outcome / $broken / $mentioned / $doses / $released", expected,
                    evaluateContract(c, outcome, broken, mentioned, doses, released))
            }
        assertEquals(Contract.UNMENTIONED, Contract.fromId("unmentioned"))
        assertEquals(Contract.SAMPLES, Contract.fromId("samples"))
        assertEquals(Contract.QUIET, Contract.fromId("quiet"))
        assertEquals(Contract.UNAIDED, Contract.fromId("unaided"))
        for (id in listOf(null, "disable", "QUIET", "")) assertNull(Contract.fromId(id))
    }

    @Test fun unaidedSurvivesReferralAndInterruption() {
        val a = attempt().copy(contract = "unaided")
        var live = LiveAttempt(1, a)
        live = captureDecision(live, 1, Decision.REFERRAL, false, false)
        live = captureDecision(live, 1, Decision.RELEASED, true, true)
        val event = finishAttempt(live, 1, TerminalKind.DECIDED).terminal!!
        assertTrue(reduceReplayResult(pending(a), event).receipt!!.contractMet)
        val interrupted = reduceReplayResult(pending(a),
            AttemptResult(a.id, a, terminalKind = TerminalKind.INTERRUPTED))
        assertEquals("unaided", interrupted.snapshot.pendingAttempt!!.contract)
        val resumed = interrupted.snapshot.pendingAttempt!!.copy(status = AttemptStatus.STARTED)
        assertTrue(reduceReplayResult(pending(resumed), victory(resumed)).receipt!!.contractMet)
        assertTrue(reduceReplayResult(pending(a), deskPass(a)).receipt!!.contractMet)
    }

    @Test fun contractAwardedOnceWithMark() {
        for (c in Contract.entries) {
            val a = attempt().copy(contract = c.id, loadoutId = "first", contentVersion = 2)
            val event = victory(a).copy(everBroken = false, doses = 2) // SAMPLES needs two
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
                victory(later).copy(everBroken = event.everBroken))
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
        val a = attempt().copy(contract = "unaided")
        val result = reduceReplayResult(pending(a), victory(a).copy(assessmentDecision = Decision.REFUSED))
        assertEquals("unaided", result.receipt!!.contract)
        assertTrue(result.newMarks.none { it.kind == "contract" })
        assertTrue(result.snapshot.album.contracts.isEmpty())
    }

    @Test fun debugAndStoryEarnNoContract() {
        for (c in Contract.entries) for (a in listOf(attempt().copy(debugForced = true),
            attempt().copy(story = true, replayEligible = false), attempt().copy(training = true, replayEligible = false),
            attempt().copy(replayEligible = false), attempt()).map { it.copy(contract = c.id) }) {
            val event = victory(a).copy(everBroken = false,
                debugForced = !a.debugForced && a.replayEligible)
            val result = reduceReplayResult(pending(a), event)
            assertTrue(result.newMarks.none { it.kind == "contract" })
            assertTrue(result.snapshot.album.contracts.isEmpty())
            assertEquals(c.id, result.receipt!!.contract)
        }
    }

    @Test fun unknownContractIdIsIgnoredByReducer() {
        val a = attempt().copy(contract = "retired-contract")
        val result = reduceReplayResult(pending(a), victory(a))
        assertFalse(result.rejected)
        assertTrue(result.newMarks.none { it.kind == "contract" })
        assertTrue(result.snapshot.album.contracts.isEmpty())
        assertEquals("retired-contract", result.receipt!!.contract)
    }

    @Test fun contractsFollowDeskAndAssessmentOutcomes() {
        val quiet = attempt().copy(contract = "quiet")
        assertEquals("desk", reduceReplayResult(pending(quiet), deskPass(quiet)).snapshot.album.contracts.getValue("quiet").stage)
        assertEquals("assessment", reduceReplayResult(pending(quiet), victory(quiet)).snapshot.album.contracts.getValue("quiet").stage)
        assertTrue(reduceReplayResult(pending(quiet), victory(quiet).copy(everBroken = true)).snapshot.album.contracts.isEmpty())
        // Unaided requires a final clearance, even when a crash causes referral.
        val unaided = attempt().copy(contract = "unaided")
        assertEquals("assessment", reduceReplayResult(pending(unaided), victory(unaided).copy(everBroken = true))
            .snapshot.album.contracts.getValue("unaided").stage)
        assertTrue(reduceReplayResult(pending(unaided), victory(unaided).copy(everBroken = true, assessmentDecision = Decision.REFUSED))
            .snapshot.album.contracts.isEmpty())
    }

    @Test fun unfinishedContractsEarnNothingAndInterruptionKeepsChoice() {
        for (c in Contract.entries) for (terminal in listOf(TerminalKind.ABANDONED, TerminalKind.INTERRUPTED)) {
            val a = attempt().copy(contract = c.id)
            val live = captureDecision(LiveAttempt(1, a), 1, Decision.REFERRAL, false, false, crashed = true)
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
            val event = victory(a).copy(everBroken = index == 1)
            s = reduceReplayResult(s.copy(nextAttemptId = a.id + 1, pendingAttempt = a), event).snapshot
            assertEquals(s, reduceReplayResult(s, event).snapshot)
            assertEquals(s, reduceReplayResult(s, victory()).snapshot)
        }
        assertEquals(mapOf("de" to 3, "fr" to 1), s.album.clears)
        assertEquals(1L, s.album.infiltration.getValue("de").attemptId)
        assertEquals(emptyMap<String, Int>(), reduceReplayResult(pending(), deskPass()).snapshot.album.clears) // a free-act desk pass is no clear
        assertEquals("desk", reduceReplayResult(pending(), deskPass()).snapshot.album.infiltration.getValue("de").stage) // the album mark stays
    }

    @Test fun freeActDeskPassAddsNoClearButMachineReleaseAddsOne() {
        val free = pending().copy(act = Act.FREE)
        val desk = reduceReplayResult(free, deskPass()).snapshot
        assertEquals(0, passedCount(desk, "de"))
        assertEquals(1, passedCount(reduceReplayResult(free, victory()).snapshot, "de"))
        // a desk pass after a release leaves the count alone
        val a = attempt(2)
        val after = reduceReplayResult(reduceReplayResult(free, victory()).snapshot.copy(pendingAttempt = a, nextAttemptId = 3), deskPass(a)).snapshot
        assertEquals(1, passedCount(after, "de"))
    }

    @Test fun countsFollowMarkAndHistoricalEligibility() {
        for (a in listOf(attempt(), attempt().copy(training = true, replayEligible = false),
            attempt().copy(story = true, replayEligible = false), attempt().copy(replayEligible = false),
            attempt().copy(debugForced = true))) {
            for (decision in listOf(Decision.RELEASED, Decision.REFUSED, Decision.CAUGHT))
                for (terminal in TerminalKind.entries) for (debug in listOf(false, true)) {
                    val e = victory(a).copy(assessmentDecision = decision, terminalKind = terminal, debugForced = debug)
                    val expected = if (!a.training && !a.debugForced && !debug && (a.story || a.replayEligible) &&
                        terminal == TerminalKind.DECIDED && decision == Decision.RELEASED) mapOf("de" to 1) else emptyMap()
                    assertEquals("$a / $e", expected, reduceReplayResult(pending(a), e).snapshot.album.clears)
                }
        }
        // A crash no longer wins on its own: crashed, referred and released is an ordinary clear.
        assertEquals(mapOf("de" to 1), reduceReplayResult(pending(), victory().copy(everBroken = true)).snapshot.album.clears)
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

    @Test fun classificationMatrix() {
        val pass = victory()
        assertEquals(ReplayOutcome.CLEARED, classifyReplayOutcome(pass))
        assertEquals(ReplayOutcome.CLEARED, classifyReplayOutcome(deskPass()))
        assertEquals(ReplayOutcome.CLEARED, classifyReplayOutcome(pass.copy(everBroken = true)))
        assertEquals(ReplayOutcome.REFUSED, classifyReplayOutcome(pass.copy(assessmentDecision = Decision.REFUSED)))
        assertEquals(ReplayOutcome.REFUSED, classifyReplayOutcome(pass.copy(deskDecision = Decision.REFUSED, assessmentDecision = null)))
        assertEquals(ReplayOutcome.CAUGHT, classifyReplayOutcome(pass.copy(assessmentDecision = Decision.CAUGHT)))
        assertEquals(ReplayOutcome.INCOMPLETE, classifyReplayOutcome(pass.copy(assessmentDecision = null))) // the associate awaits
        assertEquals(ReplayOutcome.INCOMPLETE, classifyReplayOutcome(pass.copy(deskDecision = null, assessmentDecision = null)))
        assertEquals(ReplayOutcome.ABANDONED, classifyReplayOutcome(pass.copy(terminalKind = TerminalKind.ABANDONED)))
        assertEquals(ReplayOutcome.INTERRUPTED, classifyReplayOutcome(pass.copy(terminalKind = TerminalKind.INTERRUPTED)))
    }

    @Test fun caughtIsRecordedAsAFailedMission() {
        val r = reduceReplayResult(pending().copy(counters = CareerCounters(4, 3, 2, 2)), victory().copy(assessmentDecision = Decision.CAUGHT))
        assertEquals(ReplayOutcome.CAUGHT, r.receipt!!.outcome)
        assertEquals(CareerCounters(5, 3, 0, 2), r.snapshot.counters)
        assertTrue(r.snapshot.countries.isEmpty())
        assertEquals(Album(), r.snapshot.album)
        assertEquals(r.snapshot, decoded(CareerJson.encode(r.snapshot)))
    }

    @Test fun frozenEligibilityExcludesTrainingStoryAndDebug() {
        for (a in listOf(attempt().copy(training = true, replayEligible = false), attempt().copy(story = true, replayEligible = false), attempt())) {
            val event = victory(a).copy(debugForced = !a.training && !a.story)
            val s = reduceReplayResult(pending(a), event).snapshot
            assertTrue(s.album.infiltration.isEmpty())
            assertEquals(if (a.training) 0 else 1, s.counters.missions)
            assertEquals(if (a.story) setOf("de") else emptySet<String>(), s.album.historicalCleared)
        }
        assertEquals(ReplayOutcome.CLEARED, classifyReplayOutcome(victory()))
    }

    @Test fun victoryCountersAndReceipt() {
        val s = reduceReplayResult(pending(), victory()).snapshot
        assertEquals(CareerCounters(1, 1, 1, 1, 0, 1), s.counters)
        assertEquals(setOf("de"), s.countries)
        assertEquals(listOf(NewMark("infiltration", "de")), s.receipt!!.newMarks)
        assertEquals(1L, s.appliedThroughId)
        assertNull(s.pendingAttempt)
    }

    @Test fun crashedReleasesCountAsBrokenButAddNoSabotage() {
        var s = CareerSnapshot()
        for ((i, pair) in listOf("de" to "de_x1", "de" to "de_x1", "austria" to "de_x1", "de" to "de_x2").withIndex()) {
            val a = attempt(i + 1L, pair.first, pair.second)
            s = reduceReplayResult(s.copy(nextAttemptId = a.id + 1, pendingAttempt = a), victory(a).copy(everBroken = true)).snapshot
        }
        assertEquals(4, s.counters.broken)
        assertEquals(4, s.counters.rooms)
        assertTrue(s.album.sabotage.isEmpty())
        assertTrue(s.album.units.isEmpty())
        assertEquals(setOf("de", "austria"), s.album.infiltration.keys)
        assertEquals(1L, s.album.infiltration.getValue("de").attemptId)
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

    @Test fun referralCannotSettleReplayAndFailureKeepsBest() {
        assertTrue(reduceReplayResult(pending(), victory().copy(assessmentDecision = null)).rejected)
        val s = reduceReplayResult(pending().copy(counters = CareerCounters(10, 8, 5, 7)), victory().copy(assessmentDecision = Decision.REFUSED)).snapshot
        assertEquals(CareerCounters(11, 8, 0, 7), s.counters)
        assertTrue(s.countries.isEmpty())
        val desk = reduceReplayResult(pending(), deskPass().copy(deskDecision = Decision.REFUSED))
        assertEquals(ReplayOutcome.REFUSED, desk.receipt!!.outcome)
        assertEquals(CareerCounters(1, 0, 0, 0), desk.snapshot.counters)
    }

    @Test fun deskPassWithoutReferralIsClearedExactlyOnce() {
        val r = reduceReplayResult(pending(), deskPass())
        assertEquals(ReplayOutcome.CLEARED, r.receipt!!.outcome)
        assertEquals(CareerCounters(1, 1, 1, 1, 0, 0), r.snapshot.counters) // no room counted
        assertEquals("desk", r.snapshot.album.infiltration.getValue("de").stage)
        assertEquals(r.snapshot, reduceReplayResult(r.snapshot, deskPass()).snapshot)
        val live = captureDecision(LiveAttempt(3, attempt()), 3, Decision.PASS, false, false)
        assertEquals(ReplayOutcome.CLEARED, classifyReplayOutcome(finishAttempt(live, 3, TerminalKind.DECIDED).terminal!!))
        // A referral is not terminal until the associate decides.
        val referred = captureDecision(LiveAttempt(4, attempt()), 4, Decision.REFERRAL, false, false)
        assertNull(finishAttempt(referred, 4, TerminalKind.DECIDED).terminal)
    }

    @Test fun debugStoryKeepsCountersWithoutHistoricalAward() {
        val a = attempt().copy(story = true, replayEligible = false)
        val s = reduceReplayResult(pending(a), victory(a).copy(debugForced = true)).snapshot
        assertEquals(1, s.counters.cleared)
        assertEquals(setOf("de"), s.countries)
        assertTrue(s.album.historicalCleared.isEmpty())
    }

    @Test fun storyDeskPassWaitsForTheBuildingTest() {
        val a = attempt().copy(story = true, replayEligible = false)
        val event = deskPass(a)
        assertEquals(ReplayOutcome.INCOMPLETE, classifyReplayOutcome(event))
        assertTrue(reduceReplayResult(pending(a), event).rejected)
        val training = a.copy(training = true)
        assertEquals(ReplayOutcome.CLEARED, classifyReplayOutcome(deskPass(training)))
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
            victory(broken).copy(everBroken = true)).snapshot
        assertEquals(first.album.infiltration, third.album.infiltration)
        assertEquals(setOf("de"), third.album.historicalCleared)
        assertTrue(third.album.sabotage.isEmpty())
    }

    @Test fun debugBreakAndNonReplayAbandonmentGiveNoMarks() {
        val event = victory().copy(everBroken = true, debugForced = true)
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

    @Test fun backIsIgnoredOnlyOnceTheStageVerdictIsCaptured() {
        val fresh = LiveAttempt(1, attempt())
        assertFalse(backIgnored(null, false))
        assertFalse(backIgnored(fresh, false))
        val desk = captureDecision(fresh, 1, Decision.REFERRAL, false, false)
        assertTrue(backIgnored(desk, false))
        assertFalse(backIgnored(desk, true)) // referral room running, no assessment yet
        assertTrue(backIgnored(captureDecision(desk, 1, Decision.CAUGHT, true, true), true))
    }

    @Test fun stageCaptureIgnoresRoomSetupAndStaleCallbacks() {
        val a = attempt()
        val desk = captureDecision(LiveAttempt(7, a), 7, Decision.REFERRAL, false, false)
        assertEquals(Decision.REFERRAL, desk.desk)
        assertEquals(desk, captureDecision(desk, 6, Decision.RELEASED, true, true, crashed = true))
        assertEquals(desk, captureDecision(desk, 7, Decision.RELEASED, true, false))
        val room = captureDecision(desk, 7, Decision.REFUSED, true, true)
        val ended = finishAttempt(room, 7, TerminalKind.DECIDED)
        assertEquals(ReplayOutcome.REFUSED, classifyReplayOutcome(ended.terminal!!))
        assertEquals(ended, finishAttempt(ended, 7, TerminalKind.ABANDONED))
        assertEquals(ended, finishAttempt(ended, 7, TerminalKind.INTERRUPTED))
        assertEquals(ended, captureDecision(ended, 7, Decision.RELEASED, true, true, crashed = true))
    }

    @Test fun debugTaintAndBrokenHistorySurviveStageChanges() {
        val a = attempt().copy(story = true, replayEligible = false)
        val desk = forceAttempt(captureDecision(LiveAttempt(2, a), 2, Decision.REFERRAL, false, false, crashed = true), 2)
        assertEquals(desk, forceAttempt(desk, 1))
        val room = captureDecision(desk, 2, Decision.RELEASED, true, true)
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
            airportId = "fra", effectiveLevel = Persona.TRAINING.level)
        assertEquals(Recovery(c.primary(), Backstory.BENCH), recoverPreparation(a, listOf(c), setOf(a.officerId), setOf("dest_de")))
        assertNull(recoverPreparation(a, emptyList(), setOf(a.officerId), setOf("dest_de")))
        assertNull(recoverPreparation(a, listOf(c), emptySet(), setOf("dest_de")))
        assertNull(recoverPreparation(a.copy(officerId = "retired"), listOf(c), setOf("retired"), setOf("dest_de")))
        assertNull(recoverPreparation(a.copy(language = "missing"), listOf(c), setOf(a.officerId), setOf("dest_de")))
    }

    @Test fun recoveryRequiresExactAirportArtLanguageOfficerAndPreparation() {
        val a = attempt(12, "de", "de_x2").copy(status = AttemptStatus.INTERRUPTED, preparation = preparation())
        fun recover(a: Attempt, art: Set<String> = setOf("dest_grauwald")) = recoverPreparation(a, Country.all, setOf("de_x2"), art)
        assertNotNull(recover(a))
        assertNull(recover(a.copy(status = AttemptStatus.STARTED)))
        assertNull(recoverPreparation(a, Country.all, setOf("de_x2"), setOf("dest_grauwald"), setOf("fr")))
        assertNull(recover(a, setOf("dest_rhenstal")))
        assertNull(recover(a.copy(airportId = "hhn")))
        assertNull(recover(a.copy(language = "fr")))
        assertNull(recover(a.copy(officerId = "de_f1")))
        assertNull(recover(a.copy(preparation = preparation().copy(countryId = "fr"))))
    }

    /** The destination comes from the attempt's airport, not the cover's country (a future nationality may differ). */
    @Test fun recoveryRestoresTheAttemptsDestination() {
        val canada = Country.of("canada")
        for (airport in canada.airports) {
            val officer = airport.officers.first()
            val a = attempt(3, canada.id, officer).copy(language = airport.lang, airportId = airport.id, status = AttemptStatus.INTERRUPTED,
                preparation = preparation().copy(countryId = canada.id, kind = TripKind.ARRIVAL)) // a German visiting Canada
            val r = recoverPreparation(a, Country.all, setOf(officer), setOf(airport.art))!!
            assertEquals(Destination(canada, airport), r.destination)
            assertEquals(airport.lang, r.cover.lang)
        }
    }

}

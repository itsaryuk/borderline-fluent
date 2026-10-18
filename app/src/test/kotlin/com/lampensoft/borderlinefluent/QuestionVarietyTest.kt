package com.lampensoft.borderlinefluent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Machine rooms vary: probe phrasings per level, and seeds the player heard in recent rooms are avoided. */
class QuestionVarietyTest {
    private val byCat = TN_QUESTIONS.groupBy({ it.first }, { it.second })

    @Test fun probeVariantsKeepTheirLevelAndEveryOneIsUsed() {
        for ((level, variants) in TN_PROBE_VARIANTS.withIndex()) for (v in variants) assertEquals(v, level, probeLevel(v))
        for (v in TN_PROBE_UNNAMED) assertEquals(0, probeLevel(v))
        assertEquals(-1, probeLevel(TN_QUESTIONS.first().second)); assertEquals(-1, probeLevel(null))
        val heard = (0..200).flatMap { Assessment(0.5, Backstory.BENCH, Random(it), evidence = 1.0).seeds.filter(::isProbe) }.toSet()
        assertEquals(TN_PROBE_VARIANTS.flatten().toSet(), heard)
        val story = (0..200).flatMap { Assessment(0.5, Backstory.BENCH, Random(it), evidence = 1.0, named = false, storyTest = true).seeds.filter(::isProbe) }.toSet()
        assertEquals(TN_PROBE_UNNAMED.toSet() + TN_PROBE_VARIANTS.drop(1).flatten(), story)
        for (lang in TN_LOCAL.keys + "en") assertTrue(story.none { "Free AI" in Assessment.local(it, Backstory.BENCH, lang)!! })
    }

    @Test fun roomsAvoidRecentSeedsWhileAlternativesRemain() {
        for (s in 0..100) {
            val first = Assessment(0.5, Backstory.BENCH, Random(s), evidence = 1.0)
            val again = Assessment(0.5, Backstory.BENCH, Random(s + 1000), evidence = 1.0, recent = first.seeds.toSet())
            assertTrue("$s ${first.seeds} ${again.seeds}", again.seeds.none { it in first.seeds })
            // Same inputs, same room.
            assertEquals(again.seeds, Assessment(0.5, Backstory.BENCH, Random(s + 1000), evidence = 1.0, recent = first.seeds.toSet()).seeds)
        }
    }

    @Test fun anExhaustedCategoryFallsBackToAnySeed() {
        val absurd = byCat.getValue("Absurd")
        val recent = (absurd + TN_PROBE_VARIANTS[0]).toSet()
        for (s in 0..50) {
            val a = Assessment(0.5, Backstory.BENCH, Random(s), evidence = 0.0, recent = recent)
            assertTrue(a.seeds.first() in absurd)
            assertEquals(1, a.seeds.count { it in TN_PROBE_VARIANTS[0] })
            assertEquals(8, a.seeds.size)
        }
    }

    @Test fun theBuildingTestKeepsTheBirdWhateverWasAskedBefore() {
        for (s in 0..50) {
            val a = Assessment(0.5, Backstory.BENCH, Random(s), evidence = 1.0, named = false, storyTest = true, recent = setOf(Assessment.BIRD_SEED))
            assertEquals(Assessment.BIRD_SEED, a.seeds[1])
        }
    }

    @Test fun askedSeedsAreRememberedAcrossRoomsAndCapped() {
        val first = List(10) { "q$it" }
        assertEquals(first, rememberSeeds(emptyList(), first))
        val next = rememberSeeds(first, listOf("q2", "x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8"))
        assertEquals(RECENT_SEEDS, next.size)
        assertEquals(listOf("q2", "x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8"), next.takeLast(9))
        assertFalse("q0" in next) // oldest dropped
        assertEquals(1, next.count { it == "q2" })
    }

    @Test fun theReducerRecordsTheRoomsSeedsWhateverTheOutcome() {
        for (decision in listOf(Decision.RELEASED, Decision.REFUSED, Decision.CAUGHT)) {
            val s = CareerSnapshot(act = Act.FREE, recentSeeds = listOf("old"))
            val a = attempt(s.nextAttemptId).copy(progressEligible = true)
            val asked = Assessment(0.5, Backstory.BENCH, Random(3)).seeds.take(5)
            val e = AttemptResult(a.id, a, deskDecision = Decision.REFERRAL, assessmentDecision = decision, seeds = asked)
            val after = reduceReplayResult(s.copy(pendingAttempt = a, nextAttemptId = a.id + 1), e).also { assertFalse(it.rejected) }.snapshot
            assertEquals(listOf("old") + asked, after.recentSeeds)
        }
        val live = LiveAttempt(1, attempt())
        val decided = captureDecision(live, 1, Decision.RELEASED, inRoom = true, roomReady = true, seeds = listOf("a", "b"))
        assertEquals(listOf("a", "b"), finishAttempt(decided, 1, TerminalKind.DECIDED).terminal!!.seeds)
    }

    @Test fun anAbandonedRoomsSeedsReachRecentSeeds() {
        val s = CareerSnapshot(act = Act.FREE, recentSeeds = listOf("old"))
        val a = attempt(s.nextAttemptId).copy(progressEligible = true)
        val asked = listOf("a", "b")
        val live = LiveAttempt(1, a)
        assertTrue(finishAttempt(live, 1, TerminalKind.ABANDONED).terminal!!.seeds.isEmpty())
        val aborted = finishAttempt(withSeeds(live, 1, asked), 1, TerminalKind.ABANDONED).terminal!!
        assertEquals(asked, aborted.seeds)
        val after = reduceReplayResult(s.copy(pendingAttempt = a, nextAttemptId = a.id + 1), aborted).also { assertFalse(it.rejected) }.snapshot
        assertEquals(listOf("old") + asked, after.recentSeeds)
        // A decided room keeps its own seeds; a finished attempt is untouched.
        val decided = captureDecision(live, 1, Decision.RELEASED, inRoom = true, roomReady = true, seeds = listOf("x"))
        assertEquals(listOf("x"), withSeeds(decided, 1, asked).seeds)
        assertEquals(live, withSeeds(live, 2, asked))
    }

    @Test fun recentSeedsRoundTripAndOldSavesReadEmpty() {
        val seeds = Assessment(0.5, Backstory.BENCH, Random(1), evidence = 1.0).seeds
        val s = CareerSnapshot(act = Act.FREE, recentSeeds = seeds)
        val back = decoded(CareerJson.encode(s))
        assertEquals(seeds, back.recentSeeds)
        assertTrue(back.extra.isEmpty())
        assertFalse(JSONObject(CareerJson.encode(CareerSnapshot())).has("recentSeeds"))
        val old = JSONObject(CareerJson.encode(s)).apply { remove("recentSeeds") }
        assertTrue(decoded(old.toString()).recentSeeds.isEmpty())
        assertTrue(decoded(old.toString()).extra.isEmpty())
        val damaged = JSONObject(CareerJson.encode(s)).apply { put("recentSeeds", JSONArray(listOf("a", "a", "", 3))) }
        assertEquals(listOf("a", "3"), decoded(damaged.toString()).recentSeeds)
    }
}

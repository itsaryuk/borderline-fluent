package com.lampensoft.borderlinefluent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class UsaFinishTest {
    private fun usaAttempt(id: Long = 1): Attempt {
        val airport = Country.of("usa").airports.first()
        return attempt(id, "usa", airport.officers.first()).copy(language = airport.lang, airportId = airport.id,
            contentVersion = 2, progressEligible = true,
            preparation = preparation().copy(countryId = "usa", kind = TripKind.ARRIVAL))
    }

    @Test fun firstReleaseSavesCompletionOnceAndTravelContinues() {
        val a = usaAttempt()
        val before = pending(a).copy(act = Act.FREE, usaUnlocked = true)
        val event = victory(a).copy(doses = 1)
        val first = reduceReplayResult(before, event).snapshot
        assertTrue(usaCompletionPending(first))
        assertEquals("assessment", first.album.infiltration.getValue("usa").stage)
        assertEquals(first, reduceReplayResult(first, event).snapshot)
        assertTrue(usaCompletionPending(decoded(CareerJson.encode(first))))
        assertEquals(Act.FREE, first.act)
        assertTrue(availableFlights(first, Country.all).isNotEmpty())
        assertFalse(badTester(first))
        val secondAttempt = usaAttempt(2)
        val again = reduceReplayResult(first.copy(pendingAttempt = secondAttempt, nextAttemptId = 3), victory(secondAttempt).copy(doses = 1)).snapshot
        assertFalse(usaCompletionPending(again))
        assertEquals(first.album.infiltration["usa"], again.album.infiltration["usa"])
        assertEquals(2, again.machineSessions)
        assertFalse(usaCompletionPending(freshFreeCareer()))
    }

    @Test fun aZeroDoseUsaReleaseIsTheEndingToo() {
        val a = usaAttempt()
        val honest = reduceReplayResult(pending(a).copy(act = Act.FREE, usaUnlocked = true), victory(a)).snapshot
        assertTrue(usaCompletionPending(honest))
        assertTrue(decoded(CareerJson.encode(honest)).usaCompleted)
        assertTrue(usaCompletionPending(decoded(CareerJson.encode(honest))))
        val c = usaAttempt(3)
        val notFree = reduceReplayResult(pending(c).copy(act = Act.LEFT, usaUnlocked = true), victory(c).copy(doses = 2)).snapshot
        assertFalse(notFree.usaCompleted)
    }

    @Test fun legacySaveDerivesUsaCompletedFromTheAlbumMarkButANewKeyIsTakenAsIs() {
        val a = usaAttempt()
        val honest = reduceReplayResult(pending(a).copy(act = Act.FREE, usaUnlocked = true), victory(a)).snapshot
        assertTrue("usa" in honest.album.infiltration)
        val raw = CareerJson.encode(honest.copy(usaCompleted = false)) // album mark, ending not earned
        assertEquals(false, org.json.JSONObject(raw).get("usaCompleted")) // always written
        assertFalse(decoded(raw).usaCompleted) // new save: the key is taken as is
        val legacy = org.json.JSONObject(raw).apply { remove("usaCompleted") }.toString()
        assertTrue(decoded(legacy).usaCompleted) // legacy with the mark: the ending was already seen
        val noMark = CareerJson.encode(freshFreeCareer())
        assertFalse(decoded(org.json.JSONObject(noMark).apply { remove("usaCompleted") }.toString()).usaCompleted)
    }

    @Test fun aReleaseOfAnAttemptThatIsNotReplayEligibleIsNotTheEnding() {
        val a = usaAttempt().copy(replayEligible = false)
        val s = reduceReplayResult(pending(a).copy(act = Act.FREE, usaUnlocked = true), victory(a).copy(doses = 1)).snapshot
        assertFalse(s.usaCompleted)
        assertFalse(usaCompletionPending(s))
    }

    @Test fun otherOutcomesTrainingStoryAndDebugNeverFinishUsa() {
        val a = usaAttempt()
        for (event in listOf(deskPass(a), victory(a).copy(assessmentDecision = Decision.REFUSED, doses = 1),
                victory(a).copy(assessmentDecision = Decision.CAUGHT, doses = 1), victory(a).copy(debugForced = true, doses = 1),
                victory(a).copy(terminalKind = TerminalKind.ABANDONED, doses = 1))) {
            assertFalse(usaCompletionPending(reduceReplayResult(pending(a).copy(act = Act.FREE), event).snapshot))
        }
        for (excluded in listOf(a.copy(training = true, replayEligible = false),
                a.copy(story = true, replayEligible = false), a.copy(debugForced = true),
                attempt().copy(progressEligible = true))) {
            assertFalse(usaCompletionPending(reduceReplayResult(pending(excluded).copy(act = Act.FREE), victory(excluded).copy(doses = 1)).snapshot))
        }
        for (act in listOf(Act.STORY, Act.LEFT))
            assertFalse(usaCompletionPending(reduceReplayResult(pending(a).copy(act = act), victory(a).copy(doses = 1)).snapshot))
    }

    @Test fun completionSurvivesRestartAndFailedAcknowledgementUntilItsReceiptIsSaved() = runBlocking {
        var disk = CareerInput(CareerJson.encode(CareerSnapshot(act = Act.FREE, usaUnlocked = true)), StoryReset.VERSION)
        var writesSucceed = true
        fun store() = CareerStore(read = { disk }, write = { raw, marker ->
            if (writesSucceed) disk = CareerInput(raw, marker)
            writesSucceed
        }, io = Dispatchers.Unconfined, publication = Dispatchers.Unconfined)
        val initial = store()
        initial.open()
        val a = (initial.start(usaAttempt()) as CareerSave.Saved).snapshot.pendingAttempt!!
        writesSucceed = false
        assertTrue(initial.settle(victory(a).copy(doses = 1)) is CareerSave.WriteFailed)
        assertFalse(usaCompletionPending(initial.acknowledged))
        writesSucceed = true
        assertTrue(usaCompletionPending((initial.retry() as CareerSave.Saved).snapshot))
        val reopened = store()
        assertTrue(usaCompletionPending((reopened.open() as CareerSave.Saved).snapshot))
        writesSucceed = false
        assertTrue(reopened.endingShown(a.id) is CareerSave.WriteFailed)
        assertTrue(usaCompletionPending(reopened.acknowledged))
        assertTrue(usaCompletionPending(decoded(disk.snapshot!!)))
        writesSucceed = true
        assertFalse(usaCompletionPending((reopened.retry() as CareerSave.Saved).snapshot))
        assertFalse(usaCompletionPending((store().open() as CareerSave.Saved).snapshot))
        assertFalse((reopened.endingShown(a.id) as CareerSave.Saved).changed)
        assertTrue("usa" in reopened.acknowledged!!.album.infiltration)
    }

    @Test fun usaAddsOneFixedDifficultyOffsetWithoutChangingQuestionsOrOtherAssessments() {
        val offset = assessmentOffset(Act.FREE, "usa", story = false, training = false)
        assertEquals(USA_ASSESSMENT_OFFSET, offset, 0.0)
        for (suspicion in listOf(0.0, 0.5, 1.0)) {
            val usual = Assessment(suspicion, Backstory.BENCH, Random(7))
            val usa = Assessment(suspicion, Backstory.BENCH, Random(7), difficultyOffset = offset)
            assertEquals(usual.index + USA_ASSESSMENT_OFFSET, usa.index, 0.0)
            assertEquals(usual.questions, usa.questions)
        }
        // The same merely neutral answers pass elsewhere and fail at the USA's stricter starting index.
        val neutral = RoomCheck(feeling = false, evasive = false, implant = false, inLanguage = true, clarity = 2, trick = false)
        val usual = Assessment(0.0, Backstory.BENCH, Random(7))
        val usa = Assessment(0.0, Backstory.BENCH, Random(7), difficultyOffset = offset)
        for (assessment in listOf(usual, usa)) {
            while (assessment.verdict() == null) { assessment.onCheck(neutral); assessment.next() }
        }
        assertEquals(Decision.RELEASED, usual.verdict())
        assertEquals(Decision.REFUSED, usa.verdict())
        for (act in Act.entries) for (country in listOf("usa", "de"))
            for (story in listOf(false, true)) for (training in listOf(false, true)) {
                val expected = if (act == Act.FREE && country == "usa" && !story && !training) USA_ASSESSMENT_OFFSET else 0.0
                assertEquals(expected, assessmentOffset(act, country, story, training), 0.0)
            }
    }
}

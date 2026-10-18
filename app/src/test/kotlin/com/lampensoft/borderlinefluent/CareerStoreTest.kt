package com.lampensoft.borderlinefluent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CareerStoreTest {
    private class Disk(var durable: CareerInput = CareerInput()) {
        var cache = durable
        var succeeds = true
        var throws = false
        var reads = 0
        val writes = mutableListOf<Pair<String, Int>>()
        var entered: CompletableDeferred<Unit>? = null
        var release: CompletableDeferred<Unit>? = null
        fun store(legacyFinale: Boolean = false) = CareerStore(read = { reads++; cache }, write = { raw, marker ->
            writes += raw to marker
            cache = CareerInput(raw, marker)
            entered?.complete(Unit); release?.await()
            if (throws) error("disk")
            if (succeeds) durable = cache
            succeeds
        }, io = Dispatchers.Unconfined, publication = Dispatchers.Unconfined, legacyFinale = { legacyFinale })
    }
    private suspend fun start(store: CareerStore): Attempt {
        val result = store.start(attempt().copy(preparation = preparation()), "interview") as CareerSave.Saved
        return result.snapshot.pendingAttempt!!
    }

    @Test fun arrivalDoneWithoutIdentityIsRejectedNotThrown() = runBlocking {
        val initial = CareerSnapshot(act = Act.STORY, act1Stage = Act1Stage.ARRIVAL, identity = null)
        val store = Disk(CareerInput(CareerJson.encode(initial), StoryReset.VERSION)).store()
        store.open()
        assertTrue(store.setAct1Stage(Act1Stage.DONE) is CareerSave.Rejected)
    }

    @Test fun epilogueFrameWriteFailureRetriesAndRestartKeepsFrameAndVerdict() = runBlocking {
        for (frame in 0..7) {
            val initial = CareerSnapshot(act = Act.LEFT, act1Stage = Act1Stage.DONE,
                epilogueFrame = frame, epiloguePassed = false)
            val disk = Disk(CareerInput(CareerJson.encode(initial), StoryReset.VERSION))
            val store = disk.store()
            store.open()
            disk.succeeds = false
            val next = (frame + 1).coerceAtMost(7)
            assertTrue(store.setEpilogueFrame(next) is CareerSave.WriteFailed)
            assertEquals(frame, store.acknowledged!!.epilogueFrame)
            disk.succeeds = true
            val saved = store.retry() as CareerSave.Saved
            val reopened = Disk(disk.durable).store().open() as CareerSave.Saved
            assertEquals(saved.snapshot, reopened.snapshot)
            assertEquals(next, reopened.snapshot.epilogueFrame)
            assertEquals(false, reopened.snapshot.epiloguePassed)
        }
    }

    @Test fun epilogueTestRestartNeverDiscardsPreparationBeforeReplacementCommits() = runBlocking {
        val a = attempt().copy(story = true, training = false, replayEligible = false, preparation = preparation(),
            status = AttemptStatus.INTERRUPTED)
        val original = pending(a).copy(epilogueFrame = 5)
        val disk = Disk(CareerInput(CareerJson.encode(original), StoryReset.VERSION))
        val store = disk.store()
        assertTrue(store.open() is CareerSave.Saved)
        disk.succeeds = false
        assertTrue(store.start(a.copy(id = 0)) is CareerSave.WriteFailed)
        assertEquals(a, store.acknowledged!!.pendingAttempt)
        assertEquals(original, decoded(disk.durable.snapshot!!))
        disk.succeeds = true
        val saved = store.retry() as CareerSave.Saved
        assertEquals(5, saved.snapshot.epilogueFrame)
        assertEquals(a.preparation, saved.snapshot.pendingAttempt!!.preparation)
        assertEquals(AttemptStatus.STARTED, saved.snapshot.pendingAttempt!!.status)
        assertEquals(original.nextAttemptId, saved.snapshot.pendingAttempt!!.id)
    }

    @Test fun epilogueChoiceClearsResumeAndUnshownReceiptAtomically() = runBlocking {
        val a = attempt().copy(story = true, replayEligible = false)
        val result = reduceReplayResult(pending(a), victory(a)).snapshot.copy(epilogueFrame = 7)
        for (act in listOf(Act.LEFT, Act.FREE)) {
            val disk = Disk(CareerInput(CareerJson.encode(result), StoryReset.VERSION))
            val store = disk.store(); store.open()
            store.setAct(act)
            val reopened = (Disk(disk.durable).store().open() as CareerSave.Saved).snapshot
            assertEquals(act, reopened.act)
            assertNull(reopened.epilogueFrame)
            assertNull(pendingEnding(reopened.receipt))
        }
    }

    /** A stored STARTED attempt becomes INTERRUPTED on open; a failed write retries those exact bytes. */
    @Test fun interruptionRetriesCandidateWithoutPublishingItOrReloadingFailedCache() = runBlocking {
        for (throws in listOf(false, true)) {
            val old = pending(attempt().copy(preparation = preparation())).copy(counters = CareerCounters(19, 11))
            val raw = "  " + CareerJson.encode(old) + "  "
            val disk = Disk(CareerInput(raw, 2)); disk.succeeds = false; disk.throws = throws
            val store = disk.store()
            val failed = store.open() as CareerSave.WriteFailed
            assertEquals(old, store.acknowledged)
            assertEquals(old, failed.snapshot)
            assertEquals(raw, disk.durable.snapshot)
            val candidate = disk.writes.single()
            assertEquals(AttemptStatus.INTERRUPTED, decoded(candidate.first).pendingAttempt!!.status)
            assertTrue(store.open() is CareerSave.WriteFailed)
            assertTrue(store.discardPreparation() is CareerSave.WriteFailed)
            assertTrue(store.start(attempt()) is CareerSave.WriteFailed)
            assertTrue(store.settle(victory()) is CareerSave.WriteFailed)
            assertEquals(1, disk.reads)
            disk.succeeds = true; disk.throws = false
            val saved = store.retry() as CareerSave.Saved
            assertEquals(candidate, disk.writes.last())
            assertEquals(old.counters, saved.snapshot.counters)
            assertEquals(saved.snapshot, (Disk(disk.durable).store().open() as CareerSave.Saved).snapshot)
        }
    }

    @Test fun startOverBacksUpTheUnreadableSnapshotThenStartsFresh() = runBlocking {
        val kept = mutableListOf<String?>()
        var disk = CareerInput("{broken", 2)
        fun store(ok: Boolean = true) = CareerStore(read = { disk }, write = { raw, marker -> disk = CareerInput(raw, marker); true },
            io = Dispatchers.Unconfined, publication = Dispatchers.Unconfined, backup = { kept += it; ok })
        val failing = store(ok = false)
        assertTrue(failing.open() is CareerSave.Unreadable)
        assertTrue(failing.startOver() is CareerSave.WriteFailed) // no backup, no overwrite
        assertEquals("{broken", disk.snapshot)
        val s = store()
        assertTrue(s.open() is CareerSave.Unreadable)
        assertTrue(s.resetProgress() is CareerSave.Unreadable) // the ordinary writes stay refused
        assertEquals(CareerSnapshot(), (s.startOver() as CareerSave.Saved).snapshot)
        assertEquals(listOf<String?>("{broken", "{broken"), kept)
        assertEquals(CareerSnapshot(), (store().open() as CareerSave.Saved).snapshot)
        assertTrue(store().startOver() is CareerSave.Rejected) // readable career: nothing to start over from
    }

    @Test fun firstRunLanguagesMustCoverPassportAndASecondCountry() {
        val japan = Nationality.find("japan")!!
        assertEquals(LangGap(null), languageGap(setOf("ja"), japan)) // one country only
        assertEquals(LangGap("ja"), languageGap(setOf("en"), japan)) // passport language missing
        assertNull(languageGap(setOf("ja", "en"), japan))
        assertEquals(LangGap(null), languageGap(setOf("zh"), null))
        assertNull(languageGap(setOf("ja", "zh"), null))
    }

    @Test fun futureContentNeverWrites() = runBlocking {
        val raw = org.json.JSONObject(CareerJson.encode(CareerSnapshot(counters = CareerCounters(8, 4)))).put("contentVersion", 4).toString()
        val future = Disk(CareerInput(raw, 2)); val store = future.store()
        assertTrue(store.open() is CareerSave.Unreadable)
        assertTrue(store.discardPreparation() is CareerSave.Unreadable)
        assertTrue(store.retry() is CareerSave.Unreadable)
        assertTrue(future.writes.isEmpty()); assertEquals(raw, future.durable.snapshot)
    }

    @Test fun falseWriteRetriesExactBytesWithoutReadingFailedCache() = runBlocking {
        val disk = Disk(); val store = disk.store()
        store.open(); val a = start(store)
        val before = store.acknowledged
        disk.succeeds = false
        assertTrue(store.settle(victory(a), "result") is CareerSave.WriteFailed)
        assertEquals(before, store.acknowledged)
        val failed = disk.writes.last()
        assertTrue(store.settle(victory(a).copy(terminalKind = TerminalKind.INTERRUPTED)) is CareerSave.WriteFailed)
        assertEquals(3, disk.writes.size)
        disk.succeeds = true
        val saved = store.retry() as CareerSave.Saved
        assertEquals("result", saved.continuation)
        assertEquals(failed, disk.writes.last())
        assertEquals(1, saved.snapshot.counters.missions)
        assertEquals(mapOf("de" to 1), saved.snapshot.album.clears)
        assertEquals(1, disk.reads)
        assertEquals(saved.snapshot, decoded(disk.durable.snapshot!!))
    }

    @Test fun exceptionAndFailedFreshWriteRemainStaged() = runBlocking {
        val disk = Disk()
        disk.throws = true
        val store = disk.store()
        assertTrue(store.open() is CareerSave.WriteFailed)
        assertNull(store.acknowledged)
        assertNull(disk.durable.snapshot)
        val bytes = disk.writes.single()
        assertTrue(store.open() is CareerSave.WriteFailed)
        assertEquals(1, disk.reads)
        disk.throws = false
        val saved = store.retry() as CareerSave.Saved
        assertEquals(bytes, disk.writes.last())
        assertEquals(CareerSnapshot(), saved.snapshot)
        assertEquals(2, disk.durable.marker)
    }

    @Test fun concurrentCallbacksWaitForOneAcknowledgement() = runBlocking {
        val disk = Disk(); val store = disk.store(); store.open(); val a = start(store)
        disk.entered = CompletableDeferred(); disk.release = CompletableDeferred()
        val one = async { store.settle(victory(a)) }
        disk.entered!!.await()
        val two = async { store.settle(victory(a)) }
        yield()
        assertEquals(0, store.acknowledged!!.counters.missions)
        assertFalse(one.isCompleted); assertFalse(two.isCompleted)
        disk.release!!.complete(Unit)
        val saved = one.await() as CareerSave.Saved
        val duplicate = two.await() as CareerSave.Saved
        assertEquals(saved.receipt, duplicate.receipt)
        assertFalse(duplicate.changed)
        assertEquals(mapOf("de" to 1), duplicate.snapshot.album.clears)
        assertEquals(1, store.acknowledged!!.counters.missions)
        assertEquals(3, disk.writes.size)
    }

    @Test fun thalassiaSeenClearsAnInterruptedTrip() = runBlocking {
        val a = attempt().copy(story = true, training = false, replayEligible = false, preparation = preparation(), status = AttemptStatus.INTERRUPTED)
        val disk = Disk(CareerInput(CareerJson.encode(pending(a)), StoryReset.VERSION))
        val store = disk.store()
        assertTrue(store.open() is CareerSave.Saved)
        val saved = store.thalassiaSeen() as CareerSave.Saved
        assertTrue(saved.snapshot.thalassiaSeen)
        assertNull(saved.snapshot.pendingAttempt)
        assertNull(saved.snapshot.booking)
    }

    @Test fun restartBeforeAndAfterResultCommit() = runBlocking {
        val disk = Disk(); val store = disk.store(); store.open(); val a = start(store)
        val before = disk.durable
        store.settle(victory(a))
        val after = disk.durable
        val interruptedStore = Disk(before).store()
        val interrupted = interruptedStore.open() as CareerSave.Saved
        assertEquals(AttemptStatus.INTERRUPTED, interrupted.snapshot.pendingAttempt!!.status)
        assertEquals(a.preparation, interrupted.snapshot.pendingAttempt.preparation)
        assertEquals(0, interrupted.snapshot.counters.missions)
        assertTrue(interruptedStore.settle(victory(a)) is CareerSave.Rejected)
        assertTrue(interruptedStore.start(a) is CareerSave.Rejected)
        interruptedStore.discardPreparation()
        assertEquals(2L, start(interruptedStore).id)
        val completedStore = Disk(after).store()
        val completed = completedStore.open() as CareerSave.Saved
        assertEquals(1, completed.snapshot.counters.missions)
        assertNull(completed.snapshot.pendingAttempt)
        assertEquals(completed.snapshot.receipt, (completedStore.settle(victory(a)) as CareerSave.Saved).receipt)
    }

    @Test fun markerRepairPreservesExactSnapshot() = runBlocking {
        val raw = "  " + CareerJson.encode(CareerSnapshot(counters = CareerCounters(3, 2), countries = setOf("retired"))) + "  "
        val disk = Disk(CareerInput(raw, null))
        val store = disk.store()
        assertEquals(3, (store.open() as CareerSave.Saved).snapshot.counters.missions)
        assertEquals(raw to 2, disk.writes.single())
    }

    @Test fun corruptSnapshotNeverWritesUntilExplicitPreparationDiscard() = runBlocking {
        val disk = Disk(CareerInput("{", 2)); val store = disk.store()
        assertTrue(store.open() is CareerSave.Unreadable)
        assertTrue(store.retry() is CareerSave.Unreadable)
        assertTrue(store.start(attempt()) is CareerSave.Unreadable)
        assertTrue(store.discardPreparation() is CareerSave.Unreadable)
        assertTrue(disk.writes.isEmpty())
        val json = org.json.JSONObject(CareerJson.encode(pending(attempt().copy(preparation = preparation()))))
        json.getJSONObject("pendingAttempt").getJSONObject("preparation").put("days", "bad")
        val damaged = Disk(CareerInput(json.toString(), 2)); val recovery = damaged.store()
        assertTrue(recovery.open() is CareerSave.Unreadable)
        assertTrue(damaged.writes.isEmpty())
        assertTrue(recovery.discardPreparation() is CareerSave.Saved)
        assertNull(recovery.acknowledged!!.pendingAttempt)
        assertEquals(2L, start(recovery).id)
    }

    @Test fun failedStartReservesOneIdAndBlocksOtherWrites() = runBlocking {
        val disk = Disk(); val store = disk.store(); store.open()
        disk.succeeds = false
        assertTrue(store.start(attempt().copy(preparation = preparation())) is CareerSave.WriteFailed)
        assertEquals(1L, store.acknowledged!!.nextAttemptId)
        val candidate = disk.writes.last()
        assertTrue(store.start(attempt(country = "jp")) is CareerSave.WriteFailed)
        disk.succeeds = true
        store.retry()
        assertEquals(candidate, disk.writes.last())
        assertEquals(2L, store.acknowledged!!.nextAttemptId)
        assertEquals("de", store.acknowledged!!.pendingAttempt!!.countryId)
        assertTrue(store.settle(victory(attempt(20))) is CareerSave.Rejected)
    }

    @Test fun cancellationCannotSplitCommitAndPublication() = runBlocking {
        val disk = Disk(); val store = disk.store(); store.open(); val a = start(store)
        disk.entered = CompletableDeferred(); disk.release = CompletableDeferred()
        val job = launch { store.settle(victory(a)) }
        disk.entered!!.await(); job.cancel(); disk.release!!.complete(Unit); job.join()
        assertEquals(1, decoded(disk.durable.snapshot!!).counters.missions)
        assertEquals(1, store.acknowledged!!.counters.missions)
        assertTrue(store.state.value is CareerSave.Saved)
    }
    @Test fun ioAndPublicationUseSeparateDispatchers() = runBlocking {
        val io = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "career-io") }.asCoroutineDispatcher()
        val publication = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "career-publication") }.asCoroutineDispatcher()
        try {
            val threads = mutableListOf<String>()
            val store = CareerStore(read = {
                threads += Thread.currentThread().name.substringBefore(" @coroutine#")
                CareerInput()
            }, write = { _, _ ->
                threads += Thread.currentThread().name.substringBefore(" @coroutine#")
                true
            }, io = io, publication = publication)
            var publishedOn: String? = null
            val observer = launch(Dispatchers.Unconfined) {
                store.state.collect { if (it is CareerSave.Saved) publishedOn = Thread.currentThread().name.substringBefore(" @coroutine#") }
            }
            store.open()
            observer.cancelAndJoin()
            assertEquals(listOf("career-io", "career-io"), threads)
            assertEquals("career-publication", publishedOn)
        } finally { io.close(); publication.close() }
    }

    @Test fun restartAfterFailedResultUsesDurableStartNotFailedCache() = runBlocking {
        val disk = Disk(); val store = disk.store(); store.open(); val a = start(store)
        disk.succeeds = false
        store.settle(victory(a))
        assertEquals(1, decoded(disk.cache.snapshot!!).counters.missions)
        val reloaded = Disk(disk.durable).store().open() as CareerSave.Saved
        assertEquals(0, reloaded.snapshot.counters.missions)
        assertEquals(AttemptStatus.INTERRUPTED, reloaded.snapshot.pendingAttempt!!.status)
        assertEquals(Album(), reloaded.snapshot.album)
    }


    /** PRD §6b: booking a trip with a queue scene marks it seen, in the save; a full catalog starts over. */
    @Test fun bookedEncounterIsSeenAndSurvivesRestart() = runBlocking {
        val booking = Booking("de", "fkb", "de_x1", preparation().copy(encounter = QueueEncounter("kolbasa")))
        val disk = Disk(); val store = disk.store()
        store.open()
        assertEquals(setOf("kolbasa"), (store.setBooking(booking) as CareerSave.Saved).snapshot.encountersSeen)
        val reloaded = (Disk(disk.durable).store().open() as CareerSave.Saved).snapshot
        assertEquals(setOf("kolbasa"), reloaded.encountersSeen)
        assertEquals(QueueEncounter("kolbasa"), reloaded.booking!!.preparation.encounter)
        assertEquals(setOf("kolbasa"), (store.setBooking(null) as CareerSave.Saved).snapshot.encountersSeen) // a cancel keeps it seen
    }

    /** A legacy career (no saved act, finale pref set) is in act 2: the wrap measures its tier's pool, not act 1's. */
    @Test fun bookingWrapMeasuresTheLegacyActsPool() = runBlocking {
        val ordinary = Encounters.pool(0).filter { !it.harvest }.map { it.id }
        val booked = ordinary.last()
        val seen = Encounters.pool(0).map { it.id }.toSet() - booked
        val initial = CareerSnapshot(act = null, progressPoints = 5, encountersSeen = seen)
        val booking = Booking("de", "fkb", "de_x1", preparation().copy(encounter = QueueEncounter(booked)))
        for (legacy in listOf(false, true)) {
            val store = Disk(CareerInput(CareerJson.encode(initial), StoryReset.VERSION)).store(legacyFinale = legacy)
            store.open()
            val after = (store.setBooking(booking) as CareerSave.Saved).snapshot.encountersSeen
            // Act 1 pool (tier 0) is covered: wraps. Act 2 at tier 2 has unseen tier 1-2 scenes: no wrap.
            assertEquals("legacy=$legacy", if (legacy) seen + booked else Encounters.pool(0).filter { it.harvest }.map { it.id }.toSet() + booked, after)
        }
    }

    /** Sticky booking: kept across a restart, cleared only by the interview's start, a cancel or a new passport. */
    @Test fun bookingSurvivesRestartUntilStartCancelOrNewIdentity() = runBlocking {
        val booking = Booking("de", "fkb", "de_x1", preparation())
        val disk = Disk(); val store = disk.store()
        store.open()
        assertEquals(booking, (store.setBooking(booking) as CareerSave.Saved).snapshot.booking)
        assertEquals(booking, (Disk(disk.durable).store().open() as CareerSave.Saved).snapshot.booking)
        assertNull(start(store).let { store.acknowledged!!.booking })
        val other = Disk(); val s2 = other.store(); s2.open(); s2.setBooking(booking)
        assertNull((s2.setBooking(null) as CareerSave.Saved).snapshot.booking)
        s2.setBooking(booking)
        val id = Identity.roll(kotlin.random.Random(1), Nationality.USA, Gender.WOMAN, NameList.FEMALE)
        assertNull((s2.setIdentity(id) as CareerSave.Saved).snapshot.booking)
        // A damaged booking is dropped on read; the career stays readable.
        val json = org.json.JSONObject(CareerJson.encode(CareerSnapshot(booking = booking)))
        json.getJSONObject("booking").put("officerId", "nobody")
        assertNull(decoded(json.toString()).booking)
        assertFalse(CareerJson.encode(CareerSnapshot()).contains("booking")) // no booking: bytes as before
    }

    @Test fun walkingAwayFromMarekTakesTwoDaysOnce() = runBlocking {
        val disk = Disk(CareerInput(CareerJson.encode(CareerSnapshot(act = Act.STORY, daysAway = 5)), StoryReset.VERSION))
        val store = disk.store(); store.open()
        assertEquals(7, (store.setAct(Act.FREE, walkedAway = true) as CareerSave.Saved).snapshot.daysAway)
        assertEquals(7, (store.setAct(Act.FREE, walkedAway = true) as CareerSave.Saved).snapshot.daysAway)
        assertEquals(7, (Disk(disk.durable).store().open() as CareerSave.Saved).snapshot.daysAway)
    }

    @Test fun returnToMarekClearsWalkedAwayAndGrantsTheLevelsEarnedWhileAway() = runBlocking {
        val initial = CareerSnapshot(act = Act.FREE, walkedAway = true, progressPoints = 6, upgradesGranted = 0)
        val disk = Disk(CareerInput(CareerJson.encode(initial), StoryReset.VERSION))
        val store = disk.store(); store.open()
        assertTrue((store.setAct(Act.FREE) as CareerSave.Saved).snapshot.walkedAway) // Keep helping never clears it
        val saved = (store.returnToMarek() as CareerSave.Saved).snapshot
        assertFalse(saved.walkedAway)
        assertTrue(saved.marekReturned)
        assertEquals(upgradesFor(6), saved.upgradesGranted)
        val reopened = (Disk(disk.durable).store().open() as CareerSave.Saved).snapshot
        assertTrue(reopened.marekReturned && !reopened.walkedAway)
        assertFalse((store.returnToMarek() as CareerSave.Saved).changed)
    }

    /** The finale's choice (PRD §3a): "Keep helping" keeps the identity with nothing booked; "Leave" keeps both. */
    @Test fun finaleChoiceKeepsTheIdentityAndFreeClearsTheBooking() = runBlocking {
        val story = Identity.roll(kotlin.random.Random(2), Nationality.find("de")!!, Gender.NONBINARY, NameList.FEMALE)
        val booking = Booking("de", "fkb", "de_x1", preparation())
        for (choice in listOf(Act.LEFT, Act.FREE)) {
            val disk = Disk(); val store = disk.store()
            store.open(); store.setIdentity(story); store.setBooking(booking)
            val saved = (store.setAct(choice) as CareerSave.Saved).snapshot
            assertEquals(choice, saved.act)
            assertEquals(choice, (Disk(disk.durable).store().open() as CareerSave.Saved).snapshot.act)
            val free = choice == Act.FREE
            assertEquals(story, saved.identity)
            assertEquals(story, (Disk(disk.durable).store().open() as CareerSave.Saved).snapshot.identity)
            assertEquals(if (free) null else booking, saved.booking)
            // Neither choice repeats passport setup.
            assertEquals(Screen.START,
                nextSetupScreen(true, false, passportDone = saved.identity != null, inputDone = true))
            val writes = disk.writes.size
            assertFalse((store.setAct(choice) as CareerSave.Saved).changed)
            assertEquals(writes, disk.writes.size)
        }
        // Act 2 keeps its rolled identity on a repeated choice.
        val disk = Disk(); val store = disk.store(); store.open(); store.setAct(Act.FREE)
        val own = Identity.roll(kotlin.random.Random(1), Nationality.USA, Gender.WOMAN, NameList.FEMALE)
        store.setIdentity(own)
        assertEquals(own, (store.setAct(Act.FREE) as CareerSave.Saved).snapshot.identity)
    }

    @Test fun interruptedStoryCanBeDiscardedBeforeKeepingHelping() = runBlocking {
        val own = Identity.roll(kotlin.random.Random(9), Nationality.find("de")!!, Gender.UNSAID, NameList.MALE)
        val a = attempt().copy(story = true, replayEligible = false, preparation = preparation())
        val old = pending(a).copy(act = Act.LEFT, identity = own)
        val disk = Disk(CareerInput(CareerJson.encode(old), StoryReset.VERSION))
        val store = disk.store()
        assertEquals(AttemptStatus.INTERRUPTED, (store.open() as CareerSave.Saved).snapshot.pendingAttempt!!.status)
        assertNull((store.discardPreparation("keep-helping") as CareerSave.Saved).snapshot.pendingAttempt)
        val free = (store.setAct(Act.FREE) as CareerSave.Saved).snapshot
        assertEquals(own, free.identity)
        assertNull(free.pendingAttempt)
        assertNull(free.booking)
        assertEquals(Act.FREE, free.act)
        assertEquals(free, (Disk(disk.durable).store().open() as CareerSave.Saved).snapshot)
    }

    /** A malformed pendingAttempt and no identity: "Begin mission" discards the preparation, then asks for the passport. */
    @Test fun malformedPreparationWithoutIdentityBeginsAtThePassport() = runBlocking {
        val json = org.json.JSONObject(CareerJson.encode(pending(attempt().copy(preparation = preparation()))))
        json.getJSONObject("pendingAttempt").getJSONObject("preparation").put("days", "bad")
        val disk = Disk(CareerInput(json.toString(), 2)); val store = disk.store()
        val bad = store.open() as CareerSave.Unreadable
        assertTrue(bad.read.preparationOnly)
        val saved = store.discardPreparation("new-mission") as CareerSave.Saved
        val identity = saved.snapshot.identity
        assertNull(identity)
        val setup = nextSetupScreen(true, false, passportDone = true && identity != null, inputDone = true)
        assertEquals(Screen.PASSPORT, missionScreen(identity != null, setup, playable = true, booked = false))
        assertEquals(Screen.PASSPORT, missionScreen(false, Screen.START, playable = true, booked = true))
        assertEquals(Screen.DESTINATION, missionScreen(true, Screen.START, playable = true, booked = true)) // resume, no reroll
        assertEquals(Screen.DESTINATION, missionScreen(true, Screen.START, playable = true, booked = false))
        assertEquals(Screen.LANGUAGES, missionScreen(true, Screen.START, playable = false, booked = true))
    }

    /** The app died during the ending beat, after the receipt committed: the restart opens the result once. */
    @Test fun anEndingNeverShownReopensOnceAfterARestart() = runBlocking {
        val disk = Disk(); val store = disk.store(); store.open()
        val a = start(store)
        store.settle(AttemptResult(a.id, a, Decision.REFUSED, null)) as CareerSave.Saved
        val after = Disk(disk.durable); val restarted = after.store()
        val r = (restarted.open() as CareerSave.Saved).snapshot.receipt!!
        assertEquals(Screen.RESULT, pendingEnding(r))
        assertEquals(Ending("de", room = false, broken = false, home = true, story = false), r.ending)
        assertEquals(Decision.REFUSED, r.ending!!.decision(r.outcome))
        val shown = restarted.endingShown(r.id) as CareerSave.Saved
        assertEquals(r.newMarks, shown.receipt!!.newMarks)
        assertNull(pendingEnding((Disk(after.durable).store().open() as CareerSave.Saved).snapshot.receipt))
    }

    @Test fun keepHelpingPreservesClearedActOneDestinationAcrossRestartAndDeparture() = runBlocking {
        val disk = Disk(); val store = disk.store(); store.open()
        val identity = Identity.roll(kotlin.random.Random(2), Nationality.find("fr")!!, Gender.WOMAN, NameList.FEMALE)
        store.setIdentity(identity)
        val a = (store.start(attempt().copy(story = true, replayEligible = false, preparation = preparation()))
            as CareerSave.Saved).snapshot.pendingAttempt!!
        val cleared = (store.settle(AttemptResult(a.id, a, Decision.PASS, Decision.RELEASED)) as CareerSave.Saved).snapshot
        assertEquals(Act.LEFT, cleared.act)
        assertEquals(a.countryId, cleared.lastPassedCountry)
        val free = (store.setAct(Act.FREE) as CareerSave.Saved).snapshot
        assertEquals(a.countryId, free.lastPassedCountry)
        val restored = (Disk(disk.durable).store().open() as CareerSave.Saved).snapshot
        assertEquals(Act.FREE, restored.act)
        assertEquals(a.countryId, restored.lastPassedCountry)
        assertEquals(Country.of(a.countryId).nation, freeDeparture(restored, identity))
        val row = countryPickerRowState(a.countryId, restored.lastPassedCountry,
            passedCount(restored, a.countryId), bookable = true)
        assertTrue(row.here)
        assertEquals(1, row.clears)
        assertFalse(row.enabled)
    }

    @Test fun eitherStoryTestVerdictReopensTheFinale() = runBlocking {
        for (verdict in listOf(Decision.RELEASED, Decision.REFUSED)) {
            val disk = Disk(); val store = disk.store(); store.open()
            val a = store.start(attempt().copy(story = true, replayEligible = false, preparation = preparation())).let { (it as CareerSave.Saved).snapshot.pendingAttempt!! }
            store.settle(AttemptResult(a.id, a, Decision.PASS, verdict))
            val saved = (Disk(disk.durable).store().open() as CareerSave.Saved).snapshot
            assertEquals(Screen.FINALE, pendingEnding(saved.receipt))
            assertEquals(Act.LEFT, saved.act)
        }
        assertNull(pendingEnding(null))
        assertNull(pendingEnding(Receipt(1, ReplayOutcome.CLEARED, emptyList()))) // older receipts: shown
    }
}

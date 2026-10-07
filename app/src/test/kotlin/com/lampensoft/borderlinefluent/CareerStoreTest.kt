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
        fun store() = CareerStore(read = { reads++; cache }, write = { raw, marker ->
            writes += raw to marker
            cache = CareerInput(raw, marker)
            entered?.complete(Unit); release?.await()
            if (throws) error("disk")
            if (succeeds) durable = cache
            succeeds
        }, io = Dispatchers.Unconfined, publication = Dispatchers.Unconfined)
    }
    private suspend fun start(store: CareerStore): Attempt {
        val result = store.start(attempt().copy(preparation = preparation()), "interview") as CareerSave.Saved
        return result.snapshot.pendingAttempt!!
    }

    @Test fun contentMigrationRetriesCandidateWithoutPublishingItOrReloadingFailedCache() = runBlocking {
        for (throws in listOf(false, true)) for (pending in listOf<Attempt?>(null, oldPending())) {
            val old = ownerCareer(pending)
            val raw = "  " + CareerJson.encode(old) + "  "
            val disk = Disk(CareerInput(raw, 1)); disk.succeeds = false; disk.throws = throws
            val store = disk.store()
            val failed = store.open() as CareerSave.WriteFailed
            assertEquals(old, store.acknowledged)
            assertEquals(old, failed.snapshot)
            assertEquals(setOf("ashbury", "austria", "grauwald"), passedCountries(store.acknowledged))
            assertEquals(raw, disk.durable.snapshot)
            val candidate = disk.writes.single()
            assertNotEquals(raw, candidate.first)
            assertEquals(3, decoded(candidate.first).contentVersion)
            assertEquals(setOf("uk", "austria", "de"), passedCountries(decoded(candidate.first)))
            if (pending != null) assertEquals(AttemptStatus.INTERRUPTED, decoded(candidate.first).pendingAttempt!!.status)
            assertTrue(store.open() is CareerSave.WriteFailed)
            assertTrue(store.discardPreparation() is CareerSave.WriteFailed)
            assertTrue(store.start(attempt()) is CareerSave.WriteFailed)
            assertTrue(store.settle(victory()) is CareerSave.WriteFailed)
            assertEquals(1, disk.reads)
            // Crash before commit: read the old durable bytes, not SharedPreferences' failed in-memory cache.
            val restarted = Disk(disk.durable).store().open() as CareerSave.Saved
            assertEquals(decoded(candidate.first), restarted.snapshot)
            disk.succeeds = true; disk.throws = false
            val saved = store.retry() as CareerSave.Saved
            assertEquals(candidate, disk.writes.last())
            assertEquals(decoded(candidate.first), saved.snapshot)
            assertEquals(old.counters, saved.snapshot.counters)
            assertEquals(saved.snapshot, store.acknowledged)
            val committed = Disk(disk.durable)
            assertEquals(saved.snapshot, (committed.store().open() as CareerSave.Saved).snapshot)
            assertTrue(committed.writes.isEmpty())
        }
    }

    @Test fun obsoletePreparationDiscardKeepsMigratedHistoryAndSequence() = runBlocking {
        val before = ownerCareer(oldPending())
        val store = Disk(CareerInput(CareerJson.encode(before), 1)).store()
        val opened = (store.open() as CareerSave.Saved).snapshot
        assertNull(recoverPreparation(opened.pendingAttempt!!, Country.all, setOf("de_x2"), setOf("dest_grauwald")))
        assertTrue(store.settle(victory(before.pendingAttempt!!)) is CareerSave.Rejected)
        val after = (store.discardPreparation() as CareerSave.Saved).snapshot
        assertEquals(opened.copy(pendingAttempt = null), after)
        assertEquals(before.counters, after.counters)
        assertEquals(before.nextAttemptId, after.nextAttemptId)
        assertEquals(setOf("uk", "austria", "de"), passedCountries(after))
    }

    @Test fun damagedLegacyPreparationMigratesOnlyOnSuccessfulDiscard() = runBlocking {
        val original = ownerCareer(oldPending())
        val json = org.json.JSONObject(CareerJson.encode(original))
        json.getJSONObject("pendingAttempt").getJSONObject("preparation").put("days", "broken")
        val raw = json.toString()
        val disk = Disk(CareerInput(raw, 1)); val store = disk.store()
        val read = store.open() as CareerSave.Unreadable
        assertEquals(raw, read.read.raw)
        assertTrue(disk.writes.isEmpty())
        val history = store.acknowledged
        disk.succeeds = false
        assertTrue(store.discardPreparation() is CareerSave.WriteFailed)
        assertEquals(history, store.acknowledged)
        assertEquals(setOf("ashbury", "austria", "grauwald"), passedCountries(history))
        val candidate = disk.writes.single()
        assertEquals(3, decoded(candidate.first).contentVersion)
        disk.succeeds = true
        val saved = store.retry() as CareerSave.Saved
        assertEquals(candidate, disk.writes.last())
        assertEquals(setOf("uk", "austria", "de"), passedCountries(saved.snapshot))
        assertEquals(original.counters, saved.snapshot.counters)
        assertEquals(original.nextAttemptId, saved.snapshot.nextAttemptId)
        assertNull(saved.snapshot.pendingAttempt)
    }

    @Test fun preferenceOnlyMigrationAndFutureContentNeverInventEvidence() = runBlocking {
        val disk = Disk(CareerInput(counters = CareerCounters(8, 4), countries = setOf("ashbury", "grauwald")))
        val s = (disk.store().open() as CareerSave.Saved).snapshot
        assertEquals(setOf("uk", "de"), passedCountries(s))
        assertTrue(s.album.infiltration.isEmpty()); assertTrue(s.album.sabotage.isEmpty()); assertTrue(s.album.units.isEmpty())
        val raw = org.json.JSONObject(CareerJson.encode(s)).put("contentVersion", 4).toString()
        val future = Disk(CareerInput(raw, 1)); val store = future.store()
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

    @Test fun exceptionAndFailedMigrationRemainStaged() = runBlocking {
        val disk = Disk(CareerInput(counters = CareerCounters(7, 3), countries = setOf("de")))
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
        assertEquals(7, saved.snapshot.counters.missions)
        assertEquals(1, disk.durable.marker)
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

    @Test fun markerRepairPreservesExactSnapshotAndLegacyIsIgnored() = runBlocking {
        val raw = "  " + CareerJson.encode(migrateCareer(CareerCounters(3, 2), setOf("retired"))) + "  "
        val disk = Disk(CareerInput(raw, null, CareerCounters(99)))
        val store = disk.store()
        assertEquals(3, (store.open() as CareerSave.Saved).snapshot.counters.missions)
        assertEquals(raw to 1, disk.writes.single())
    }

    @Test fun corruptSnapshotNeverWritesUntilExplicitPreparationDiscard() = runBlocking {
        val disk = Disk(CareerInput("{", 1)); val store = disk.store()
        assertTrue(store.open() is CareerSave.Unreadable)
        assertTrue(store.retry() is CareerSave.Unreadable)
        assertTrue(store.start(attempt()) is CareerSave.Unreadable)
        assertTrue(store.discardPreparation() is CareerSave.Unreadable)
        assertTrue(disk.writes.isEmpty())
        val json = org.json.JSONObject(CareerJson.encode(pending(attempt().copy(preparation = preparation()))))
        json.getJSONObject("pendingAttempt").getJSONObject("preparation").put("days", "bad")
        val damaged = Disk(CareerInput(json.toString(), 1)); val recovery = damaged.store()
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

}

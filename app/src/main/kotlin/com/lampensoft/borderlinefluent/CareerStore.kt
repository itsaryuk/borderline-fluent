package com.lampensoft.borderlinefluent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface CareerSave {
    data class Saved(
        val snapshot: CareerSnapshot, val receipt: Receipt? = null, val continuation: String? = null, val changed: Boolean = true,
    ) : CareerSave
    data class WriteFailed(val snapshot: CareerSnapshot?, val continuation: String?) : CareerSave
    data class Unreadable(val read: CareerRead.Unreadable) : CareerSave
    data class Rejected(val snapshot: CareerSnapshot?) : CareerSave
}

class CareerStore(
    private val read: suspend () -> CareerInput,
    private val write: suspend (snapshot: String, marker: Int) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val publication: CoroutineDispatcher = Dispatchers.Main,
) {
    private data class Staged(val snapshot: CareerSnapshot, val raw: String, val receipt: Receipt?, val continuation: String?)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<CareerSave?>(null)
    val state: StateFlow<CareerSave?> = mutableState.asStateFlow()
    @Volatile var acknowledged: CareerSnapshot? = null
        private set
    private var staged: Staged? = null
    private var unreadable: CareerRead.Unreadable? = null

    suspend fun open(): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        acknowledged?.let { return@withLock CareerSave.Saved(it, changed = false) }
        val input = try { withContext(io) { read() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return@withLock failRead(CareerRead.Unreadable(null)) }
        when (val loaded = loadCareer(input)) {
            is CareerRead.Unreadable -> failRead(loaded)
            is CareerRead.Ready -> {
                val migrated = loaded.snapshot
                val s = if (migrated.pendingAttempt?.status == AttemptStatus.STARTED)
                    migrated.copy(pendingAttempt = migrated.pendingAttempt.copy(status = AttemptStatus.INTERRUPTED)) else migrated
                if (loaded.needsWrite || s != loaded.original) {
                    // Only disk-backed history is acknowledged until the candidate commits. A failed
                    // SharedPreferences commit may already have changed its in-memory cache.
                    if (input.snapshot != null) acknowledge(loaded.original)
                    val unchanged = s == loaded.original
                    stage(s, null, null, input.snapshot.takeIf { unchanged })
                } else {
                    acknowledge(s)
                    publish(CareerSave.Saved(s, changed = false))
                }
            }
        }
    }

    suspend fun start(context: Attempt, continuation: String? = null): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (s.pendingAttempt != null || context.preparation == null || s.nextAttemptId == Long.MAX_VALUE)
            return@withLock CareerSave.Rejected(s)
        val a = context.copy(id = s.nextAttemptId, status = AttemptStatus.STARTED, deskDecision = null,
            assessmentDecision = null, everBroken = false, debugForced = false)
        stage(s.copy(nextAttemptId = s.nextAttemptId + 1, pendingAttempt = a), null, continuation)
    }

    suspend fun settle(event: AttemptResult, continuation: String? = null): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        val result = try { reduceReplayResult(s, event) } catch (_: ArithmeticException) { return@withLock CareerSave.Rejected(s) }
        if (result.rejected) return@withLock CareerSave.Rejected(s)
        if (result.snapshot == s) return@withLock CareerSave.Saved(s, result.receipt, changed = false)
        stage(result.snapshot, result.receipt, continuation)
    }

    suspend fun discardPreparation(continuation: String? = null): CareerSave = mutex.withLock {
        staged?.let { return@withLock CareerSave.WriteFailed(acknowledged, it.continuation) }
        val bad = unreadable
        if (bad != null && !bad.preparationOnly) return@withLock CareerSave.Unreadable(bad)
        val s = bad?.snapshot ?: acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (s.pendingAttempt?.status == AttemptStatus.STARTED) return@withLock CareerSave.Rejected(s)
        stage(CountryMigration.migrate(s.copy(pendingAttempt = null)), null, continuation)
    }

    suspend fun retry(): CareerSave = mutex.withLock {
        if (staged != null) commit() else unreadable?.let { CareerSave.Unreadable(it) }
            ?: acknowledged?.let { CareerSave.Saved(it, changed = false) } ?: CareerSave.Rejected(null)
    }

    private fun blocked(): CareerSave? = staged?.let { CareerSave.WriteFailed(acknowledged, it.continuation) }
        ?: unreadable?.let { CareerSave.Unreadable(it) }

    private suspend fun failRead(read: CareerRead.Unreadable): CareerSave {
        unreadable = read
        read.snapshot?.let { acknowledge(it) }
        return publish(CareerSave.Unreadable(read))
    }

    private suspend fun stage(snapshot: CareerSnapshot, receipt: Receipt?, continuation: String?, raw: String? = null): CareerSave {
        val bytes = try { raw ?: CareerJson.encode(snapshot) } catch (_: Exception) { return CareerSave.Rejected(acknowledged) }
        val decoded = CareerJson.decode(bytes) as? CareerRead.Ready ?: return CareerSave.Rejected(acknowledged)
        // Detach caller-owned lists/maps before suspending; failed commits retry these exact bytes.
        staged = Staged(decoded.snapshot, bytes, receipt, continuation)
        return commit()
    }

    private suspend fun commit(): CareerSave = withContext(NonCancellable) {
        val candidate = checkNotNull(staged)
        val saved = try { withContext(io) { write(candidate.raw, candidate.snapshot.schemaVersion) } }
        catch (_: Exception) { false }
        if (!saved) publish(CareerSave.WriteFailed(acknowledged, candidate.continuation))
        else {
            withContext(publication) {
                acknowledged = candidate.snapshot
                staged = null; unreadable = null
                val result = CareerSave.Saved(candidate.snapshot, candidate.receipt, candidate.continuation)
                mutableState.value = result
                result
            }
        }
    }

    private suspend fun acknowledge(snapshot: CareerSnapshot) = withContext(publication) { acknowledged = snapshot }
    private suspend fun publish(result: CareerSave): CareerSave = withContext(publication) { mutableState.value = result; result }
}

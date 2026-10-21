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
    private val legacyFinale: () -> Boolean = { false },
    /** Keeps a copy of the unreadable snapshot ([raw], or the stored one when null) before [startOver] replaces it; false aborts. */
    private val backup: suspend (raw: String?) -> Boolean = { true },
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
                val read = loaded.snapshot
                val s = if (read.pendingAttempt?.status == AttemptStatus.STARTED)
                    read.copy(pendingAttempt = read.pendingAttempt.copy(status = AttemptStatus.INTERRUPTED)) else read
                if (loaded.needsWrite || s != read) {
                    // Only disk-backed history is acknowledged until the candidate commits. A failed
                    // SharedPreferences commit may already have changed its in-memory cache.
                    if (input.snapshot != null) acknowledge(read)
                    stage(s, null, null, input.snapshot.takeIf { s == read })
                } else {
                    acknowledge(s)
                    publish(CareerSave.Saved(s, changed = false))
                }
            }
        }
    }

    /** [chain]: the leg-1 chain the boarding pass showed ([Chain.offer]); pinned only for its own id at its own airport. */
    suspend fun start(context: Attempt, continuation: String? = null, chain: Chain? = null): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        // Restart the epilogue test in one write: never lose its preparation between discard and start.
        val interrupted = s.pendingAttempt
        val resumeEpilogue = s.epilogueFrame == 5 && context.story && !context.training &&
            interrupted?.status == AttemptStatus.INTERRUPTED && context.copy(id = interrupted.id) == interrupted
        if (s.pendingAttempt != null && !resumeEpilogue || context.preparation == null || s.nextAttemptId == Long.MAX_VALUE)
            return@withLock CareerSave.Rejected(s)
        // The bonus mission is pinned here, seeded by the id; a resumed attempt keeps its own (the boarding pass showed it).
        // A career that walked away from Marek hears nothing from Free AI: no mission.
        // A chain: leg 2 at its own airport, or leg 1 as offered for this id; any other new mission cancels a waiting leg 2.
        val waiting = s.chain?.takeIf { it.to == context.airportId }
        val offered = chain?.takeIf { s.chain == null && it.stage == ChainStage.LEG1 && it.id == s.nextAttemptId && it.from == context.airportId }
        val fresh = (waiting ?: offered)?.let(Mission::of)?.takeIf { context.mission == null && Mission.due(context) && !s.walkedAway }
            ?: Mission.choose(s.nextAttemptId, s.planted, s.plantsSinceCustom).takeIf { context.mission == null && Mission.due(context) && !s.walkedAway }
        val a = context.copy(id = s.nextAttemptId, status = AttemptStatus.STARTED, deskDecision = null,
            assessmentDecision = null, everBroken = false, debugForced = false, mission = context.mission ?: fresh)
        // A newly pinned mission moves the plant-your-own cadence ([Mission.customDue]); a resumed one doesn't.
        val since = when (fresh?.kind) { null -> s.plantsSinceCustom; MissionKind.CUSTOM -> 0; else -> s.plantsSinceCustom + 1 }
        stage(s.copy(nextAttemptId = s.nextAttemptId + 1, pendingAttempt = a, booking = null, plantsSinceCustom = since,
            chain = if (fresh == null) s.chain else null), null, continuation)
    }

    suspend fun settle(event: AttemptResult, continuation: String? = null): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        val result = try { reduceReplayResult(s, event, legacyFinale()) } catch (_: ArithmeticException) { return@withLock CareerSave.Rejected(s) }
        if (result.rejected) return@withLock CareerSave.Rejected(s)
        if (result.snapshot == s) return@withLock CareerSave.Saved(s, result.receipt, changed = false)
        stage(result.snapshot, result.receipt, continuation)
    }

    /** The latest receipt's ending was shown: a restart no longer reopens it ([pendingEnding]). Keeps the receipt's marks. */
    suspend fun endingShown(id: Long): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        val r = s.receipt?.takeIf { it.id == id && it.ending != null } ?: return@withLock CareerSave.Saved(s, s.receipt, changed = false)
        stage(s.copy(receipt = r.copy(ending = null)), r.copy(ending = null), null)
    }

    /** The passport screen's identity; a reroll replaces it and cancels a trip booked under the old passport. */
    suspend fun setIdentity(identity: Identity): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (s.identity == identity) CareerSave.Saved(s, changed = false) else stage(s.copy(identity = identity, booking = null), null, null)
    }

    /** The booked trip (sticky: no rerolls); null cancels it. Cleared by [start] when its interview begins. */
    suspend fun setBooking(booking: Booking?): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (s.booking == booking) return@withLock CareerSave.Saved(s, changed = false)
        // The booked trip's queue encounter counts as seen: it never comes back until all have been (PRD §6b).
        val seen = booking?.preparation?.encounter?.let {
            Encounters.markSeen(s.encountersSeen, it.id, Encounters.pool(Encounters.tier(s, actOf(s.act, legacyFinale()))))
        } ?: s.encountersSeen
        stage(s.copy(booking = booking, encountersSeen = seen), null, null)
    }

    /** "Drop the chain": a waiting leg 2 is given up, no penalty. */
    suspend fun dropChain(): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (s.chain == null) CareerSave.Saved(s, changed = false) else stage(s.copy(chain = null), null, null)
    }

    /** The finale's choice ([Act]). Entering FREE keeps the passport and clears any pending Act 1 booking. */
    suspend fun setAct(act: Act, walkedAway: Boolean = false): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (s.act == act && s.epilogueFrame == null && s.walkedAway == (s.walkedAway || walkedAway)) CareerSave.Saved(s, changed = false)
        // Walking away from Marek takes two days (owner, 2026-10-17); only the first walk away counts them.
        else stage(s.copy(act = act, walkedAway = s.walkedAway || walkedAway, daysAway = s.daysAway + if (walkedAway && !s.walkedAway) WALK_AWAY_DAYS else 0, booking = if (act == Act.FREE) null else s.booking,
            epilogueFrame = null, receipt = if ((s.epilogueFrame ?: -1) >= 6)
                s.receipt?.copy(ending = null) else s.receipt), null, null)
    }

    /** Days that pass when the player walks away from Marek at the end of act 1. */
    companion object { const val WALK_AWAY_DAYS = 2 }

    /** Walked-away career writes to Marek: Marek's help resumes, with the levels the kept-accruing points already earned. */
    suspend fun returnToMarek(): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (!s.walkedAway) return@withLock CareerSave.Saved(s, changed = false)
        stage(s.copy(walkedAway = false, marekReturned = true, upgradesGranted = maxOf(s.upgradesGranted, upgradesFor(s.progressPoints))), null, null)
    }

    suspend fun setEpilogueFrame(frame: Int): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (frame !in 0..7) return@withLock CareerSave.Rejected(s)
        stage(s.copy(epilogueFrame = frame, epiloguePassed = if (frame == 0) null else s.epiloguePassed, epilogueDoses = if (frame == 0) null else s.epilogueDoses), null, null)
    }

    /** Onboarding transitions use the same staged write/retry boundary as attempts. */
    suspend fun setAct1Stage(next: Act1Stage, frame: Int? = null): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        val candidate = try { advanceAct1(s, next, frame ?: s.prologueFrame) }
            catch (_: IllegalArgumentException) { return@withLock CareerSave.Rejected(s) }
        stage(candidate, null, null)
    }

    suspend fun upgradeSeen(upgrade: Int): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (upgrade !in 1..s.upgradesGranted) return@withLock CareerSave.Rejected(s)
        if (upgrade in s.cutscenesSeen) CareerSave.Saved(s, changed = false)
        else stage(s.copy(cutscenesSeen = s.cutscenesSeen + upgrade, usaUnlocked = s.usaUnlocked || upgrade == 3), null, null)
    }

    suspend fun whySceneSeen(): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (s.whySceneSeen) CareerSave.Saved(s, changed = false) else stage(s.copy(whySceneSeen = true), null, null)
    }

    suspend fun visitSceneSeen(): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (s.visitSceneSeen) CareerSave.Saved(s, changed = false) else stage(s.copy(visitSceneSeen = true), null, null)
    }

    suspend fun thalassiaSeen(): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (s.thalassiaSeen) CareerSave.Saved(s, changed = false) else stage(s.copy(thalassiaSeen = true, pendingAttempt = null, booking = null, chain = null), null, null) // the journey ended: no interrupted trip or waiting leg 2 may resume past it
    }

    suspend fun newsSeen(id: String): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (id in s.newsSeen) CareerSave.Saved(s, changed = false) else stage(s.copy(newsSeen = s.newsSeen + id), null, null)
    }

    suspend fun solvaneSeen(): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (s.solvaneSeen) CareerSave.Saved(s, changed = false) else stage(s.copy(solvaneSeen = true), null, null)
    }

    /** Adds courier destinations for levels that have none; stored ones never change. */
    suspend fun addUpgradeDrops(drops: Map<Int, String>): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        val fresh = drops.filterKeys { it in 1..s.upgradesGranted && it !in s.upgradeDrops }
        if (fresh.isEmpty()) CareerSave.Saved(s, changed = false)
        else stage(s.copy(upgradeDrops = s.upgradeDrops + fresh), null, null)
    }

    suspend fun restartFree(): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        val s = acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (!badTester(s)) return@withLock CareerSave.Rejected(s)
        stage(freshFreeCareer(), null, "restart-free")
    }

    /** Settings > Reset progress: a brand-new act 1 career. Same staging as the other writes; refuses an unreadable career. */
    suspend fun resetProgress(): CareerSave = mutex.withLock {
        blocked()?.let { return@withLock it }
        if (acknowledged == null) return@withLock CareerSave.Rejected(null)
        stage(CareerSnapshot(), null, "reset-progress")
    }

    /** READ_ERROR's way out: back the unreadable snapshot up (never deleted), then stage a fresh career past [blocked]. */
    suspend fun startOver(): CareerSave = mutex.withLock {
        val bad = unreadable ?: return@withLock CareerSave.Rejected(acknowledged)
        if (staged != null) return@withLock blocked()!!
        val kept = try { withContext(io) { backup(bad.raw) } } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        if (!kept) return@withLock CareerSave.WriteFailed(acknowledged, null)
        stage(CareerSnapshot(), null, "start-over")
    }

    suspend fun discardPreparation(continuation: String? = null): CareerSave = mutex.withLock {
        staged?.let { return@withLock CareerSave.WriteFailed(acknowledged, it.continuation) }
        val bad = unreadable
        if (bad != null && !bad.preparationOnly) return@withLock CareerSave.Unreadable(bad)
        val s = bad?.snapshot ?: acknowledged ?: return@withLock CareerSave.Rejected(null)
        if (s.pendingAttempt?.status == AttemptStatus.STARTED) return@withLock CareerSave.Rejected(s)
        // A discarded leg 2 waits again ([start] moved it from the career onto the attempt).
        val chain = s.pendingAttempt?.mission?.chain?.takeIf { it.stage == ChainStage.LEG2 } ?: s.chain
        stage(s.copy(pendingAttempt = null, epilogueFrame = null, epiloguePassed = null, epilogueDoses = null, chain = chain), null, continuation)
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
        // Encode + decode off the caller's (Main) dispatcher; still before anything is staged or committed.
        val (bytes, read) = withContext(io) {
            val b = try { raw ?: CareerJson.encode(snapshot) } catch (_: Exception) { return@withContext null }
            b to CareerJson.decode(b)
        } ?: return CareerSave.Rejected(acknowledged)
        val decoded = read as? CareerRead.Ready ?: return CareerSave.Rejected(acknowledged)
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

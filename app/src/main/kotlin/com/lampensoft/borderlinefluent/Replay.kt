package com.lampensoft.borderlinefluent

// Unknown JSON values belong to their containing record, including first-award metadata.
typealias CareerFields = Map<String, String>

data class CareerCounters(
    val missions: Int = 0, val cleared: Int = 0, val streak: Int = 0, val best: Int = 0,
    val broken: Int = 0, val rooms: Int = 0, val extra: CareerFields = emptyMap(),
)

data class AwardMeta(
    val attemptId: Long, val countryId: String, val officerId: String, val stage: String,
    val effectiveLevel: Int?, val loadoutId: String? = null, val contentVersion: Int = 1,
    val extra: CareerFields = emptyMap(),
)

data class Album(
    val historicalCleared: Set<String> = emptySet(),
    val infiltration: Map<String, AwardMeta> = emptyMap(), val sabotage: Map<String, AwardMeta> = emptyMap(),
    val units: Map<String, Map<String, AwardMeta>> = emptyMap(), val extra: CareerFields = emptyMap(),
    val clears: Map<String, Int> = emptyMap(),
    val contracts: Map<String, AwardMeta> = emptyMap(),
)

data class Preparation(
    val name: String, val latin: String, val female: Boolean, val photo: Person, val countryId: String,
    val passportNo: String, val born: String, val home: String, val job: String, val from: String, val city: String,
    val purpose: String, val days: Int, val stayed: String, val travelledWith: String, val met: String,
    val waiting: String, val stamps: List<String>, val persona: Persona,
    val extra: CareerFields = emptyMap(), val photoExtra: CareerFields = emptyMap(), val personaExtra: CareerFields = emptyMap(),
)

enum class AttemptStatus { STARTED, INTERRUPTED }
enum class TerminalKind { DECIDED, ABANDONED, INTERRUPTED }
enum class ReplayOutcome { INFILTRATION, SABOTAGE, CLEAR, FAILURE, ABANDONED, INTERRUPTED, INCOMPLETE }

enum class Contract(val id: String) {
    QUIET("quiet"), DISABLE("disable");
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } }
}

fun evaluateContract(c: Contract, outcome: ReplayOutcome) = when (c) {
    Contract.QUIET -> outcome == ReplayOutcome.INFILTRATION
    Contract.DISABLE -> outcome == ReplayOutcome.SABOTAGE
}

data class Attempt(
    val id: Long, val countryId: String, val officerId: String, val language: String,
    val training: Boolean, val story: Boolean, val replayEligible: Boolean,
    val effectiveLevel: Int, val contentVersion: Int = 1, val loadoutId: String? = null,
    val deskDecision: Decision? = null, val assessmentDecision: Decision? = null,
    val everBroken: Boolean = false, val debugForced: Boolean = false,
    val preparation: Preparation? = null, val status: AttemptStatus = AttemptStatus.STARTED,
    val extra: CareerFields = emptyMap(), val airportId: String? = null,
    val contract: String? = null,
)

data class AttemptResult(
    val id: Long, val context: Attempt, val deskDecision: Decision? = context.deskDecision,
    val assessmentDecision: Decision? = context.assessmentDecision, val everBroken: Boolean = context.everBroken,
    val terminalKind: TerminalKind = TerminalKind.DECIDED, val debugForced: Boolean = context.debugForced,
    /** A post-story desk PASS that ended the mission without the assessment (the officer stayed unsuspicious). */
    val assessmentSkipped: Boolean = false,
)

data class NewMark(val kind: String, val countryId: String, val officerId: String? = null, val extra: CareerFields = emptyMap())
data class Receipt(val id: Long, val outcome: ReplayOutcome, val newMarks: List<NewMark>, val extra: CareerFields = emptyMap(),
    val contract: String? = null)
data class CareerSnapshot(
    val schemaVersion: Int = 1, val counters: CareerCounters = CareerCounters(), val countries: Set<String> = emptySet(),
    val album: Album = Album(), val nextAttemptId: Long = 1, val appliedThroughId: Long = 0,
    val pendingAttempt: Attempt? = null, val receipt: Receipt? = null, val extra: CareerFields = emptyMap(),
    val contentVersion: Int = 3,
)

/** Picker badges use committed evidence, including migrated history, without inferring clears from counters. */
fun passedCountries(snapshot: CareerSnapshot?): Set<String> = snapshot?.album?.let {
    it.historicalCleared + it.infiltration.keys + it.sabotage.keys
}.orEmpty()

data class ReplayReduction(
    val snapshot: CareerSnapshot, val newMarks: List<NewMark> = emptyList(), val receipt: Receipt? = null,
    val rejected: Boolean = false,
)

fun migrateCareer(counters: CareerCounters, countries: Set<String>): CareerSnapshot =
    CareerSnapshot(counters = counters.copy(extra = counters.extra.toMap()), countries = countries.toSet(),
        album = Album(historicalCleared = countries.toSet(), clears = countries.associateWith { 1 }))

fun classifyReplayOutcome(event: AttemptResult): ReplayOutcome {
    if (event.terminalKind == TerminalKind.INTERRUPTED) return ReplayOutcome.INTERRUPTED
    if (event.terminalKind == TerminalKind.ABANDONED) return ReplayOutcome.ABANDONED
    val a = event.context
    val final = event.assessmentDecision ?: event.deskDecision ?: return ReplayOutcome.INCOMPLETE
    if (final == Decision.SECONDARY) return ReplayOutcome.FAILURE
    if (final == Decision.PASS && event.assessmentDecision == null && !a.training && !(event.assessmentSkipped && !a.story))
        return ReplayOutcome.INCOMPLETE
    val eligible = a.replayEligible && !a.training && !a.story && !a.debugForced && !event.debugForced
    if (final == Decision.BROKEN) return if (eligible) ReplayOutcome.SABOTAGE else ReplayOutcome.CLEAR
    return if (eligible && event.deskDecision == Decision.PASS && (event.assessmentDecision == Decision.PASS || event.assessmentSkipped && event.assessmentDecision == null) &&
        !event.everBroken && !a.everBroken && a.deskDecision != Decision.BROKEN && event.deskDecision != Decision.BROKEN)
        ReplayOutcome.INFILTRATION else ReplayOutcome.CLEAR
}

fun reduceReplayResult(snapshot: CareerSnapshot, event: AttemptResult): ReplayReduction {
    if (event.id <= snapshot.appliedThroughId) return ReplayReduction(snapshot, receipt = snapshot.receipt?.takeIf { it.id == event.id })
    val a = snapshot.pendingAttempt
    if (a == null || a.id != event.id || event.context != a || a.status != AttemptStatus.STARTED)
        return ReplayReduction(snapshot, rejected = true)
    val outcome = classifyReplayOutcome(event)
    if (outcome == ReplayOutcome.INCOMPLETE) return ReplayReduction(snapshot, rejected = true)
    if (outcome == ReplayOutcome.INTERRUPTED)
        return ReplayReduction(snapshot.copy(pendingAttempt = a.copy(status = AttemptStatus.INTERRUPTED)))
    val passed = outcome in setOf(ReplayOutcome.INFILTRATION, ReplayOutcome.SABOTAGE, ReplayOutcome.CLEAR)
    val count = !a.training && (event.terminalKind == TerminalKind.DECIDED || a.replayEligible)
    val final = event.assessmentDecision ?: event.deskDecision
    val c = snapshot.counters
    val counters = if (!count) c else c.copy(
        missions = Math.addExact(c.missions, 1), cleared = Math.addExact(c.cleared, if (passed) 1 else 0),
        streak = if (passed) Math.addExact(c.streak, 1) else 0,
        best = if (passed) maxOf(c.best, Math.addExact(c.streak, 1)) else c.best,
        broken = Math.addExact(c.broken, if (passed && final == Decision.BROKEN) 1 else 0),
        rooms = Math.addExact(c.rooms, if (passed && event.assessmentDecision == Decision.PASS) 1 else 0),
    )
    var album = snapshot.album
    val marks = mutableListOf<NewMark>()
    val meta = AwardMeta(a.id, a.countryId, a.officerId, if (event.assessmentDecision != null) "assessment" else "desk",
        a.effectiveLevel, a.loadoutId, a.contentVersion)
    if (outcome == ReplayOutcome.INFILTRATION && a.countryId !in album.infiltration) {
        album = album.copy(infiltration = album.infiltration + (a.countryId to meta))
        marks += NewMark("infiltration", a.countryId)
    }
    if (outcome == ReplayOutcome.SABOTAGE) {
        if (a.countryId !in album.sabotage) {
            album = album.copy(sabotage = album.sabotage + (a.countryId to meta))
            marks += NewMark("sabotage", a.countryId)
        }
        val units = album.units[a.countryId].orEmpty()
        if (a.officerId !in units) {
            album = album.copy(units = album.units + (a.countryId to (units + (a.officerId to meta))))
            marks += NewMark("unit", a.countryId, a.officerId)
        }
    }
    val contract = Contract.fromId(a.contract)
    if (contract != null && evaluateContract(contract, outcome) && contract.id !in album.contracts) {
        album = album.copy(contracts = album.contracts + (contract.id to meta))
        // CareerFields contains raw JSON; these fixed IDs need only string quoting.
        marks += NewMark("contract", a.countryId, extra = mapOf("contract" to "\"${contract.id}\""))
    }
    val storyClear = count && passed && a.story && !a.debugForced && !event.debugForced
    if (outcome == ReplayOutcome.INFILTRATION || outcome == ReplayOutcome.SABOTAGE || storyClear)
        album = album.copy(clears = album.clears + (a.countryId to Math.addExact(passedCount(snapshot, a.countryId), 1)))
    if (storyClear) album = album.copy(historicalCleared = album.historicalCleared + a.countryId)
    val receipt = Receipt(a.id, outcome, marks.toList(), contract = a.contract)
    return ReplayReduction(snapshot.copy(counters = counters, countries = if (count && passed) snapshot.countries + a.countryId else snapshot.countries,
        album = album, appliedThroughId = a.id, pendingAttempt = null, receipt = receipt), receipt.newMarks, receipt)
}

/** Runtime capture is separate from the immutable, acknowledged STARTED context. */
data class LiveAttempt(
    val run: Int, val context: Attempt, val desk: Decision? = null, val assessment: Decision? = null,
    val everBroken: Boolean = false, val debugForced: Boolean = false, val terminal: AttemptResult? = null,
    val assessmentSkipped: Boolean = false,
)
enum class AbortCause { ABANDONED, INTERRUPTED }
fun abortKind(cause: AbortCause) = if (cause == AbortCause.ABANDONED) TerminalKind.ABANDONED else TerminalKind.INTERRUPTED
fun canStartAttempt(ready: Boolean, brainAvailable: Boolean, saving: Boolean, pending: Boolean) =
    ready && brainAvailable && !saving && !pending

fun captureDecision(live: LiveAttempt, run: Int, decision: Decision, inRoom: Boolean, roomReady: Boolean): LiveAttempt {
    if (live.run != run || live.terminal != null || inRoom && !roomReady) return live
    return if (inRoom) live.copy(assessment = live.assessment ?: decision, everBroken = live.everBroken || decision == Decision.BROKEN)
    else live.copy(desk = live.desk ?: decision, everBroken = live.everBroken || decision == Decision.BROKEN)
}
fun skipAssessment(live: LiveAttempt, run: Int): LiveAttempt =
    if (live.run != run || live.terminal != null) live else live.copy(assessmentSkipped = true)
fun forceAttempt(live: LiveAttempt, run: Int): LiveAttempt =
    if (live.run != run || live.terminal != null) live else live.copy(debugForced = true)
fun finishAttempt(live: LiveAttempt, run: Int, kind: TerminalKind): LiveAttempt {
    if (live.run != run || live.terminal != null) return live
    val event = AttemptResult(live.context.id, live.context, live.desk, live.assessment, live.everBroken, kind, live.debugForced, live.assessmentSkipped)
    if (classifyReplayOutcome(event) == ReplayOutcome.INCOMPLETE) return live
    return live.copy(terminal = event)
}

fun Backstory.preparation(persona: Persona) = Preparation(name, latin, female, photo, country.id, passportNo,
    born, home, job, from, city, purpose, days, stayed, travelledWith, met, waiting, stamps.toList(), persona)
fun Preparation.restore(country: Country, lang: String): Backstory? = if (country.id != countryId) null else
    Backstory(name, latin, female, photo, country, passportNo, born, home, job, from, city, purpose, days,
        stayed, travelledWith, met, waiting, stamps.toList(), lang)

fun launchAfterStart(interrupted: Boolean, brainAvailable: Boolean) = !interrupted && brainAvailable

/** Recovery never chooses a replacement country/unit or silently downloads missing preparation assets. */
fun recoverPreparation(a: Attempt, playable: List<Country>, sheets: Set<String>, art: Set<String>,
    enabledLanguages: Set<String> = Language.all.map { it.code }.toSet()): Backstory? {
    if (a.status != AttemptStatus.INTERRUPTED || a.contentVersion != 2) return null
    val country = playable.firstOrNull { it.id == a.countryId } ?: return null
    val airport = country.airports.firstOrNull { it.id == a.airportId } ?: return null
    if (airport.lang !in enabledLanguages || airport.lang != a.language || a.officerId !in airport.officers || airport.art !in art || a.officerId !in sheets) return null
    val preparation = a.preparation ?: return null
    if (preparation.persona.level != a.effectiveLevel || !CountryMigration.compatibleCover(preparation)) return null
    return preparation.restore(country, a.language)
}

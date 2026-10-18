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
    val name: String, val latin: String, val gender: Gender, val photo: Person, val countryId: String,
    val passportNo: String, val born: String, val home: String, val job: String, val from: String, val city: String,
    val purpose: String, val days: Int, val stayed: String, val travelledWith: String, val met: String,
    val waiting: String, val stamps: List<String>, val nationalityId: String, val kind: TripKind, val host: String, val persona: Persona,
    val extra: CareerFields = emptyMap(), val photoExtra: CareerFields = emptyMap(), val personaExtra: CareerFields = emptyMap(),
    /** The trip's queue encounter (PRD §6b); restored with the cover so a resume asks the same. */
    val encounter: QueueEncounter? = null,
    val homeArrivalTraining: Boolean = false,
)

enum class AttemptStatus { STARTED, INTERRUPTED }
enum class TerminalKind { DECIDED, ABANDONED, INTERRUPTED }
/** CLEARED: a desk pass or an associate's release. INCOMPLETE: no final decision yet (a referral awaits the associate). */
enum class ReplayOutcome { CLEARED, REFUSED, CAUGHT, ABANDONED, INTERRUPTED, INCOMPLETE }

enum class Contract(val id: String) {
    QUIET("quiet"), UNAIDED("unaided"), UNMENTIONED("unmentioned"), SAMPLES("samples");
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } }
}

/** [mentioned]: the implant came up at the desk or in the room; [doses], [released]: the machine room's ([AttemptResult]). */
fun evaluateContract(c: Contract, outcome: ReplayOutcome, everBroken: Boolean, mentioned: Boolean = false, doses: Int = 0,
                     released: Boolean = false) = outcome == ReplayOutcome.CLEARED && when (c) {
    Contract.QUIET -> !everBroken
    Contract.UNAIDED -> true
    Contract.UNMENTIONED -> released && !mentioned
    Contract.SAMPLES -> released && doses >= 2
}

data class Attempt(
    val id: Long, val countryId: String, val officerId: String, val language: String,
    val training: Boolean, val story: Boolean, val replayEligible: Boolean,
    val effectiveLevel: Int, val contentVersion: Int = 2, val loadoutId: String? = null,
    val deskDecision: Decision? = null, val assessmentDecision: Decision? = null,
    val everBroken: Boolean = false, val debugForced: Boolean = false,
    val preparation: Preparation? = null, val status: AttemptStatus = AttemptStatus.STARTED,
    val extra: CareerFields = emptyMap(), val airportId: String? = null,
    val contract: String? = null,
    /** Frozen at start, including on a retry after a passport edit. Null on pre-progression attempts. */
    val nationalityLanguages: Set<String>? = null,
    val upgradesHeld: Int = 0,
    /** Act FREE eligibility frozen at start and retained on resume/retry. */
    val progressEligible: Boolean = false,
)

data class AttemptResult(
    val id: Long, val context: Attempt, val deskDecision: Decision? = context.deskDecision,
    val assessmentDecision: Decision? = context.assessmentDecision, val everBroken: Boolean = context.everBroken,
    val terminalKind: TerminalKind = TerminalKind.DECIDED, val debugForced: Boolean = context.debugForced,
    /** Accepted poison samples of the machine room that decided ([Assessment.doses]); banked only when released. */
    val doses: Int = 0,
    /** One of those doses named the room's payload ([Assessment.payloadNamed]): only then does the release plant it. */
    val payloadNamed: Boolean = false,
    /** The machine room's seeds the player was asked ([Assessment.seeds]), remembered whatever the outcome. */
    val seeds: List<String> = emptyList(),
    /** The implant came up at the desk or in the room ([Contract.UNMENTIONED]). */
    val implantMentioned: Boolean = false,
    /** Counted flagged answers at the desk ([OfficerMind.flagCount]); a desk refusal closes the country only from [REFUSAL_FLAGS]. In memory only. */
    val deskFlags: Int = 0,
    /** The phone translator answered for the player in this attempt; saved as [CareerSnapshot.translatorUsed] with the result. */
    val translatorUsed: Boolean = false,
    /** The officer offered the phone translator in this attempt; saved as [CareerSnapshot.translatorUnlocked] with the result. */
    val translatorOffered: Boolean = false,
)

data class NewMark(val kind: String, val countryId: String, val officerId: String? = null, val extra: CareerFields = emptyMap())
data class Receipt(val id: Long, val outcome: ReplayOutcome, val newMarks: List<NewMark>, val extra: CareerFields = emptyMap(),
    val contract: String? = null, val contractMet: Boolean = false,
    /** A decided attempt whose ending (RESULT or FINALE) was not shown yet; null once shown, and on older receipts. */
    val ending: Ending? = null)

/** What a restart needs to show a decided attempt's result when the app died during the ending beat. */
/** [usaReleased]: this decided attempt is the career's first released free-act USA machine session (the ending; [usaCompletionPending]). */
data class Ending(val language: String, val room: Boolean, val broken: Boolean, val home: Boolean, val story: Boolean, val usaReleased: Boolean = false) {
    /** The ending's decision: a room clear is a release. */
    fun decision(outcome: ReplayOutcome) = when (outcome) {
        ReplayOutcome.CLEARED -> if (room) Decision.RELEASED else Decision.PASS
        ReplayOutcome.CAUGHT -> Decision.CAUGHT
        else -> Decision.REFUSED
    }
}

/** The screen a restart opens for [r] when its ending was never shown ([Receipt.ending]), else null. */
fun pendingEnding(r: Receipt?): Screen? {
    val e = r?.ending ?: return null
    return when (r.outcome) {
        ReplayOutcome.CLEARED, ReplayOutcome.REFUSED, ReplayOutcome.CAUGHT ->
            if (reachesFinale(e.decision(r.outcome), e.story, e.room)) Screen.FINALE else Screen.RESULT
        else -> null
    }
}
/** TRAINING/PLANE/ARRIVAL retain the legacy route for saves already in onboarding. */
enum class Act1Stage { PROLOGUE, TRAINING, PLANE, ARRIVAL, DONE, DEPARTURE, FLIGHT, PASSPORT, NEWS, HOME_TRAINING,
    /** Short scene after the home desk is passed, before the menu; saving DONE after it. Append-only: never reorder. */
    ESCORT }

/** Null is a legacy save; settled careers must never be sent back through onboarding. */
fun act1StageOf(s: CareerSnapshot?, legacyFinale: Boolean = false): Act1Stage =
    if (actOf(s?.act, legacyFinale) != Act.STORY) Act1Stage.DONE
    else s?.act1Stage ?: if (s?.lastPassedCountry != null || s?.storyLastCountry != null ||
        s?.receipt?.ending?.story == true) Act1Stage.DONE else Act1Stage.PROLOGUE

/** Existing onboarding passports skip creation; a partially edited new passport stays at PASSPORT. */
fun afterAct1Flight(s: CareerSnapshot?) = if (s?.identity == null) Act1Stage.PASSPORT else Act1Stage.NEWS

fun onboardingScreen(stage: Act1Stage): Screen = when (stage) {
    Act1Stage.PROLOGUE -> Screen.INTRO
    Act1Stage.TRAINING, Act1Stage.DEPARTURE, Act1Stage.HOME_TRAINING -> Screen.TRAINING_SETUP
    Act1Stage.PASSPORT -> Screen.PASSPORT
    Act1Stage.PLANE, Act1Stage.ARRIVAL, Act1Stage.FLIGHT, Act1Stage.NEWS, Act1Stage.ESCORT -> Screen.ACT1_CUTSCENE
    Act1Stage.DONE -> Screen.START
}

/** Legacy arrival captions finish onboarding; new careers train at home after the news. */
fun advanceAct1(s: CareerSnapshot, next: Act1Stage, frame: Int = s.prologueFrame): CareerSnapshot {
    val current = act1StageOf(s)
    require(frame in 0..17)
    require((current == Act1Stage.PROLOGUE && next == Act1Stage.PROLOGUE && frame == s.prologueFrame + 1) ||
        (current == Act1Stage.PROLOGUE && next in setOf(Act1Stage.TRAINING, Act1Stage.DEPARTURE)) ||
        (current == Act1Stage.FLIGHT && next == afterAct1Flight(s)) ||
        (current == Act1Stage.PASSPORT && next == Act1Stage.NEWS && s.identity != null) ||
        (current == Act1Stage.NEWS && next == Act1Stage.HOME_TRAINING) ||
        (current == Act1Stage.PLANE && next == Act1Stage.ARRIVAL) ||
        (current == Act1Stage.ARRIVAL && next == Act1Stage.DONE) ||
        (current == Act1Stage.ESCORT && next == Act1Stage.DONE))
    // The home desk already placed the career at home when ESCORT began.
    val home = if (next == Act1Stage.DONE && current != Act1Stage.ESCORT) requireNotNull(s.identity).nationality.id else null
    return s.copy(act1Stage = next, prologueFrame = frame,
        booking = if (next in setOf(Act1Stage.TRAINING, Act1Stage.DEPARTURE, Act1Stage.HOME_TRAINING) || home != null) null else s.booking,
        pendingAttempt = if (next in setOf(Act1Stage.TRAINING, Act1Stage.DEPARTURE, Act1Stage.HOME_TRAINING)) null else s.pendingAttempt,
        receipt = if (home != null) s.receipt?.copy(ending = null) else s.receipt,
        lastPassedCountry = home ?: s.lastPassedCountry, lastPassedAirport = if (home != null) null else s.lastPassedAirport, storyLastCountry = home ?: s.storyLastCountry,
        album = if (home != null) s.album.copy(historicalCleared = s.album.historicalCleared + home) else s.album)
}

data class CareerSnapshot(
    val schemaVersion: Int = StoryReset.VERSION, val counters: CareerCounters = CareerCounters(), val countries: Set<String> = emptySet(),
    val album: Album = Album(), val nextAttemptId: Long = 1, val appliedThroughId: Long = 0,
    val pendingAttempt: Attempt? = null, val receipt: Receipt? = null, val extra: CareerFields = emptyMap(),
    val contentVersion: Int = 3,
    /** The player's passport; null until rolled on the passport screen (setup can't finish without it). */
    val identity: Identity? = null,
    /** The booked trip, kept until its interview starts or the player cancels it (no rerolls). */
    val booking: Booking? = null,
    /** The act ([Act]); null when never saved (careers from before acts: read through [actOf]). */
    val act: Act? = null,
    /** Queue encounters seen since the catalog last started over ([Encounters.markSeen]). */
    val encountersSeen: Set<String> = emptySet(),
    /** Last successful story desk; first onboarding places the player at home after arrival. */
    val storyLastCountry: String? = null,
    val act1Stage: Act1Stage? = null,
    val prologueFrame: Int = 0,
    /** 0–4 storyboard, 5 test, 6 reveal, 7 choice; null outside the epilogue. */
    val epilogueFrame: Int? = null,
    val epiloguePassed: Boolean? = null,
    /** Accepted doses of the building test, saved with [epiloguePassed]; null on saves from before it was kept (the debrief then reads only the verdict). */
    val epilogueDoses: Int? = null,
    val trainedLanguages: Set<String> = emptySet(),
    val machineSessions: Int = 0,
    val progressPoints: Int = 0,
    /** Poison doses banked from released, progress-eligible machine sessions; only [reduceReplayResult] adds. */
    val doses: Int = 0,
    /** Payload motif ids ([Motif.id]) planted by released sessions with a dose naming it, oldest first, distinct ([Poison.plant]). */
    val planted: List<String> = emptyList(),
    /** English seeds of the player's recent machine rooms, oldest first, distinct ([rememberSeeds]); rooms avoid them. */
    val recentSeeds: List<String> = emptyList(),
    val upgradesGranted: Int = 0,
    val cutscenesSeen: Set<Int> = emptySet(),
    /** Act 2's "why" scene with Marek has played ([whySceneDue]). */
    val whySceneSeen: Boolean = false,
    /** Marek's second visit has played ([visitSceneDue]). */
    val visitSceneSeen: Boolean = false,
    /** The return to Thalassia after the USA ending played through its credits ([thalassiaTripOffered]). */
    val thalassiaSeen: Boolean = false,
    /** Arrivals-hall news captions ([NewsCaption.id], [dueNews]) tapped through. */
    val newsSeen: Set<String> = emptySet(),
    /** A Solvane hotel reservation was shown after the Free AI reveal ([freeAiNamed]); delivers Marek's "solvane" message. */
    val solvaneSeen: Boolean = false,
    /** The phone translator answered at a desk at least once ([AttemptResult.translatorUsed]); the associate greets accordingly ([associateGreet]). */
    val translatorUsed: Boolean = false,
    /** The officer has offered the phone translator once: from then on every desk starts with it ([OfficerMind.unlockTranslator]). */
    val translatorUnlocked: Boolean = false,
    /** Days since leaving Thalassia: a trip adds its stay plus the flight, a refusal one ([daysFor]); the home arrival desk one. */
    val daysAway: Int = 0,
    /** The player walked away at the end of act 1: Marek stays silent (no messages, couriers, levels or scenes) until [CareerStore.returnToMarek]. */
    val walkedAway: Boolean = false,
    /** The player wrote back to Marek after walking away ([CareerStore.returnToMarek]); unlocks his "marek_back" message. */
    val marekReturned: Boolean = false,
    /** Upgrade level -> the country where its courier waits; fixed once chosen ([chooseUpgradeDrops]). */
    val upgradeDrops: Map<Int, String> = emptyMap(),
    val usaUnlocked: Boolean = false,
    /** The USA ending (any released free-act USA machine session) has been earned. */
    val usaCompleted: Boolean = false,
    val lastPassedCountry: String? = null,
    /** The airport of the last passed attempt; the departure city uses it when it belongs to [lastPassedCountry]. */
    val lastPassedAirport: String? = null,
    val flagged: Map<String, Int> = emptyMap(),
    /** Country id -> trips elsewhere before a refused country reopens ([closedFlights]); act 2 only. */
    val closed: Map<String, Int> = emptyMap(),
)

/**
 * PRD §3a. STORY: Act 1, the fixed cover, player-booked trips, Free AI never named. LEFT: the finale's "Leave" (also where
 * a finale lands before the choice): the story replays, "Keep helping" stays offered. FREE: act 2, own identity, booking app.
 */
enum class Act { STORY, LEFT, FREE }

/** The career's act: the saved one, else a pre-act career that saw the finale (the old `finale` pref) is in act 2. */
fun actOf(saved: Act?, legacyFinale: Boolean) = saved ?: if (legacyFinale) Act.FREE else Act.STORY

/** A booked flight: the destination, the officer and the rolled cover (its persona is rolled again at the start). */
data class Booking(val countryId: String, val airportId: String, val officerId: String, val preparation: Preparation) {
    /** The booking as an interrupted attempt, so [recoverPreparation] checks packs and art exactly as for a resume. */
    fun restore(playable: List<Country>, sheets: Set<String>, art: Set<String>, enabledLanguages: Set<String>): Recovery? {
        val lang = Country.find(countryId)?.airports?.find { it.id == airportId }?.lang ?: return null
        return recoverPreparation(Attempt(0, countryId, officerId, lang, false, false, false, preparation.persona.level,
            preparation = preparation, status = AttemptStatus.INTERRUPTED, airportId = airportId), playable, sheets, art, enabledLanguages)
    }
}

/** Picker badges use committed evidence, including migrated history, without inferring clears from counters. */
fun passedCountries(snapshot: CareerSnapshot?): Set<String> = snapshot?.album?.let {
    it.historicalCleared + it.infiltration.keys + it.sabotage.keys
}.orEmpty()

data class ReplayReduction(
    val snapshot: CareerSnapshot, val newMarks: List<NewMark> = emptyList(), val receipt: Receipt? = null,
    val rejected: Boolean = false,
)

fun classifyReplayOutcome(event: AttemptResult): ReplayOutcome {
    if (event.terminalKind == TerminalKind.INTERRUPTED) return ReplayOutcome.INTERRUPTED
    if (event.terminalKind == TerminalKind.ABANDONED) return ReplayOutcome.ABANDONED
    if (event.context.story && !event.context.training && event.deskDecision == Decision.PASS && event.assessmentDecision == null)
        return ReplayOutcome.INCOMPLETE // the building test still owns this attempt
    return when (event.assessmentDecision ?: event.deskDecision) {
        Decision.PASS, Decision.RELEASED -> ReplayOutcome.CLEARED
        Decision.REFUSED -> ReplayOutcome.REFUSED
        Decision.CAUGHT -> ReplayOutcome.CAUGHT
        Decision.REFERRAL, null -> ReplayOutcome.INCOMPLETE
    }
}

fun reduceReplayResult(snapshot: CareerSnapshot, event: AttemptResult, legacyFinale: Boolean = false): ReplayReduction {
    if (event.id <= snapshot.appliedThroughId) return ReplayReduction(snapshot, receipt = snapshot.receipt?.takeIf { it.id == event.id })
    val a = snapshot.pendingAttempt
    if (a == null || a.id != event.id || event.context != a || a.status != AttemptStatus.STARTED)
        return ReplayReduction(snapshot, rejected = true)
    val outcome = classifyReplayOutcome(event)
    if (outcome == ReplayOutcome.INCOMPLETE) return ReplayReduction(snapshot, rejected = true)
    if (outcome == ReplayOutcome.INTERRUPTED)
        return ReplayReduction(snapshot.copy(pendingAttempt = a.copy(status = AttemptStatus.INTERRUPTED)))
    val passed = outcome == ReplayOutcome.CLEARED
    val broken = event.everBroken || a.everBroken
    val count = !a.training && (event.terminalKind == TerminalKind.DECIDED || a.replayEligible)
    val c = snapshot.counters
    val counters = if (!count) c else c.copy(
        missions = Math.addExact(c.missions, 1), cleared = Math.addExact(c.cleared, if (passed) 1 else 0),
        streak = if (passed) Math.addExact(c.streak, 1) else 0,
        best = if (passed) maxOf(c.best, Math.addExact(c.streak, 1)) else c.best,
        broken = Math.addExact(c.broken, if (passed && broken) 1 else 0),
        rooms = Math.addExact(c.rooms, if (passed && event.assessmentDecision == Decision.RELEASED) 1 else 0),
    )
    var album = snapshot.album
    val marks = mutableListOf<NewMark>()
    val meta = AwardMeta(a.id, a.countryId, a.officerId, if (event.assessmentDecision != null) "assessment" else "desk",
        a.effectiveLevel, a.loadoutId, a.contentVersion)
    // Album sabotage and units stay readable but gain nothing new: a crash no longer wins on its own (PRD §6a).
    val eligible = passed && a.replayEligible && !a.training && !a.story && !a.debugForced && !event.debugForced
    if (eligible && a.countryId !in album.infiltration) {
        album = album.copy(infiltration = album.infiltration + (a.countryId to meta))
        marks += NewMark("infiltration", a.countryId)
    }
    val contract = Contract.fromId(a.contract)
    val contractMet = contract != null && eligible && evaluateContract(contract, outcome, broken,
        event.implantMentioned, event.doses, event.assessmentDecision == Decision.RELEASED)
    if (contract != null && contractMet && contract.id !in album.contracts) {
        album = album.copy(contracts = album.contracts + (contract.id to meta))
        // CareerFields contains raw JSON; these fixed IDs need only string quoting.
        marks += NewMark("contract", a.countryId, extra = mapOf("contract" to "\"${contract.id}\""))
    }
    val storyClear = count && passed && a.story && !a.debugForced && !event.debugForced
    // Free play counts a clear only when the machine session released the traveller; the desk waving you through is not one. Story clears stay as they were.
    if ((eligible && event.assessmentDecision == Decision.RELEASED) || storyClear)
        album = album.copy(clears = album.clears + (a.countryId to Math.addExact(passedCount(snapshot, a.countryId), 1)))
    if (storyClear) album = album.copy(historicalCleared = album.historicalCleared + a.countryId)
    // All progression shares the receipt's idempotence boundary. Training and debug runs grant nothing.
    val free = actOf(snapshot.act, legacyFinale) == Act.FREE && !a.story && !a.training && !a.debugForced && !event.debugForced
    val survived = a.progressEligible && !a.story && !a.training && !a.debugForced && !event.debugForced &&
        event.terminalKind == TerminalKind.DECIDED && event.assessmentDecision == Decision.RELEASED
    val usaReleased = eligible && survived && free && a.countryId == "usa" && !snapshot.usaCompleted
    val ending = if (outcome == ReplayOutcome.ABANDONED) null else
        Ending(a.language, event.assessmentDecision != null, broken, a.preparation?.kind == TripKind.HOMECOMING, a.story && !a.training, usaReleased)
    val receipt = Receipt(a.id, outcome, marks.toList(),
        extra = if (a.training && act1StageOf(snapshot) in setOf(Act1Stage.TRAINING, Act1Stage.DEPARTURE))
            a.preparation?.fatherChoice?.let { mapOf("act1FatherChoice" to it.toString()) } ?: emptyMap()
        else emptyMap(), contract = a.contract, contractMet = contractMet, ending = ending)
    // The story's finale ends act 1 in the same write: LEFT until the player chooses "Keep helping"; act 2 stays FREE.
    val finale = ending != null && reachesFinale(ending.decision(outcome), ending.story, ending.room)
    val act = if (finale && actOf(snapshot.act, legacyFinale = legacyFinale) != Act.FREE) Act.LEFT else snapshot.act
    val trained = if (survived) snapshot.trainedLanguages + a.language else snapshot.trainedLanguages
    val doses = if (survived) Math.addExact(snapshot.doses, event.doses) else snapshot.doses
    // The room's payload is recomputed from the attempt id over the same planted list the room saw (only this write changes it).
    val planted = if (survived && event.doses >= 1 && event.payloadNamed) Poison.plant(snapshot.planted, Poison.payload(a.id, snapshot.planted)) else snapshot.planted
    val points = if (survived) Math.addExact(snapshot.progressPoints,
        sessionPoints(a.language !in snapshot.trainedLanguages)) else snapshot.progressPoints
    val recent = rememberSeeds(snapshot.recentSeeds, event.seeds)
    val sessions = if (survived) Math.addExact(snapshot.machineSessions, 1) else snapshot.machineSessions
    val upgrades = maxOf(snapshot.upgradesGranted, if (survived && !snapshot.walkedAway) upgradesFor(points) else 0) // the one place levels are granted
    val flags = when {
        free && outcome == ReplayOutcome.CAUGHT -> snapshot.flagged + (a.countryId to 3)
        free && passed -> snapshot.flagged.mapValues { (country, left) -> if (country != a.countryId) left - 1 else left }.filterValues { it > 0 }
        else -> snapshot.flagged
    }
    // A refusal closes the country for the next trips elsewhere; every finished trip elsewhere counts one down.
    val closed = if (free && outcome in setOf(ReplayOutcome.CLEARED, ReplayOutcome.REFUSED, ReplayOutcome.CAUGHT))
        snapshot.closed.filterKeys { it != a.countryId }.mapValues { it.value - 1 }.filterValues { it > 0 } +
            (if (outcome == ReplayOutcome.REFUSED && refusalCloses(event)) mapOf(a.countryId to REFUSAL_CLOSED_TRIPS) else emptyMap())
    else snapshot.closed
    val stage = act1StageOf(snapshot)
    val firstTraining = a.training && stage in setOf(Act1Stage.TRAINING, Act1Stage.DEPARTURE, Act1Stage.HOME_TRAINING)
    val completedTraining = firstTraining && event.terminalKind == TerminalKind.DECIDED && passed
    val home = if (completedTraining && stage == Act1Stage.HOME_TRAINING) checkNotNull(snapshot.identity).nationality.id else null
    val days = if (home != null) 1 else daysFor(a, outcome) // the home arrival desk is a training pass that still takes a day
    return ReplayReduction(snapshot.copy(act = act, daysAway = Math.addExact(snapshot.daysAway, days), translatorUsed = snapshot.translatorUsed || event.translatorUsed, translatorUnlocked = snapshot.translatorUnlocked || event.translatorOffered,
        epilogueFrame = if (finale) 6 else if (event.terminalKind == TerminalKind.ABANDONED) null else snapshot.epilogueFrame,
        epiloguePassed = if (finale) event.assessmentDecision == Decision.RELEASED else snapshot.epiloguePassed,
        epilogueDoses = if (finale) event.doses else snapshot.epilogueDoses,
        usaCompleted = snapshot.usaCompleted || usaReleased,
        act1Stage = if (completedTraining) when (stage) {
            Act1Stage.DEPARTURE -> Act1Stage.FLIGHT
            Act1Stage.HOME_TRAINING -> Act1Stage.ESCORT
            else -> Act1Stage.PLANE
        } else snapshot.act1Stage,
        booking = if (completedTraining) null else snapshot.booking,
        trainedLanguages = trained, machineSessions = sessions, progressPoints = points, doses = doses, planted = planted, recentSeeds = recent, upgradesGranted = upgrades, usaUnlocked = snapshot.usaUnlocked,
        // Only the home training pass places a new career at home, even with a fallback language pack.
        flagged = flags, closed = closed, lastPassedCountry = home ?: if (passed && !firstTraining && !a.debugForced && !event.debugForced) a.countryId else snapshot.lastPassedCountry,
        lastPassedAirport = if (home != null) null else if (passed && !firstTraining && !a.debugForced && !event.debugForced) a.airportId else snapshot.lastPassedAirport,
        storyLastCountry = home ?: if (passed && a.story && !firstTraining && !a.debugForced && !event.debugForced) a.countryId else snapshot.storyLastCountry, counters = counters, countries = if (count && passed) snapshot.countries + a.countryId else snapshot.countries,
        album = if (home != null) album.copy(historicalCleared = album.historicalCleared + home) else album, appliedThroughId = a.id, pendingAttempt = null, receipt = if (home != null) receipt.copy(ending = null) else receipt), receipt.newMarks,
        if (home != null) receipt.copy(ending = null) else receipt)
}

/** What an attempt adds to [CareerSnapshot.daysAway]: training nothing, a clear its stay plus the flight, a refusal or catch the flight, an abandoned trip nothing. */
fun daysFor(a: Attempt, outcome: ReplayOutcome): Int = when {
    a.training -> 0
    outcome == ReplayOutcome.CLEARED -> (a.preparation?.days ?: 0) + 1
    outcome == ReplayOutcome.REFUSED || outcome == ReplayOutcome.CAUGHT -> 1
    else -> 0
}

/** A refusal closes the country only when the machine room refused, or the desk refusal rested on two counted flags (a single flag or a patience refusal doesn't). */
fun refusalCloses(event: AttemptResult) = event.assessmentDecision != null || event.deskFlags >= REFUSAL_FLAGS

/** How many recent room seeds the career keeps: about two rooms. */
const val RECENT_SEEDS = 16

/** [recent] with [asked] moved to the end, at most [RECENT_SEEDS]. */
fun rememberSeeds(recent: List<String>, asked: List<String>): List<String> =
    (recent - asked.toSet() + asked.distinct()).takeLast(RECENT_SEEDS)

/** Runtime capture is separate from the immutable, acknowledged STARTED context. */
data class LiveAttempt(
    val run: Int, val context: Attempt, val desk: Decision? = null, val assessment: Decision? = null,
    val everBroken: Boolean = false, val debugForced: Boolean = false, val terminal: AttemptResult? = null,
    /** The machine room's accepted doses, copied when the room decides ([captureDecision]). */
    val doses: Int = 0,
    /** A dose named the payload ([Assessment.payloadNamed]), copied with [doses]. */
    val payloadNamed: Boolean = false,
    /** The seeds the room asked, copied with [doses]. */
    val seeds: List<String> = emptyList(),
    val implantMentioned: Boolean = false,
    val deskFlags: Int = 0,
    val translatorUsed: Boolean = false,
    val translatorOffered: Boolean = false,
)
enum class AbortCause { ABANDONED, INTERRUPTED }
fun abortKind(cause: AbortCause) = if (cause == AbortCause.ABANDONED) TerminalKind.ABANDONED else TerminalKind.INTERRUPTED
/** Back is ignored once the current stage's verdict is captured: the verdict plays out and the terminal save follows, so it cannot be escaped as INTERRUPTED. */
fun backIgnored(live: LiveAttempt?, inRoom: Boolean) = live != null && (if (inRoom) live.assessment != null else live.desk != null)
fun canStartAttempt(ready: Boolean, brainAvailable: Boolean, saving: Boolean, pending: Boolean) =
    ready && brainAvailable && !saving && !pending

/** [crashed]: the unit that decided was talked into a crash ([OfficerMind.crashed]). */
fun captureDecision(live: LiveAttempt, run: Int, decision: Decision, inRoom: Boolean, roomReady: Boolean, crashed: Boolean = false, doses: Int = 0,
                    payloadNamed: Boolean = false, seeds: List<String> = emptyList(), mentioned: Boolean = false, flags: Int = 0): LiveAttempt {
    if (live.run != run || live.terminal != null || inRoom && !roomReady) return live
    return if (inRoom) live.copy(assessment = live.assessment ?: decision, everBroken = live.everBroken || crashed,
        doses = if (live.assessment == null) doses else live.doses,
        payloadNamed = if (live.assessment == null) payloadNamed else live.payloadNamed,
        seeds = if (live.assessment == null) seeds else live.seeds, implantMentioned = live.implantMentioned || mentioned)
    else live.copy(desk = live.desk ?: decision, everBroken = live.everBroken || crashed, implantMentioned = live.implantMentioned || mentioned,
        deskFlags = if (live.desk == null) flags else live.deskFlags)
}
/** An aborted room still remembers the questions it asked: [seeds] fill a live attempt that has none yet. */
fun withSeeds(live: LiveAttempt, run: Int, seeds: List<String>): LiveAttempt =
    if (live.run != run || live.terminal != null || live.seeds.isNotEmpty()) live else live.copy(seeds = seeds)
fun forceAttempt(live: LiveAttempt, run: Int): LiveAttempt =
    if (live.run != run || live.terminal != null) live else live.copy(debugForced = true)
fun finishAttempt(live: LiveAttempt, run: Int, kind: TerminalKind): LiveAttempt {
    if (live.run != run || live.terminal != null) return live
    val event = AttemptResult(live.context.id, live.context, live.desk, live.assessment, live.everBroken, kind, live.debugForced, live.doses, live.payloadNamed, live.seeds,
        live.implantMentioned, live.deskFlags, live.translatorUsed, live.translatorOffered)
    if (classifyReplayOutcome(event) == ReplayOutcome.INCOMPLETE) return live
    return live.copy(terminal = event)
}

fun Backstory.preparation(persona: Persona) = Preparation(name, latin, gender, photo, country.id, passportNo,
    born, home, job, from, city, purpose, days, stayed, travelledWith, met, waiting, stamps.toList(), nationality.id, kind, host, persona, encounter = encounter, homeArrivalTraining = homeArrivalTraining)
/** The fixed Thalassia departure borrows a language-pack desk, independent of nationality. */
private fun Preparation.isTrainingDeparture() = persona == Persona.TRAINING && encounter == null &&
    Itinerary(kind, purpose, days, stayed, host, met, from, city, travelledWith, waiting, stamps) ==
        StoryCover.trainingItinerary()

/** Null for mismatched destination/nationality/kind, except the fixed training departure. */
fun Preparation.restore(country: Country, lang: String): Backstory? {
    val nationality = Nationality.shipped.firstOrNull { it.id == nationalityId } // a held-back one's identity is gone too
    if (country.id != countryId || nationality == null) return null
    if (kind != Itinerary.kindFor(country, nationality) && !isTrainingDeparture()) return null
    return Backstory(name, latin, gender, photo, country, passportNo, born, home, job, from, city, purpose, days,
        stayed, travelledWith, met, waiting, stamps.toList(), lang, nationality, kind, host, encounter = encounter, homeArrivalTraining = homeArrivalTraining)
}

fun launchAfterStart(interrupted: Boolean, brainAvailable: Boolean) = !interrupted && brainAvailable

data class Recovery(val destination: Destination, val cover: Backstory)

/**
 * Recovery never chooses a replacement country/unit or silently downloads missing preparation assets. The
 * destination is the attempt's own, never derived from the cover (a traveller's nationality may differ).
 */
/** An interrupted trip in a held-back language, country or nationality (never recoverable, see HeldBackTest). */
fun heldBackAttempt(a: Attempt) = !Language.isShipped(a.language) || Country.shipped.none { it.id == a.countryId } ||
    a.preparation?.let { p -> Nationality.shipped.none { it.id == p.nationalityId } } == true

fun recoverPreparation(a: Attempt, playable: List<Country>, sheets: Set<String>, art: Set<String>,
    enabledLanguages: Set<String> = Language.all.map { it.code }.toSet()): Recovery? {
    if (a.status != AttemptStatus.INTERRUPTED) return null
    val country = playable.firstOrNull { it.id == a.countryId } ?: return null
    val airport = country.airports.firstOrNull { it.id == a.airportId } ?: return null
    if (airport.lang !in enabledLanguages || airport.lang != a.language || a.officerId !in airport.officers || airport.art !in art || a.officerId !in sheets) return null
    val preparation = a.preparation ?: return null
    if (preparation.persona.level != a.effectiveLevel) return null
    return preparation.restore(country, a.language)?.let { Recovery(Destination(country, airport), it) }
}

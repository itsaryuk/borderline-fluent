package com.lampensoft.borderlinefluent

/** What Free AI asks for on a trip: plant a named motif, plant your own (CUSTOM), or an airport chain (CHAIN). */
enum class MissionKind { PLANT, CUSTOM, CHAIN }

/** A chain's leg: LEG1 only gets through at [Chain.from]; LEG2 flies on to [Chain.to] and plants [Chain.motif]. */
enum class ChainStage { LEG1, LEG2 }

/**
 * An airport chain (phase 3): get through at airport [from] (pass or released), then fly [from] → [to] and plant
 * [motif] there. [id]: the attempt id leg 1 was offered for (its seed). Leg 1 lives on its attempt's [Mission]; once it
 * clears, the career keeps the chain at LEG2 ([CareerSnapshot.chain]) until leg 2 starts, is dropped or booked around.
 */
data class Chain(val id: Long, val motif: Motif, val from: String, val to: String, val stage: ChainStage) {
    val fromAirport get() = airport(from)
    val toAirport get() = airport(to)
    /** Leg 2 can be booked: [to]'s country is [bookable] ([missionCountries]) and [to] among its [airports] ([eligibleAirports]). */
    fun canFlyOn(bookable: List<Country>, airports: (Country) -> List<Airport>): Boolean {
        val b = toAirport ?: return false
        return bookable.any { it.id == b.country.id } && airports(b.country).any { it.id == to }
    }
    companion object {
        fun airport(id: String) = Country.all.firstNotNullOfOrNull { c -> c.airports.firstOrNull { it.id == id }?.let { Destination(c, it) } }

        /**
         * The chain Free AI offers for the career's next trip, seeded by its id; null for most trips (about 1 in 3 is
         * one), plant-your-own's slot, a chain already under way, a walked-away career, and fewer than two [bookable]
         * countries (the trip's open, unclosed destinations, [missionCountries]). Leg 1 never goes to the USA; leg 2 is
         * another bookable country. [airports]: a country's airports with an installed pack ([eligibleAirports]).
         */
        fun offer(s: CareerSnapshot, bookable: List<Country>, airports: (Country) -> List<Airport>): Chain? {
            val id = s.nextAttemptId
            if (s.act != Act.FREE || s.walkedAway || s.chain != null || Mission.customDue(id, s.plantsSinceCustom)) return null
            val r = java.util.SplittableRandom(id * 31 + 17)
            if (r.nextInt(3) != 0) return null
            val open = bookable.distinctBy { it.id }.filter { airports(it).isNotEmpty() }.sortedBy { it.id }
            if (open.size < 2) return null
            val firsts = open.filter { it.id != Nationality.USA.id }.ifEmpty { return null }
            val a = firsts[r.nextInt(firsts.size)]
            val seconds = open.filter { it.id != a.id }
            val b = seconds[r.nextInt(seconds.size)]
            fun pick(c: Country) = airports(c).sortedBy { it.id }.let { it[r.nextInt(it.size)] }
            return Chain(id, Poison.payload(id, s.planted), pick(a).id, pick(b).id, ChainStage.LEG1)
        }
    }
}

/**
 * Free AI's bonus mission for one act-2 trip: plant [motif] in this trip's machine, or (CUSTOM, [motif] null) anything the
 * player likes. Pinned on the attempt at its start ([CareerStore.start]), so the room's payload never changes when motifs
 * are added. Planted = released with a credited dose ([reduceReplayResult]) naming the motif (any dose for CUSTOM), worth
 * [BONUS_POINTS]; skipping it costs nothing.
 */
data class Mission(val kind: MissionKind, val motif: Motif?, val chain: Chain? = null) {
    /** Leg 1 of a chain: only getting through counts, no bonus yet. */
    val firstLeg get() = chain?.stage == ChainStage.LEG1
    companion object {
        /** Act-2 trips outside the story and training: the ones whose machine room has a payload. */
        fun due(a: Attempt) = a.progressEligible && !a.story && !a.training
        /**
         * Attempt [attemptId]'s mission, seeded by it: plant-your-own when [customDue], else an unplanted motif first
         * ([Poison.payload]). [sinceCustom]: [CareerSnapshot.plantsSinceCustom].
         */
        fun choose(attemptId: Long, planted: List<String>, sinceCustom: Int = 0) =
            if (customDue(attemptId, sinceCustom)) Mission(MissionKind.CUSTOM, null)
            else Mission(MissionKind.PLANT, Poison.payload(attemptId, planted))

        /** Every 3rd or 4th mission is plant-your-own: after two named ones half the time (seeded), after three always; never two in a row. */
        fun customDue(attemptId: Long, sinceCustom: Int) =
            sinceCustom >= 3 || sinceCustom == 2 && java.util.SplittableRandom(attemptId * 31 + 5).nextBoolean()
        /** [c]'s leg as the trip's mission: leg 2 plants the chain's motif; leg 1's room has no payload ([payload]). */
        fun of(c: Chain) = Mission(MissionKind.CHAIN, c.motif, c)
        /** An attempt saved before missions: the payload its room computed then, over the [Motif.ORIGINAL] four. */
        fun legacy(attemptId: Long, planted: List<String>) = Mission(MissionKind.PLANT, Poison.payload(attemptId, planted, Motif.ORIGINAL))
    }
}

/** Upgrade points a planted mission adds (one constant, if the pacing proves too fast). */
const val BONUS_POINTS = 1

/**
 * The room's payload: the pinned mission's motif, none for plant-your-own and a chain's leg 1 (only getting through counts), else (attempts without a mission: walked-away
 * careers, tests) the one computed from the id.
 */
fun Attempt.payload(planted: List<String>): Motif? =
    if (mission?.kind == MissionKind.CUSTOM || mission?.firstLeg == true) null else mission?.motif ?: Poison.payload(id, planted)

/** The run summary's mission line: planted, skipped (never a failure), a chain's leg 1 through, or the chain cancelled. */
enum class MissionOutcome { PLANTED, SKIPPED, LEG_DONE, CHAIN_CANCELLED }

/**
 * The last acknowledged receipt's mission and how it went; null while saving, for unsaved or failed saves, trips without
 * one and abandoned trips. Read from the saved snapshot: later writes (scenes after the result) keep its receipt.
 */
fun missionResult(state: CareerSave?, saving: Boolean): Pair<Mission, MissionOutcome>? =
    if (saving) null else (state as? CareerSave.Saved)?.snapshot?.receipt?.takeIf { it.outcome != ReplayOutcome.ABANDONED }?.let { r ->
        r.mission?.let { m -> m to when {
            m.firstLeg -> if (r.outcome == ReplayOutcome.CLEARED) MissionOutcome.LEG_DONE else MissionOutcome.CHAIN_CANCELLED
            r.bonus -> MissionOutcome.PLANTED
            else -> MissionOutcome.SKIPPED
        } }
    }

package com.lampensoft.borderlinefluent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**  phase 3: airport chains (get through at A, then fly A → B and plant there). */
class ChainTest {
    private val dub = Attempt(0, "ireland", "en_iem", "en", training = false, story = false, replayEligible = true, effectiveLevel = 2,
        airportId = "dub", preparation = preparation().copy(countryId = "ireland"), progressEligible = true)
    private val lis = Attempt(0, "portugal", "pt_rm", "pt", training = false, story = false, replayEligible = true, effectiveLevel = 2,
        airportId = "lis", preparation = preparation().copy(countryId = "portugal"), progressEligible = true)
    private val leg1 = Chain(7, Motif.MOON, "dub", "lis", ChainStage.LEG1)
    private val leg2 = leg1.copy(stage = ChainStage.LEG2)
    private val free = CareerSnapshot(act = Act.FREE, nextAttemptId = 7)
    private val countries = listOf("ireland", "portugal", "de", "usa").map { Country.of(it) }
    private val all: (Country) -> List<Airport> = { it.airports }

    private fun store(s: CareerSnapshot): CareerStore {
        var cache = CareerInput(CareerJson.encode(s), StoryReset.VERSION)
        return CareerStore(read = { cache }, write = { raw, marker -> cache = CareerInput(raw, marker); true },
            io = Dispatchers.Unconfined, publication = Dispatchers.Unconfined, legacyFinale = { false })
    }
    private fun started(s: CareerSnapshot, a: Attempt, chain: Chain? = null) = runBlocking {
        (store(s).also { it.open() }.start(a, "interview", chain) as CareerSave.Saved).snapshot
    }
    private fun released(a: Attempt, doses: Int = 1, named: Boolean = true) = victory(a).copy(doses = doses, payloadNamed = named)

    @Test fun aboutOneTripInThreeIsAChainSeededAndNeverInPlantYourOwnsSlot() {
        val offers = (1L..300).map { id -> id to Chain.offer(free.copy(nextAttemptId = id), countries, all) }
        val n = offers.count { it.second != null }
        assertTrue("$n", n in 70..130)
        for ((id, c) in offers) {
            val s = free.copy(nextAttemptId = id)
            assertEquals(c, Chain.offer(s, countries, all)) // seeded
            if (c == null) continue
            assertFalse(Mission.customDue(id, s.plantsSinceCustom))
            assertEquals(id, c.id)
            assertEquals(ChainStage.LEG1, c.stage)
            assertNotEquals("usa", c.fromAirport!!.country.id) // never the USA as leg 1
            assertNotEquals(c.fromAirport!!.country.id, c.toAirport!!.country.id)
            assertTrue(c.fromAirport!!.country in countries && c.toAirport!!.country in countries)
        }
        assertTrue(offers.any { it.second?.toAirport?.country?.id == "usa" }) // the USA can be leg 2
        // Plant-your-own's slot is never a chain.
        for (id in 1L..300) assertNull(Chain.offer(free.copy(nextAttemptId = id, plantsSinceCustom = 3), countries, all))
    }

    /** Fable: "Fly on" when B can't be booked booked somewhere else and looped; it needs B's country open and B's airport installed. */
    @Test fun flyOnNeedsBsCountryBookableAndBsAirportInstalled() {
        assertTrue(leg2.canFlyOn(countries, all))
        assertFalse(leg2.canFlyOn(countries.filter { it.id != "portugal" }, all)) // closed, or the country just passed
        assertFalse(leg2.canFlyOn(countries) { c -> c.airports.filter { it.id != "lis" } }) // pack evicted or sheet lost
        assertFalse(leg2.copy(to = "nowhere").canFlyOn(countries, all))
    }

    @Test fun noChainWithFewerThanTwoOpenCountriesWalkedAwayOutsideActTwoOrWhileOneWaits() {
        val id = (1L..300).first { Chain.offer(free.copy(nextAttemptId = it), countries, all) != null }
        val s = free.copy(nextAttemptId = id)
        assertNull(Chain.offer(s, countries.take(1), all))
        assertNull(Chain.offer(s, listOf(Country.of("usa"), Country.of("ireland")).take(1), all))
        // Only the USA besides one country: leg 1 can't be the USA, leg 2 can.
        Chain.offer(s, listOf(Country.of("usa"), Country.of("ireland")), all)?.let { assertEquals("dub", it.from) }
        // A country without an installed pack doesn't count.
        assertNull(Chain.offer(s, countries, { c -> if (c.id == "ireland") c.airports else emptyList() }))
        assertNull(Chain.offer(s.copy(walkedAway = true), countries, all))
        assertNull(Chain.offer(s.copy(act = Act.LEFT), countries, all))
        assertNull(Chain.offer(s.copy(chain = leg2), countries, all))
    }

    @Test fun leg1IsPinnedOnlyAtItsAirportForItsOwnId() {
        val a = started(free, dub, leg1).pendingAttempt!!
        assertEquals(Mission.of(leg1), a.mission)
        assertNull(a.payload(emptyList())) // leg 1 plants nothing
        // Booked elsewhere: the chain is cancelled, the trip gets an ordinary mission.
        val elsewhere = started(free, lis, leg1)
        assertNotEquals(MissionKind.CHAIN, elsewhere.pendingAttempt!!.mission!!.kind)
        assertNull(elsewhere.chain)
        // An offer for another id (a stale one) is never pinned.
        assertNotEquals(MissionKind.CHAIN, started(free.copy(nextAttemptId = 8), dub, leg1).pendingAttempt!!.mission!!.kind)
        // Walked away: nothing at all.
        assertNull(started(free.copy(walkedAway = true), dub, leg1).pendingAttempt!!.mission)
    }

    @Test fun leg1ThroughOffersLeg2AndAFailureCancelsTheChain() {
        val a = started(free, dub, leg1).pendingAttempt!!
        val s = pending(a).copy(act = Act.FREE)
        for (event in listOf(released(a, doses = 0, named = false), deskPass(a))) {
            val r = reduceReplayResult(s, event)
            assertEquals(leg2, r.snapshot.chain)
            assertFalse(r.receipt!!.bonus) // leg 1 pays nothing, even planted
            assertEquals(Mission.of(leg1) to MissionOutcome.LEG_DONE, missionResult(CareerSave.Saved(r.snapshot, r.receipt), false))
            assertEquals(leg2, decoded(CareerJson.encode(r.snapshot)).chain) // survives a restart
        }
        assertFalse(reduceReplayResult(s, released(a)).receipt!!.bonus)
        for (event in listOf(victory(a).copy(assessmentDecision = Decision.REFUSED), victory(a).copy(assessmentDecision = Decision.CAUGHT),
                deskPass(a).copy(deskDecision = Decision.REFUSED), deskPass(a).copy(terminalKind = TerminalKind.ABANDONED))) {
            val r = reduceReplayResult(s, event)
            assertNull(r.snapshot.chain)
            if (r.receipt!!.outcome != ReplayOutcome.ABANDONED)
                assertEquals(MissionOutcome.CHAIN_CANCELLED, missionResult(CareerSave.Saved(r.snapshot, r.receipt), false)!!.second)
        }
        // An interrupted leg 1 keeps its mission on the attempt for the resume.
        val i = reduceReplayResult(s, deskPass(a).copy(deskDecision = null, terminalKind = TerminalKind.INTERRUPTED))
        assertEquals(Mission.of(leg1), i.snapshot.pendingAttempt!!.mission)
        assertEquals(Mission.of(leg1), decoded(CareerJson.encode(i.snapshot)).pendingAttempt!!.mission)
    }

    @Test fun leg2PaysOneBonusAtTheChainsEndOnce() {
        val waiting = free.copy(nextAttemptId = 9, chain = leg2)
        val started = started(waiting, lis)
        val b = started.pendingAttempt!!
        assertEquals(Mission.of(leg2), b.mission)
        assertEquals(Motif.MOON, b.payload(emptyList())) // leg 2's room carries the chain's motif
        assertNull(started.chain) // leg 2 lives on its attempt now
        val s = pending(b).copy(act = Act.FREE)
        val honest = reduceReplayResult(s, released(b, doses = 0, named = false)).snapshot.progressPoints
        val r = reduceReplayResult(s, released(b))
        assertTrue(r.receipt!!.bonus)
        assertEquals(honest + BONUS_POINTS, r.snapshot.progressPoints)
        assertEquals(listOf("moon"), r.snapshot.planted)
        assertNull(r.snapshot.chain)
        assertEquals(MissionOutcome.PLANTED, missionResult(CareerSave.Saved(r.snapshot, r.receipt), false)!!.second)
        assertEquals(r.snapshot, reduceReplayResult(r.snapshot, released(b)).snapshot) // idempotent
        // Leg 2 failing: "Bonus skipped", and the chain is over.
        val f = reduceReplayResult(s, victory(b).copy(assessmentDecision = Decision.REFUSED, doses = 1, payloadNamed = true))
        assertFalse(f.receipt!!.bonus)
        assertNull(f.snapshot.chain)
        assertEquals(MissionOutcome.SKIPPED, missionResult(CareerSave.Saved(f.snapshot, f.receipt), false)!!.second)
    }

    @Test fun aWaitingLeg2IsCancelledByATripElsewhereAndCanBeDropped() = runBlocking {
        val waiting = free.copy(nextAttemptId = 9, chain = leg2)
        val elsewhere = started(waiting, dub)
        assertNull(elsewhere.chain)
        assertNotEquals(MissionKind.CHAIN, elsewhere.pendingAttempt!!.mission!!.kind)
        // A training or story trip leaves it waiting.
        assertEquals(leg2, started(waiting, dub.copy(progressEligible = false)).chain)
        val st = store(waiting).also { it.open() }
        assertNull((st.dropChain() as CareerSave.Saved).snapshot.chain)
    }

    /** Fable: an interrupted leg 2 that is discarded waits again; Thalassia ends a waiting chain. */
    @Test fun aDiscardedLeg2WaitsAgainAndThalassiaEndsTheChain() = runBlocking {
        val b = started(free.copy(nextAttemptId = 9, chain = leg2), lis).pendingAttempt!!
        val interrupted = free.copy(nextAttemptId = 10, pendingAttempt = b.copy(status = AttemptStatus.INTERRUPTED))
        assertEquals(leg2, (store(interrupted).also { it.open() }.discardPreparation() as CareerSave.Saved).snapshot.chain)
        // A discarded leg 1 is just gone (the offer is per id).
        val a = started(free, dub, leg1).pendingAttempt!!
        assertNull((store(free.copy(nextAttemptId = 8, pendingAttempt = a.copy(status = AttemptStatus.INTERRUPTED))).also { it.open() }
            .discardPreparation() as CareerSave.Saved).snapshot.chain)
        assertNull((store(free.copy(chain = leg2)).also { it.open() }.thalassiaSeen() as CareerSave.Saved).snapshot.chain)
    }

    /** Fable: the booking keeps the chain it was made for, so a restart never recomputes the offer from today's packs. */
    @Test fun aBookingKeepsItsChainAcrossARestart() {
        val booking = Booking("ireland", "dub", "en_iem", preparation().copy(countryId = "ireland"), leg1)
        val s = free.copy(booking = booking)
        assertEquals(booking, decoded(CareerJson.encode(s)).booking)
        // Older saves (no chain on the ticket) and a damaged chain keep the ticket, with no chain.
        val old = JSONObject(CareerJson.encode(s)).also { it.getJSONObject("booking").remove("chain") }
        assertEquals(booking.copy(chain = null), decoded(old.toString()).booking)
        assertFalse(JSONObject(CareerJson.encode(free.copy(booking = booking.copy(chain = null)))).getJSONObject("booking").has("chain"))
        val damaged = JSONObject(CareerJson.encode(s)).also { it.getJSONObject("booking").getJSONObject("chain").put("to", "xxx") }
        assertEquals(booking.copy(chain = null), decoded(damaged.toString()).booking)
    }

    @Test fun oldAndDamagedSavesReadAsNoChain() {
        val s = free.copy(chain = leg2)
        assertEquals(leg2, decoded(CareerJson.encode(s)).chain)
        assertFalse(JSONObject(CareerJson.encode(free)).has("chain")) // nothing written without one
        assertNull(decoded(CareerJson.encode(free)).chain)
        fun damaged(edit: (JSONObject) -> Unit) = decoded(JSONObject(CareerJson.encode(s)).also { edit(it.getJSONObject("chain")) }.toString()).chain
        assertNull(damaged { it.put("to", "xxx") })
        assertNull(damaged { it.put("motif", "kazoo") })
        assertNull(damaged { it.put("stage", "LEG9") })
        assertNull(damaged { it.remove("from") })
        assertNull(decoded(JSONObject(CareerJson.encode(s)).put("chain", "LEG2").toString()).chain)
        // A damaged chain mission on a pending attempt reads as its computed payload (phase 1's rule).
        val p = pending(dub.copy(id = 3, mission = Mission.of(leg1))).copy(act = Act.FREE)
        val json = JSONObject(CareerJson.encode(p))
        json.getJSONObject("pendingAttempt").getJSONObject("mission").getJSONObject("chain").put("from", "nowhere")
        assertEquals(Mission.legacy(3, emptyList()), decoded(json.toString()).pendingAttempt!!.mission)
    }

    @Test fun chainStringsExistInEveryLocaleWithTheirPlaceholders() {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val dirs = res.listFiles { f -> f.name == "values" || f.name.startsWith("values-") }!!.filter { File(it, "strings.xml").isFile }
        assertEquals(13, dirs.size)
        val args = mapOf("mission_chain_leg1" to 1, "mission_chain_leg2" to 3, "mission_chain_done" to 2, "mission_chain_cancelled" to 0,
            "chain_fly_on" to 1, "chain_drop" to 0)
        for (dir in dirs) {
            val kids = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml")).documentElement.childNodes
            val strings = (0 until kids.length).mapNotNull { kids.item(it) as? Element }.associate { it.getAttribute("name") to it.textContent }
            for ((key, n) in args) {
                val v = strings[key]
                assertTrue("${dir.name}/$key", v?.isNotBlank() == true)
                for (i in 1..n) assertTrue("${dir.name}/$key %$i", "%$i\$s" in v!!)
                assertFalse("${dir.name}/$key %${n + 1}", "%${n + 1}\$s" in v!!)
            }
        }
    }

    @Test fun chainCitiesReadInTheUiLanguage() {
        assertEquals("Dublin", chainCity("dub", "en"))
        assertEquals("Дублин", chainCity("dub", "ru"))
        assertEquals("Lisbon", chainCity("lis", "de")) // Latin scripts keep the Latin name
        assertEquals("XYZ", chainCity("xyz", "en"))
    }

    @Test fun `a pick other than leg 2's country leaves the chain, Anywhere does not`() {
        assertTrue(pickLeavesChain("ireland", "portugal"))
        assertFalse(pickLeavesChain("portugal", "portugal"))
        assertFalse(pickLeavesChain("random", "portugal"))
        assertFalse(pickLeavesChain("ireland", null))
    }
}

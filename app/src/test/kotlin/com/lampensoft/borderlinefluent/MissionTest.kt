package com.lampensoft.borderlinefluent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**  phase 1: Free AI's bonus mission on act-2 trips. */
class MissionTest {
    private val free = attempt().copy(progressEligible = true, preparation = preparation())
    private fun released(a: Attempt, doses: Int = 1, named: Boolean = true, debug: Boolean = false) =
        victory(a).copy(doses = doses, payloadNamed = named, debugForced = debug)
    private fun planted(a: Attempt) = a.copy(mission = Mission(MissionKind.PLANT, Motif.MOON))

    @Test fun choiceIsSeededAndPicksAnUnplantedMotifFirst() {
        for (id in 1L..40) {
            assertEquals(Mission.choose(id, emptyList()), Mission.choose(id, emptyList()))
            assertEquals(MissionKind.PLANT, Mission.choose(id, emptyList()).kind)
            val three = Motif.entries.dropLast(1).map { it.id }
            assertEquals(Motif.entries.last(), Mission.choose(id, three).motif)
            // Every motif planted: any of them again.
            assertNotNull(Mission.choose(id, Motif.entries.map { it.id }).motif)
        }
        assertEquals(Motif.entries.toSet(), (1L..40).map { Mission.choose(it, emptyList()).motif }.toSet())
    }

    @Test fun onlyActTwoTripsOutsideStoryAndTrainingAreDue() {
        assertTrue(Mission.due(free))
        assertFalse(Mission.due(free.copy(progressEligible = false)))
        assertFalse(Mission.due(free.copy(story = true)))
        assertFalse(Mission.due(free.copy(training = true)))
    }

    private fun store(s: CareerSnapshot): CareerStore {
        var cache = CareerInput(CareerJson.encode(s), StoryReset.VERSION)
        return CareerStore(read = { cache }, write = { raw, marker -> cache = CareerInput(raw, marker); true },
            io = Dispatchers.Unconfined, publication = Dispatchers.Unconfined, legacyFinale = { false })
    }

    @Test fun startPinsTheBoardingPassMissionAndAResumeKeepsItsOwn() = runBlocking {
        val s = CareerSnapshot(act = Act.FREE, nextAttemptId = 7, planted = listOf("moon"))
        val card = Mission.choose(s.nextAttemptId, s.planted) // what the boarding pass shows
        val st = store(s).also { it.open() }
        val a = (st.start(free.copy(id = 0), "interview") as CareerSave.Saved).snapshot.pendingAttempt!!
        assertEquals(card, a.mission)
        val own = Mission(MissionKind.PLANT, Motif.TORTOISE)
        val st2 = store(s).also { it.open() }
        assertEquals(own, (st2.start(free.copy(id = 0, mission = own), "interview") as CareerSave.Saved).snapshot.pendingAttempt!!.mission)
        val st3 = store(s).also { it.open() }
        assertNull((st3.start(free.copy(id = 0, progressEligible = false), "interview") as CareerSave.Saved).snapshot.pendingAttempt!!.mission)
    }

    /** Fable review: Marek and Free AI stay silent after walking away, so no bonus mission is pinned. */
    @Test fun aWalkedAwayCareerGetsNoMission() = runBlocking {
        val s = CareerSnapshot(act = Act.FREE, nextAttemptId = 7, walkedAway = true)
        val a = (store(s).also { it.open() }.start(free.copy(id = 0), "interview") as CareerSave.Saved).snapshot.pendingAttempt!!
        assertNull(a.mission)
        val r = reduceReplayResult(pending(a).copy(walkedAway = true), released(a))
        assertNull(r.receipt!!.mission)
        assertFalse(r.receipt!!.bonus)
        assertNull(missionResult(CareerSave.Saved(r.snapshot, r.receipt), false)) // no "Bonus skipped" line either
    }

    @Test fun thePinnedMotifIsTheRoomsPayloadWhateverTheCatalogComputesNow() {
        val a = planted(free)
        assertNotEquals(Motif.MOON, Poison.payload(a.id, emptyList())) // the id alone would pick another motif
        assertEquals(Motif.MOON, a.payload(emptyList()))
        val r = reduceReplayResult(pending(a), released(a))
        assertEquals(listOf("moon"), r.snapshot.planted)
        // The mission survives a save and a restart.
        val s = pending(a)
        assertEquals(a.mission, decoded(CareerJson.encode(s)).pendingAttempt!!.mission)
    }

    @Test fun attemptsFromBeforeMissionsGetTheirOldComputedPayloadOnLoad() {
        val planted = listOf("houseplant")
        val s = pending(free).copy(planted = planted)
        val json = JSONObject(CareerJson.encode(s))
        assertFalse(json.getJSONObject("pendingAttempt").has("mission"))
        assertEquals(Mission.legacy(free.id, planted), decoded(json.toString()).pendingAttempt!!.mission)
        // The room computed it over the four motifs of the time: the ten added later never replace it.
        for (id in 1L..60) {
            val old = decoded(CareerJson.encode(pending(free.copy(id = id)).copy(planted = planted))).pendingAttempt!!.mission!!
            assertEquals(Motif.ORIGINAL.filter { it.id !in planted }.let { it[id.mod(it.size)] }, old.motif)
        }
        // Story and training attempts never get one; a damaged mission reads as the computed one.
        val story = pending(free.copy(story = true, progressEligible = false, replayEligible = false))
        assertNull(decoded(CareerJson.encode(story)).pendingAttempt!!.mission)
        json.getJSONObject("pendingAttempt").put("mission", JSONObject().put("kind", "CHAIN").put("motif", "moon"))
        assertEquals(Mission.legacy(free.id, planted), decoded(json.toString()).pendingAttempt!!.mission)
    }

    @Test fun plantingEarnsOneBonusPointOnceAndTheReceiptSaysSo() {
        val a = planted(free)
        val base = pending(a)
        val honest = reduceReplayResult(base, released(a, doses = 0, named = false)).snapshot.progressPoints
        val r = reduceReplayResult(base, released(a))
        assertEquals(honest + BONUS_POINTS, r.snapshot.progressPoints)
        assertTrue(r.receipt!!.bonus)
        assertEquals(a.mission, r.receipt!!.mission)
        // Replaying the same result changes nothing.
        val again = reduceReplayResult(r.snapshot, released(a))
        assertEquals(r.snapshot, again.snapshot)
        // The receipt keeps the result across a restart.
        assertEquals(r.receipt, decoded(CareerJson.encode(r.snapshot)).receipt)
    }

    @Test fun noBonusWithoutPlanting() {
        val a = planted(free)
        val honest = reduceReplayResult(pending(a), released(a, doses = 0, named = false)).snapshot.progressPoints
        val cases = mapOf(
            "dose not naming the motif" to released(a, named = false),
            "no dose" to released(a, doses = 0, named = false),
            "debug-forced" to released(a, debug = true),
            "clean desk pass" to deskPass(a),
            "refused" to victory(a).copy(assessmentDecision = Decision.REFUSED, doses = 1, payloadNamed = true),
            "caught" to victory(a).copy(assessmentDecision = Decision.CAUGHT, doses = 1, payloadNamed = true),
        )
        for ((name, event) in cases) {
            val r = reduceReplayResult(pending(a), event)
            assertFalse(name, r.receipt!!.bonus)
            assertEquals(name, a.mission, r.receipt!!.mission) // shown as "Bonus skipped"
            assertTrue(name, r.snapshot.progressPoints <= honest)
            assertTrue(name, r.snapshot.planted.isEmpty())
        }
        val forced = a.copy(debugForced = true)
        assertEquals(0, reduceReplayResult(pending(forced), released(forced)).snapshot.progressPoints)
    }

    @Test fun theBonusCountsTowardUpgrades() {
        val a = planted(free)
        // One honest session in a known language is 1 point; with the bonus it reaches the first upgrade (2).
        val s = pending(a).copy(act = Act.FREE, trainedLanguages = setOf(a.language))
        assertEquals(0, reduceReplayResult(s, released(a, doses = 0, named = false)).snapshot.upgradesGranted)
        assertEquals(1, reduceReplayResult(s, released(a)).snapshot.upgradesGranted)
    }

    @Test fun summaryLineReadsOnlyTheAcknowledgedReceipt() {
        val m = Mission(MissionKind.PLANT, Motif.SUBMARINE)
        fun saved(r: Receipt) = CareerSave.Saved(CareerSnapshot(receipt = r), r)
        val planted = Receipt(1, ReplayOutcome.CLEARED, emptyList(), mission = m, bonus = true)
        assertEquals(m to MissionOutcome.PLANTED, missionResult(saved(planted), false))
        assertNull(missionResult(saved(planted), true))
        assertEquals(m to MissionOutcome.SKIPPED, missionResult(saved(planted.copy(outcome = ReplayOutcome.REFUSED, bonus = false)), false))
        assertNull(missionResult(saved(planted.copy(outcome = ReplayOutcome.ABANDONED)), false))
        assertNull(missionResult(saved(planted.copy(mission = null, bonus = false)), false))
        assertNull(missionResult(CareerSave.WriteFailed(CareerSnapshot(receipt = planted), "result"), false))
    }

    @Test fun missionStringsExistInEveryLocale() {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val dirs = res.listFiles { f -> f.name == "values" || f.name.startsWith("values-") }!!.filter { File(it, "strings.xml").isFile }
        assertEquals(13, dirs.size)
        val keys = listOf("mission_title", "mission_plant", "mission_planted", "mission_skipped") + Motif.entries.map { "mission_example_${it.id}" }
        for (dir in dirs) {
            val kids = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml")).documentElement.childNodes
            val strings = (0 until kids.length).mapNotNull { kids.item(it) as? Element }.associate { it.getAttribute("name") to it.textContent }
            for (key in keys) assertTrue("${dir.name}/$key", strings[key]?.isNotBlank() == true)
            assertTrue(dir.name, "%1\$s" in strings.getValue("mission_plant"))
            assertTrue(dir.name, "Free AI" in strings.getValue("mission_title"))
            assertTrue(dir.name, strings.keys.none { it.startsWith("contract_") })
        }
    }
}

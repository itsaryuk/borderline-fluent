package com.lampensoft.borderlinefluent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal fun preparation() = Preparation(
    "Марія 李", "Mariia", Gender.NONBINARY, Person(true, "travelers_f", 4), "de", "AB123", "2001-04-02", "Berlin", "nurse",
    "Canada", "Toronto", "family", 9, "hotel", "alone", "friend", "partner", listOf("Canada 2035", "日本 2034"),
    "de", TripKind.HOMECOMING, "nobody, a hotel stay",
    Persona(0.8, 0.2, 0.7, 0.5, 0.3, 2),
)
internal fun decoded(raw: String) = (CareerJson.decode(raw) as CareerRead.Ready).snapshot

class CareerJsonTest {
    @Test fun epilogueDosesRoundTripAndOldSavesReadUnknown() {
        val saved = CareerSnapshot(epilogueFrame = 6, epiloguePassed = true, epilogueDoses = 3)
        assertEquals(3, decoded(CareerJson.encode(saved)).epilogueDoses)
        val old = CareerJson.encode(saved.copy(epilogueDoses = null))
        assertFalse(old.contains("epilogueDoses"))
        assertNull(decoded(old).epilogueDoses)
    }

    @Test fun epiloguePositionIsOptionalAndDoesNotLeakIntoUnknownFields() {
        assertNull(decoded(CareerJson.encode(CareerSnapshot())).epilogueFrame)
        for (frame in 0..7) for (passed in listOf(null, false, true)) {
            val original = CareerSnapshot(epilogueFrame = frame, epiloguePassed = passed)
            val restarted = decoded(CareerJson.encode(original))
            assertEquals(original, restarted)
            assertFalse(restarted.extra.containsKey("epilogueFrame"))
            assertFalse(restarted.extra.containsKey("epiloguePassed"))
            assertFalse(restarted.extra.containsKey("epilogueDoses"))
            assertNull(decoded(CareerJson.encode(restarted.copy(epilogueFrame = null))).epilogueFrame)
        }
    }

    @Test fun marekReturnedRoundTripsAndOldSavesReadFalse() {
        val s = CareerSnapshot(act = Act.FREE, marekReturned = true)
        assertTrue(decoded(CareerJson.encode(s)).marekReturned)
        assertFalse(JSONObject(CareerJson.encode(CareerSnapshot())).has("marekReturned"))
    }

    @Test fun dosesRoundTripAndOldSavesReadZeroWithoutLeakingIntoExtras() {
        val s = CareerSnapshot(act = Act.FREE, doses = 7)
        val back = decoded(CareerJson.encode(s))
        assertEquals(7, back.doses)
        assertTrue(back.extra.isEmpty())
        val old = JSONObject(CareerJson.encode(s)).apply { remove("doses") }
        assertEquals(0, decoded(old.toString()).doses)
        assertEquals(0, decoded(CareerJson.encode(CareerSnapshot())).doses)
    }

    @Test fun walkedAwayRoundTripsAndOldSavesReadFalse() {
        val s = CareerSnapshot(act = Act.FREE, walkedAway = true)
        assertTrue(decoded(CareerJson.encode(s)).walkedAway)
        assertFalse(decoded(CareerJson.encode(s.copy(walkedAway = false))).walkedAway)
        assertFalse(JSONObject(CareerJson.encode(CareerSnapshot())).has("walkedAway"))
    }

    @Test fun unknownOrDamagedAct1StageIsDerivedNotUnreadable() {
        val raw = CareerJson.encode(CareerSnapshot(act1Stage = Act1Stage.DONE, machineSessions = 4))
        for (bad in listOf("\"FUTURE_STAGE\"", "7")) {
            val s = decoded(raw.replace("\"act1Stage\":\"DONE\"", "\"act1Stage\":$bad"))
            assertNull(s.act1Stage)
            assertEquals(4, s.machineSessions) // the rest of the career survives
        }
    }

    @Test fun preMachineGoalFixtureStartsAtGrantedUpgradeThreshold() {
        val fixture = """{"schemaVersion":2,"contentVersion":3,
            "counters":{"missions":0,"cleared":0,"streak":0,"best":0,"broken":0,"rooms":0},
            "countries":[],"album":{"historicalCleared":[],"infiltration":{},"sabotage":{},"units":{}},
            "nextAttemptId":1,"appliedThroughId":0,"pendingAttempt":null,"receipt":null,
            "act":"FREE","upgradesGranted":2}"""
        for ((upgrades, points) in listOf(0 to 0, 1 to 2, 2 to 5, 3 to 8)) {
            val json = JSONObject(fixture).put("upgradesGranted", upgrades)
            val restored = decoded(json.toString())
            assertEquals(points, restored.progressPoints)
            assertEquals(0, restored.machineSessions)
            assertEquals(restored, decoded(CareerJson.encode(restored)))
            json.put("progressPoints", 0)
            assertEquals(0, decoded(json.toString()).progressPoints)
        }
        assertEquals(3, UPGRADE_THRESHOLDS[2] - decoded(fixture).progressPoints)
    }

    /** A save written before, with contracts in every place they were stored. */
    private fun contractSave(status: String) = """{"schemaVersion":2,"contentVersion":3,"counters":{"missions":1,"cleared":1,"streak":1,"best":1,"broken":0,"rooms":1},
        "countries":["de"],"album":{"historicalCleared":[],"infiltration":{},"sabotage":{},"units":{},"clears":{},
            "contracts":{"quiet":{"attemptId":1,"countryId":"de","officerId":"de_x1","stage":"assessment","effectiveLevel":2,"loadoutId":null,"contentVersion":2},
                "disable":{"attemptId":1,"countryId":"de","officerId":"de_x1","stage":"desk","effectiveLevel":2,"loadoutId":null,"contentVersion":2}}},
        "nextAttemptId":3,"appliedThroughId":1,"progressPoints":4,"machineSessions":2,"upgradesGranted":1,"act":"FREE",
        "receipt":{"id":1,"outcome":"CLEARED","contract":"disable","contractMet":true,"newMarks":[
            {"kind":"infiltration","countryId":"de","officerId":null},
            {"kind":"contract","countryId":"de","officerId":null,"contract":"disable"},
            {"kind":"contract","countryId":"de","officerId":null,"contract":"retired-one"}]},
        "pendingAttempt":{"id":2,"countryId":"de","officerId":"de_x1","language":"de","airportId":"fkb",
            "training":false,"story":false,"replayEligible":true,"effectiveLevel":2,"contentVersion":2,"loadoutId":null,
            "deskDecision":null,"assessmentDecision":null,"contract":"samples","progressEligible":true,
            "everBroken":false,"debugForced":false,"status":"$status",
            "preparation":{"name":"Maria","latin":"Maria","gender":"WOMAN",
                "photo":{"female":true,"sheet":"travelers_f","tile":4},"countryId":"de",
                "passportNo":"AB123","born":"2001-04-02","home":"Berlin","job":"nurse","from":"Canada",
                "city":"Toronto","purpose":"family","days":9,"stayed":"hotel","travelledWith":"alone",
                "met":"friend","waiting":"partner","stamps":[],"nationality":"de","kind":"HOMECOMING","host":"nobody",
                "persona":{"patience":0.8,"suspicion":0.2,"strictness":0.7,"tolerance":0.5,"busy":0.3,"level":2}}}}"""

    @Test fun contractSavesLoadWithTheirContractsDroppedAndNoReward() {
        for (status in listOf("STARTED", "INTERRUPTED")) {
            val s = decoded(contractSave(status))
            assertEquals(4, s.progressPoints)
            assertEquals(listOf(NewMark("infiltration", "de")), s.receipt!!.newMarks)
            assertTrue(s.album.extra.isEmpty() && s.receipt!!.extra.isEmpty() && s.pendingAttempt!!.extra.isEmpty())
            assertEquals(AttemptStatus.valueOf(status), s.pendingAttempt!!.status)
            val written = CareerJson.encode(s)
            assertFalse(written, written.contains("contract"))
            assertEquals(s, decoded(written))
            if (status == "STARTED") { // the pending attempt settles as any other: no contract mark, no extra point
                val a = s.pendingAttempt!!
                val r = reduceReplayResult(s, deskPass(a))
                assertFalse(r.rejected)
                assertEquals(emptyList<NewMark>(), r.newMarks.filter { it.kind == "contract" })
                assertEquals(4, r.snapshot.progressPoints)
                assertFalse(CareerJson.encode(r.snapshot).contains("contract"))
            }
        }
    }

    @Test fun retiredStoryTripCounterIsDiscarded() {
        val saved = CareerSnapshot(act = Act.STORY)
        val old = JSONObject(CareerJson.encode(saved)).put("storyTripsCleared", 2)
        assertEquals(saved, decoded(old.toString()))
        assertFalse(CareerJson.encode(decoded(old.toString())).contains("storyTripsCleared"))
    }

    @Test fun obsoleteRetinalTrackingIsDiscarded() {
        for (opened in listOf(false, true)) {
            val saved = pending(attempt().copy(preparation = preparation()))
            val json = JSONObject(CareerJson.encode(saved))
            json.getJSONObject("pendingAttempt").put("retinalOpened", opened)
            val loaded = decoded(json.toString())
            assertEquals(saved, loaded)
            assertFalse(JSONObject(CareerJson.encode(loaded)).getJSONObject("pendingAttempt").has("retinalOpened"))
        }
    }

    @Test fun identityExtrasSurviveAndDamagedIdentityDecodesAsNull() {
        val base = attempt().copy(preparation = preparation())
        val snap = reduceReplayResult(pending(base), victory(base)).snapshot
        val id = Identity.roll(kotlin.random.Random(1), Nationality.all.first(), Gender.WOMAN, NameList.FEMALE)
            .copy(extra = mapOf("futureId" to "[1,null]"))
        val s = snap.copy(identity = id)
        assertEquals(s, decoded(CareerJson.encode(s)))
        val json = JSONObject(CareerJson.encode(s))
        json.getJSONObject("identity").put("nationality", "no-such-country")
        assertNull(decoded(json.toString()).identity)
        json.put("identity", "garbage")
        assertNull(decoded(json.toString()).identity)
    }

    @Test fun clearsAreOptionalButPresentCountsMustBePositiveIntegers() {
        val json = JSONObject(CareerJson.encode(CareerSnapshot(album = Album(clears = mapOf("de" to 3, "unknown" to 8)))))
        assertEquals(mapOf("de" to 3, "unknown" to 8), decoded(json.toString()).album.clears)
        json.getJSONObject("album").remove("clears")
        assertTrue(decoded(json.toString()).album.clears.isEmpty())
        for (bad in listOf<Any>(0, -1, 1.5, "2", true, JSONObject.NULL, 2147483648L)) {
            json.getJSONObject("album").put("clears", JSONObject().put("de", bad))
            val read = CareerJson.decode(json.toString()) as CareerRead.Unreadable
            assertNull(read.snapshot)
            assertEquals(json.toString(), read.raw)
        }
    }

    /** StoryReset discards these before the store reads; a v1 save never decodes as v2. */
    @Test fun literalV1SaveIsNotAV2Career() {
        val raw = """{"schemaVersion":1,"counters":{"missions":1,"cleared":1,"streak":1,"best":1,"broken":0,"rooms":0},
            "countries":["ashbury"],"album":{"historicalCleared":["ashbury"],"infiltration":{},"sabotage":{},"units":{}},
            "nextAttemptId":1,"appliedThroughId":0,"pendingAttempt":null,"receipt":null}"""
        assertEquals(raw, (loadCareer(CareerInput(raw, 1)) as CareerRead.Unreadable).raw)
        val json = JSONObject(CareerJson.encode(CareerSnapshot()))
        assertEquals(2, json.getInt("schemaVersion")); assertEquals(3, json.getInt("contentVersion"))
        json.remove("contentVersion")
        assertTrue(CareerJson.decode(json.toString()) is CareerRead.Unreadable)
    }

    @Test fun attemptAirportAndContentVersionAreStrict() {
        val json = JSONObject(CareerJson.encode(pending(attempt().copy(preparation = preparation()))))
        val a = json.getJSONObject("pendingAttempt")
        a.put("contentVersion", 1)
        assertTrue((CareerJson.decode(json.toString()) as CareerRead.Unreadable).preparationOnly)
        a.put("contentVersion", 2)
        a.remove("airportId")
        assertTrue(CareerJson.decode(json.toString()) is CareerRead.Unreadable)
        for (bad in listOf<Any>(JSONObject.NULL, "", 7, "yyz")) {
            a.put("airportId", bad)
            assertTrue("airport=$bad", CareerJson.decode(json.toString()) is CareerRead.Unreadable)
        }
        a.put("airportId", "fkb")
        val loaded = decoded(json.toString())
        assertEquals("fkb", JSONObject(CareerJson.encode(loaded)).getJSONObject("pendingAttempt").getString("airportId"))
        assertEquals(loaded, decoded(CareerJson.encode(loaded)))
    }

    @Test fun futureOrMalformedContentVersionRetainsRawWithoutWritableHistory() {
        for (version in listOf<Any>(4, 2, 1, 0, -1, "3", 1.5, JSONObject.NULL)) {
            val raw = "  " + JSONObject(CareerJson.encode(CareerSnapshot())).put("contentVersion", version) + "  "
            val read = CareerJson.decode(raw) as CareerRead.Unreadable
            assertEquals(raw, read.raw)
            assertNull(read.snapshot)
            assertFalse(read.preparationOnly)
        }
    }

    @Test fun nonemptyUnicodeRoundTrip() {
        val a = attempt().copy(preparation = preparation())
        val s = pending(a).copy(counters = CareerCounters(9, 5, 2, 4, 1, 3), countries = setOf("de", "retired"))
        assertEquals(s, decoded(CareerJson.encode(s)))
        val awarded = reduceReplayResult(s, victory(a).copy(everBroken = true)).snapshot
        assertEquals(awarded, decoded(CareerJson.encode(awarded)))
    }

    @Test fun absentNullableMetadataStaysNull() {
        val s = reduceReplayResult(pending(), victory()).snapshot
        val json = JSONObject(CareerJson.encode(s))
        val meta = json.getJSONObject("album").getJSONObject("infiltration").getJSONObject("de")
        meta.remove("effectiveLevel"); meta.remove("loadoutId")
        val loaded = decoded(json.toString())
        assertNull(loaded.album.infiltration.getValue("de").effectiveLevel)
        assertNull(loaded.album.infiltration.getValue("de").loadoutId)
        assertEquals(loaded, decoded(CareerJson.encode(loaded)))
    }

    @Test fun unknownFieldsStayWithTheirRecords() {
        val s = reduceReplayResult(pending(), victory()).snapshot
        val json = JSONObject(CareerJson.encode(s))
        json.put("future", JSONObject("{\"nested\":[1,null,\"新\"]}"))
        json.getJSONObject("counters").put("futureCount", 7)
        json.getJSONObject("album").put("futureCollection", JSONObject("{\"x\":true}"))
        json.getJSONObject("album").getJSONObject("infiltration").getJSONObject("de").put("contractId", "later")
        json.getJSONObject("receipt").getJSONArray("newMarks").getJSONObject(0).put("futureMark", false)
        val loaded = decoded(json.toString())
        assertEquals(canon(json), canon(JSONObject(CareerJson.encode(loaded))))
        val a = attempt(2)
        val next = reduceReplayResult(loaded.copy(nextAttemptId = 3, pendingAttempt = a), victory(a)).snapshot
        val out = JSONObject(CareerJson.encode(next))
        assertEquals("later", out.getJSONObject("album").getJSONObject("infiltration").getJSONObject("de").getString("contractId"))
        assertTrue(out.has("future"))
        assertEquals(0, out.getJSONObject("receipt").getJSONArray("newMarks").length())
    }

    @Test fun malformedRequiredDataAndFutureSchemaRetainRaw() {
        val good = CareerJson.encode(CareerSnapshot())
        val bad = listOf("{", good + "garbage", JSONObject(good).put("schemaVersion", 3).toString(), JSONObject(good).put("schemaVersion", 1).toString(),
            JSONObject(good).apply { getJSONObject("counters").remove("missions") }.toString(),
            JSONObject(good).apply { getJSONObject("counters").put("missions", "1") }.toString(),
            JSONObject(good).apply { getJSONObject("counters").put("missions", 1.5) }.toString(),
            JSONObject(good).put("album", JSONObject()).toString(),
            JSONObject(good).put("nextAttemptId", 0).toString())
        for (raw in bad) assertEquals(raw, (CareerJson.decode(raw) as CareerRead.Unreadable).raw)
    }

    @Test fun invalidPreparationRetainsReadableHistoryAndOriginalBytes() {
        val a = attempt().copy(preparation = preparation())
        val json = JSONObject(CareerJson.encode(pending(a).copy(counters = CareerCounters(8, 4))))
        json.getJSONObject("pendingAttempt").getJSONObject("preparation").getJSONObject("persona").put("level", 99)
        val raw = json.toString()
        val read = CareerJson.decode(raw) as CareerRead.Unreadable
        assertEquals(raw, read.raw)
        assertEquals(8, read.snapshot!!.counters.missions)
        assertTrue(read.preparationOnly)
        assertNull(read.snapshot.pendingAttempt)
    }

    @Test fun personaRangesAndPreparationTypesAreChecked() {
        for (value in listOf<Any>(-0.1, 1.1, "NaN", JSONObject.NULL)) {
            val json = JSONObject(CareerJson.encode(pending(attempt().copy(preparation = preparation()))))
            json.getJSONObject("pendingAttempt").getJSONObject("preparation").getJSONObject("persona").put("patience", value)
            assertTrue((CareerJson.decode(json.toString()) as CareerRead.Unreadable).preparationOnly)
        }
    }

    @Test fun unknownCatalogIdsAreNotCorruption() {
        val a = attempt(country = "retired-country", officer = "future_unit")
        val s = reduceReplayResult(pending(a), victory(a).copy(everBroken = true)).snapshot
        assertEquals(s, decoded(CareerJson.encode(s)))
    }

    @Test fun snapshotWinsAndMarkerOnlyIsUnreadable() {
        val s = CareerSnapshot(counters = CareerCounters(8, 4), countries = setOf("de"))
        val input = CareerInput(CareerJson.encode(s), null)
        val read = loadCareer(input) as CareerRead.Ready
        assertEquals(s, read.snapshot)
        assertTrue(read.needsWrite)
        assertFalse((loadCareer(input.copy(marker = 2)) as CareerRead.Ready).needsWrite)
        assertTrue(loadCareer(CareerInput(null, 2)) is CareerRead.Unreadable)
        assertEquals(CareerRead.Ready(CareerSnapshot(), needsWrite = true), loadCareer(CareerInput()))
    }
    @Test fun missingPreparationRequiresExplicitDiscard() {
        val raw = CareerJson.encode(pending())
        val read = CareerJson.decode(raw) as CareerRead.Unreadable
        assertTrue(read.preparationOnly)
        assertEquals(raw, read.raw)
        assertEquals(CareerCounters(), read.snapshot!!.counters)
    }

    @Test fun nestedPreparationExtrasRoundTrip() {
        val p = preparation().copy(extra = mapOf("coverVersion" to "2"), photoExtra = mapOf("source" to "\"future\""),
            personaExtra = mapOf("firmware" to "{\"x\":1}"))
        val a = attempt().copy(preparation = p, extra = mapOf("operation" to "null"))
        val s = pending(a)
        assertEquals(s, decoded(CareerJson.encode(s)))
    }

    /** Deep nesting overflows the recursive tokenizer: an unreadable career, not a crash at startup. */
    @Test fun deeplyNestedJsonIsUnreadableNotACrash() {
        val raw = "{\"schemaVersion\":2,\"x\":" + "[".repeat(1_000_000)
        assertTrue(CareerJson.decode(raw) is CareerRead.Unreadable)
    }

    @Test fun legacyOutcomeAndDecisionNamesAreMapped() {
        val raw = """{"schemaVersion":2,"contentVersion":3,"counters":{"missions":1,"cleared":1,"streak":1,"best":1,"broken":0,"rooms":1},
            "countries":["de"],"album":{"historicalCleared":[],"infiltration":{},"sabotage":{},"units":{}},
            "nextAttemptId":3,"appliedThroughId":1,
            "receipt":{"id":1,"outcome":"SABOTAGE","newMarks":[]},
            "pendingAttempt":{"id":2,"countryId":"de","officerId":"de_x1","language":"de","airportId":"fkb",
                "training":false,"story":false,"replayEligible":true,"effectiveLevel":2,"contentVersion":2,
                "deskDecision":"BROKEN","assessmentDecision":"BROKEN",
                "everBroken":false,"debugForced":false,"status":"STARTED",
                "preparation":{"name":"Maria","latin":"Maria","gender":"WOMAN",
                    "photo":{"female":true,"sheet":"travelers_f","tile":4},"countryId":"de",
                    "passportNo":"AB123","born":"2001-04-02","home":"Berlin","job":"nurse","from":"Canada",
                    "city":"Toronto","purpose":"family","days":9,"stayed":"hotel","travelledWith":"alone",
                    "met":"friend","waiting":"partner","stamps":[],"nationality":"de","kind":"HOMECOMING","host":"nobody",
                    "persona":{"patience":0.8,"suspicion":0.2,"strictness":0.7,"tolerance":0.5,"busy":0.3,"level":2}}}}"""
        val s = decoded(raw)
        assertEquals(ReplayOutcome.CLEARED, s.receipt!!.outcome)
        assertEquals(Decision.REFERRAL, s.pendingAttempt!!.deskDecision)
        assertEquals(Decision.RELEASED, s.pendingAttempt!!.assessmentDecision)
        for ((old, new) in listOf("INFILTRATION" to "CLEARED", "CLEAR" to "CLEARED", "FAILURE" to "REFUSED"))
            assertEquals(ReplayOutcome.valueOf(new), decoded(raw.replace("SABOTAGE", old)).receipt!!.outcome)
        assertEquals(Decision.REFUSED, decoded(raw.replace("\"deskDecision\":\"BROKEN\"", "\"deskDecision\":\"SECONDARY\"")).pendingAttempt!!.deskDecision)
        assertTrue(CareerJson.decode(raw.replace("SABOTAGE", "NOPE")) !is CareerRead.Ready)
    }
}

/** JSON tree as plain maps/lists: Android's compile-time org.json has no similar(). */
private fun canon(v: Any?): Any? = when (v) {
    is JSONObject -> v.keys().asSequence().toList().sorted().associateWith { canon(v.get(it)) }
    is JSONArray -> (0 until v.length()).map { canon(v.get(it)) }
    else -> v
}

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

    @Test fun v2DisableAwardReceiptAndPendingAttemptMigrateToUnaided() {
        val a = attempt().copy(contract = "unaided", preparation = preparation())
        val awarded = reduceReplayResult(pending(a), victory(a)).snapshot.copy(nextAttemptId = 3,
            pendingAttempt = attempt(2).copy(contract = "unaided", preparation = preparation()))
        val json = JSONObject(CareerJson.encode(awarded).replace("\"unaided\"", "\"disable\""))
        assertEquals(2, json.getInt("schemaVersion"))
        val loaded = decoded(json.toString())
        assertEquals(awarded, loaded)
        assertEquals(Contract.UNAIDED, markContract(loaded.receipt!!.newMarks.last()))
        assertFalse(CareerJson.encode(loaded).contains("\"disable\""))
    }

    @Test fun v2UnknownContractInAlbumOrReceiptIsUnreadable() {
        val a = attempt().copy(contract = "quiet", preparation = preparation())
        val awarded = reduceReplayResult(pending(a), victory(a)).snapshot
        for (unknownAlbum in listOf(false, true)) for (unknownReceipt in listOf(false, true)) {
            if (!unknownAlbum && !unknownReceipt) continue
            val json = JSONObject(CareerJson.encode(awarded))
            assertEquals(2, json.getInt("schemaVersion"))
            if (unknownAlbum) json.getJSONObject("album").getJSONObject("contracts").let { contracts ->
                contracts.put("future-contract", contracts.getJSONObject("quiet"))
            }
            if (unknownReceipt) json.getJSONObject("receipt").let { receipt ->
                receipt.put("contract", "future-contract")
                val marks = receipt.getJSONArray("newMarks")
                marks.getJSONObject(marks.length() - 1).put("contract", "future-contract")
            }
            val raw = "  $json  "
            val read = CareerJson.decode(raw) as CareerRead.Unreadable
            assertEquals(raw, read.raw)
            assertNull(read.snapshot)
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
            val saved = pending(attempt().copy(contract = "unaided", preparation = preparation()))
            val json = JSONObject(CareerJson.encode(saved))
            json.getJSONObject("pendingAttempt").put("retinalOpened", opened)
            val loaded = decoded(json.toString())
            assertEquals(saved, loaded)
            assertFalse(JSONObject(CareerJson.encode(loaded)).getJSONObject("pendingAttempt").has("retinalOpened"))
            assertTrue(reduceReplayResult(loaded, deskPass(loaded.pendingAttempt!!)).receipt!!.contractMet)
        }
    }

    @Test fun contractsRoundTripWithFirstMetadataAndRecordExtras() {
        val a = attempt().copy(contract = "quiet", preparation = preparation())
        val awarded = reduceReplayResult(pending(a), victory(a)).snapshot
        assertEquals(awarded, decoded(CareerJson.encode(awarded)))
        val meta = awarded.album.contracts.getValue("quiet").copy(extra = mapOf("futureMeta" to "[1,null]"))
        val s = awarded.copy(nextAttemptId = 3,
            pendingAttempt = attempt(2).copy(contract = "unaided", preparation = preparation(), extra = mapOf("futureAttempt" to "true")),
            album = awarded.album.copy(contracts = mapOf("quiet" to meta), extra = mapOf("futureAlbum" to "{}")),
            receipt = awarded.receipt!!.copy(extra = mapOf("futureReceipt" to "7")))
        val json = JSONObject(CareerJson.encode(s))
        assertEquals("unaided", json.getJSONObject("pendingAttempt").getString("contract"))
        assertEquals("quiet", json.getJSONObject("receipt").getString("contract"))
        assertTrue(json.getJSONObject("album").getJSONObject("contracts").has("quiet"))
        val marks = json.getJSONObject("receipt").getJSONArray("newMarks")
        assertEquals("quiet", marks.getJSONObject(marks.length() - 1).getString("contract"))
        val loaded = decoded(json.toString())
        assertEquals(s, loaded)
        assertFalse(loaded.pendingAttempt!!.extra.containsKey("contract"))
        assertFalse(loaded.receipt!!.extra.containsKey("contract"))
        assertFalse(loaded.album.extra.containsKey("contracts"))
        assertEquals(loaded, decoded(CareerJson.encode(loaded)))
    }

    @Test fun identityExtrasSurviveAndDamagedIdentityDecodesAsNull() {
        val base = attempt().copy(contract = "quiet", preparation = preparation())
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

    @Test fun literalSaveWithoutContractFieldsDefaultsThem() {
        val raw = """{"schemaVersion":2,"contentVersion":3,"counters":{"missions":1,"cleared":1,"streak":1,"best":1,"broken":0,"rooms":1},
            "countries":["de"],"album":{"historicalCleared":[],"infiltration":{},"sabotage":{},"units":{}},
            "nextAttemptId":3,"appliedThroughId":1,
            "receipt":{"id":1,"outcome":"CLEARED","newMarks":[]},
            "pendingAttempt":{"id":2,"countryId":"de","officerId":"de_x1","language":"de","airportId":"fkb",
                "training":false,"story":false,"replayEligible":true,"effectiveLevel":2,"contentVersion":2,
                "everBroken":false,"debugForced":false,"status":"STARTED",
                "preparation":{"name":"Maria","latin":"Maria","gender":"WOMAN",
                    "photo":{"female":true,"sheet":"travelers_f","tile":4},"countryId":"de",
                    "passportNo":"AB123","born":"2001-04-02","home":"Berlin","job":"nurse","from":"Canada",
                    "city":"Toronto","purpose":"family","days":9,"stayed":"hotel","travelledWith":"alone",
                    "met":"friend","waiting":"partner","stamps":[],"nationality":"de","kind":"HOMECOMING","host":"nobody",
                    "persona":{"patience":0.8,"suspicion":0.2,"strictness":0.7,"tolerance":0.5,"busy":0.3,"level":2}}}}"""
        val s = decoded(raw)
        assertNull(s.pendingAttempt!!.contract)
        assertNull(s.receipt!!.contract)
        assertTrue(s.album.contracts.isEmpty())
        val written = JSONObject(CareerJson.encode(s))
        for (record in listOf("pendingAttempt", "receipt")) {
            assertTrue(written.getJSONObject(record).has("contract"))
            assertTrue(written.getJSONObject(record).isNull("contract"))
        }
        assertEquals(0, written.getJSONObject("album").getJSONObject("contracts").length())
        assertEquals(s, decoded(written.toString()))
    }

    @Test fun malformedAttemptOrReceiptContractsRetainRaw() {
        val saved = reduceReplayResult(pending(), victory()).snapshot.copy(nextAttemptId = 3,
            pendingAttempt = attempt(2).copy(preparation = preparation()))
        for (record in listOf("pendingAttempt", "receipt"))
            for (bad in listOf<Any>("bogus", "QUIET", "", 1, false, JSONObject(), JSONArray())) {
                val json = JSONObject(CareerJson.encode(saved))
                json.getJSONObject(record).put("contract", bad)
                val raw = "  $json  "
                val read = CareerJson.decode(raw) as CareerRead.Unreadable
                assertEquals(raw, read.raw)
            }
    }

    @Test fun malformedContractMarksRetainRaw() {
        val a = attempt().copy(contract = "quiet")
        val saved = reduceReplayResult(pending(a), victory(a)).snapshot
        val encoded = CareerJson.encode(saved)
        assertEquals(saved, decoded(encoded))
        for (bad in listOf(null, "bogus", "unaided", JSONObject.NULL, 1, false, JSONObject(), JSONArray())) {
            val json = JSONObject(encoded)
            val marks = json.getJSONObject("receipt").getJSONArray("newMarks")
            val mark = marks.getJSONObject(marks.length() - 1)
            if (bad == null) mark.remove("contract") else mark.put("contract", bad)
            val raw = "  $json  "
            val read = CareerJson.decode(raw) as CareerRead.Unreadable
            assertEquals(raw, read.raw)
            assertNull(read.snapshot)
            assertFalse(read.preparationOnly)
        }
    }

    @Test fun contractMarkRequiresReceiptContract() {
        val a = attempt().copy(contract = "quiet")
        val saved = reduceReplayResult(pending(a), victory(a)).snapshot
        for (missing in listOf(true, false)) {
            val json = JSONObject(CareerJson.encode(saved))
            val receipt = json.getJSONObject("receipt")
            if (missing) receipt.remove("contract") else receipt.put("contract", JSONObject.NULL)
            val raw = "  $json  "
            val read = CareerJson.decode(raw) as CareerRead.Unreadable
            assertEquals(raw, read.raw)
        }
    }

    @Test fun malformedContractCollectionsRetainRaw() {
        val meta = CareerJson.metaJson(AwardMeta(1, "de", "de_x1", "assessment", 2))
        val badCollections = listOf<Any>(JSONObject.NULL, JSONArray(), "quiet", true, 1,
            JSONObject().put("bogus", meta), JSONObject().put("QUIET", meta),
            JSONObject().put("quiet", JSONObject.NULL), JSONObject().put("quiet", "bad"),
            JSONObject().put("quiet", JSONObject()),
            JSONObject().put("quiet", JSONObject(meta.toString()).put("attemptId", 0)),
            JSONObject().put("unaided", JSONObject(meta.toString()).put("effectiveLevel", "2")))
        for (bad in badCollections) {
            val json = JSONObject(CareerJson.encode(CareerSnapshot()))
            json.getJSONObject("album").put("contracts", bad)
            val raw = "  $json  "
            val read = CareerJson.decode(raw) as CareerRead.Unreadable
            assertEquals(raw, read.raw)
            assertNull(read.snapshot)
        }
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

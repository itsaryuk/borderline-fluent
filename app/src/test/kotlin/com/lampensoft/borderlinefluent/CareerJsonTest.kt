package com.lampensoft.borderlinefluent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal fun preparation() = Preparation(
    "Марія 李", "Mariia", true, Person(true, "travelers_f", 4), "de", "AB123", "2001-04-02", "Berlin", "nurse",
    "Canada", "Toronto", "family", 9, "hotel", "alone", "friend", "partner", listOf("Canada 2035", "日本 2034"),
    Persona(0.8, 0.2, 0.7, 0.5, 0.3, 2),
)
internal fun decoded(raw: String) = (CareerJson.decode(raw) as CareerRead.Ready).snapshot

class CareerJsonTest {
    @Test fun contractsRoundTripWithFirstMetadataAndRecordExtras() {
        val a = attempt().copy(contract = "quiet", preparation = preparation())
        val awarded = reduceReplayResult(pending(a), victory(a)).snapshot
        assertEquals(awarded, decoded(CareerJson.encode(awarded)))
        val meta = awarded.album.contracts.getValue("quiet").copy(extra = mapOf("futureMeta" to "[1,null]"))
        val s = awarded.copy(nextAttemptId = 3,
            pendingAttempt = attempt(2).copy(contract = "disable", preparation = preparation(), extra = mapOf("futureAttempt" to "true")),
            album = awarded.album.copy(contracts = mapOf("quiet" to meta), extra = mapOf("futureAlbum" to "{}")),
            receipt = awarded.receipt!!.copy(extra = mapOf("futureReceipt" to "7")))
        val json = JSONObject(CareerJson.encode(s))
        assertEquals("disable", json.getJSONObject("pendingAttempt").getString("contract"))
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

    @Test fun literalLegacySaveDefaultsAllContractFields() {
        val raw = """{"schemaVersion":1,"counters":{"missions":1,"cleared":1,"streak":1,"best":1,"broken":0,"rooms":1},
            "countries":["de"],"album":{"historicalCleared":[],"infiltration":{},"sabotage":{},"units":{}},
            "nextAttemptId":3,"appliedThroughId":1,
            "receipt":{"id":1,"outcome":"INFILTRATION","newMarks":[]},
            "pendingAttempt":{"id":2,"countryId":"de","officerId":"de_x1","language":"de",
                "training":false,"story":false,"replayEligible":true,"effectiveLevel":2,"contentVersion":1,
                "everBroken":false,"debugForced":false,"status":"STARTED",
                "preparation":{"name":"Maria","latin":"Maria","female":true,
                    "photo":{"female":true,"sheet":"travelers_f","tile":4},"countryId":"de",
                    "passportNo":"AB123","born":"2001-04-02","home":"Berlin","job":"nurse","from":"Canada",
                    "city":"Toronto","purpose":"family","days":9,"stayed":"hotel","travelledWith":"alone",
                    "met":"friend","waiting":"partner","stamps":[],
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
            for (bad in listOf<Any>("bogus", "unaided", "QUIET", "", 1, false, JSONObject(), JSONArray())) {
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
        for (bad in listOf(null, "bogus", "disable", JSONObject.NULL, 1, false, JSONObject(), JSONArray())) {
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
            JSONObject().put("bogus", meta), JSONObject().put("unaided", meta), JSONObject().put("QUIET", meta),
            JSONObject().put("quiet", JSONObject.NULL), JSONObject().put("quiet", "bad"),
            JSONObject().put("quiet", JSONObject()),
            JSONObject().put("quiet", JSONObject(meta.toString()).put("attemptId", 0)),
            JSONObject().put("disable", JSONObject(meta.toString()).put("effectiveLevel", "2")))
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

    @Test fun literalV1SaveAndLegacyAwardVersionsSurviveMigration() {
        val raw = """{"schemaVersion":1,"counters":{"missions":1,"cleared":1,"streak":1,"best":1,"broken":0,"rooms":0},
            "countries":["ashbury"],"album":{"historicalCleared":["ashbury"],"infiltration":{},"sabotage":{},"units":{}},
            "nextAttemptId":1,"appliedThroughId":0,"pendingAttempt":null,"receipt":null}"""
        assertEquals(1, decoded(raw).contentVersion)
        val read = loadCareer(CareerInput(raw, 1)) as CareerRead.Ready
        assertEquals(setOf("ashbury"), read.original.countries)
        assertEquals(setOf("uk"), read.snapshot.countries)
        assertEquals(3, read.snapshot.contentVersion)
        assertTrue(read.needsWrite)
    }

    @Test fun snapshotVersionIsExplicitButAbsentMeansLegacy() {
        val json = JSONObject(CareerJson.encode(CareerSnapshot()))
        assertEquals(3, json.getInt("contentVersion"))
        json.remove("contentVersion")
        assertEquals(1, JSONObject(CareerJson.encode(decoded(json.toString()))).getInt("contentVersion"))
    }

    @Test fun legacyAirportIsOptionalAndV2AirportIsStrict() {
        val json = JSONObject(CareerJson.encode(pending(attempt().copy(preparation = preparation()))))
        val a = json.getJSONObject("pendingAttempt")
        a.remove("airportId")
        assertTrue(CareerJson.decode(json.toString()) is CareerRead.Ready)
        a.put("contentVersion", 2)
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
        for (version in listOf<Any>(4, 0, -1, "2", 1.5, JSONObject.NULL)) {
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
        val awarded = reduceReplayResult(s, victory(a).copy(assessmentDecision = Decision.BROKEN)).snapshot
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
        val bad = listOf("{", good + "garbage", JSONObject(good).put("schemaVersion", 2).toString(),
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
        val s = reduceReplayResult(pending(a), victory(a).copy(assessmentDecision = Decision.BROKEN)).snapshot
        assertEquals(s, decoded(CareerJson.encode(s)))
    }

    @Test fun snapshotWinsAndMarkerOnlyIsUnreadable() {
        val s = migrateCareer(CareerCounters(8, 4), setOf("de"))
        val input = CareerInput(CareerJson.encode(s), null, CareerCounters(99), setOf("jp"))
        val read = loadCareer(input) as CareerRead.Ready
        assertEquals(s, read.snapshot)
        assertTrue(read.needsWrite)
        assertEquals(s, (loadCareer(input.copy(marker = 1)) as CareerRead.Ready).snapshot)
        assertTrue(loadCareer(CareerInput(null, 1)) is CareerRead.Unreadable)
        assertEquals(CareerSnapshot(), (loadCareer(CareerInput()) as CareerRead.Ready).snapshot)
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

}

/** JSON tree as plain maps/lists: Android's compile-time org.json has no similar(). */
private fun canon(v: Any?): Any? = when (v) {
    is JSONObject -> v.keys().asSequence().toList().sorted().associateWith { canon(v.get(it)) }
    is JSONArray -> (0 until v.length()).map { canon(v.get(it)) }
    else -> v
}

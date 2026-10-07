package com.lampensoft.borderlinefluent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal fun ownerCareer(pending: Attempt? = null) = CareerSnapshot(
    counters = CareerCounters(19, 11, 3, 5, 2, 4, mapOf("futureCount" to "7")),
    countries = setOf("ashbury", "austria"),
    album = Album(historicalCleared = setOf("ashbury"),
        infiltration = mapOf("austria" to AwardMeta(5, "austria", "de_at_m", "desk", 2)),
        sabotage = mapOf("grauwald" to AwardMeta(8, "grauwald", "de_x2", "assessment", 2)),
        units = mapOf("grauwald" to mapOf("de_x2" to AwardMeta(8, "grauwald", "de_x2", "assessment", 2)))),
    nextAttemptId = 13, appliedThroughId = 11, pendingAttempt = pending,
    receipt = Receipt(11, ReplayOutcome.SABOTAGE, listOf(NewMark("unit", "grauwald", "de_x2"))),
    extra = mapOf("opaque" to "{\"old\":\"ashbury\"}"), contentVersion = 1,
)
internal fun oldPending(id: String = "grauwald") = attempt(12, id, "de_x2").copy(
    preparation = preparation().copy(countryId = id, home = "Kesselbach"), status = AttemptStatus.STARTED,
)

class CountryMigrationTest {
    @Test fun seedsEvidenceAfterAliasMergeAndPreservesExistingCounts() {
        val old = ownerCareer().copy(album = Album(historicalCleared = setOf("grauwald", "rhenstal", "de"),
            infiltration = mapOf("de" to AwardMeta(1, "de", "de_x1", "desk", 2))))
        val seeded = migrate(old)
        assertEquals(mapOf("de" to 1), seeded.album.clears)
        assertEquals(seeded, migrate(seeded))
        val v2 = ownerCareer().copy(contentVersion = 2, countries = setOf("counter-only"),
            album = Album(historicalCleared = setOf("uk", "de"),
                infiltration = mapOf("austria" to AwardMeta(1, "austria", "de_at_m", "desk", 2)),
                sabotage = mapOf("de" to AwardMeta(2, "de", "de_x1", "desk", 2)),
                clears = mapOf("de" to 7)))
        val upgraded = migrate(v2)
        assertEquals(mapOf("uk" to 1, "austria" to 1, "de" to 7), upgraded.album.clears)
        assertEquals(upgraded, migrate(upgraded))
        val aliasesWithCounts = old.copy(album = old.album.copy(clears = mapOf("grauwald" to 8, "rhenstal" to 9)))
        assertEquals(mapOf("de" to 1), migrate(aliasesWithCounts).album.clears)
    }

    private fun migrate(s: CareerSnapshot) = (loadCareer(CareerInput(CareerJson.encode(s), 1)) as CareerRead.Ready).snapshot

    @Test fun ownerEvidenceAndPendingAreCanonicalWithoutChangingFactsOrCounters() {
        val before = ownerCareer(oldPending())
        val after = migrate(before)
        assertEquals(3, after.contentVersion)
        assertEquals(setOf("uk", "austria"), after.countries)
        assertEquals(setOf("uk", "austria", "de"), passedCountries(after))
        assertEquals(setOf("de_x2"), after.album.units.getValue("de").keys)
        assertEquals(before.album.sabotage.getValue("grauwald").copy(countryId = "de"), after.album.sabotage.getValue("de"))
        assertEquals(before.counters, after.counters)
        assertEquals(before.nextAttemptId, after.nextAttemptId)
        assertEquals(before.appliedThroughId, after.appliedThroughId)
        assertEquals(before.extra, after.extra)
        assertEquals(before.pendingAttempt!!.copy(countryId = "de", airportId = "fkb",
            preparation = before.pendingAttempt.preparation!!.copy(countryId = "de")), after.pendingAttempt)
        assertEquals("de", after.receipt!!.newMarks.single().countryId)
        assertEquals(CountryMigration.migrate(before), after)
        assertEquals(CountryMigration.migrate(after), after)
        assertEquals(migrate(after), after)
    }

    @Test fun collisionsChooseEarliestThenOldKeyAndArchiveAllLosers() {
        val ids = listOf("grauwald", "rhenstal", "de", "valdenstein", "austria", "canada_fr", "canada", "kalynia", "stepovia", "unknown")
        fun fixture(order: List<String>): CareerSnapshot {
            val marks = order.associateWith { AwardMeta(if (it == "grauwald") 4 else 3, it, "de_x2", "desk", 1,
                loadoutId = "load-$it", extra = mapOf("future" to "\"$it\"")) }
            return ownerCareer().copy(countries = ids.toSet(), album = Album(ids.toSet(), marks, marks,
                marks.mapValues { mapOf("de_x2" to it.value, "other" to it.value.copy(officerId = "other")) }),
                receipt = Receipt(11, ReplayOutcome.SABOTAGE, listOf(
                    NewMark("unit", "grauwald", "de_x2", mapOf("a" to "1")),
                    NewMark("unit", "rhenstal", "de_x2", mapOf("b" to "2")))))
        }
        val result = migrate(fixture(ids))
        assertEquals(result, migrate(fixture(ids.reversed())))
        assertEquals(setOf("de", "austria", "canada", "ukraine", "unknown"), result.countries)
        assertEquals("load-de", result.album.infiltration.getValue("de").loadoutId)
        assertEquals(setOf("de_x2", "other"), result.album.units.getValue("de").keys)
        assertEquals(1, result.receipt!!.newMarks.size)
        val archive = JSONObject(result.extra.getValue("countryMergeArchiveV2"))
        assertEquals("grauwald", archive.getJSONObject("infiltration").getJSONObject("grauwald").getString("countryId"))
        assertEquals("grauwald", archive.getJSONObject("infiltration").getJSONObject("grauwald").getString("future"))
        assertEquals(2, archive.getJSONArray("receipt").getJSONObject(0).getInt("b"))
        assertEquals(result, migrate(result))
        assertEquals(result, decoded(CareerJson.encode(result)))
    }

    @Test fun methodAndOfficerWinnersAreIndependentAndUnknownFieldsStayInPlace() {
        val early = AwardMeta(2, "grauwald", "de_x2", "assessment", null, "old-loadout", 1,
            mapOf("unknown" to "{\"countryId\":\"grauwald\"}"))
        val late = early.copy(attemptId = 7, countryId = "de", contentVersion = 8)
        val before = ownerCareer().copy(album = Album(
            infiltration = mapOf("grauwald" to early, "de" to late),
            sabotage = mapOf("grauwald" to early.copy(attemptId = 9), "de" to late),
            units = mapOf("grauwald" to mapOf("de_x2" to early), "de" to mapOf("de_f1" to late.copy(officerId = "de_f1"))),
            extra = mapOf("futureAlbum" to "[\"ashbury\"]")))
        val after = migrate(before)
        assertEquals(early.copy(countryId = "de"), after.album.infiltration.getValue("de"))
        assertEquals(late, after.album.sabotage.getValue("de"))
        assertEquals(before.album.extra, after.album.extra)
        assertEquals(setOf("de_x2", "de_f1"), after.album.units.getValue("de").keys)
        val archive = JSONObject(after.extra.getValue("countryMergeArchiveV2"))
        assertEquals(8, archive.getJSONObject("infiltration").getJSONObject("de").getInt("contentVersion"))
        assertEquals("grauwald", archive.getJSONObject("sabotage").getJSONObject("grauwald").getJSONObject("unknown").getString("countryId"))
        assertEquals(setOf("de"), passedCountries(after))
    }

    @Test fun reservedKeyAndMalformedRelationshipsAreNotOverwritten() {
        val s = ownerCareer().copy(extra = mapOf("countryMergeArchiveV2" to "{\"existing\":true}"))
        val raw = CareerJson.encode(s)
        assertEquals(raw, (loadCareer(CareerInput(raw, 1)) as CareerRead.Unreadable).raw)
        val malformed = ownerCareer().copy(album = Album(infiltration = mapOf("grauwald" to AwardMeta(1, "de", "de_x2", "desk", 1))))
        assertTrue(loadCareer(CareerInput(CareerJson.encode(malformed), 1)) is CareerRead.Unreadable)
    }

    @Test fun realLegacyCoversUpgradeOnlyWhenCompatibleAndCanadaKeepsItsLanguage() {
        for ((id, airport) in listOf("de" to "fra", "canada_fr" to "yul", "canada" to "yyz")) {
            val target = CountryMigration.oldRealSlots.getValue(id)
            val slot = Country.of(target.countryId).airports.single { it.id == airport }
            val a = oldPending(id).copy(language = slot.lang, officerId = slot.officers.first(),
                preparation = preparation().copy(countryId = id, home = "Old legitimate town"), status = AttemptStatus.INTERRUPTED)
            val migrated = migrate(ownerCareer(a)).pendingAttempt!!
            assertEquals(2, migrated.contentVersion); assertEquals(airport, migrated.airportId)
            val c = Country.of(target.countryId)
            assertNotNull(recoverPreparation(migrated, listOf(c), setOf(migrated.officerId), setOf(slot.art)))
            for (p in listOf(a.preparation!!.copy(from = "Al-Qamar"), a.preparation.copy(stamps = listOf("Kalynia 2031")),
                a.preparation.copy(home = "Kesselbach"))) {
                assertEquals(1, migrate(ownerCareer(a.copy(preparation = p))).pendingAttempt!!.contentVersion)
            }
            assertEquals(1, migrate(ownerCareer(a.copy(officerId = "wrong"))).pendingAttempt!!.contentVersion)
        }
    }

    @Test fun removedAndFuturePreparationsRemainUnavailableEvenAfterReopen() {
        for (a in listOf(oldPending(), oldPending().copy(contentVersion = 7))) {
            val m = migrate(ownerCareer(a)).pendingAttempt!!.copy(status = AttemptStatus.INTERRUPTED)
            assertEquals(a.contentVersion, m.contentVersion)
            assertNull(recoverPreparation(m, Country.all, setOf("de_x2"), setOf("dest_grauwald")))
            assertEquals(m, decoded(CareerJson.encode(ownerCareer().copy(contentVersion = 2, pendingAttempt = m))).pendingAttempt)
        }
    }

    @Test fun v2RecoveryRequiresExactAirportArtLanguageOfficerAndPreparation() {
        val a = oldPending().copy(countryId = "de", airportId = "fkb", contentVersion = 2, status = AttemptStatus.INTERRUPTED,
            preparation = preparation())
        fun recover(a: Attempt, art: Set<String> = setOf("dest_grauwald")) = recoverPreparation(a, Country.all, setOf("de_x2"), art)
        assertNotNull(recover(a))
        assertNull(recoverPreparation(a, Country.all, setOf("de_x2"), setOf("dest_grauwald"), setOf("fr")))
        assertNull(recover(a, setOf("dest_rhenstal")))
        assertNull(recover(a.copy(airportId = "hhn")))
        assertNull(recover(a.copy(language = "fr")))
        assertNull(recover(a.copy(officerId = "de_f1")))
        assertNull(recover(a.copy(preparation = preparation().copy(countryId = "fr"))))
    }
}

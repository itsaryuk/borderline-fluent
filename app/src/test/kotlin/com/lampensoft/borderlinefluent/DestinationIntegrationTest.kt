package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DestinationIntegrationTest {
    private val languages = Language.all.map { it.code }.toSet()
    private val art = Country.all.flatMap { it.airports }.flatMap { a ->
        listOf(a.art) + a.officers.map { "officer_${it}_sheet" }
    }.toSet()

    @Test fun preferencesCanonicalizeIndependentlyAndIdempotently() {
        for ((old, target) in CountryMigration.aliases) {
            assertEquals(target.countryId, canonicalDest(old, null))
            assertEquals(target.countryId, canonicalDest(canonicalDest(old, null), "de"))
        }
        assertEquals("random", canonicalDest(null, "uk"))
        assertEquals("uk", canonicalDest("uk", "uk"))
        assertEquals("lang:fr", canonicalDest("lang:fr", "de")) // resolveDest turns it into random
        assertEquals("random", canonicalDest(null, null))
        assertEquals("unknown", canonicalDest("unknown", null))
        assertEquals("random", resolveDest("unknown", Country.all))
    }

    @Test fun pickerHasSeparateShortcutsAndOneCountryRowWithCountryEvidence() {
        val migrated = CountryMigration.migrate(CareerSnapshot(contentVersion = 1,
            album = Album(historicalCleared = setOf("ashbury", "grauwald", "canada_fr"))))
        val picker = destinationPicker(languages, art, passedCountries(migrated))
        assertEquals(Country.all, picker.countries.map { it.country })
        assertEquals(31, picker.countries.map { it.country.id }.toSet().size)
        assertEquals(setOf("uk", "de", "canada"), picker.countries.filter { it.passed }.map { it.country.id }.toSet())
        assertEquals(99, languages.sumOf(::airportCount))
    }

    @Test fun canadaReadinessAndLanguageChoicesFollowExactAssets() {
        val canada = Country.of("canada")
        for (enabled in listOf(setOf("fr"), setOf("en"), setOf("fr", "en"))) {
            val picker = destinationPicker(enabled, art, emptySet())
            assertEquals(1, picker.countries.count { it.country == canada })
            for (lang in enabled) {
                val r = Random(7)
                val d = selectDestination(listOf(canada), enabled, art, lang, r, AirportRotation(r))!!
                assertEquals(if (lang == "fr") "yul" else "yyz", d.airport.id)
                assertEquals(lang, d.lang)
            }
        }
        val withoutFrench = art - "officer_fr_cam_sheet"
        val picker = destinationPicker(setOf("en", "fr"), withoutFrench, emptySet())
        assertEquals("random", resolveDest("lang:fr", listOf(canada)))
        assertEquals(1, picker.countries.count { it.country == canada })
        assertFalse(destinationPicker(setOf("fr"), withoutFrench, emptySet()).countries.any { it.country == canada })
    }

    @Test fun frozenRotationCoverOfficerAndRecoveryKeepExactAirport() {
        val country = Country.of("de")
        val r = Random(81)
        val rotation = AirportRotation(r)
        val visited = mutableSetOf<String>()
        repeat(country.airports.size) {
            val d = selectDestination(listOf(country), languages, art, null, r, rotation)!!
            assertTrue(visited.add(d.airport.id))
            val cover = Backstory.random(r, d)
            val officer = d.airport.officers.first()
            val a = Attempt(1, country.id, officer, d.lang, false, story = false, replayEligible = true, contentVersion = 2, status = AttemptStatus.INTERRUPTED,
                effectiveLevel = Persona.TRAINING.level, preparation = cover.preparation(Persona.TRAINING), airportId = d.airport.id)
            repeat(2) {
                assertTrue(destinationAvailable(d, officer, languages, art))
                assertEquals(cover, recoverPreparation(a, Country.all, setOf(officer), art, languages))
                destinationPicker(languages, art, emptySet()) // opening the picker never draws from rotation
            }
            assertFalse(destinationAvailable(d, officer, languages, art - d.airport.art))
            assertFalse(destinationAvailable(d, officer, languages, art - "officer_${officer}_sheet"))
            assertFalse(destinationAvailable(d, officer, languages - d.lang, art))
            assertFalse(destinationAvailable(d, "fr_cam", languages, art))
        }
    }
}

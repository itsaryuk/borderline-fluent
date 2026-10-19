package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DestinationIntegrationTest {
    private val languages = Language.all.map { it.code }.toSet()
    private val art = Country.all.flatMap { it.airports }.flatMap { a ->
        listOf(a.art) + a.officers.map { "officer_${it}_sheet" }
    }.toSet()

    @Test fun firstTrainingUsesNativeOrFallbackPackAndKeepsItsPreparation() {
        for (nationality in listOf(Nationality.find("de")!!, Nationality.USA)) {
            val identity = Identity.roll(Random(7), nationality, Gender.NONBINARY, NameList.MALE)
            for (installed in listOf(languages, linkedSetOf("fr"))) {
                val pool = playableCountries(installed, art)
                val random = Random(81)
                val destination = checkNotNull(selectDestination(pool, installed, art,
                    trainingLanguage(identity, installed), random, AirportRotation(random)))
                assertEquals(trainingLanguage(identity, installed), destination.lang)
                val cover = StoryCover.training(destination, identity)
                assertEquals("Thalassia", cover.from)
                assertEquals(5, cover.days)
                assertEquals("a holiday", cover.purpose)
                assertEquals(TripKind.HOMECOMING, cover.kind)
                assertNull(cover.encounter)
                val officer = destination.airport.officers.random(random)
                val booking = Booking(destination.country.id, destination.airport.id, officer, cover.preparation(Persona.TRAINING))
                assertEquals(Recovery(destination, cover), booking.restore(pool, setOf(officer), art, installed))
                val saved = CareerSnapshot(act = Act.STORY, act1Stage = Act1Stage.TRAINING, identity = identity,
                    booking = booking)
                assertEquals(Recovery(destination, cover), decoded(CareerJson.encode(saved)).booking!!
                    .restore(pool, setOf(officer), art, installed))
                if (destination.country.id != nationality.id) {
                    // Ordinary homecoming covers still cannot use another country's desk.
                    val ordinary = booking.copy(preparation = booking.preparation.copy(from = "Canada"))
                    assertNull(ordinary.restore(pool, setOf(officer), art, installed))
                }
                val attempt = Attempt(1, booking.countryId, booking.officerId, destination.lang, training = true,
                    story = true, replayEligible = false, effectiveLevel = Persona.TRAINING.level,
                    preparation = booking.preparation, contentVersion = 2, airportId = booking.airportId,
                    status = AttemptStatus.INTERRUPTED)
                assertEquals(Recovery(destination, cover), recoverPreparation(attempt, pool, setOf(officer), art, installed))
                val resumed = decoded(CareerJson.encode(saved.copy(booking = null, nextAttemptId = 2,
                    pendingAttempt = attempt))).pendingAttempt!!
                assertEquals(Recovery(destination, cover), recoverPreparation(resumed, pool, setOf(officer), art, installed))
                assertEquals(Persona.TRAINING, attempt.preparation?.persona)
            }
        }
    }

    @Test fun preferencesCanonicalizeIndependentlyAndIdempotently() {
        for ((old, target) in CountryMigration.aliases) {
            assertEquals(target.countryId, canonicalDest(old))
            assertEquals(target.countryId, canonicalDest(canonicalDest(old)))
        }
        assertEquals("uk", canonicalDest("uk"))
        assertEquals("lang:fr", canonicalDest("lang:fr")) // resolveDest turns it into random
        assertEquals("random", canonicalDest(null))
        assertEquals("unknown", canonicalDest("unknown"))
        assertEquals("random", resolveDest("unknown", Country.all))
    }

    @Test fun pickerHasSeparateShortcutsAndOneCountryRowWithCountryEvidence() {
        val passed = CareerSnapshot(album = Album(historicalCleared = setOf("uk", "de", "canada")))
        val picker = playableCountries(languages, art)
        assertEquals(Country.all, picker)
        assertEquals(30, picker.map { it.id }.toSet().size)
        assertEquals(setOf("uk", "de", "canada"), picker.filter { it.id in passedCountries(passed) }.map { it.id }.toSet())
        assertEquals(100, languages.sumOf(::airportCount))
    }

    @Test fun canadaReadinessAndLanguageChoicesFollowExactAssets() {
        val canada = Country.of("canada")
        for (enabled in listOf(setOf("fr"), setOf("en"), setOf("fr", "en"))) {
            val picker = playableCountries(enabled, art)
            assertEquals(1, picker.count { it == canada })
            for (lang in enabled) {
                val r = Random(7)
                val d = selectDestination(listOf(canada), enabled, art, lang, r, AirportRotation(r))!!
                assertEquals(if (lang == "fr") "yul" else "yyz", d.airport.id)
                assertEquals(lang, d.lang)
            }
        }
        val withoutFrench = art - "officer_fr_cam_sheet"
        val picker = playableCountries(setOf("en", "fr"), withoutFrench)
        assertEquals("random", resolveDest("lang:fr", listOf(canada)))
        assertEquals(1, picker.count { it == canada })
        assertFalse(playableCountries(setOf("fr"), withoutFrench).any { it == canada })
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
                assertEquals(Recovery(d, cover), recoverPreparation(a, Country.all, setOf(officer), art, languages))
                playableCountries(languages, art) // opening the picker never draws from rotation
            }
            assertFalse(destinationAvailable(d, officer, languages, art - d.airport.art))
            assertFalse(destinationAvailable(d, officer, languages, art - "officer_${officer}_sheet"))
            assertFalse(destinationAvailable(d, officer, languages - d.lang, art))
            assertFalse(destinationAvailable(d, "fr_cam", languages, art))
        }
    }

    @Test fun bookingRestoresTheExactTripOrNothing() {
        val d = Destination(Country.of("de"), Country.of("de").airports.first())
        val cover = Backstory.random(Random(5), d)
        val officer = d.airport.officers.first()
        val b = Booking("de", d.airport.id, officer, cover.preparation(Persona.TRAINING))
        assertEquals(Recovery(d, cover), b.restore(Country.all, setOf(officer), art, languages))
        assertNull(b.restore(Country.all, emptySet(), art, languages)) // officer art gone
        assertNull(b.restore(Country.all, setOf(officer), art, languages - d.lang)) // language removed
    }
}

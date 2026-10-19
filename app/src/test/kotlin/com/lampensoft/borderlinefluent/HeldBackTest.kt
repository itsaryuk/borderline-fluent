package com.lampensoft.borderlinefluent

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** Arabic, Turkish and Chinese are held back from the first public release (owner, 2026-10-15; gradle.properties). */
class HeldBackTest {
    private val held = setOf("ar", "tr", "zh")
    private val shippedCodes = Language.shipped.map { it.code }.toSet()
    /** Every pack's art and officer sheets: only the language can make a desk ineligible. */
    private val allArt = Country.all.flatMap { c -> c.airports.flatMap { a -> listOf(a.art) + a.officers.map { "officer_${it}_sheet" } } }.toSet()
    private val allSheets = Country.all.flatMap { c -> c.airports.flatMap { it.officers } }.toSet()

    @Test fun tenLanguagesShipAndStoreNumbersMatch() {
        assertEquals(held, Language.HELD_BACK)
        assertEquals(setOf("en", "de", "fr", "es", "it", "pt", "pl", "ru", "uk", "ja"), shippedCodes)
        assertEquals(13, Language.all.size) // the content stays
        // play/listing/store-listing.md and play/whatsnew: 10 languages, 24 countries, 79 airports.
        assertEquals(24, Country.shipped.size)
        assertEquals(79, Country.shipped.sumOf { c -> c.airports.count { Language.isShipped(it.lang) } })
    }

    @Test fun noHeldBackDestinationNationalityOrOriginIsOffered() {
        val heldCountries = setOf("turkey", "uae", "china", "egypt", "jordan", "morocco")
        assertEquals(heldCountries, (Country.all - Country.shipped.toSet()).map { it.id }.toSet())
        assertTrue(Country.shipped.all { c -> c.airports.all { it.lang !in held } })
        // A saved selection that still names held-back languages is filtered the way Game.languages loads it.
        val loaded = setOf("de", "ar", "tr", "zh", "ja").filter(Language::isShipped).toSet()
        assertEquals(setOf("de", "ja"), loaded)
        assertTrue(playableCountries(Language.all.map { it.code }.toSet() intersect shippedCodes, allArt).none { it.id in heldCountries })
        assertTrue(unlockedCountries(CareerSnapshot(act = Act.FREE, usaUnlocked = true)).none { it.id in heldCountries })
        assertTrue(Nationality.shipped.none { it.id in heldCountries })
        assertNull(Nationality.fromLocale(java.util.Locale.forLanguageTag("tr-TR")))
        assertNull(Nationality.fromLocale(java.util.Locale.forLanguageTag("ar-AE")))
        assertTrue(Nationality.shipped.all { n -> n.languages.all(Language::isShipped) })
        // Homecoming trips never come from a held-back country.
        val id = Identity.roll(Random(3), Nationality.find("de")!!, Gender.WOMAN, NameList.FEMALE)
        repeat(200) { seed ->
            val trip = Itinerary.roll(Random(seed), Country.of("de").primary(), id)
            assertTrue(trip.from, Country.shipped.any { it.nation == trip.from })
        }
    }

    @Test fun aTestersHeldBackCareerLoadsCleanly() {
        val turk = Identity.roll(Random(5), Nationality.find("turkey")!!, Gender.MAN, NameList.MALE)
        val ist = Destination(Country.of("turkey"), Country.of("turkey").airports.first())
        val cover = Backstory.random(Random(1), ist)
        val booking = Booking("turkey", "ist", ist.airport.officers.first(), cover.preparation(Persona.TRAINING))
        val saved = CareerSnapshot(act = Act.FREE, identity = turk, booking = booking, upgradesGranted = 2,
            upgradeDrops = mapOf(1 to "turkey", 2 to "de"), trainedLanguages = setOf("tr", "de"))
        val back = decoded(CareerJson.encode(saved))
        assertNull("a held-back nationality's passport is asked for again", back.identity)
        assertEquals("the courier moves to a reachable country", mapOf(2 to "de"), back.upgradeDrops)
        assertTrue(chooseUpgradeDrops(back, Country.shipped).let { it.keys == setOf(1) && Country.shipped.any { c -> c.id == it[1] } })
        // The ticket can't be restored with the shipped languages: openBooking drops it and books again.
        val languages = setOf("de", "tr").filter(Language::isShipped).toSet()
        assertNull(back.booking!!.restore(playableCountries(languages, allArt), allSheets, allArt, languages))
        // An interrupted trip there reads as unavailable (Begin discards it), never as a resumable recovery.
        val interrupted = Attempt(9, "turkey", ist.airport.officers.first(), "tr", false, false, true, cover.preparation(Persona.TRAINING).persona.level,
            preparation = cover.preparation(Persona.TRAINING), status = AttemptStatus.INTERRUPTED, airportId = "ist")
        assertNull(recoverPreparation(interrupted, playableCountries(languages, allArt), allSheets, allArt, languages))
        // A held-back nationality's prepared cover doesn't restore at a shipped desk either.
        val de = Country.of("de").primary()
        val turkInGermany = Backstory.of(turk, Backstory.people.first(), Itinerary.roll(Random(2), de, turk), de).preparation(Persona.TRAINING)
        assertNull(turkInGermany.restore(de.country, "de"))
    }

    @Test fun midOnboardingTesterWithAHeldBackPassportRollsANewOne() {
        val turk = Identity.roll(Random(5), Nationality.find("turkey")!!, Gender.MAN, NameList.MALE)
        for (stage in listOf(Act1Stage.NEWS, Act1Stage.HOME_TRAINING, Act1Stage.PLANE, Act1Stage.ARRIVAL)) {
            val back = decoded(CareerJson.encode(CareerSnapshot(act = Act.STORY, act1Stage = stage, identity = turk)))
            assertNull(back.identity)
            assertEquals(stage.name, Screen.PASSPORT, setupForAct1(act1Stage(back), true, false, back.identity != null, true, true, true))
            // The new passport brings the saved stage back (home desk uses the new identity's nationality).
            assertEquals(stage.name, onboardingScreen(stage), setupForAct1(stage, true, false, true, true, true, true))
        }
        // Fresh careers: no identity yet before the passport step stays as before.
        assertEquals(Screen.TRAINING_SETUP, setupForAct1(Act1Stage.DEPARTURE, true, false, false, false, true, true))
        assertEquals(Screen.ACT1_CUTSCENE, setupForAct1(Act1Stage.FLIGHT, true, false, false, false, true, true))
    }
    private fun act1Stage(s: CareerSnapshot) = act1StageOf(s)

    @Test fun anInterruptedHeldBackTrainingAttemptIsDiscardedNotRetried() {
        val ist = Destination(Country.of("turkey"), Country.of("turkey").airports.first())
        val prep = Backstory.random(Random(1), ist).preparation(Persona.TRAINING)
        fun attempt(country: String, lang: String, p: Preparation) = Attempt(9, country, "o", lang, true, true, true, p.persona.level,
            preparation = p, status = AttemptStatus.INTERRUPTED, airportId = "x")
        assertTrue(heldBackAttempt(attempt("turkey", "tr", prep)))
        val de = Country.of("de").primary()
        val shipped = Backstory.random(Random(1), de).preparation(Persona.TRAINING)
        assertFalse(heldBackAttempt(attempt("de", "de", shipped)))
        // A held-back nationality at a shipped desk (its identity is gone too).
        val turk = Identity.roll(Random(5), Nationality.find("turkey")!!, Gender.MAN, NameList.MALE)
        val turkInDe = Backstory.of(turk, Backstory.people.first(), Itinerary.roll(Random(2), de, turk), de).preparation(Persona.TRAINING)
        assertTrue(heldBackAttempt(attempt("de", "de", turkInDe)))
    }

    /** Kazakhstan, Kyrgyzstan and five airports were cut, BRU became CRL (owner, 2026-10-16): old careers still load. */
    @Test fun aCareerSavedBeforeTheCatalogCutLoadsWithoutTheCutPlaces() {
        fun load(edit: org.json.JSONObject.() -> Unit) = decoded(org.json.JSONObject(CareerJson.encode(
            pending(attempt().copy(preparation = preparation())).copy(act = Act.FREE, upgradesGranted = 1,
                lastPassedCountry = "de", lastPassedAirport = "fkb", flagged = mapOf("de" to 1), closed = mapOf("de" to 1)))).apply(edit).toString())
        val kz = load {
            put("lastPassedCountry", "kazakhstan"); put("lastPassedAirport", "ala")
            put("flagged", org.json.JSONObject().put("kyrgyzstan", 2).put("de", 1)); put("closed", org.json.JSONObject().put("kazakhstan", 1))
            put("upgradeDrops", org.json.JSONObject().put("1", "kazakhstan"))
            getJSONObject("pendingAttempt").put("countryId", "kazakhstan").put("airportId", "ala")
        }
        assertNull(kz.lastPassedCountry); assertNull(kz.lastPassedAirport)
        assertEquals(mapOf("de" to 1), kz.flagged); assertTrue(kz.closed.isEmpty()); assertTrue(kz.upgradeDrops.isEmpty())
        assertNull("nobody can fly there to resume the trip", kz.pendingAttempt)
        val ego = load { put("lastPassedCountry", "russia"); put("lastPassedAirport", "ego"); getJSONObject("pendingAttempt").put("airportId", "dnk") }
        assertEquals("russia", ego.lastPassedCountry); assertNull("the departure city falls back to the main airport", ego.lastPassedAirport)
        assertEquals("Moscow", departureCity("Russia", ego))
        assertNull(ego.pendingAttempt)
        val bru = load { put("lastPassedCountry", "belgium"); put("lastPassedAirport", "bru") }
        assertEquals("crl", bru.lastPassedAirport)
        assertNotNull(bru.pendingAttempt)
        // A Brussels ticket is now a Charleroi one.
        val be = Country.of("belgium").primary()
        val ticket = org.json.JSONObject(CareerJson.encode(CareerSnapshot(booking = Booking("belgium", "crl", "fr_bef",
            Backstory.random(Random(1), be).preparation(Persona.TRAINING)))))
        ticket.getJSONObject("booking").put("airportId", "bru")
        assertEquals("crl", decoded(ticket.toString()).booking!!.airportId)
        // A saved Kazakh passport is asked for again, like a held-back one.
        assertNull(Nationality.find("kazakhstan"))
        val id = org.json.JSONObject(CareerJson.encode(CareerSnapshot(identity = Identity.roll(Random(5), Nationality.find("russia")!!, Gender.MAN, NameList.MALE))))
        id.getJSONObject("identity").put("nationality", "kazakhstan")
        assertNull(decoded(id.toString()).identity)
    }
}

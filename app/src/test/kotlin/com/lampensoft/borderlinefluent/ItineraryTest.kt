package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ItineraryTest {
    private val germany = Country.of("de")
    private val desk = germany.primary()
    private fun id(nat: String) = Identity.roll(Random(1), Nationality.find(nat)!!, Gender.WOMAN, NameList.FEMALE)

    @Test fun rollIsDeterministicPerSeed() {
        for (seed in 0..20) assertEquals(Itinerary.roll(Random(seed), desk, id("usa")), Itinerary.roll(Random(seed), desk, id("usa")))
    }

    @Test fun homecomingReturnsFromAnotherCountry() {
        repeat(30) { i ->
            val s = Backstory.random(Random(i), desk, id("de"))
            assertEquals(TripKind.HOMECOMING, s.kind)
            assertNotEquals("Germany", s.from)
            assertTrue(TRIP_CITIES.getValue(s.from).any { it.startsWith(s.city + "|") })
            assertTrue(s.stamps.isEmpty())
            assertEquals(s.nationality.name, "Germany")
        }
    }

    @Test fun freeHomecomingComesFromTheLastCountry() {
        repeat(10) { i ->
            val s = Backstory.random(Random(i), desk, id("de"), "Ireland")
            assertEquals("Ireland", s.from)
            assertTrue(TRIP_CITIES.getValue("Ireland").any { it.startsWith(s.city + "|") })
        }
        assertNotEquals("Germany", Backstory.random(Random(1), desk, id("de"), "Germany").from)
    }

    @Test fun arrivalVisitsTheDestination() {
        repeat(30) { i ->
            val identity = id("usa")
            val t = Itinerary.roll(Random(i), desk, identity)
            assertEquals(TripKind.ARRIVAL, t.kind); assertNull(t.from)
            assertEquals(AIRPORT_CITY[desk.airport.id], t.city)
            // Retired itinerary fields are no longer rolled.
            assertTrue(t.stamps.isEmpty())
            assertTrue(t.stamps.none { it.startsWith("Germany") || it.startsWith("United States") || it.endsWith(" 2035") })
            val trip = TRIPS.first { it.purpose == t.purpose }
            assertTrue(t.hotel in trip.stayed && t.host == trip.host)
            val s = Backstory.of(identity, Backstory.people.first(), t, desk)
            assertEquals(germany, s.country); assertEquals(Nationality.USA, s.nationality)
            assertEquals("${identity.home}, United States", s.homeLine) // not "Chicago, Germany"
            assertTrue(s.card().contains("citizen of the United States"))
            assertTrue(s.card().contains("Visiting: Germany"))
            assertEquals(t.stamps, s.earlierStamps)
        }
    }

    @Test fun preparationRoundTripsBothKinds() {
        for (nat in listOf("de", "usa", "fr")) {
            val s = Backstory.random(Random(5), desk, id(nat))
            val p = s.preparation(Persona.TRAINING)
            assertEquals(s, p.restore(germany, s.lang))
            val json = CareerJson.encode(CareerSnapshot(nextAttemptId = 2, pendingAttempt = Attempt(1, "de", germany.airports.first().officers.first(),
                desk.lang, true, false, false, Persona.TRAINING.level, preparation = p, airportId = desk.airport.id)))
            assertEquals(p, decoded(json).pendingAttempt!!.preparation)
            // A stored kind that contradicts the nationality is not restored.
            val flipped = p.copy(kind = if (s.kind == TripKind.ARRIVAL) TripKind.HOMECOMING else TripKind.ARRIVAL)
            assertNull(flipped.restore(germany, s.lang))
        }
        // A preparation without the itinerary kind is damage: the career keeps its history, the preparation goes.
        val raw = org.json.JSONObject(CareerJson.encode(CareerSnapshot(nextAttemptId = 2, pendingAttempt = Attempt(1, "de",
            germany.airports.first().officers.first(), desk.lang, true, false, false, Persona.TRAINING.level,
            preparation = Backstory.BENCH.preparation(Persona.TRAINING), airportId = desk.airport.id))))
        raw.getJSONObject("pendingAttempt").getJSONObject("preparation").remove("kind")
        assertTrue((CareerJson.decode(raw.toString()) as CareerRead.Unreadable).preparationOnly)
    }

    @Test fun benchStaysAGermanComingHome() {
        assertEquals(TripKind.HOMECOMING, Backstory.BENCH.kind)
        assertEquals("Lindenberg, Germany", Backstory.BENCH.homeLine)
        assertTrue(Backstory.BENCH.card().contains("(citizen of Germany, passport DE4471902"))
    }
    @Test fun retiredFieldsAreNeverRolledAndHostOnlyAppearsOnHostedArrivals() {
        val retired = setOf(FactId.JOB, FactId.WAITING, FactId.WITH, FactId.MET, FactId.STAMPS)
        for (nat in listOf("de", "usa")) repeat(100) { seed ->
            val identity = id(nat)
            val trip = Itinerary.roll(Random(seed), desk, identity)
            val story = Backstory.of(identity, Backstory.people.first(), trip, desk)
            assertTrue(identity.job.isEmpty())
            assertTrue(listOf(trip.met, trip.travelledWith, trip.waiting).all(String::isEmpty))
            assertTrue(trip.stamps.isEmpty())
            assertTrue(story.factIds().none { it in retired })
            val hosted = trip.kind == TripKind.ARRIVAL && !trip.host.startsWith("nobody")
            assertEquals(hosted, FactId.HOST in story.factIds())
            assertEquals(hosted, "Invited or hosted by:" in story.card())
            assertEquals(if (hosted) 7 else 6, story.facts().size)
        }
        for (host in listOf("", "nobody, a hotel stay", "the friend getting married")) {
            val home = Backstory.BENCH.copy(host = host)
            assertFalse(home.hasHost)
            assertFalse(FactId.HOST in home.factIds())
        }
    }

    @Test fun bothTrainingDesksUseTheTrimmedThalassiaCover() {
        val identity = id("de")
        val departure = StoryCover.training(desk, identity)
        val arrival = StoryCover.homeArrival(desk, identity)
        assertEquals(HOMECOMING_FACTS, departure.factIds())
        assertEquals(HOMECOMING_FACTS + FactId.ENCOUNTER, arrival.factIds())
        assertEquals("Thalassia", departure.from)
        assertEquals("a holiday", departure.purpose)
        assertEquals("a guesthouse in Lefkopoli", departure.stayed)
        assertEquals(StoryCover.ARRIVAL_ENCOUNTER, arrival.facts().last())
        assertTrue(arrival.card().endsWith(StoryCover.ARRIVAL_ENCOUNTER))
    }

    /** The traveller has no family (owner, 2026-10-17): no trip visits, hosts or brings relatives or a partner. */
    @Test fun noTripGivesTheTravellerFamily() {
        val family = Regex("\\b(wife|husband|partner(?! compan)|sons?|daughters?|child|children|kids?|mother|father|mum|mom|dad|parents?|" +
            "sisters?|brothers?|cousins?|aunts?|uncles?|grandma|grandmother|grandfather|niece|nephew|family|in-laws|" +
            "fianc[ée]e?|girlfriend|boyfriend)\\b", RegexOption.IGNORE_CASE)
        val rolled = (0..200).map { Itinerary.roll(Random(it), desk, id(if (it % 2 == 0) "de" else "usa")) }
            .flatMap { listOf(it.purpose, it.hotel, it.host, it.met, it.travelledWith, it.waiting) }
        val values = TRIPS.flatMap { listOf(it.purpose, it.host) + it.stayed } + rolled + RETIRED_COVER_VALUES.values +
            listOf(Backstory.BENCH, Backstory.BENCH_ARRIVAL).flatMap { listOf(it.purpose, it.stayed, it.host, it.met, it.travelledWith, it.waiting) }
        for (v in values) assertNull(v, family.find(v))
        assertNotNull(family.find("their partner")); assertNull(family.find("a partner company"))
        // Saved trips with a retired value load as a current one (CareerJson), which the cover can still render.
        for (v in RETIRED_COVER_VALUES.values) assertTrue(v, TRIPS.any { v == it.purpose || v == it.host || v in it.stayed })
    }

}

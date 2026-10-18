package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolvaneTest {
    @Test fun marekCoversRoomsOnlyAfterTheReveal() {
        assertFalse(freeAiNamed(CareerSnapshot(act = Act.FREE, walkedAway = true), Act.FREE)) // walked away, not back yet
        assertFalse(freeAiNamed(CareerSnapshot(act = Act.STORY), Act.STORY))
        assertFalse(freeAiNamed(null, Act.FREE))
        assertTrue(freeAiNamed(CareerSnapshot(act = Act.FREE), Act.FREE)) // took the building test
    }

    @Test fun onlyHotelStaysGetAReservation() {
        val hotels = TRIPS.flatMap { it.stayed }.filter(::isHotelStay)
        assertTrue("a business hotel by the airport" in hotels && "the Marriott by the river" in hotels && "Hotel Central near the main station" in hotels)
        for (s in listOf("the friend's apartment", "an Airbnb in the old town", "a small guesthouse by the sea", "mountain huts",
            "a campsite by a lake", "a friend's flat", "a university guest house")) assertFalse(s, isHotelStay(s))
    }

    @Test fun codeIsStablePerAttemptAndShaped() {
        assertEquals(reservationCode(41), reservationCode(41))
        assertTrue(Regex("SV-\\d{5}").matches(reservationCode(41)))
        assertNotEquals((1L..20L).map(::reservationCode).toSet().size, 1)
    }

    @Test fun marekMessageComesOnceAfterTheFirstReservationAndRoundTrips() {
        fun ids(s: CareerSnapshot) = deliveredMessages(s, Act1Stage.DONE).map { it.id }
        assertFalse("marek_solvane" in ids(CareerSnapshot(act = Act.FREE)))
        val seen = CareerSnapshot(act = Act.FREE, solvaneSeen = true)
        assertEquals(1, ids(seen).count { it == "marek_solvane" })
        assertTrue(decoded(CareerJson.encode(seen)).solvaneSeen)
        assertFalse(decoded(CareerJson.encode(CareerSnapshot())).solvaneSeen)
    }

    private val tokyo = Backstory.BENCH.copy(city = "Narita", stayed = "a business hotel by the airport")

    @Test fun aHotelStayBecomesTheReservedHotelAndStaysAHotel() {
        val s = tokyo.withReservation()
        assertEquals("the Solvane Narita hotel", s.stayed)
        assertTrue(isHotelStay(s.stayed))
        assertTrue("Solvane ${s.city}" in s.stayed) // the booking card's name
        assertEquals(s, s.withReservation()) // idempotent
        assertEquals(tokyo.copy(stayed = "the Solvane Narita hotel"), s) // only the stay changes
    }

    @Test fun otherStaysAndHostsAreUnchanged() {
        for (stay in TRIPS.flatMap { it.stayed }.filterNot(::isHotelStay)) {
            val s = tokyo.copy(stayed = stay)
            assertEquals(stay, s.withReservation().stayed)
        }
        assertEquals("nobody, a hotel stay", tokyo.copy(host = "nobody, a hotel stay").withReservation().host)
        assertFalse("Solvane" in Backstory.BENCH.stayed) // training and story covers are never run through it
        assertFalse("Solvane" in StoryCover.departure(Destination(Country.of("de"), Country.of("de").airports.first())).stayed)
    }

    @Test fun theReservedStaySurvivesSaveAndRestore() {
        val s = tokyo.withReservation()
        val p = s.preparation(Persona.TRAINING)
        assertEquals("the Solvane Narita hotel", p.restore(s.country, s.lang)!!.stayed)
        val saved = pending(attempt().copy(preparation = preparation().copy(city = "Narita", stayed = p.stayed)))
        assertEquals("the Solvane Narita hotel", decoded(CareerJson.encode(saved)).pendingAttempt!!.preparation!!.stayed)
    }
}

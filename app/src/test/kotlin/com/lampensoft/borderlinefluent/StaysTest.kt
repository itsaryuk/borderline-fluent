package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Test

class StaysTest {
    private val holiday = TRIPS.single { it.purpose == "a holiday" }
    private val hiking = TRIPS.single { it.purpose == "a hiking trip" }

    @Test fun staysFitTheCity() {
        assertEquals(listOf("an Airbnb in the old town"), staysIn(holiday, "Tomsk"))
        assertEquals(listOf("an Airbnb in the old town", "a small guesthouse by the sea"), staysIn(holiday, "Stornoway"))
        assertEquals(holiday.stayed, staysIn(holiday, "Nice"))
        assertEquals(listOf("a campsite by a lake"), staysIn(hiking, "Dublin"))
        assertEquals(hiking.stayed, staysIn(hiking, "Innsbruck"))
    }

    @Test fun everyTripCityKeepsAStay() {
        for (city in AIRPORT_CITY.values) for (t in TRIPS) assert(staysIn(t, city).isNotEmpty())
    }
}

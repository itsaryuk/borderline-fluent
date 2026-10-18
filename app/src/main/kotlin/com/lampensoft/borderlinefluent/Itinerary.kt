package com.lampensoft.borderlinefluent

import kotlin.random.Random

/** HOMECOMING: the desk is the traveller's own country (returning from [Itinerary.from]); ARRIVAL: a visit there. */
enum class TripKind { ARRIVAL, HOMECOMING }

/**
 * One trip, rolled once per mission ([roll]); no reroll. [hotel]: where they stay (or stayed); [host]: who invited them
 * ("nobody, …" for none); [city]: the trip's city in Latin, in the destination for ARRIVAL and in [from] for HOMECOMING.
 * [from]: the departure country (random for HOMECOMING, continuity for Free AI ARRIVAL). Retired fields remain empty for save compatibility.
 */
data class Itinerary(
    val kind: TripKind, val purpose: String, val days: Int, val hotel: String, val host: String, val met: String,
    val from: String?, val city: String, val travelledWith: String, val waiting: String, val stamps: List<String>,
) {
    companion object {
        fun kindFor(destination: Country, nationality: Nationality) =
            if (destination.id == nationality.id) TripKind.HOMECOMING else TripKind.ARRIVAL

        fun roll(r: Random, destination: Destination, identity: Identity, departure: String? = null): Itinerary {
            val country = destination.country
            val kind = kindFor(country, identity.nationality)
            // Real countries only: their names have well-known translations the answer check can match.
            // A Free AI flight home comes from the last country passed, not a random one.
            val from = if (kind == TripKind.HOMECOMING) departure?.takeIf { it != country.nation }
                ?: Country.shipped.map { it.nation }.distinct().filter { it != country.nation }.random(r) else departure
            val place = if (kind == TripKind.HOMECOMING) checkNotNull(from) else country.nation
            val trip = TRIPS.random(r)
            val days = r.nextInt(3, 15)
            val city = if (kind == TripKind.HOMECOMING) TRIP_CITIES.getValue(place).random(r).substringBefore('|') else AIRPORT_CITY.getValue(destination.airport.id)
            return Itinerary(kind, trip.purpose, days, staysIn(trip, city).random(r), trip.host, "", from, city, "", "", emptyList())
        }
    }
}

/** Coast, warm beaches and mountains among the trip cities: "a beach hotel" in Tomsk or "mountain huts" in Dublin read as lies. */
private val SEASIDE = setOf("Marseille", "Nice", "Saint-Denis", "Olbia", "Naples", "Rimini", "Lisbon", "Ponta Delgada", "Funchal",
    "Praia da Vitória", "Gdańsk", "Murmansk", "Arkhangelsk", "Odesa", "Istanbul", "Rize", "Dalaman", "Izmir", "Dubai", "Abu Dhabi",
    "Tokyo", "Hiroshima", "Niigata", "Kagoshima", "Naha", "Komatsu", "Xiamen", "Dakar", "Cancún", "Cartagena", "Casablanca",
    "Sydney", "Los Angeles", "Praia", "Luanda", "Stornoway", "Dublin", "New York", "Barcelona", "Montreal")
private val BEACH = setOf("Nice", "Saint-Denis", "Olbia", "Naples", "Rimini", "Funchal", "Ponta Delgada", "Praia da Vitória", "Odesa",
    "Dalaman", "Izmir", "Dubai", "Abu Dhabi", "Naha", "Xiamen", "Dakar", "Cancún", "Cartagena", "Sydney", "Los Angeles", "Praia",
    "Luanda", "Barcelona", "Lisbon")
private val MOUNTAINS = setOf("Innsbruck", "Salzburg", "Zurich", "Mendoza", "Medellín", "Bogotá", "Guilin",
    "Wuyishan", "Kayseri", "Nevşehir", "Rize", "Guiyang", "Lanzhou", "Kraków", "Ivano-Frankivsk", "Chitose", "Komatsu", "Perugia")

/** The stay is a hotel (booked through the Solvane Collection); apartments, guesthouses, huts and hosts' homes are not. */
fun isHotelStay(stayed: String) = stayed.contains("hotel", ignoreCase = true) || stayed.contains("Marriott")

/** The cover-story stay of a booked hotel trip: the reserved Solvane hotel (the booking card shows the same "Solvane <city>"). */
fun solvaneStay(city: String) = "the Solvane $city hotel"

/** A paid free-play booking replaces a hotel stay with the reserved hotel; other stays stay as rolled. */
fun Backstory.withReservation() = if (isHotelStay(stayed)) copy(stayed = solvaneStay(city)) else this

/** "SV-" and five digits, stable per attempt id (the id the booked trip will start with). */
fun reservationCode(attemptId: Long) = "SV-%05d".format(kotlin.random.Random(attemptId * 7919 + 13).nextInt(100000))

/** [trip]'s stays that fit [city]; the trip's first stay when none does. */
internal fun staysIn(trip: Trip, city: String) = trip.stayed.filter { stay ->
    ("beach" !in stay || city in BEACH) && ("sea" !in stay || city in SEASIDE) && ("mountain" !in stay || city in MOUNTAINS)
}.ifEmpty { trip.stayed.take(1) }

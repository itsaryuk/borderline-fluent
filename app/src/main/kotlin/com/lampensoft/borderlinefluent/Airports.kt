package com.lampensoft.borderlinefluent

import kotlin.random.Random

/** Only this airport's pack art and officers can make it eligible. */
fun eligibleAirports(country: Country, enabledLanguages: Set<String>, packArt: Set<String>, requiredLang: String? = null): List<Airport> =
    country.airports.filter { a -> a.lang in enabledLanguages && (requiredLang == null || a.lang == requiredLang) &&
        a.art in packArt && a.officers.any { "officer_${it}_sheet" in packArt } }

/** Keep canonical countries intact: airport counts must not weight nationality selection. */
fun playableCountries(enabledLanguages: Set<String>, packArt: Set<String>, requiredLang: String? = null): List<Country> =
    Country.all.filter { eligibleAirports(it, enabledLanguages, packArt, requiredLang).isNotEmpty() }

/** Session-only shuffle bags. Call once per new preparation, never when rendering or recovering one. */
class AirportRotation(private val random: Random) {
    private data class Bag(var ids: Set<String> = emptySet(), val remaining: MutableList<String> = mutableListOf(), var last: String? = null)
    private val bags = mutableMapOf<Pair<String, String?>, Bag>()

    fun next(country: Country, eligible: List<Airport>, requiredLang: String? = null): Airport? {
        require(eligible.all { it in country.airports && (requiredLang == null || it.lang == requiredLang) })
        val byId = eligible.associateBy { it.id }
        val bag = bags.getOrPut(country.id to requiredLang) { Bag() }
        if (bag.ids != byId.keys) {
            bag.ids = byId.keys.toSet()
            bag.remaining.clear()
        }
        if (byId.isEmpty()) return null
        if (bag.remaining.isEmpty()) {
            bag.remaining.addAll(byId.keys.shuffled(random))
            if (bag.remaining.size > 1 && bag.remaining.first() == bag.last) {
                val other = random.nextInt(1, bag.remaining.size)
                val first = bag.remaining[0]
                bag.remaining[0] = bag.remaining[other]
                bag.remaining[other] = first
            }
        }
        return byId.getValue(bag.remaining.removeAt(0).also { bag.last = it })
    }
}

/** Uniform country draw followed by airport rotation under the same eligibility constraint. */
fun selectDestination(countries: List<Country>, enabledLanguages: Set<String>, packArt: Set<String>, requiredLang: String?,
    random: Random, rotation: AirportRotation): Destination? {
    val eligible = countries.distinctBy { it.id }.map { it to eligibleAirports(it, enabledLanguages, packArt, requiredLang) }
        .filter { it.second.isNotEmpty() }
    if (eligible.isEmpty()) return null
    val (country, airports) = eligible.random(random)
    return Destination(country, checkNotNull(rotation.next(country, airports, requiredLang)))
}

/** Exact frozen preparation check: another sheet at the same airport is not a replacement. */
fun destinationAvailable(destination: Destination, officerId: String, enabledLanguages: Set<String>, packArt: Set<String>): Boolean =
    destination.lang in enabledLanguages && destination.airport.art in packArt &&
        officerId in destination.airport.officers && "officer_${officerId}_sheet" in packArt

fun airportCount(lang: String): Int = Country.all.sumOf { c -> c.airports.count { it.lang == lang } }

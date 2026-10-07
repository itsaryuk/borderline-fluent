package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CitizensTest {
    private val latin = Regex("""[\p{IsLatin}\s'\-]+""")

    @Test fun namesForEveryLanguageBothGendersWithLatinForms() {
        assertEquals(Language.all.map { it.code }.toSet(), NAMES.keys)
        for ((l, n) in NAMES) {
            assertEquals(l, 10, n.male.size); assertEquals(l, 10, n.female.size); assertEquals(l, 12, n.surnames.size)
            for (f in listOf(false, true)) repeat(20) {
                val name = n.random(Random(it), f)
                assertTrue("$l ${name.latin}", latin.matches(name.latin))
                if (Language.of(l).romanize == null) assertEquals(name.native, name.latin)
            }
        }
        assertEquals(Name("Орлова", "Orlova"), NAMES.getValue("ru").surnames[0].second)
        assertEquals(Name("佐藤健", "Sato Ken"), Names(listOf(Name("健", "Ken")), listOf(), listOf(Name("佐藤", "Sato") to Name("佐藤", "Sato")), true).random(Random(1), false))
    }

    @Test fun citiesForEveryCountry() {
        assertEquals(Country.all.map { it.id }.toSet(), CITIES.keys)
        assertTrue(CITIES.values.all { it.size >= 4 && it.all(String::isNotBlank) })
    }

    @Test fun randomCoversAreConsistentCitizensComingHome() {
        val genders = mutableSetOf<Boolean>()
        for (c in Country.all) for (a in c.airports) repeat(30) { i ->
            val s = Backstory.random(Random(i * 1000 + c.id.hashCode()), Destination(c, a))
            assertEquals(a.lang, s.lang)
            assertEquals(11, s.facts().size)
            assertTrue(s.facts()[0].endsWith("; date of birth: ${s.born}"))
            assertTrue(s.passportNo.startsWith(c.iso))
            genders += s.female
            assertEquals(c, s.country)
            assertEquals("photo matches the identity", s.female, s.photo.female)
            assertTrue(s.home in CITIES.getValue(c.id))
            assertNotEquals(c.nation, s.from)
            assertTrue(Country.all.any { it.nation == s.from })
            assertTrue(TRIP_CITIES.getValue(s.from).any { it.startsWith(s.city + "|") })
            assertEquals("${s.from} 2035", s.stamps.first())
            assertTrue(s.stamps.size in 1..3)
            s.stamps.forEach { st -> assertTrue(st, Country.all.any { it.nation == st.substringBeforeLast(" ") }) }
            s.stamps.drop(1).forEach { st -> assertTrue(st, st.takeLast(4).toInt() in 2030..2034); assertTrue(!st.startsWith(c.nation)) }
            val trip = TRIPS.first { it.purpose == s.purpose }
            assertTrue(s.stayed in trip.stayed && s.met in trip.met && trip.with.any { it.first == s.travelledWith })
            val withKey = trip.with.first { it.first == s.travelledWith }.second
            assertTrue("${s.travelledWith} / ${s.waiting}", s.waiting in waitingFor(withKey).map { it.first })
            assertTrue(s.born.takeLast(4).toInt() in 1980..2002)
            assertEquals(11, s.facts().size)
            assertTrue(s.facts()[10], s.facts()[10].startsWith("Passport stamps: ${s.from} 2035")) // the card lists them all too
            // The name's gender follows the identity: the given name comes from that gender's list.
            val n = NAMES.getValue(a.lang)
            assertTrue(s.name, (if (s.female) n.female else n.male).any { it.native in s.name })
        }
        assertEquals(setOf(false, true), genders)
    }

    @Test fun tripCitiesForEveryRealCountryInFiveScripts() {
        assertEquals(Country.all.map { it.nation }.toSet(), TRIP_CITIES.keys)
        assertTrue(TRIP_CITIES.values.flatten().all { it.split('|').size == 6 })
        assertEquals("トロント", cityIn("Toronto", "ja")); assertEquals(null, cityIn("Toronto", "de"))
    }

    /**
     * Ui.kt's cover card reads "nobody…" as alone and turns "their " into "your " for travelledWith and waiting, but
     * prints met as is (free plurals like "clients at the fair"), so met must never start with "their ".
     */
    @Test fun companionValuesFitTheCoverCard() {
        val starts = listOf("their ", "nobody", "a ", "an ", "two ", "the ", "your ")
        (TRIPS.flatMap { t -> t.with.map { it.first } } + WAITING.map { it.first }).forEach { v -> assertTrue(v, starts.any(v::startsWith)) }
        TRIPS.flatMap { it.met }.forEach { v -> assertFalse(v, v.startsWith("their ")) }
    }

    @Test fun cardFilledForBothGenders() {
        val seen = mutableSetOf<Boolean>()
        repeat(20) { i ->
            val s = Backstory.random(Random(i), Country.of("russia").primary())
            assertEquals("russia", s.country.id)
            seen += s.female
            val card = s.card()
            for (bad in listOf("null", "{", "}")) assertFalse("$bad in $card", bad in card)
            assertTrue(card, (if (s.female) "woman" else "man") in card && s.name in card && s.latin in card && s.job in card)
        }
        assertEquals(setOf(false, true), seen)
    }

    @Test fun nobodyWaitsWhoTravelled() {
        assertTrue(waitingFor("partner").none { it.second == "partner" || it.second == "alone" })
        assertTrue(waitingFor("alone").any { it.second == "partner" })
    }

    @Test fun ukraineCityFormsAndBenchAreStable() {
        assertEquals(listOf("Kyiv|Киев|Київ|كييف|キーウ|基辅"), TRIP_CITIES.getValue("Ukraine"))
        assertEquals("de", Backstory.BENCH.country.id)
        assertEquals("de", Backstory.BENCH.lang)
        assertEquals("Lindenberg", Backstory.BENCH.home)
        assertEquals("DE4471902", Backstory.BENCH.passportNo)
        assertEquals(11, Backstory.BENCH.facts().size)
        assertEquals("Passport stamps: Canada 2035, Japan 2033", Backstory.BENCH.facts()[10])
        assertEquals("Name: Michael Brandt; date of birth: 3 Jun 1984", Backstory.BENCH.facts()[0])
        for (a in Country.of("canada").airports) {
            val s = Backstory.random(Random(2), Destination(Country.of("canada"), a))
            assertEquals(a.lang, s.lang)
            assertEquals(11, s.facts().size)
            assertTrue(s.facts()[0].endsWith("; date of birth: ${s.born}"))
            assertEquals(s.facts(a.lang), s.facts())
        }
    }
}

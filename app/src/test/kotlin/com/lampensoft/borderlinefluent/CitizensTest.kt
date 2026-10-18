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

    @Test fun citiesForEveryNationality() {
        assertEquals(Nationality.all.map { it.id }.toSet(), CITIES.keys)
        assertTrue(CITIES.values.all { it.size >= 4 && it.all(String::isNotBlank) })
    }

    @Test fun randomCoversAreConsistentCitizensComingHome() {
        val genders = mutableSetOf<Gender>()
        for (c in Country.all) for (a in c.airports) repeat(30) { i ->
            val s = Backstory.random(Random(i * 1000 + c.id.hashCode()), Destination(c, a))
            assertEquals(a.lang, s.lang)
            assertEquals(6, s.facts().size)
            assertTrue(s.facts()[0].endsWith("; date of birth: ${s.born}"))
            assertTrue(s.passportNo.startsWith(c.iso))
            genders += s.gender
            assertEquals(c, s.country)
            assertEquals("photo matches the identity", s.gender == Gender.WOMAN, s.photo.female)
            assertTrue(s.home in CITIES.getValue(c.id))
            assertNotEquals(c.nation, s.from)
            assertTrue(Country.all.any { it.nation == s.from })
            assertTrue(TRIP_CITIES.getValue(s.from).any { it.startsWith(s.city + "|") })
            val trip = TRIPS.first { it.purpose == s.purpose }
            assertTrue(s.stayed in trip.stayed)
            assertTrue(s.born.takeLast(4).toInt() in 1980..2002)
            assertEquals(6, s.facts().size)
            // The name's gender follows the identity: the given name comes from that gender's list.
            val n = NAMES.getValue(a.lang)
            assertTrue(s.name, (if (s.gender == Gender.WOMAN) n.female else n.male).any { it.native in s.name })
        }
        assertEquals(setOf(Gender.WOMAN, Gender.MAN), genders)
    }

    @Test fun tripCitiesForEveryRealCountryInFiveScripts() {
        assertEquals(Country.all.map { it.nation }.toSet(), TRIP_CITIES.keys)
        assertTrue(TRIP_CITIES.values.flatten().all { it.split('|').size == 6 })
        assertEquals("トロント", cityIn("Toronto", "ja")); assertEquals(null, cityIn("Toronto", "de"))
    }

    @Test fun cardFilledForBothGenders() {
        val seen = mutableSetOf<Gender>()
        repeat(20) { i ->
            val s = Backstory.random(Random(i), Country.of("russia").primary())
            assertEquals("russia", s.country.id)
            seen += s.gender
            val card = s.card()
            for (bad in listOf("null", "{", "}")) assertFalse("$bad in $card", bad in card)
            assertTrue(card, genderWord(s.gender) in card && s.name in card && s.latin in card)
        }
        assertEquals(setOf(Gender.WOMAN, Gender.MAN), seen)
        for (g in Gender.entries) assertTrue(Backstory.BENCH.copy(gender = g).card().contains("Brandt, ${genderWord(g)} (citizen"))
    }

    @Test fun ukraineCityFormsAndBenchAreStable() {
        assertEquals(listOf("Kyiv|Киев|Київ|كييف|キーウ|基辅"), TRIP_CITIES.getValue("Ukraine"))
        assertEquals("de", Backstory.BENCH.country.id)
        assertEquals("de", Backstory.BENCH.lang)
        assertEquals("Lindenberg", Backstory.BENCH.home)
        assertEquals("DE4471902", Backstory.BENCH.passportNo)
        assertEquals(6, Backstory.BENCH.facts().size)
        assertEquals("Name: Michael Brandt; date of birth: 3 Jun 1984", Backstory.BENCH.facts()[0])
        for (a in Country.of("canada").airports) {
            val s = Backstory.random(Random(2), Destination(Country.of("canada"), a))
            assertEquals(a.lang, s.lang)
            assertEquals(6, s.facts().size)
            assertTrue(s.facts()[0].endsWith("; date of birth: ${s.born}"))
            assertEquals(s.facts(a.lang), s.facts())
        }
    }
}

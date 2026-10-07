package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test

class BenchDestinationsTest {
    @Test fun primaryDestinationsMatchEveryRequestedLanguage() {
        for (lang in JudgeCases.all.keys) {
            val d = Destination.forLanguage(lang)
            assertEquals(lang, d.lang)
            assertEquals(d.country.airports.first(), d.airport)
        }
        assertEquals("kbp", Destination.forLanguage("uk").airport.id)
        assertEquals("ukraine", Destination.forLanguage("uk").country.id)
        assertEquals("lhr", Destination.forLanguage("en").airport.id)
        assertEquals("uk", Destination.forLanguage("en").country.id)
    }

    @Test fun birthAndStampExtrasUseBenchFactsAndCatalogQuestionsInEveryLanguage() {
        val labels = listOf("born right", "born wrong", "stamp year right", "stamp year wrong")
        val story = Backstory.BENCH
        assertEquals("3 Jun 1984", story.born)
        assertEquals("Japan 2033", story.stamps[1])
        assertEquals(Language.all.map { it.code }.toSet(), JudgeCases.all.keys)
        for ((lang, cases) in JudgeCases.all) {
            val extras = cases.extra.filter { it.label in labels }
            assertEquals(lang, labels, extras.map { it.label })
            assertEquals(listOf(false, true, false, true), extras.map { it.conflict })
            val country = coverNation("Japan", lang)
            for ((i, extra) in extras.withIndex()) {
                assertEquals(story, extra.story)
                assertEquals(renderLine(if (i < 2) "ask.born.1" else "ask.stamps.3",
                    lang, story.female, story.days, "", country), extra.q)
                assertTrue(extra.a.contains(listOf("1984", "1975", "2033", "2032")[i]))
                assertFalse(extra.q.contains('{'))
            }
        }
    }

    @Test fun ukrainianCitizenCasesAreSeparateFromTheGermanBaseline() {
        assertEquals(195, JudgeCases.all.values.sumOf { it.cases.size + 1 })
        assertEquals(39, JudgeCases.paraphrase.values.sumOf { it.size })
        assertEquals(26, JudgeCases.tricks.values.sumOf { it.size })
        assertEquals(12, JudgeCases.all.getValue("uk").extra.size)
        assertEquals("de", Backstory.BENCH.country.id)
        assertEquals("Lindenberg", Backstory.BENCH.home)
        val cases = JudgeCases.ukrainianCitizen
        assertEquals(8, cases.size)
        assertEquals(3, cases.count { it.conflict })
        assertEquals(setOf("Kyiv", "Lviv"), cases.map { it.story.home }.toSet())
        for (e in cases) {
            assertEquals("ukraine", e.story.country.id)
            assertEquals("uk", e.story.lang)
            assertEquals("Canada", e.story.from)
            assertTrue(e.story.female)
            assertEquals("Олена Коваль", e.story.name)
            assertEquals(11, e.story.facts().size)
            assertFalse(e in JudgeCases.all.getValue("uk").extra)
        }
    }
}

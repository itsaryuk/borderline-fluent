package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.text.DateFormat
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.random.Random

class CoverTextTest {
    private val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
    private val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    private fun strings(ui: String): Map<Int, String> {
        val file = File(res, "${if (ui == "en") "values" else "values-$ui"}/strings.xml")
        val elements = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        val byName = (0 until elements.length).map { elements.item(it) as Element }.associate {
            it.getAttribute("name") to it.textContent.replace("\\'", "'").replace("\\n", "\n")
        }
        return names.mapNotNull { (id, name) -> byName[name]?.let { id to it } }.toMap()
    }
    private val sample = Backstory.BENCH.copy(
        name = "Anna Nowak", latin = "Anna Nowak", female = true, country = Country.of("poland"),
        born = "8 Jul 1996", home = "Gdańsk", job = "nurse", from = "China", city = "Shanghai",
        purpose = "a job interview", days = 12, stayed = "an Airbnb near the station",
        travelledWith = "nobody, travelled alone", met = "the team they might join", waiting = "their parents",
        stamps = listOf("China 2035"),
    )

    @Test fun everyFactValueHasANamedTranslationInEveryUiLanguage() {
        val generated = JOBS + TRIPS.flatMap { listOf(it.purpose) + it.stayed + it.with.map { p -> p.first } + it.met } +
            WAITING.map { it.first }
        val bench = Backstory.BENCH.let { listOf(it.job, it.purpose, it.stayed, it.travelledWith, it.met, it.waiting) }
        assertEquals((generated + bench).toSet(), COVER_VALUE_IDS.keys)
        assertEquals("Each value has its own stable id", COVER_VALUE_IDS.size, COVER_VALUE_IDS.values.toSet().size)
        for (ui in Language.all.map { it.code }) {
            val text = strings(ui)
            for ((english, id) in COVER_VALUE_IDS) {
                assertTrue("$ui: $english (${names[id]})", !text[id].isNullOrBlank())
            }
            // Both genders, solo/accompanied, all combinations and 1–3 stamps remain renderable.
            for (country in Country.all) for (airport in country.airports) repeat(10) { seed ->
                val s = Backstory.random(Random(seed), Destination(country, airport))
                val lines = coverLines(s, ui, text::getValue)
                assertEquals(4, lines.size)
                lines.forEach { assertTrue(text.getValue(it.template).isNotBlank()); assertTrue(it.values.all(String::isNotBlank)) }
            }
            coverLines(Backstory.BENCH, ui, text::getValue)
        }
    }

    @Test fun jobsUseTheCoverGenderInEveryUiLanguage() {
        val gendered = mapOf(
            ("ru" to "nurse") to ("медбрат" to "медсестра"),
            ("uk" to "nurse") to ("медбрат" to "медсестра"),
            ("pl" to "nurse") to ("pielęgniarz" to "pielęgniarka"),
            ("de" to "chef") to ("Küchenchef" to "Küchenchefin"),
            ("pl" to "chef") to ("szef kuchni" to "szefowa kuchni"),
            ("uk" to "chef") to ("шеф-кухар" to "шеф-кухарка"),
        )
        for (ui in Language.all.map { it.code }) {
            val text = strings(ui)
            for (job in JOBS) for (female in listOf(false, true)) {
                val story = sample.copy(job = job, female = female)
                val rendered = coverLines(story, ui, text::getValue)[0].values[2]
                val forms = gendered[ui to job]
                val expected = if (forms == null) text.getValue(COVER_VALUE_IDS.getValue(job))
                    else if (female) forms.second else forms.first
                assertEquals("$ui / $job / female=$female", coverIsolate(expected, ui), rendered)
                assertFalse(rendered, rendered.any { it in "{}|/" })
            }
        }
    }

    @Test fun renderingInEveryUiLanguageLeavesEnglishFactsAndPromptsUnchanged() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            val facts = sample.facts()
            assertEquals("Occupation (job): nurse", facts[8])
            assertEquals("Purpose of the trip (why they went): a job interview", facts[2])
            assertEquals("Passport stamps: China 2035", facts[10])
            val card = sample.card()
            fun prompts() = listOf(classifyPrompt(sample.country.primary(), sample), suggestPrompt(sample.country.primary(), sample),
                comparePrompt())
            val before = prompts()
            val snapshot = sample.copy(stamps = sample.stamps.toList())
            for (ui in Language.all.map { it.code }) {
                Locale.setDefault(coverLocale(ui))
                val text = strings(ui)
                coverLines(sample, ui, text::getValue)
                coverBirthDate(sample.born, ui)
                assertEquals(ui, snapshot, sample)
                assertEquals(ui, facts, sample.facts())
                assertEquals(ui, card, sample.card())
                assertEquals(ui, before, prompts())
                assertEquals("8 Jul 1996", sample.born)
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun countryNamesLocalizeButPlacesAndAllStampYearsStayIntact() {
        for (ui in Language.all.map { it.code }) {
            val text = strings(ui)
            val lines = coverLines(sample, ui, text::getValue)
            assertEquals(coverIsolate("Gdańsk", ui), lines[0].values[0])
            assertEquals(coverIsolate("Shanghai", ui), lines[1].values[1])
            assertEquals(coverIsolate(Country.of("china").uiName(ui), ui), lines[1].values[2])
            // Archived unknown nation names must remain renderable verbatim.
            val stamps = coverStamps(listOf("China 2035", "Canada 2033", "Borovia 2030"), ui)
            assertTrue(stamps, stamps.contains(Country.of("china").uiName(ui) + " 2035"))
            assertTrue(stamps, stamps.contains(Country.of("canada").uiName(ui) + " 2033"))
            assertTrue(stamps, stamps.endsWith(coverIsolate("Borovia 2030", ui)))
            assertFalse(stamps, stamps.contains("Québec"))
        }
    }

    @Test fun mediumDatesUseUiLocaleAndNeverShiftDayWithDeviceTimeZone() {
        val previous = TimeZone.getDefault()
        try {
            for (zone in listOf("Pacific/Kiritimati", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (ui in Language.all.map { it.code }) {
                    val utc = TimeZone.getTimeZone("UTC")
                    val date = GregorianCalendar(utc, Locale.ENGLISH).apply {
                        clear(); set(1996, 6, 8)
                    }.time
                    val expected = DateFormat.getDateInstance(DateFormat.MEDIUM, coverLocale(ui)).apply { timeZone = utc }.format(date)
                    assertEquals("$ui / $zone", expected, coverBirthDate("8 Jul 1996", ui))
                }
            }
            assertTrue(coverBirthDate("8 Jul 1996", "ru"), "июл" in coverBirthDate("8 Jul 1996", "ru"))
            assertEquals(Locale.forLanguageTag("pt-PT"), coverLocale("pt"))
            assertEquals(Locale.ENGLISH, coverLocale("unsupported"))
        } finally { TimeZone.setDefault(previous) }
    }
}

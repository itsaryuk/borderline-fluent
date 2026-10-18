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
        name = "Anna Nowak", latin = "Anna Nowak", gender = Gender.WOMAN, country = Country.of("poland"),
        born = "8 Jul 1996", home = "Gdańsk", job = "nurse", from = "China", city = "Shanghai",
        purpose = "a job interview", days = 12, stayed = "an Airbnb near the station",
        travelledWith = "nobody, travelled alone", met = "the team they might join", waiting = "their parents",
        stamps = listOf("China 2035"),
    )

    @Test fun everyFactValueHasANamedTranslationInEveryUiLanguage() {
        val generated = TRIPS.flatMap { listOf(it.purpose, it.host) + it.stayed }
        val bench = Backstory.BENCH.let { listOf(it.purpose, it.stayed) }
        val story = listOf("a holiday", "a guesthouse in Lefkopoli")
        assertEquals((generated + bench + story).toSet(), COVER_VALUE_IDS.keys)
        assertEquals("Each value has its own stable id", COVER_VALUE_IDS.size, COVER_VALUE_IDS.values.toSet().size)
        for (ui in Language.all.map { it.code }) {
            val text = strings(ui)
            for ((english, id) in COVER_VALUE_IDS) {
                assertTrue("$ui: $english (${names[id]})", !text[id].isNullOrBlank())
            }
            // Every generated homecoming cover remains renderable.
            for (country in Country.all) for (airport in country.airports) repeat(10) { seed ->
                val s = Backstory.random(Random(seed), Destination(country, airport))
                val lines = coverLines(s, ui, text::getValue)
                assertEquals(3, lines.size)
                lines.forEach { assertTrue(text.getValue(it.template).isNotBlank()); assertTrue(it.values.all(String::isNotBlank)) }
            }
            coverLines(Backstory.BENCH, ui, text::getValue)
        }
    }

    @Test fun theReservedHotelIsTranslatedWithTheCityInEveryUiLanguage() {
        val s = sample.copy(city = "Narita", stayed = "a business hotel by the airport").withReservation()
        for (ui in Language.all.map { it.code }) {
            val text = strings(ui)
            val stay = coverLines(s, ui, text::getValue)[2].values[0]
            assertTrue("$ui: $stay", "Solvane Narita" in stay && '{' !in stay)
        }
        assertEquals("the Solvane Narita hotel", coverLines(s, "en", strings("en")::getValue)[2].values[0])
    }

    @Test fun renderingInEveryUiLanguageLeavesEnglishFactsAndPromptsUnchanged() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            val facts = sample.facts()
            assertEquals("Purpose of the trip (why they went): a job interview", facts[2])
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

    @Test fun countryNamesLocalizeButPlacesStayIntact() {
        for (ui in Language.all.map { it.code }) {
            val text = strings(ui)
            val lines = coverLines(sample, ui, text::getValue)
            assertEquals(coverIsolate("Gdańsk", ui), lines[0].values[0])
            assertEquals(coverIsolate("Shanghai", ui), lines[1].values[1])
            assertEquals(coverIsolate(Country.of("china").uiName(ui), ui), lines[1].values[2])
        }
    }

    /** A visit shows the destination and its host, not a return trip; the USA localizes like any nationality. */
    @Test fun arrivalCoverShowsTheVisit() {
        val visit = Backstory.BENCH_ARRIVAL
        for (ui in Language.all.map { it.code }) {
            val text = strings(ui)
            val lines = coverLines(visit, ui, text::getValue)
            assertEquals(R.string.cover_story_visit, lines[1].template)
            assertEquals(R.string.cover_story_visit_stay, lines[2].template)
            assertEquals(coverIsolate("Berlin", ui), lines[1].values[1])
            assertEquals(coverIsolate(Country.of("de").uiName(ui), ui), lines[1].values[2])
            assertEquals(coverIsolate(text.getValue(R.string.cover_value_host_wedding_friend), ui), lines[4].values[0])
            val filled = fillBold(text.getValue(lines[1].template), *lines[1].values.toTypedArray()).text
            assertFalse(filled, '%' in filled)
            assertEquals(Nationality.USA.uiName(ui), coverNation("United States", ui))
        }
        assertEquals("Vereinigte Staaten", coverNation("United States", "de"))
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
    @Test fun archivedRetiredValuesNeverRenderAndHostIsConditional() {
        for (ui in Language.all.map { it.code }) {
            val text = strings(ui)
            for (kind in TripKind.entries) for (host in listOf("", "nobody, a hotel stay", "the friend getting married")) {
                val story = Backstory.BENCH_ARRIVAL.copy(kind = kind, host = host,
                    job = "retired job", met = "retired meeting", waiting = "retired waiting",
                    travelledWith = "retired companion", stamps = listOf("retired stamp"))
                val lines = coverLines(story, ui, text::getValue)
                assertEquals(story.hasHost, lines.any { it.template == R.string.cover_host_line })
                assertEquals(kind == TripKind.ARRIVAL, lines.any { it.template == R.string.cover_from_line })
                assertTrue(lines.flatMap { it.values }.none { "retired" in it })
                for (line in lines) assertFalse(fillBold(text.getValue(line.template), *line.values.toTypedArray()).text.contains('%'))
            }
        }
    }

}

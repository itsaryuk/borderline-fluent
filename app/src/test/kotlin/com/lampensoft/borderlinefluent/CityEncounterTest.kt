package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class CityEncounterTest {
    /** Act 2 after the first upgrade box was handed over, Germany visited before. */
    private val s = CareerSnapshot(act = Act.FREE, upgradesGranted = 1, cutscenesSeen = setOf(1), countries = setOf("de"))
    private val ids = (1L..300L)
    private fun due(id: Long, act: Act? = Act.FREE, story: Boolean = false, training: Boolean = false,
                    country: String = "de", cleared: Boolean = true, snap: CareerSnapshot? = s) =
        cityEncounterDue(id, act, story, training, country, cleared, snap)

    @Test fun introCaptionAlternatesPerKind() {
        for (k in CityKind.values()) {
            assertEquals(cityIntroRes(k, 0), cityIntroRes(k, 2))
            assertEquals(cityIntroRes(k, 1), cityIntroRes(k, 3))
            assertTrue(cityIntroRes(k, 0) != cityIntroRes(k, 1))
        }
        assertEquals(6, CityKind.values().flatMap { k -> (0..1).map { cityIntroRes(k, it) } }.toSet().size)
    }

    @Test fun tvRumoursAreTheFourHeadlinesPlusTheWorldNews() {
        val tv = setOf(R.string.rumour_lenses, R.string.rumour_briefcase, R.string.rumour_thalassia, R.string.rumour_recalibration)
        assertEquals(tv, CITY_RUMOURS.filter { it.tv && it.stage == null }.map { it.res }.toSet())
        assertTrue(CITY_RUMOURS.filter { it.stage != null }.all { it.tv })
        assertEquals(56, CITY_RUMOURS.count { it.stage != null })
        assertEquals(CITY_RUMOURS.size, CITY_RUMOURS.map { it.res }.toSet().size)
    }

    @Test fun aboutOneTripInThreeAndDeterministic() {
        val n = ids.count { due(it) }
        assertTrue("$n of 300", n in 80..120)
        ids.forEach { assertEquals(due(it), due(it)) }
    }

    @Test fun everyExclusion() {
        val hits = ids.filter { due(it) }
        assertTrue(hits.isNotEmpty())
        for (id in hits) {
            assertFalse("failed trip", due(id, cleared = false))
            assertFalse("story", due(id, story = true))
            assertFalse("training", due(id, training = true))
            assertFalse("usa", due(id, country = "usa"))
            assertFalse("act 1", due(id, act = Act.STORY))
            assertFalse("left", due(id, act = Act.LEFT))
            assertFalse("no career", due(id, snap = null))
            assertFalse("after the true ending", due(id, snap = s.copy(thalassiaSeen = true)))
            assertTrue("from the first free-play trip, before any upgrade", due(id, snap = CareerSnapshot(act = Act.FREE, countries = setOf("de"))))
        }
    }

    @Test fun aFirstVisitAlwaysGetsOneWithEveryExclusion() {
        assertTrue(ids.all { due(it, country = "fr") })
        assertTrue(ids.all { firstVisit("fr", s) } && !firstVisit("de", s) && !firstVisit("fr", null))
        assertFalse(ids.any { due(it, country = "fr", cleared = false) })
        assertFalse(ids.any { due(it, country = "fr", story = true) })
        assertFalse(ids.any { due(it, country = "fr", training = true) })
        assertFalse(ids.any { due(it, country = "fr", act = Act.STORY) })
        assertFalse(ids.any { due(it, country = "fr", act = Act.LEFT) })
        assertFalse(ids.any { due(it, country = "usa", snap = CareerSnapshot(act = Act.FREE)) })
        assertFalse(ids.any { due(it, country = "fr", snap = s.copy(thalassiaSeen = true)) })
        // The count the rotation used to give the first trip is gone: a revisit is one in three again.
        assertTrue(ids.count { due(it, snap = CareerSnapshot(act = Act.FREE, countries = setOf("de"))) } < 150)
    }

    @Test fun firstVisitReadsTheCareerBeforeTheAttempt() {
        val a = attempt(country = "fr")
        val before = pending(a).copy(act = Act.FREE)
        val after = reduceReplayResult(before, victory(a)).snapshot
        assertTrue(firstVisit("fr", before))
        assertFalse("the terminal save adds the country", firstVisit("fr", after))
    }

    @Test fun aFirstVisitIsATaxiAndKeepsTheRotation() {
        for (shown in 0 until 8) {
            assertEquals(CityKind.TAXI to shown, cityPick(true, shown))
            assertEquals(cityKind(shown) to shown + 1, cityPick(false, shown))
        }
        // Two first visits between ordinary encounters: the ordinary ones still go BAR, HOTEL, BAR.
        var shown = 0
        val kinds = listOf(false, true, false, true, false, false).map { f -> cityPick(f, shown).also { shown = it.second }.first }
        assertEquals(listOf(CityKind.BAR, CityKind.TAXI, CityKind.HOTEL, CityKind.TAXI, CityKind.BAR, CityKind.BAR), kinds)
    }

    @Test fun taxiRadioByLanguageAndEnglishByCountry() {
        assertEquals("taxi_en_gb", taxiSong("en", "uk"))
        assertEquals("taxi_en_ie", taxiSong("en", "ireland"))
        assertEquals("taxi_en_us", taxiSong("en", "canada"))
        assertEquals("taxi_en_au", taxiSong("en", "australia"))
        assertEquals("taxi_en_gb", taxiSong("en", "usa"))
        assertEquals("taxi_fr", taxiSong("fr", "canada"))
        for (l in listOf("de", "fr", "es", "it", "pt", "pl", "ru", "uk", "ja")) assertEquals("taxi_$l", taxiSong(l, "x"))
        // Every song the radio can name is in the catalog, as a song of that language's pack.
        val english = Country.all.filter { c -> c.airports.any { it.lang == "en" } }.map { taxiSong("en", it.id) }
        for (key in english + listOf("de", "fr", "es", "it", "pt", "pl", "ru", "uk", "ja").map { taxiSong(it, "x") })
            assertEquals(key, key.removePrefix("taxi_").take(2), SONGS.single { it.key == key }.lang)
    }

    @Test fun regionByLanguageAndEnglishByCountry() {
        assertEquals("gb", regionOf("en", "uk")); assertEquals("ie", regionOf("en", "ireland"))
        assertEquals("us", regionOf("en", "canada")); assertEquals("au", regionOf("en", "australia"))
        assertEquals("gb", regionOf("en", "usa")); assertEquals("gb", regionOf("en", "singapore"))
        assertEquals("fr", regionOf("fr", "canada")); assertEquals("uk", regionOf("uk", "ukraine"))
    }

    @Test fun everyRegionOrdersEachItemByItsOwnName() {
        for ((region, items) in BAR_MENU) {
            assertEquals(region, 3, items.size)
            for (item in items) assertEquals("$region ${item.native}", item.drink, orderOf(region, item.native))
            assertNotNull(BAR_SIGN[region])
        }
        assertEquals(setOf("gb", "us", "ie", "au", "fr", "de", "es", "it", "pt", "pl", "ru", "uk", "ja"), BAR_MENU.keys)
        // Every region a desk language can reach has a menu.
        for (l in listOf("de", "fr", "es", "it", "pt", "pl", "ru", "uk", "ja"))
            assertTrue(l, BAR_MENU.containsKey(regionOf(l, "x")))
        for (c in Country.all) if (c.airports.any { it.lang == "en" }) assertTrue(c.id, BAR_MENU.containsKey(regionOf("en", c.id)))
    }

    @Test fun ordersIgnoreCaseAndAccents() {
        assertEquals(Drink.COFFEE, orderOf("fr", "Je voudrais un CAFÉ, s'il vous plaît"))
        assertEquals(Drink.COFFEE, orderOf("fr", "un cafe"))
        assertEquals(Drink.WATER, orderOf("pt", "Uma ÁGUA por favor"))
        assertEquals(Drink.COFFEE, orderOf("it", "Un CAFFÈ"))
        assertEquals(Drink.SPECIAL, orderOf("pt", "um galão"))
        assertEquals(Drink.COFFEE, orderOf("es", "un cafe solo"))
        assertEquals(Drink.WATER, orderOf("de", "Ein Glas WASSER"))
        assertEquals(Drink.SPECIAL, orderOf("de", "eine Apfelschorle bitte"))
        assertEquals(Drink.SPECIAL, orderOf("us", "A Root Beer please"))
        assertEquals(Drink.COFFEE, orderOf("gb", "a flat white"))
    }

    @Test fun ordersInCyrillicAndJapanese() {
        assertEquals(Drink.WATER, orderOf("ru", "Дайте воды, пожалуйста"))
        assertEquals(Drink.COFFEE, orderOf("ru", "Мне Кофе"))
        assertEquals(Drink.SPECIAL, orderOf("ru", "стакан морса"))
        assertEquals(Drink.WATER, orderOf("uk", "Склянку води"))
        assertEquals(Drink.COFFEE, orderOf("uk", "Каву, будь ласка"))
        assertEquals(Drink.SPECIAL, orderOf("uk", "узвар"))
        assertEquals(Drink.WATER, orderOf("ja", "お水をください"))
        assertEquals(Drink.COFFEE, orderOf("ja", "コーヒーをお願いします"))
        assertEquals(Drink.SPECIAL, orderOf("ja", "ラムネください"))
        assertEquals(Drink.COFFEE, orderOf("ja", "koohii onegaishimasu"))
    }

    @Test fun theFirstDrinkMentionedWins() {
        assertEquals(Drink.COFFEE, orderOf("gb", "a coffee, no, water"))
        assertEquals(Drink.WATER, orderOf("gb", "water or a coffee"))
        assertEquals(Drink.SPECIAL, orderOf("de", "Apfelschorle und dann Kaffee"))
        assertEquals(Drink.COFFEE, orderOf("ru", "кофе или воду"))
        assertEquals(Drink.SPECIAL, orderOf("ja", "ラムネとお水"))
    }

    @Test fun anAnswerWithoutAnOrderMatchesNothing() {
        assertNull(orderOf("gb", "I am here for a conference"))
        assertNull(orderOf("gb", "a pale sale")) // stems start words: no "ale" or "gin" inside others
        assertNull(orderOf("pl", "wódka")) // not water
        assertNull(orderOf("fr", ""))
        assertNull(orderOf("zh", "water"))
        assertNull(orderOf("tr", "su"))
    }

    @Test fun rotationIsBarTwoThirdsAndNeverATaxi() {
        val kinds = (0 until 9).map(::cityKind)
        assertEquals(listOf(CityKind.BAR, CityKind.HOTEL, CityKind.BAR), kinds.take(3))
        assertEquals(kinds.take(3), kinds.drop(3).take(3))
        assertEquals(6, kinds.count { it == CityKind.BAR })
        assertFalse(kinds.contains(CityKind.TAXI))
        assertFalse((-5..50).any { cityKind(it) == CityKind.TAXI })
        assertFalse((0..50).any { cityPick(false, it).first == CityKind.TAXI })
    }

    @Test fun aRepeatVisitRidesAndAFirstVisitTalks() {
        // The taxi beat is every eligible landing; only the first visit talks (cityPick), the rest ride.
        for (id in ids) {
            assertTrue(cityRideEligible(Act.FREE, false, false, "de", true, s))
            assertTrue(cityRideEligible(Act.FREE, false, false, "fr", true, s))
        }
        assertTrue(firstVisit("fr", s) && cityPick(true, 0).first == CityKind.TAXI)
        assertFalse(firstVisit("de", s))
        // A ride is more common than an encounter, and every encounter is also eligible for the taxi beat.
        assertTrue(ids.all { !due(it) || cityRideEligible(Act.FREE, false, false, "de", true, s) })
    }

    @Test fun theRideIsExcludedWhereEncountersAre() {
        assertFalse(cityRideEligible(Act.FREE, false, false, "de", false, s))
        assertFalse(cityRideEligible(Act.FREE, true, false, "de", true, s))
        assertFalse(cityRideEligible(Act.FREE, false, true, "de", true, s))
        assertFalse(cityRideEligible(Act.FREE, false, false, "usa", true, s))
        assertFalse(cityRideEligible(Act.STORY, false, false, "de", true, s))
        assertFalse(cityRideEligible(Act.LEFT, false, false, "de", true, s))
        assertFalse(cityRideEligible(null, false, false, "de", true, s))
        assertFalse(cityRideEligible(Act.FREE, false, false, "de", true, null))
        assertFalse(cityRideEligible(Act.FREE, false, false, "de", true, s.copy(thalassiaSeen = true)))
    }

    @Test fun rideCaptionRotatesThroughThree() {
        val res = (0 until 3).map(::cityRideRes)
        assertEquals(3, res.toSet().size)
        for (i in 0 until 9) assertEquals(res[i % 3], cityRideRes(i))
        assertEquals(res[2], cityRideRes(-1))
    }

    /** A free-act career at world-news [stage] ([newsStage]); poisoned with every motif planted unless [clean]. */
    private fun at(stage: Int, clean: Boolean = false, planted: List<String> = Motif.entries.map { it.id }) = CareerSnapshot(
        act = Act.FREE, upgradesGranted = stage.coerceAtMost(3), usaUnlocked = stage >= STAGE_USA, usaCompleted = stage >= STAGE_POST_USA,
        thalassiaSeen = stage >= STAGE_POST_THAL, doses = if (clean) 0 else 1, planted = if (clean) emptyList() else planted)
    private fun pool(s: CareerSnapshot, act: Act = Act.FREE) = (0 until 1500).mapNotNull { pickRumour(s, act, emptyList(), Random(it)) }.toSet()
    private val news = CITY_RUMOURS.filter { it.stage != null }

    @Test fun rumoursByTierPoisonAndUsa() {
        val low = pool(at(1, clean = true)).map(CITY_RUMOURS::get)
        assertTrue(low.filter { it.stage == null }.all { it.minTier <= 1 && !it.poison && !it.usa })
        assertEquals(4, low.count { it.stage == null })
        assertTrue(low.none { it.poison })
        // Tier 0: the t0 news and the bartender's one line; the up1 rumours wait for the first upgrade.
        val zero = pool(at(0, clean = true)).map(CITY_RUMOURS::get)
        assertEquals(listOf(R.string.rumour_phones), zero.filter { it.stage == null }.map { it.res })
        assertTrue(zero.filter { it.stage != null }.all { it.stage == 0 })
        assertTrue(pool(at(3, clean = true)).none { CITY_RUMOURS[it].poison })
        val poisoned = pool(at(3))
        assertTrue(poisoned.any { CITY_RUMOURS[it].poison })
        assertTrue(poisoned.none { CITY_RUMOURS[it].usa })
        assertTrue(pool(at(STAGE_USA, clean = true)).any { CITY_RUMOURS[it].usa })
        // Every rumour airs for some career.
        val all = (0 until STAGE_POST_THAL).flatMap { st -> pool(at(st)) + pool(at(st, clean = true)) }.toSet()
        assertEquals(CITY_RUMOURS.indices.toSet(), all)
    }

    @Test fun headlinesAirInTheirStageAndTheNextExactOnesOnlyInTheirOwn() {
        for (x in news) for (st in -1..STAGE_POST_THAL) {
            val s = if (st < 0) CareerSnapshot(act = Act.STORY) else at(st, clean = x.clean)
            val want = st in 0 until STAGE_POST_THAL && (st == x.stage || (!x.exact && st == x.stage!! + 1))
            assertEquals("${x.res} at $st", want, rumourFits(x, s, s.act ?: Act.STORY))
        }
    }

    @Test fun newsGatesPoisonCleanAndMotif() {
        for (x in news.filter { it.poison }) assertFalse(rumourFits(x, at(x.stage!!, clean = true), Act.FREE))
        for (x in news.filter { it.clean }) assertFalse(rumourFits(x, at(x.stage!!), Act.FREE))
        for (x in news.filter { it.motif != null }) {
            assertFalse(rumourFits(x, at(x.stage!!, planted = Motif.entries.map { it.id } - x.motif!!.id), Act.FREE))
            assertTrue(rumourFits(x, at(x.stage!!, planted = listOf(x.motif!!.id)), Act.FREE))
        }
        assertEquals(setOf(Motif.MOON, Motif.TORTOISE), news.mapNotNull { it.motif }.toSet())
        assertTrue(news.filter { it.motif != null }.all { it.poison })
    }

    @Test fun nothingAtTheBarAfterTheTrueEndingOrInActOne() {
        assertTrue(CITY_RUMOURS.none { it.stage == STAGE_POST_THAL })
        assertTrue(pool(at(STAGE_POST_THAL)).none { CITY_RUMOURS[it].stage != null })
        assertTrue(pool(CareerSnapshot(act = Act.STORY), Act.STORY).none { CITY_RUMOURS[it].stage != null })
    }

    @Test fun thalassiaStaysClosedOnlyUntilTheUsaIsDone() {
        val i = CITY_RUMOURS.indexOfFirst { it.res == R.string.rumour_thalassia }
        assertTrue(rumourFits(CITY_RUMOURS[i], at(STAGE_USA), Act.FREE))
        assertFalse(rumourFits(CITY_RUMOURS[i], at(STAGE_POST_USA), Act.FREE))
        assertFalse(i in pool(at(STAGE_POST_USA)))
    }

    @Test fun headlinesNeverNameFreeAiElTrazadorOrTheRadio() {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val en = File(res, "values/strings.xml").readText()
        assertTrue(Regex("""name="news_[a-h][0-9][0-9]"""").findAll(en).count() == 60)
        for (f in res.listFiles()!!.filter { it.name.startsWith("values") }) {
            val lines = Regex("""<string name="news_[a-h][0-9][0-9]">(.*?)</string>""").findAll(File(f, "strings.xml").readText()).map { it.groupValues[1] }.toList()
            assertEquals(f.name, 60, lines.size)
            for (t in lines) assertFalse("${f.name}: $t", "Free AI" in t || "Trazador" in t || "<" in t)
        }
        // No radio surface (owner): the taxi plays music only.
        assertFalse(Regex("(?i)\\bradio\\b|on the radio").containsMatchIn(Regex("""<string name="(news|rumour)_[^"]*">(.*?)</string>""").findAll(en).joinToString { it.value }))
    }

    @Test fun theBartenderAndTheTvShareTheDraws() {
        val picks = (0 until 1000).mapNotNull { pickRumour(at(1, clean = true), Act.FREE, emptyList(), Random(it)) }
        val talk = picks.count { !CITY_RUMOURS[it].tv }
        assertTrue("bartender $talk of ${picks.size}", talk in 400..600)
        // A recent bartender line leaves the TV for this draw.
        val phones = CITY_RUMOURS.indexOfFirst { it.res == R.string.rumour_phones }
        assertTrue((0 until 50).all { CITY_RUMOURS[pickRumour(at(1, clean = true), Act.FREE, listOf(phones), Random(it))!!].tv })
    }

    @Test fun neverOneOfTheLastThree() {
        var recent = emptyList<Int>()
        repeat(50) { i ->
            val r = pickRumour(at(3), Act.FREE, recent, Random(i))
            assertNotNull(r); r!!
            assertFalse("$r in $recent", r in recent)
            recent = rememberRumour(recent, r)
            assertTrue(recent.size <= RECENT_RUMOURS)
        }
        // Every other fitting rumour recent: the last one comes; never a repeat while another fits.
        val fits = CITY_RUMOURS.indices.filter { rumourFits(CITY_RUMOURS[it], at(1, clean = true), Act.FREE) }
        assertEquals(listOf(fits.last()), (0 until 20).map { pickRumour(at(1, clean = true), Act.FREE, fits.dropLast(1), Random(it)) }.distinct())
    }

    @Test fun fixedLinesForEveryLanguage() {
        for (l in Language.all) for (k in CityKind.entries)
            assertTrue("${l.code} $k", CITY_LINES[l.code]?.get(k).orEmpty().isNotBlank())
        assertEquals(Language.all.map { it.code }.toSet(), CITY_LINES.keys)
    }

    @Test fun russiaHasASilentCab() {
        assertEquals(CityKind.TAXI, cityPick(true, 0, "de").first)
        assertTrue(cityPick(true, 0, "russia").first != CityKind.TAXI) // the first visit still gets an encounter, never the talking cab
    }

    @Test fun drinksKeepTheirRealSizes() {
        assertTrue(drinkCm(Drink.COFFEE, "de") < drinkCm(Drink.WATER, "de"))
        assertTrue(drinkCm(Drink.SPECIAL, "ja") > drinkCm(Drink.SPECIAL, "it"))
        assertEquals(0f, drinkCm(null, "de"), 0f)
    }
}

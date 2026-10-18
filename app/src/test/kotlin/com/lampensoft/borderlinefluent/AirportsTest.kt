package com.lampensoft.borderlinefluent

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AirportsTest {
    // Frozen from the pre-refactor 99 slots; assignments from the approved plan, not the runtime catalog.
    // 2026-10-16 cultural review (owner): Kazakhstan, Kyrgyzstan and EGO DNK OZH cut, BRU -> CRL; KZN and UUD
    // (no old slot, empty first column) take the Kazakh and Kyrgyz officers.
    private val slots = """
de|de|fra|de|dest_de|de_f1,de_m1
valdenstein|austria|inn|de|dest_valdenstein|de_x1,de_x2
elbrandt|austria|szg|de|dest_elbrandt|de_x1,de_x2
grauwald|de|fkb|de|dest_grauwald|de_x1,de_x2
rhenstal|de|hhn|de|dest_rhenstal|de_x1,de_x2
fr|fr|cdg|fr|dest_fr|fr_f1,fr_m1
valmoree|fr|mrs|fr|dest_valmoree|fr_x1,fr_x2
montclair|fr|nce|fr|dest_montclair|fr_x1,fr_x2
belcastre|fr|tuf|fr|dest_belcastre|fr_x1,fr_x2
ostrevie|fr|lil|fr|dest_ostrevie|fr_x1,fr_x2
es|es|mad|es|dest_es|es_m1,es_f1
sanaurelio|colombia|ctg|es|dest_sanaurelio|es_x1,es_x2
costaverde|colombia|mde|es|dest_costaverde|es_x1,es_x2
valdoria|es|vll|es|dest_valdoria|es_x1,es_x2
monteluna|argentina|mdz|es|dest_monteluna|es_x1,es_x2
italy|italy|fco|it|dest_italy|it_rm,it_rf
sanvelario|italy|rmi|it|dest_sanvelario|it_xm,it_xf
portugal|portugal|lis|pt|dest_portugal|pt_rm,pt_rf
corvanas|portugal|pdl|pt|dest_corvanas|pt_xm,pt_xf
poland|poland|waw|pl|dest_poland|pl_rm,pl_rf
zalesia|poland|krk|pl|dest_zalesia|pl_xm,pl_xf
russia|russia|svo|ru|dest_russia|ru_rm,ru_rf
zarya|russia|mmk|ru|dest_zarya|ru_xm,ru_xf
kalynia|ukraine|kbp|uk|dest_kalynia|uk_xm,uk_xf
stepovia|ukraine|hrk|uk|dest_stepovia|uk_ym,uk_yf
turkey|turkey|ist|tr|dest_turkey|tr_rm,tr_rf
kizilkaya|turkey|nav|tr|dest_kizilkaya|tr_xm,tr_xf
uae|uae|dxb|ar|dest_uae|ar_rm,ar_rf
alqamar|uae|auh|ar|dest_alqamar|ar_xm,ar_xf
japan|japan|nrt|ja|dest_japan|ja_rm,ja_rf
hoshijima|japan|hij|ja|dest_hoshijima|ja_xm,ja_xf
china|china|pek|zh|dest_china|zh_rm,zh_rf
yunlan|china|kwl|zh|dest_yunlan|zh_xm,zh_xf
austria|austria|vie|de|dest_austria|de_atf
switzerland|switzerland|zrh|de|dest_switzerland|de_chm
hohenmark|de|bre|de|dest_hohenmark|de_x1,de_x2
siebenau|de|str|de|dest_siebenau|de_x1,de_x2
brennland|de|dtm|de|dest_brennland|de_x1,de_x2
belgium|belgium|crl|fr|dest_belgium|fr_bef
canada_fr|canada|yul|fr|dest_fr|fr_cam
senegal|senegal|dss|fr|dest_senegal|fr_snm
lorvel|fr|avn|fr|dest_lorvel|fr_x1,fr_x2
saintclairon|fr|run|fr|dest_saintclairon|fr_x1,fr_x2
mexico|mexico|mex|es|dest_mexico|es_mxf
argentina|argentina|eze|es|dest_argentina|es_arm
colombia|colombia|bog|es|dest_colombia|es_cof
coralina|mexico|cun|es|dest_coralina|es_x1,es_x2
altamira|mexico|cjs|es|dest_altamira|es_x1,es_x2
valtorre|italy|flr|it|dest_valtorre|it_xm,it_xf
portoserena|italy|olb|it|dest_portoserena|it_xm,it_xf
montecaldo|italy|nap|it|dest_montecaldo|it_xm,it_xf
lagodoro|italy|vrn|it|dest_lagodoro|it_xm,it_xf
castellume|italy|peg|it|dest_castellume|it_xm,it_xf
brazil|brazil|gru|pt|dest_brazil|pt_brf
angola|angola|lad|pt|dest_angola|pt_aom
capeverde|capeverde|rai|pt|dest_capeverde|pt_cvm
bruma|portugal|fnc|pt|dest_bruma|pt_xm,pt_xf
serraalta|portugal|ter|pt|dest_serraalta|pt_xm,pt_xf
borovia|poland|ktw|pl|dest_borovia|pl_xm,pl_xf
wolnogrod|poland|gdn|pl|dest_wolnogrod|pl_xm,pl_xf
jeziorna|poland|poz|pl|dest_jeziorna|pl_xm,pl_xf
kresowia|poland|rze|pl|dest_kresowia|pl_xm,pl_xf
zlotogora|poland|wro|pl|dest_zlotogora|pl_xm,pl_xf
pribrezhye|russia|arh|ru|dest_pribrezhye|ru_xm,ru_xf
kedrovia|russia|tof|ru|dest_kedrovia|ru_xm,ru_xf
sonyachia|ukraine|ods|uk|dest_sonyachia|uk_xm,uk_xf,uk_ym,uk_yf
|russia|kzn|ru|dest_kazan|ru_kzf,ru_xm
|russia|uud|ru|dest_ulanude|ru_kgm,ru_xf
dnistrovia|ukraine|lwo|uk|dest_dnistrovia|uk_xm,uk_xf,uk_ym,uk_yf
polonyna|ukraine|ifo|uk|dest_polonyna|uk_xm,uk_xf,uk_ym,uk_yf
yesilvadi|turkey|rzv|tr|dest_yesilvadi|tr_xm,tr_xf
akyali|turkey|dlm|tr|dest_akyali|tr_xm,tr_xf
gumusdere|turkey|asr|tr|dest_gumusdere|tr_xm,tr_xf
taskale|turkey|adb|tr|dest_taskale|tr_xm,tr_xf
mavikent|turkey|saw|tr|dest_mavikent|tr_xm,tr_xf
egypt|egypt|cai|ar|dest_egypt|ar_egm
jordan|jordan|amm|ar|dest_jordan|ar_jof
morocco|morocco|cmn|ar|dest_morocco|ar_mam
nakheel|uae|aan|ar|dest_nakheel|ar_xm,ar_xf
wadizahra|morocco|fez|ar|dest_wadizahra|ar_xm,ar_xf
shiranami|japan|kmq|ja|dest_shiranami|ja_xm,ja_xf
aogiri|japan|kij|ja|dest_aogiri|ja_xm,ja_xf
tsukiyama|japan|koj|ja|dest_tsukiyama|ja_xm,ja_xf
kazahana|japan|cts|ja|dest_kazahana|ja_xm,ja_xf
minamikaze|japan|oka|ja|dest_minamikaze|ja_xm,ja_xf
lianhua|china|hgh|zh|dest_lianhua|zh_xm,zh_xf
songlin|china|kwe|zh|dest_songlin|zh_xm,zh_xf
haiyue|china|xmn|zh|dest_haiyue|zh_xm,zh_xf
fenglan|china|lhw|zh|dest_fenglan|zh_xm,zh_xf
qingmu|china|wus|zh|dest_qingmu|zh_xm,zh_xf
uk|uk|lhr|en|dest_uk|en_ukf
ireland|ireland|dub|en|dest_ireland|en_iem
australia|australia|syd|en|dest_australia|en_auf
canada|canada|yyz|en|dest_canada|en_cam
ashbury|uk|syy|en|dest_ashbury|en_xm,en_xf
westmarch|australia|asp|en|dest_westmarch|en_xm,en_xf
""".trimIndent().lines().map { it.split('|') }

    @Test fun catalogPreservesEveryOldSlotAndItsPackAssets() {
        assertEquals(30, Country.all.size)
        val airports = Country.all.filter { it.id != "usa" }.flatMap { it.airports }
        assertEquals(96, airports.size)
        assertEquals(96, airports.map { it.id }.toSet().size)
        assertEquals(slots.map { it[2] }.toSet(), airports.map { it.id }.toSet())
        for (country in Country.all.filter { it.id != "usa" }) {
            val expected = slots.filter { it[1] == country.id }.sortedBy { it[0] != it[1] && it[0] != "canada_fr" }
            assertEquals(country.id, expected.map { it[2] }, country.airports.map { it.id })
        }
        val root = listOf(File("packs"), File("../packs")).first { it.isDirectory }
        for ((slot, id, airport, lang, art, officers) in slots.map { Slot(it) }) {
            val old = slot.ifEmpty { airport }
            val c = Country.of(id)
            val a = c.airports.single { it.id == airport }
            assertEquals(old, lang, a.lang)
            assertEquals(old, art, a.art)
            assertEquals(old, officers.split(','), a.officers)
            val dir = File(root, "lang_$lang/src/main/assets/art")
            assertTrue(old, File(dir, "$art.webp").isFile)
            a.officers.forEach { assertTrue(old, File(dir, "officer_${it}_sheet.webp").isFile) }
        }
    }
    @Test fun usaCatalogAndImmediateReferral() {
        val usa = Country.of("usa")
        assertEquals(listOf("JFK", "LAX", "ORD"), usa.airports.map { it.iata })
        assertTrue(usa.airports.all { it.lang == "en" && it.officers == listOf("en_usm", "en_usf") })
        val root = listOf(File("packs"), File("../packs")).first { it.isDirectory }
        for (airport in usa.airports) {
            val art = File(root, "lang_en/src/main/assets/art")
            assertTrue(File(art, "${airport.art}.webp").isFile)
            for (officer in airport.officers) assertTrue(File(art, "officer_${officer}_sheet.webp").isFile)
        }
        assertTrue(Country.isFemale("en_usf"))
        assertFalse(Country.isFemale("en_usm"))
        for (home in listOf(false, true)) {
            val mind = OfficerMind(Persona.TRAINING).also { it.homecoming = home }
            mind.startDesk("usa")
            assertEquals(Decision.REFERRAL, mind.decision)
            assertEquals(0, mind.turns)
            assertFalse(mind.crashed)
        }
        for (lang in Language.all) assertFalse(OFFICER_LINES.getValue(lang.code).getValue("usa.greet.1").isBlank())
    }

    private data class Slot(val old: String, val country: String, val airport: String, val lang: String, val art: String, val officers: String) {
        constructor(p: List<String>): this(p[0], p[1], p[2], p[3], p[4], p[5])
    }
    @Test fun aliasesAreExplicitAndComplete() {
        val expected = slots.filter { it[0] != it[1] && it[0].isNotEmpty() }.associate { it[0] to CountryMigration.Target(it[1], it[2]) }
        assertEquals(66, expected.size)
        assertEquals(expected, CountryMigration.aliases)
    }
    @Test fun canonicalIdentityAndOrder() {
        assertEquals("de fr es italy portugal poland russia ukraine turkey uae japan china austria switzerland belgium canada senegal mexico argentina colombia brazil angola capeverde egypt jordan morocco uk ireland australia usa".split(' '), Country.all.map { it.id })
        assertEquals(Country.all.map { it.id }.toSet(), Country.ISO.keys)
        assertEquals(30, Country.ISO.values.toSet().size)
        assertTrue("US" in Country.ISO.values)
        assertTrue(Country.all.all { it.airports.isNotEmpty() })
        assertEquals("UA", Country.of("ukraine").iso)
        assertEquals("GB", Country.of("uk").iso)
        assertEquals(5, Country.of("ukraine").airports.size)
        assertTrue(Country.of("ukraine").airports.all { it.lang == "uk" })
        assertEquals("\uD83C\uDDFA\uD83C\uDDE6", Country.of("ukraine").emoji)
        assertEquals("\uD83C\uDDEC\uD83C\uDDE7", Country.of("uk").emoji)
        assertEquals(listOf("yul", "yyz"), Country.of("canada").airports.map { it.id })
        assertNull(Country.find("ru"))
        assertThrows(IllegalArgumentException::class.java) { Country.of("ru") }
        val de = Country.of("de")
        assertThrows(IllegalArgumentException::class.java) { Destination(de, Country.of("fr").airports.first()) }
        val d = Destination(de, de.airports.first())
        assertEquals("lhr", Destination.forLanguage("en").airport.id)
        assertEquals("kbp", Destination.forLanguage("uk").airport.id)
        assertEquals("cdg", Destination.forLanguage("fr").airport.id)
        assertEquals("German", d.language)
        assertEquals("Germany", d.nation)
        assertEquals("Flughafen Frankfurt (FRA)", d.place)
    }

    private val allArt get() = Country.all.flatMap { it.airports }.flatMap { a ->
        listOf(a.art) + a.officers.map { "officer_${it}_sheet" }
    }.toSet()
    private val languages get() = Language.all.map { it.code }.toSet()

    @Test fun eligibilityNeedsOwnArtLanguageAndAtLeastOneOwnSheet() {
        val ca = Country.of("canada")
        assertEquals(listOf("yul"), eligibleAirports(ca, setOf("fr"), allArt).map { it.id })
        assertEquals(listOf("yyz"), eligibleAirports(ca, setOf("en"), allArt).map { it.id })
        assertEquals(listOf("yul"), eligibleAirports(ca, languages, allArt, "fr").map { it.id })
        assertTrue(eligibleAirports(ca, setOf("en"), allArt, "fr").isEmpty())
        assertEquals(listOf("yyz"), eligibleAirports(ca, languages, allArt - "dest_fr").map { it.id })
        assertEquals(listOf("yyz"), eligibleAirports(ca, languages, allArt - "officer_fr_cam_sheet").map { it.id })
        val de = Country.of("de")
        assertTrue(de.airports.first() in eligibleAirports(de, languages, allArt - "officer_de_f1_sheet"))
        assertFalse(de.airports.first() in eligibleAirports(de, languages, allArt - setOf("officer_de_f1_sheet", "officer_de_m1_sheet")))
        assertSame(ca, playableCountries(languages, allArt).single { it.id == "canada" })
    }
    @Test fun rotationUsesCompleteCyclesAndReconcilesEligibility() {
        val c = Country.of("poland")
        val bag = AirportRotation(kotlin.random.Random(123))
        var last: Airport? = null
        repeat(5) {
            val cycle = List(7) { bag.next(c, c.airports)!! }
            assertEquals(c.airports.toSet(), cycle.toSet())
            assertNotEquals(last, cycle.first())
            last = cycle.last()
        }
        val changed = listOf(last!!, c.airports.first { it != last })
        assertNotEquals(last, bag.next(c, changed)) // rebuilding retains the last pick
        val reduced = c.airports.filter { it != last }.take(2)
        assertEquals(reduced.toSet(), List(2) { bag.next(c, reduced) }.toSet())
        assertEquals(reduced.first(), bag.next(c, reduced.take(1)))
        assertNull(bag.next(c, emptyList()))
        assertEquals(c.airports.toSet(), List(7) { bag.next(c, c.airports) }.toSet())
        val sameSeed = List(2) { AirportRotation(kotlin.random.Random(17)) }
        assertEquals(List(21) { sameSeed[0].next(c, c.airports) }, List(21) { sameSeed[1].next(c, c.airports) })
        val ca = Country.of("canada")
        repeat(3) { assertEquals("yul", bag.next(ca, ca.airports.filter { it.lang == "fr" }, "fr")!!.id) }
        assertEquals(ca.airports.toSet(), List(2) { bag.next(ca, ca.airports) }.toSet())
    }
    @Test fun selectionSamplesDistinctCountriesThenConstrainedAirports() {
        // Enumerate the country index, not a probabilistic frequency assertion.
        val pool = listOf(Country.of("poland"), Country.of("canada"), Country.of("poland"))
        for (index in 0..1) {
            val r = object : kotlin.random.Random() {
                override fun nextBits(bitCount: Int) = 0
                override fun nextInt(until: Int): Int { assertEquals(2, until); return index }
            }
            val d = selectDestination(pool, languages, allArt, null, r, AirportRotation(kotlin.random.Random(1)))!!
            assertSame(pool[index], d.country)
        }
        val bag = AirportRotation(kotlin.random.Random(9))
        repeat(4) {
            val d = selectDestination(listOf(Country.of("canada")), languages, allArt, "fr", kotlin.random.Random(it), bag)!!
            assertEquals("yul", d.airport.id)
            assertFalse(d.airport in eligibleAirports(d.country, languages, allArt - d.airport.art, "fr"))
        }
        assertNull(selectDestination(pool, emptySet(), allArt, null, kotlin.random.Random(1), bag))
    }

    @Test fun everyAirportHasACityAndAnEnglishNameWhereNeeded() {
        for (a in Country.all.flatMap { it.airports }) {
            assertNotNull(a.id, AIRPORT_CITY[a.id])
            assertEquals(a.id, 3, a.iata.length)
            // A non-Latin local name must have an English UI name.
            if (a.name.any { it.code > 0x24F && it !in '\u2018'..'\u201F' && it != '\u2013' && it != '\u2014' && it != '\u00AB' && it != '\u00BB' })
                assertNotEquals(a.id, a.name, a.uiName("xx"))
        }
        assertEquals("Stornoway", Itinerary.roll(kotlin.random.Random(1), Destination(Country.of("uk"), Country.of("uk").airports.last()),
            Identity(Nationality.USA, Gender.WOMAN, NameList.FEMALE, "", "", "", "", "", null, "")).city)
    }
}

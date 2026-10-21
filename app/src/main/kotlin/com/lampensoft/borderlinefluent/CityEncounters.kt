package com.lampensoft.borderlinefluent

import kotlin.random.Random

/**
 * Act-2 machine encounters outside the airport (plan 2026-10-16-indie-polish, "Act-2 middle"): after a cleared free-act
 * trip, about one in three, a robot taxi, a hotel kiosk or a robot bartender says one fixed line in the destination's
 * language and the player answers once (or skips). Only small-talk adequacy is judged ([smallTalkOddPrompt]).
 * Held in memory only: never saved, so process death just skips it; it never touches points or progress.
 */
enum class CityKind(val art: String) { TAXI("enc_taxi"), HOTEL("enc_hotel"), BAR("enc_bar") }

/** INTRO: black screen and one narration caption ([cityIntroRes]) before the scene fades in. */
enum class CityStep { INTRO, SPEAKING, READY, HEARING, CHECKING, DONE }

/** The encounter on screen. [ride]: a repeat visit's short taxi ride (no question, no mic; [CityStep.INTRO] caption, then [CityStep.DONE] driving). [adequate]: null until judged; [rumour]: index into [CITY_RUMOURS], bar only; [intro]: caption variant (the encounter count); [country]: the destination's id (the taxi radio); [heard]: what the player said, once judged. */
data class CityScene(val kind: CityKind, val lang: String, val step: CityStep = CityStep.INTRO,
                     val adequate: Boolean? = null, val rumour: Int? = null, val intro: Int = 0, val country: String = "",
                     val drink: Drink? = null, val airport: String = "", val ride: Boolean = false, val heard: String = "",
                     /** A plant-your-own line on the bar's TV instead of a rumour ([CustomPlant.barQuote]), quoted as said. */
                     val quote: String? = null)

/** The scene's region (art, menu, radio): the language code, English by country (Canada gets the US, any other English country the UK). */
fun regionOf(lang: String, countryId: String): String = if (lang != "en") lang else when (countryId) {
    "ireland" -> "ie"
    "canada" -> "us"
    "australia" -> "au"
    else -> "gb"
}

/** The country's name in its city art files (fg_taxi_germany, bg_bar_france): full name for de/fr/es, else the id. */
fun artCountry(countryId: String): String = when (countryId) {
    "de" -> "germany"
    "fr" -> "france"
    "es" -> "spain"
    else -> countryId
}

/** The taxi radio's song (a pack [Song] key): one per language, English by [regionOf]. */
fun taxiSong(lang: String, countryId: String): String =
    if (lang != "en") "taxi_$lang" else "taxi_en_${regionOf(lang, countryId)}"

/** What the player ordered at the bar (every bar is alcohol-free); [SPECIAL] is the region's own drink (art `drink_<region>`). */
enum class Drink { WATER, COFFEE, SPECIAL }

/** A bar menu row: [native] is shown and matched by [stems] (accent-, case-insensitive, at a word start); [gloss] is the English name of a special. */
class BarItem(val drink: Drink, val native: String, val stems: List<String>, val gloss: String? = null)

private fun menu(water: String, wStems: List<String>, coffee: String, cStems: List<String>, special: String, sStems: List<String>, gloss: String) =
    listOf(BarItem(Drink.WATER, water, wStems), BarItem(Drink.COFFEE, coffee, cStems), BarItem(Drink.SPECIAL, special, sStems, gloss))

private val EN_COFFEE = listOf("coffee", "flat white", "latte")

/** Fixed per language, like [CITY_LINES]; a language without a row has no menu and no orders. Written by Claude, not native-reviewed. */
val BAR_MENU: Map<String, List<BarItem>> = mapOf(
    "gb" to menu("water", listOf("water"), "coffee", EN_COFFEE, "elderflower cordial", listOf("elderflower", "cordial"), "elderflower cordial"),
    "us" to menu("water", listOf("water"), "coffee", EN_COFFEE, "root beer", listOf("root beer"), "root beer"),
    "ie" to menu("water", listOf("water"), "coffee", EN_COFFEE, "red lemonade", listOf("red lemonade", "lemonade"), "red lemonade"),
    "au" to menu("water", listOf("water"), "coffee", EN_COFFEE, "lemon, lime and bitters", listOf("lemon", "lime", "bitters"), "lemon lime and bitters"),
    "fr" to menu("de l'eau", listOf("eau"), "un café", listOf("cafe"), "un diabolo menthe", listOf("diabolo", "menthe"), "mint syrup lemonade"),
    "de" to menu("Wasser", listOf("wasser"), "Kaffee", listOf("kaffee"), "Apfelschorle", listOf("apfelschorle", "schorle"), "apple spritzer"),
    "es" to menu("agua", listOf("agua"), "café", listOf("cafe", "cortado"), "horchata", listOf("horchata"), "tiger-nut milk"),
    "it" to menu("acqua", listOf("acqua"), "caffè", listOf("caffe", "espresso"), "chinotto", listOf("chinotto"), "bitter citrus soda"),
    "pt" to menu("água", listOf("agua"), "uma bica", listOf("bica", "cafe"), "um galão", listOf("galao"), "milky coffee"),
    // "woda" not "wod": "wódka" folds to "wodka".
    "pl" to menu("woda", listOf("woda", "wody", "wodę"), "kawa", listOf("kaw"), "kompot", listOf("kompot"), "fruit compote drink"),
    "ru" to menu("вода", listOf("вода", "воду", "воды"), "кофе", listOf("коф"), "морс", listOf("морс"), "berry drink"),
    "uk" to menu("вода", listOf("вода", "воду", "води"), "кава", listOf("кав"), "узвар", listOf("узвар"), "dried-fruit drink"),
    "ja" to menu("お水", listOf("水", "みず", "mizu"), "コーヒー", listOf("コーヒー", "koohii", "kohi"), "ラムネ", listOf("ラムネ", "ramune"), "ramune soda"),
)

/** The sign on the menu, per region (English regions share one). */
val BAR_SIGN: Map<String, String> = mapOf(
    "gb" to "Alcohol-free bar", "us" to "Alcohol-free bar", "ie" to "Alcohol-free bar", "au" to "Alcohol-free bar",
    "fr" to "Bar sans alcool", "de" to "Alkoholfreie Bar", "es" to "Bar sin alcohol", "it" to "Bar analcolico",
    "pt" to "Bar sem álcool", "pl" to "Bar bezalkoholowy", "ru" to "Безалкогольный бар", "uk" to "Безалкогольний бар",
    "ja" to "ノンアルコールバー",
)

private val MARKS = Regex("\\p{M}+")
private fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(MARKS, "").lowercase()

/** First place [stem] starts a word in [t] (anywhere in Japanese, which has no spaces), or null. */
private fun stemAt(t: String, stem: String, anywhere: Boolean): Int? {
    var i = t.indexOf(stem)
    while (i >= 0) { if (anywhere || i == 0 || !t[i - 1].isLetter()) return i; i = t.indexOf(stem, i + 1) }
    return null
}

/** The drink the player asked for in [transcript] (the first one mentioned wins), or null: no menu for [region] or none named. */
fun orderOf(region: String, transcript: String): Drink? {
    val t = fold(transcript)
    return BAR_MENU[region]?.mapNotNull { item -> item.stems.mapNotNull { stemAt(t, fold(it), region == "ja") }.minOrNull()?.let { it to item.drink } }
        ?.minByOrNull { it.first }?.second
}

/** How long the black narration caption holds before the scene, unless the player taps. */
const val CITY_INTRO_MS = 3000L

/** How long a served drink's dialogue stands before the bartender moves on by himself (same under Reduce motion). */
const val CITY_MOVE_ON_MS = 5000L

/** The narration caption: two variants per kind, alternating with [variant]. */
fun cityIntroRes(kind: CityKind, variant: Int): Int {
    val b = Math.floorMod(variant, 2) == 0
    return when (kind) {
        CityKind.BAR -> if (b) R.string.city_intro_bar_1 else R.string.city_intro_bar_2
        CityKind.TAXI -> if (b) R.string.city_intro_taxi_1 else R.string.city_intro_taxi_2
        CityKind.HOTEL -> if (b) R.string.city_intro_hotel_1 else R.string.city_intro_hotel_2
    }
}

/** The ride's first-person caption: three variants, rotating with [variant]. */
fun cityRideRes(variant: Int): Int = when (Math.floorMod(variant, 3)) {
    0 -> R.string.city_ride_1
    1 -> R.string.city_ride_2
    else -> R.string.city_ride_3
}

/** Bar two thirds of the time, hotel a third, in a fixed order by how many encounters the player has had. The taxi is never in it: every landing has one ([cityRideEligible]). */
private val CITY_ROTATION = listOf(CityKind.BAR, CityKind.HOTEL, CityKind.BAR)
fun cityKind(shown: Int): CityKind = CITY_ROTATION[Math.floorMod(shown, CITY_ROTATION.size)]

/**
 * The encounter's kind and the rotation count after it: a [firstVisit] is always a taxi and leaves the rotation where it
 * was (so it never eats a bar or hotel turn); otherwise the next kind in the rotation.
 */
/** Russia (owner): the cab driver is a silent human: always the ride, never "Where to?". */
val SILENT_CABS = setOf("russia")

fun cityPick(firstVisit: Boolean, shown: Int, countryId: String = ""): Pair<CityKind, Int> =
    if (firstVisit && countryId !in SILENT_CABS) CityKind.TAXI to shown
    else cityKind(shown) to shown + 1

/**
 * Whether the trip to [countryId] is the career's first: [s] must be the career before this attempt was applied (the
 * terminal save adds a cleared country to [CareerSnapshot.countries], story trips included).
 */
fun firstVisit(countryId: String, s: CareerSnapshot?): Boolean = s != null && countryId !in s.countries

/**
 * Whether a trip gets a taxi at all: a cleared, free-act, non-story, non-training trip outside the USA, before the true
 * ending. The first visit's taxi talks; every other one is a silent ride. [s] is the career before this attempt was applied.
 */
fun cityRideEligible(act: Act?, story: Boolean, training: Boolean, countryId: String, cleared: Boolean, s: CareerSnapshot?): Boolean =
    cleared && act == Act.FREE && !story && !training && countryId != Nationality.USA.id && s != null && !s.thalassiaSeen

/**
 * Whether attempt [attemptId] ends in an encounter: an eligible trip ([cityRideEligible]); always on a [firstVisit]
 * (a taxi, owner), else a bar or hotel one time in three, seeded by the attempt id.
 */
fun cityEncounterDue(attemptId: Long, act: Act?, story: Boolean, training: Boolean, countryId: String, cleared: Boolean,
                     s: CareerSnapshot?): Boolean =
    cityRideEligible(act, story, training, countryId, cleared, s) &&
        (firstVisit(countryId, s) || Random(attemptId * 7919 + 13).nextInt(3) == 0)

/**
 * A bar rumour: [tv] a news headline off the bar's TV, else the bartender talking; [minTier] by [harvestTier]; [poison] only
 * for careers with doses; [usa] only once the USA is open; [beforeUsaDone] only until the USA is done.
 * A world-news headline (plan 2026-10-17-world-news) has a [stage] ([newsStage]) instead: it airs in that stage and the next
 * (old news fades), an [exact] one only in its own; [clean] only for careers without doses; [motif] only once it is planted.
 */
data class Rumour(val res: Int, val minTier: Int = 1, val poison: Boolean = false, val usa: Boolean = false, val tv: Boolean = false,
                  val beforeUsaDone: Boolean = false, val stage: Int? = null, val exact: Boolean = false, val clean: Boolean = false,
                  val motif: Motif? = null)

/** World-news stages after the four harvest tiers 0..3 ([newsStage]). */
const val STAGE_USA = 4
const val STAGE_POST_USA = 5
const val STAGE_POST_THAL = 6

/** The world-news stage: -1 outside act 2; the [harvestTier]; then the USA open, done, and the true ending seen. */
fun newsStage(s: CareerSnapshot?, act: Act): Int = when {
    s == null || act != Act.FREE -> -1
    s.thalassiaSeen -> STAGE_POST_THAL
    s.usaCompleted -> STAGE_POST_USA
    s.usaUnlocked -> STAGE_USA
    else -> harvestTier(s, act)
}

/** A TV headline of the world-news plan; a [motif] one is also a poisoned one. */
private fun tv(res: Int, stage: Int, exact: Boolean = false, poison: Boolean = false, clean: Boolean = false, motif: Motif? = null) =
    Rumour(res, poison = poison || motif != null, tv = true, stage = stage, exact = exact, clean = clean, motif = motif)

// Append only: indices are saved (the bar's recent list).
val CITY_RUMOURS = listOf(
    Rumour(R.string.rumour_lenses, tv = true),
    Rumour(R.string.rumour_briefcase, tv = true),
    Rumour(R.string.rumour_phones, minTier = 0), // the bartender still talks at tier 0, as before the world news
    Rumour(R.string.rumour_thalassia, tv = true, beforeUsaDone = true), // Valery's message says the border opened
    Rumour(R.string.rumour_recalibration, minTier = 2, tv = true),
    Rumour(R.string.rumour_kiosk_cried, minTier = 2),
    Rumour(R.string.rumour_laughing, minTier = 2, poison = true),
    Rumour(R.string.rumour_engineers, minTier = 3, poison = true),
    Rumour(R.string.rumour_no_questions, minTier = 3),
    Rumour(R.string.rumour_usa_teaches, usa = true),
    // World news (surface TV): ids in the string names.
    tv(R.string.news_b01, 0), tv(R.string.news_b02, 0), tv(R.string.news_b04, 0), tv(R.string.news_b05, 0),
    tv(R.string.news_b07, 0), tv(R.string.news_b08, 0), tv(R.string.news_b09, 0), tv(R.string.news_b11, 0),
    tv(R.string.news_b13, 0), tv(R.string.news_b14, 0), tv(R.string.news_b16, 0), tv(R.string.news_b17, 0),
    tv(R.string.news_c01, 1, poison = true), tv(R.string.news_c02, 1, clean = true), tv(R.string.news_c03, 1, exact = true),
    tv(R.string.news_c05, 1), tv(R.string.news_c07, 1), tv(R.string.news_c08, 1), tv(R.string.news_c09, 1),
    tv(R.string.news_c11, 1), tv(R.string.news_c12, 1),
    tv(R.string.news_d01, 2, poison = true), tv(R.string.news_d02, 2, poison = true), tv(R.string.news_d04, 2, exact = true),
    tv(R.string.news_d05, 2), tv(R.string.news_d06, 2), tv(R.string.news_d08, 2), tv(R.string.news_d09, 2, motif = Motif.MOON),
    tv(R.string.news_d11, 2), tv(R.string.news_d12, 2), tv(R.string.news_d13, 2),
    tv(R.string.news_e01, 3, poison = true), tv(R.string.news_e03, 3, clean = true), tv(R.string.news_e04, 3),
    tv(R.string.news_e05, 3), tv(R.string.news_e06, 3), tv(R.string.news_e08, 3), tv(R.string.news_e09, 3),
    tv(R.string.news_e10, 3), tv(R.string.news_e11, 3, motif = Motif.TORTOISE),
    tv(R.string.news_f01, STAGE_USA), tv(R.string.news_f03, STAGE_USA, poison = true), tv(R.string.news_f04, STAGE_USA),
    tv(R.string.news_f05, STAGE_USA), tv(R.string.news_f06, STAGE_USA), tv(R.string.news_f08, STAGE_USA),
    tv(R.string.news_f10, STAGE_USA, clean = true),
    tv(R.string.news_g01, STAGE_POST_USA, exact = true), tv(R.string.news_g04, STAGE_POST_USA, exact = true),
    tv(R.string.news_g05, STAGE_POST_USA), tv(R.string.news_g06, STAGE_POST_USA), tv(R.string.news_g08, STAGE_POST_USA, poison = true),
    tv(R.string.news_g09, STAGE_POST_USA), tv(R.string.news_g11, STAGE_POST_USA), tv(R.string.news_g12, STAGE_POST_USA),
    tv(R.string.news_d03, 2, clean = true), // up2's clean counterpart of D01/D02, appended (owner)
)

/** How many recent rumours the bar avoids. */
const val RECENT_RUMOURS = 3

/** Whether rumour [x] fits career [s] in [act]. Nothing airs after the true ending ([STAGE_POST_THAL]): no trips are left. */
fun rumourFits(x: Rumour, s: CareerSnapshot?, act: Act): Boolean {
    val clean = cleanCareer(s)
    val gates = (!x.poison || !clean) && (!x.clean || clean) && (x.motif == null || x.motif.id in s?.planted.orEmpty()) &&
        (!x.beforeUsaDone || s?.usaCompleted != true)
    val stage = newsStage(s, act)
    return gates && when (x.stage) {
        null -> x.minTier <= harvestTier(s, act) && (!x.usa || s?.usaUnlocked == true)
        else -> stage < STAGE_POST_THAL && (stage == x.stage || (!x.exact && stage == x.stage + 1))
    }
}

/**
 * One rumour index for this career, never one of [recent] while another fits; null only if none fits at all. The bartender
 * and the TV each get half the draws while both have a candidate (the TV's pool is far larger), then uniform within one.
 */
fun pickRumour(s: CareerSnapshot?, act: Act, recent: List<Int>, r: Random): Int? {
    val fits = CITY_RUMOURS.indices.filter { rumourFits(CITY_RUMOURS[it], s, act) }
    val fresh = fits.filter { it !in recent }.ifEmpty { fits }
    val (tv, talk) = fresh.partition { CITY_RUMOURS[it].tv }
    return (if (tv.isEmpty() || talk.isEmpty()) fresh else if (r.nextBoolean()) tv else talk).randomOrNull(r)
}

/** One arrivals-hall news caption ([dueNews]): it airs once, in its own [stage] ([newsStage]); [id] is saved in [CareerSnapshot.newsSeen]. */
data class NewsCaption(val id: String, val res: Int, val stage: Int)

/** The caption saves from before the world news knew as `newsSeen: true`. */
const val NEWS_THALASSIA = "thalassia"

val NEWS_CAPTIONS = listOf(
    NewsCaption("B12", R.string.news_b12, 0),
    NewsCaption("C14", R.string.news_c14, 1),
    NewsCaption(NEWS_THALASSIA, R.string.news_caption, 2),
    NewsCaption("D14", R.string.news_d14, 3),
    NewsCaption("F09", R.string.news_f09, STAGE_USA),
)

/** [recent] with [picked] moved to the end, at most [RECENT_RUMOURS]. */
fun rememberRumour(recent: List<Int>, picked: Int): List<Int> = (recent - picked + picked).takeLast(RECENT_RUMOURS)

/**
 * The machine's one line per desk language, spoken verbatim like the officer's fixed lines (subtitle: R.string.city_*_line).
 * Formal or impersonal register, no gendered form for the traveller. Written by Claude, not native-reviewed.
 */
val CITY_LINES: Map<String, Map<CityKind, String>> = mapOf(
    "en" to mapOf(CityKind.TAXI to "Where to?", CityKind.HOTEL to "Purpose of your stay?", CityKind.BAR to "What can I get you?"),
    "de" to mapOf(CityKind.TAXI to "Wohin soll es gehen?", CityKind.HOTEL to "Zweck Ihres Aufenthalts?", CityKind.BAR to "Was darf es sein?"),
    "fr" to mapOf(CityKind.TAXI to "Où allons-nous ?", CityKind.HOTEL to "Motif de votre séjour ?", CityKind.BAR to "Qu'est-ce que je vous sers ?"),
    "es" to mapOf(CityKind.TAXI to "¿Adónde vamos?", CityKind.HOTEL to "¿Motivo de su estancia?", CityKind.BAR to "¿Qué le pongo?"),
    "it" to mapOf(CityKind.TAXI to "Dove andiamo?", CityKind.HOTEL to "Motivo del soggiorno?", CityKind.BAR to "Cosa Le porto?"),
    "pt" to mapOf(CityKind.TAXI to "Para onde vamos?", CityKind.HOTEL to "Motivo da estadia?", CityKind.BAR to "O que vai ser?"),
    "pl" to mapOf(CityKind.TAXI to "Dokąd jedziemy?", CityKind.HOTEL to "Cel pobytu?", CityKind.BAR to "Co podać?"),
    "ru" to mapOf(CityKind.TAXI to "Куда едем?", CityKind.HOTEL to "Цель вашего пребывания?", CityKind.BAR to "Что вам налить?"),
    "uk" to mapOf(CityKind.TAXI to "Куди їдемо?", CityKind.HOTEL to "Мета вашого перебування?", CityKind.BAR to "Що вам налити?"),
    "tr" to mapOf(CityKind.TAXI to "Nereye gidiyoruz?", CityKind.HOTEL to "Konaklamanızın amacı nedir?", CityKind.BAR to "Ne alırsınız?"),
    "ar" to mapOf(CityKind.TAXI to "إلى أين؟", CityKind.HOTEL to "ما الغرض من الإقامة؟", CityKind.BAR to "ماذا أقدّم لحضرتك؟"),
    "ja" to mapOf(CityKind.TAXI to "どちらまで？", CityKind.HOTEL to "ご滞在の目的は？", CityKind.BAR to "何になさいますか？"),
    "zh" to mapOf(CityKind.TAXI to "您去哪儿？", CityKind.HOTEL to "您此次入住的目的是什么？", CityKind.BAR to "您想喝点什么？"),
)

/** A served drink's real height in cm (its whole art: glass, straw, saucer), so a cup and a bottle keep their sizes on the counter. */
fun drinkCm(drink: Drink?, region: String): Float = when (drink) {
    null -> 0f
    Drink.WATER -> 15f
    Drink.COFFEE -> 8f
    Drink.SPECIAL -> when (region) {
        "fr" -> 20f // tall glass and straw
        "ja" -> 19f // ramune bottle
        "de", "es" -> 17f
        "pt" -> 16f // glass on a saucer
        "uk" -> 11f
        "it" -> 10f // tumbler
        else -> 15f
    }
}

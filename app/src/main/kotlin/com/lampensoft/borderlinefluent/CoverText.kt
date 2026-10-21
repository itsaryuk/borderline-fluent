package com.lampensoft.borderlinefluent

import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Presentation-only registry. Stable resource ids, keyed by the unchanged English fact values.
 * TranslationsTest checks every resource; CoverTextTest checks every generated fact is registered.
 * Do not feed these strings back into Backstory, the judge, or officer prompts. */
internal val COVER_VALUE_IDS: Map<String, Int> = mapOf(
    "a guesthouse in Lefkopoli" to R.string.cover_value_stay_lefkopoli,
    "a logistics software conference" to R.string.cover_value_purpose_logistics,
    "Hotel Steigenberger, city centre" to R.string.cover_value_stay_steigenberger,
    "a trade fair" to R.string.cover_value_purpose_trade_fair,
    "visiting an old friend" to R.string.cover_value_purpose_friend,
    "a business meeting" to R.string.cover_value_purpose_business,
    "a holiday" to R.string.cover_value_purpose_holiday,
    "an academic conference" to R.string.cover_value_purpose_conference,
    "a friend's wedding" to R.string.cover_value_purpose_wedding,
    "a hiking trip" to R.string.cover_value_purpose_hiking,
    "a job interview" to R.string.cover_value_purpose_interview,
    "a photography assignment" to R.string.cover_value_purpose_photography,
    "a business hotel near the fairground" to R.string.cover_value_stay_fair_hotel,
    "the Marriott by the river" to R.string.cover_value_stay_marriott,
    "the friend's apartment" to R.string.cover_value_stay_friend,
    "Hotel Central near the main station" to R.string.cover_value_stay_central,
    "a business hotel by the airport" to R.string.cover_value_stay_airport,
    "an Airbnb in the old town" to R.string.cover_value_stay_airbnb_old,
    "a small guesthouse by the sea" to R.string.cover_value_stay_seaside,
    "a beach hotel" to R.string.cover_value_stay_beach,
    "the conference hotel" to R.string.cover_value_stay_conference,
    "a university guest house" to R.string.cover_value_stay_university,
    "a friend's flat" to R.string.cover_value_stay_friend_flat,
    "a hotel near the wedding venue" to R.string.cover_value_stay_wedding,
    "mountain huts" to R.string.cover_value_stay_huts,
    "a campsite by a lake" to R.string.cover_value_stay_campsite,
    "a hotel near the company" to R.string.cover_value_stay_company,
    "an Airbnb near the station" to R.string.cover_value_stay_airbnb_station,
    "a small guesthouse" to R.string.cover_value_stay_guesthouse,
    "a hotel in the old town" to R.string.cover_value_stay_old_hotel,
    "nobody, a hotel stay" to R.string.cover_value_host_hotel,
    "an old friend from university" to R.string.cover_value_host_friend,
    "a partner company" to R.string.cover_value_host_partner_company,
    "nobody, a holiday" to R.string.cover_value_host_holiday,
    "the university hosting the conference" to R.string.cover_value_host_university,
    "the friend getting married" to R.string.cover_value_host_wedding_friend,
    "nobody, a hiking holiday" to R.string.cover_value_host_hiking,
    "the company they interview with" to R.string.cover_value_host_interview,
    "the magazine they work for" to R.string.cover_value_host_magazine,
)

/** A cover fact's text in [ui]: the registered value, or the reserved Solvane hotel ([solvaneStay]), which is built from the city. */
internal fun coverValue(english: String, s: Backstory, ui: String, text: (Int) -> String): String =
    if (english == solvaneStay(s.city)) text(R.string.cover_value_stay_solvane).replace("{city}", s.city)
    else Assessment.fill(text(COVER_VALUE_IDS.getValue(english)), s, ui)

/** UI language, not the officer language or the process default. Keep European Portuguese. */
internal fun coverLocale(ui: String): Locale = Language.uiLocale(ui) ?: Locale.ENGLISH

/** Backstory's English date stays intact for prompts. Both formatters use UTC to preserve the calendar day. */
internal fun coverBirthDate(born: String, ui: String): String {
    val utc = TimeZone.getTimeZone("UTC")
    val parser = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).apply { isLenient = false; timeZone = utc }
    val date = parser.parse(born) ?: error("Invalid cover birth date: $born")
    return DateFormat.getDateInstance(DateFormat.MEDIUM, coverLocale(ui)).apply { timeZone = utc }.format(date)
}

/** Nation values have no region note (Canada, even when the first destination is Québec); every nationality localizes (USA too). */
internal fun coverNation(nation: String, ui: String): String =
    if (nation == "Thalassia") when (ui) {
        "ru" -> "Таласия"; "uk" -> "Таласіїя"; "ar" -> "ثالاسيا"; "ja" -> "タラシア"; "zh" -> "塔拉西亚"
        else -> "Thalassia"
    } else Nationality.all.firstOrNull { it.name == nation }?.uiName(ui)?.substringBefore(" (") ?: nation

// Isolate inserted text in the Arabic paragraph, including mixed Latin proper names and years.
internal fun coverIsolate(value: String, ui: String): String = if (ui == "ar") "\u2068$value\u2069" else value

/** A raw translatable template and its UI-only values; the caller retains bold emphasis on each value. */
internal data class CoverLine(val template: Int, val values: List<String>)

internal fun coverLines(s: Backstory, ui: String, text: (Int) -> String): List<CoverLine> {
    // Use the same traveler-gender patterns as OfficerLines, with the cover identity's gender.
    fun value(english: String) = coverValue(english, s, ui, text)
    fun line(template: Int, vararg values: String) = CoverLine(template, values.map { coverIsolate(it, ui) })
    val days = NumberFormat.getIntegerInstance(coverLocale(ui)).format(s.days)
    // A visit: the city is in the destination, the stay is ahead and someone may have invited them.
    val visit = s.kind == TripKind.ARRIVAL
    return listOfNotNull(
        line(R.string.cover_story_home, s.home, coverNation(s.nationality.name, ui)),
        if (visit) line(R.string.cover_story_visit, days, s.city, coverNation(s.country.nation, ui), value(s.purpose))
        else line(R.string.cover_story_trip, days, s.city, coverNation(s.from, ui), value(s.purpose)),
        line(if (visit) R.string.cover_story_visit_stay else R.string.cover_story_stay, value(s.stayed)),
        if (visit) line(R.string.cover_from_line, coverFrom(s, ui)) else null,
        if (s.hasHost) line(R.string.cover_host_line, value(s.host)) else null,
    )
}

/** Where an arrival flies in from, in [ui]: "Dublin, Ireland" for a chain's leg 2, else the country. */
internal fun coverFrom(s: Backstory, ui: String) = coverNation(s.from, ui).let { n -> if (s.fromCity.isBlank()) n else "${cityIn(s.fromCity, ui) ?: s.fromCity}, $n" }

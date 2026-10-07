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
    "a logistics software conference" to R.string.cover_value_purpose_logistics,
    "Hotel Steigenberger, city centre" to R.string.cover_value_stay_steigenberger,
    "colleagues from the company Datenwerk" to R.string.cover_value_person_datenwerk,
    "clients at the conference" to R.string.cover_value_met_conference_clients,
    "his wife and two children" to R.string.cover_value_person_wife_children,
    "software consultant" to R.string.cover_value_job_software_consultant,
    "architect" to R.string.cover_value_job_architect,
    "university lecturer" to R.string.cover_value_job_lecturer,
    "wine importer" to R.string.cover_value_job_wine_importer,
    "freelance photographer" to R.string.cover_value_job_photographer,
    "mechanical engineer" to R.string.cover_value_job_mechanical_engineer,
    "nurse" to R.string.cover_value_job_nurse,
    "marketing manager" to R.string.cover_value_job_marketing_manager,
    "travel journalist" to R.string.cover_value_job_journalist,
    "civil engineer" to R.string.cover_value_job_civil_engineer,
    "chef" to R.string.cover_value_job_chef,
    "museum curator" to R.string.cover_value_job_curator,
    "pharmacist" to R.string.cover_value_job_pharmacist,
    "graphic designer" to R.string.cover_value_job_designer,
    "a trade fair" to R.string.cover_value_purpose_trade_fair,
    "visiting an old friend" to R.string.cover_value_purpose_friend,
    "a business meeting" to R.string.cover_value_purpose_business,
    "a holiday" to R.string.cover_value_purpose_holiday,
    "an academic conference" to R.string.cover_value_purpose_conference,
    "a cousin's wedding" to R.string.cover_value_purpose_wedding,
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
    "the cousin's house" to R.string.cover_value_stay_cousin,
    "a hotel near the wedding venue" to R.string.cover_value_stay_wedding,
    "mountain huts" to R.string.cover_value_stay_huts,
    "a campsite by a lake" to R.string.cover_value_stay_campsite,
    "a hotel near the company" to R.string.cover_value_stay_company,
    "an Airbnb near the station" to R.string.cover_value_stay_airbnb_station,
    "a small guesthouse" to R.string.cover_value_stay_guesthouse,
    "a hotel in the old town" to R.string.cover_value_stay_old_hotel,
    "two colleagues from work" to R.string.cover_value_person_two_colleagues,
    "nobody, travelled alone" to R.string.cover_value_person_alone,
    "their partner" to R.string.cover_value_person_partner,
    "a colleague from work" to R.string.cover_value_person_colleague,
    "two friends" to R.string.cover_value_person_two_friends,
    "their sister" to R.string.cover_value_person_sister,
    "a university colleague" to R.string.cover_value_person_university_colleague,
    "their parents" to R.string.cover_value_person_parents,
    "a friend from school" to R.string.cover_value_person_school_friend,
    "an assistant" to R.string.cover_value_person_assistant,
    "their partner and two children" to R.string.cover_value_person_partner_children,
    "a sister and her children" to R.string.cover_value_person_sister_children,
    "an old dog and a neighbour who feeds it" to R.string.cover_value_person_dog,
    "nobody, lives alone" to R.string.cover_value_person_home_alone,
    "clients at the fair" to R.string.cover_value_met_clients,
    "a supplier named Weber" to R.string.cover_value_met_supplier,
    "an old friend from university and his family" to R.string.cover_value_met_friend_family,
    "a client named Weber" to R.string.cover_value_met_client,
    "the board of a partner company" to R.string.cover_value_met_board,
    "nobody in particular" to R.string.cover_value_met_nobody,
    "a local tour guide" to R.string.cover_value_met_tour_guide,
    "researchers from other universities" to R.string.cover_value_met_researchers,
    "a former professor" to R.string.cover_value_met_professor,
    "the cousin's family" to R.string.cover_value_met_cousin_family,
    "relatives from abroad" to R.string.cover_value_met_relatives,
    "a mountain guide" to R.string.cover_value_met_mountain_guide,
    "other hikers on the trail" to R.string.cover_value_met_hikers,
    "the hiring manager" to R.string.cover_value_met_hiring,
    "the team they might join" to R.string.cover_value_met_team,
    "a local guide" to R.string.cover_value_met_guide,
    "the magazine's local fixer" to R.string.cover_value_met_fixer,
)

/** UI language, not the officer language or the process default. Keep European Portuguese. */
internal fun coverLocale(ui: String): Locale = Language.uiLocale(ui) ?: Locale.ENGLISH

/** Backstory's English date stays intact for prompts. Both formatters use UTC to preserve the calendar day. */
internal fun coverBirthDate(born: String, ui: String): String {
    val utc = TimeZone.getTimeZone("UTC")
    val parser = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).apply { isLenient = false; timeZone = utc }
    val date = parser.parse(born) ?: error("Invalid cover birth date: $born")
    return DateFormat.getDateInstance(DateFormat.MEDIUM, coverLocale(ui)).apply { timeZone = utc }.format(date)
}

/** Nation values have no region note (Canada, even when the first destination is Québec). */
internal fun coverNation(nation: String, ui: String): String =
    Country.all.firstOrNull { it.nation == nation }?.uiName(ui)?.substringBefore(" (") ?: nation

/** Preserve all stamps, their order and years, including unknown archived nation names. */
internal fun coverStamps(stamps: List<String>, ui: String): String = stamps.joinToString(if (ui == "ar") "، " else ", ") {
    val nation = coverNation(it.substringBeforeLast(' '), ui)
    coverIsolate("$nation ${it.substringAfterLast(' ')}", ui)
}

// Isolate inserted text in the Arabic paragraph, including mixed Latin proper names and years.
internal fun coverIsolate(value: String, ui: String): String = if (ui == "ar") "\u2068$value\u2069" else value

/** A raw translatable template and its UI-only values; the caller retains bold emphasis on each value. */
internal data class CoverLine(val template: Int, val values: List<String>)

internal fun coverLines(s: Backstory, ui: String, text: (Int) -> String): List<CoverLine> {
    // Use the same traveler-gender patterns as OfficerLines, with the cover identity's gender.
    fun value(english: String) = Assessment.fill(text(COVER_VALUE_IDS.getValue(english)), s, ui)
    fun line(template: Int, vararg values: String) = CoverLine(template, values.map { coverIsolate(it, ui) })
    return listOf(
        line(R.string.cover_story_home, s.home, value(s.waiting), value(s.job)),
        line(R.string.cover_story_trip, NumberFormat.getIntegerInstance(coverLocale(ui)).format(s.days),
            s.city, coverNation(s.from, ui), value(s.purpose)),
        line(R.string.cover_story_stay, value(s.stayed), value(s.travelledWith), value(s.met)),
        CoverLine(R.string.cover_stamps_line, listOf(coverStamps(s.stamps, ui))),
    )
}

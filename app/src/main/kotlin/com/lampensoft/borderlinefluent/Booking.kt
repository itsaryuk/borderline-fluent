package com.lampensoft.borderlinefluent

/** Booking-app rows: the player's own country first ("Coming home", only when it is playable), the rest sorted by UI name. */
data class BookingRows(val home: Country?, val others: List<Country>)

fun bookingRows(playable: List<Country>, nationalityId: String?, ui: String): BookingRows {
    val home = playable.firstOrNull { it.id == nationalityId }
    return BookingRows(home, (playable - listOfNotNull(home).toSet()).sortedBy { it.uiName(ui) })
}

/** The airport's IATA code: Airport ids are lowercase IATA. */
val Airport.iata get() = id.uppercase()

/** English names of the airports whose own name is in another script; UI text uses these unless the UI is that language. */
private val AIRPORT_EN = mapOf(
    "svo" to "Sheremetyevo International Airport", "mmk" to "Murmansk Airport",
    "arh" to "Arkhangelsk Talagi Airport", "tof" to "Tomsk Bogashevo Airport", "kzn" to "Kazan International Airport",
    "uud" to "Baikal International Airport, Ulan-Ude",
    "kbp" to "Boryspil International Airport", "hrk" to "Kharkiv International Airport", "ods" to "Odesa International Airport",
    "lwo" to "Lviv Danylo Halytskyi International Airport", "ifo" to "Ivano-Frankivsk International Airport",
    "dxb" to "Dubai International Airport", "auh" to "Zayed International Airport", "aan" to "Al Ain International Airport",
    "cai" to "Cairo International Airport", "amm" to "Queen Alia International Airport", "cmn" to "Mohammed V International Airport",
    "fez" to "Fès–Saïs Airport",
    "nrt" to "Narita International Airport", "hij" to "Hiroshima Airport", "kmq" to "Komatsu Airport", "kij" to "Niigata Airport",
    "koj" to "Kagoshima Airport", "cts" to "New Chitose Airport", "oka" to "Naha Airport",
    "pek" to "Beijing Capital International Airport", "kwl" to "Guilin Liangjiang International Airport",
    "hgh" to "Hangzhou Xiaoshan International Airport", "kwe" to "Guiyang Longdongbao International Airport",
    "xmn" to "Xiamen Gaoqi International Airport", "lhw" to "Lanzhou Zhongchuan International Airport", "wus" to "Wuyishan Airport",
)

/** The airport's name for UI text (the officer's speech keeps [Airport.name]): English when the local name is in a script the UI doesn't use. */
fun Airport.uiName(ui: String): String =
    if (ui == lang) name else AIRPORT_EN[id]?.let { "$it ($iata)" } ?: name

val UPGRADE_THRESHOLDS = listOf(2, 5, 8)
fun upgradesFor(points: Int) = UPGRADE_THRESHOLDS.count { points >= it }

/** Points a released free-act machine session earns (amendment 2026-10-15): one, plus one when its language is new to the career. Doses do not count; samples only enrich the story. */
fun sessionPoints(newLanguage: Boolean) = 1 + if (newLanguage) 1 else 0

/** The poison tier 0..3 (CLEAN, TAINTED, SOUR, SPOILED): the upgrades the career's points have earned; always 0 outside act 2. */
fun harvestTier(s: CareerSnapshot?, act: Act): Int =
    if (s == null || act != Act.FREE) 0 else maxOf(s.upgradesGranted, upgradesFor(s.progressPoints)).coerceIn(0, 3)

/** True while the player has planted no poison sample (doses only grow, so a line can turn poisoned later, never clean again). */
fun cleanCareer(s: CareerSnapshot?) = (s?.doses ?: 0) == 0

/** The share of the harvest the player's doses have spoiled, in percent; shown with one decimal. */
fun harvestPercent(doses: Int) = (doses * 0.4).coerceAtMost(100.0)
fun implantBuild(upgrades: Int) = if (upgrades <= 0) "0.9" else "0.9.${upgrades.coerceAtMost(3)}"
fun pendingUpgrade(s: CareerSnapshot?) = s?.let { (1..it.upgradesGranted).firstOrNull { n -> n !in it.cutscenesSeen } }
/** Upgrades whose box has been handed over, in order; perks, the build and the USA follow these, not the granted level. */
fun deliveredUpgrades(s: CareerSnapshot?) = s?.let { (1..3).takeWhile { n -> n in it.cutscenesSeen }.size } ?: 0
/** The country where the pending upgrade's courier waits, if any (for the menu line). */
fun waitingCourierCountry(s: CareerSnapshot?): String? = pendingUpgrade(s)?.let { s?.upgradeDrops?.get(it) }
/** The pending level whose courier waits in the country the player just passed in, if any. */
fun courierDelivery(s: CareerSnapshot?) = pendingUpgrade(s)?.takeIf { n -> s?.upgradeDrops?.get(n)?.let { it == s.lastPassedCountry } == true }
/**
 * Destinations for granted levels without one, from the countries bookable now: one already passed, else home, else the
 * first; ids sorted so the pick is stable. A level behind an undelivered one shares its country: the courier delivers
 * only the lowest pending level, so one crossing hands over one box and the next screen offers the following one.
 */
fun chooseUpgradeDrops(s: CareerSnapshot?, bookable: List<Country>): Map<Int, String> {
    if (s == null) return emptyMap()
    val drops = s.upgradeDrops.toMutableMap()
    val passed = passedCountries(s)
    val home = s.identity?.nationality?.id
    for (n in 1..s.upgradesGranted) {
        if (n in drops || n in s.cutscenesSeen) continue
        val lower = pendingUpgrade(s)?.takeIf { it < n }?.let { drops[it] }
        if (lower != null) { drops[n] = lower; continue }
        val pool = bookable.sortedBy { it.id }
        val pick = pool.firstOrNull { it.id in passed } ?: pool.firstOrNull { it.id == home } ?: pool.firstOrNull() ?: break
        drops[n] = pick.id
    }
    return drops - s.upgradeDrops.keys
}
fun unlockedCountries(s: CareerSnapshot?, countries: List<Country> = Country.shipped) =
    countries.filter { it.id != Nationality.USA.id || s?.act == Act.FREE && s.usaUnlocked }
/** Trips the player completes elsewhere before a country that refused them reopens. */
const val REFUSAL_CLOSED_TRIPS = 2
private fun openFlights(s: CareerSnapshot?, countries: List<Country>) =
    unlockedCountries(s, countries).filter { it.id != s?.lastPassedCountry && (s?.flagged?.get(it.id) ?: 0) <= 0 }
/** Refused countries that are closed now; empty when that would close every flight (the lockout never strands the player). Story routes are not affected. */
fun closedFlights(s: CareerSnapshot?, countries: List<Country> = Country.shipped): Set<String> {
    if (s?.act != Act.FREE) return emptySet()
    val open = openFlights(s, countries)
    val closed = open.filter { (s.closed[it.id] ?: 0) > 0 }
    return if (closed.size == open.size) emptySet() else closed.map { it.id }.toSet()
}
fun closedTripsLeft(s: CareerSnapshot?, countryId: String, countries: List<Country> = Country.shipped) =
    if (countryId in closedFlights(s, countries)) s?.closed?.get(countryId) ?: 0 else 0
fun availableFlights(s: CareerSnapshot?, countries: List<Country>) =
    closedFlights(s, countries).let { closed -> openFlights(s, countries).filter { it.id !in closed } }
/** Catalog (shipped countries), not installed packs: removing a pack must never trigger an ending. */
fun badTester(s: CareerSnapshot?, countries: List<Country> = Country.shipped): Boolean {
    if (s?.act != Act.FREE) return false
    if (s.usaCompleted) return false // the earned trip back to Thalassia comes first (restartFree would wipe it), and after it the journey is over
    return unlockedCountries(s, countries).isNotEmpty() && availableFlights(s, countries).isEmpty()
}
fun freeDeparture(s: CareerSnapshot?, identity: Identity) =
    s?.lastPassedCountry?.let(Country::find)?.nation ?: identity.nationality.name
/** The flight leaves from the last country's main airport city (its visits to a main airport name the same city). */
fun departureCity(nation: String, s: CareerSnapshot? = null) = Country.all.firstOrNull { it.nation == nation }?.let { c ->
    AIRPORT_CITY[c.airports.firstOrNull { it.id == s?.lastPassedAirport && c.id == s.lastPassedCountry }?.id ?: c.airports.first().id] }
    ?: TRIP_CITIES[nation]?.firstOrNull()?.substringBefore('|') ?: nation
fun freshFreeCareer() = CareerSnapshot(act = Act.FREE)

fun trainingLanguage(id: Identity, installed: Set<String>): String? =
    id.nationality.languages.firstOrNull()?.takeIf { it in installed } ?: installed.firstOrNull()

/** Only itinerary topics can replace a suggestion; greetings, identity and implant probes cannot. */
fun recallHints(options: List<Hint>, perks: ImplantPerks, topic: String?, story: Backstory, lang: String,
    factText: (FactId) -> String = { story.fact(it, lang) }): List<Hint> {
    if (!perks.recall || topic == null || topic !in OFFICER_TOPICS) return options
    val fact = topicFact(topic)
    if (fact !in setOf(FactId.FROM, FactId.PURPOSE, FactId.DAYS, FactId.STAY, FactId.HOST) || fact !in story.factIds()) return options
    return listOf(Hint(factText(fact), "", "")) + options.drop(1).take(2)
}

/** Only machine sessions train a language; desk clears and the passport language do not. */
internal fun newLanguageBonuses(s: CareerSnapshot?, airports: List<Airport>): List<String> =
    if (s?.act != Act.FREE) emptyList()
    else airports.map { it.lang }.distinct().filter { it !in s.trainedLanguages }

internal fun flaggedClears(s: CareerSnapshot?, countryId: String) =
    if (s?.act == Act.FREE) s.flagged[countryId] ?: 0 else 0

/** Use the catalog, just like the ending: an uninstalled pack is not a lost escape route. */
internal fun lastFlightWarning(s: CareerSnapshot?, countries: List<Country> = Country.shipped): Boolean {
    if (s?.act != Act.FREE) return false
    val last = availableFlights(s, countries).singleOrNull() ?: return false
    return badTester(s.copy(flagged = s.flagged + (last.id to 3)), countries)
}

/** The first released free-act USA machine session ([Ending.usaReleased]).
 * It is committed atomically with its receipt ([CareerSnapshot.usaCompleted]). Keeping the ending pending until acknowledgement also recovers the message after process death.
 */
internal fun usaCompletionPending(s: CareerSnapshot?): Boolean {
    val receipt = s?.receipt ?: return false
    val ending = receipt.ending ?: return false
    return s.act == Act.FREE && ending.usaReleased // act guard: a stale receipt must not announce the ending outside act 2
}

/** The USA exam's extra start index: half the 8-question room's 25, as the room was halved (owner 2026-10-19). */
internal const val USA_ASSESSMENT_OFFSET = 12.5
internal fun assessmentOffset(act: Act, countryId: String, story: Boolean, training: Boolean) =
    if (act == Act.FREE && countryId == "usa" && !story && !training) USA_ASSESSMENT_OFFSET else 0.0

/** Act 2's desk PASS earns nothing: the result says so instead of "Convincing". */
internal fun tooCleanPass(act: Act, story: Boolean, training: Boolean, inRoom: Boolean, decision: Decision?) =
    act == Act.FREE && !story && !training && !inRoom && decision == Decision.PASS

/** Marek's second visit: act 2, after a release, once the "why" scene was seen and every installed language has been passed
 * in a machine session ([CareerSnapshot.trainedLanguages]); once per career, never on the USA final test. */
internal fun visitSceneDue(s: CareerSnapshot?, act: Act, decision: Decision?, usaFinal: Boolean, training: Boolean, story: Boolean,
    installed: Set<String>) =
    s != null && !s.walkedAway && act == Act.FREE && decision == Decision.RELEASED && !usaFinal && !training && !story &&
        s.whySceneSeen && !s.visitSceneSeen && installed.isNotEmpty() && s.trainedLanguages.containsAll(installed)

/** The "why" scene: act 2, after a release (not a refusal) that brings the career's released machine sessions
 * ([CareerSnapshot.machineSessions], which counts only releases) to 2 or more, once per career, never on the USA final test. */
internal fun whySceneDue(s: CareerSnapshot?, act: Act, decision: Decision?, usaFinal: Boolean, training: Boolean, story: Boolean) =
    s != null && !s.walkedAway && act == Act.FREE && decision == Decision.RELEASED && !usaFinal && !training && !story &&
        s.machineSessions >= 2 && !s.whySceneSeen

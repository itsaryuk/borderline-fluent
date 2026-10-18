package com.lampensoft.borderlinefluent

import kotlin.random.Random
import kotlin.math.roundToInt

/** A span of time for display: [n] of the plurals resource [plural] (days, weeks or months). */
data class Span(val plural: Int, val n: Int)

/** Careers from before the day counter reach Thalassia with 0: they keep the old "five months". */
private const val LEGACY_DAYS = 150

/** [days] away as days (under 14), weeks (under 60, rounded) or months (rounded, 30 days each). */
fun awaySpan(days: Int): Span {
    val d = if (days > 0) days else LEGACY_DAYS
    return when {
        d < 14 -> Span(R.plurals.span_days, d)
        d < 60 -> Span(R.plurals.span_weeks, (d / 7.0).roundToInt())
        else -> Span(R.plurals.span_months, (d / 30.0).roundToInt())
    }
}

/**
 * Whether a trip ends in a home vignette (a caption over the traveller's flat on leaving the summary): every cleared
 * free-play [homecoming] (the trip brought the traveller back to their own country), until the true ending; never any other trip.
 */
fun homeVignetteDue(act: Act?, story: Boolean, training: Boolean, homecoming: Boolean, cleared: Boolean, s: CareerSnapshot?): Boolean =
    homecoming && cleared && act == Act.FREE && !story && !training && s != null && !s.thalassiaSeen

/** A vignette and a bar or hotel encounter never coincide: only a talking first-visit taxi (shown on the result screen) stays beside one. */
fun keepsEncounter(homeDue: Boolean, countryId: String, firstVisit: Boolean): Boolean =
    !homeDue || (firstVisit && countryId !in SILENT_CABS)

/** The flat's art for the player's nationality [nationalityId]: "home_<id>" when [has] it, else "home_flat". */
fun homeArt(nationalityId: String, has: (String) -> Boolean): String = "home_$nationalityId".takeIf(has) ?: "home_flat"

/** Vignette texts; the last takes the day count. */
val HOME_VIGNETTES = listOf(R.string.home_rent, R.string.home_neighbour, R.string.home_job, R.string.home_groceries,
    R.string.home_phone, R.string.home_list)

/** How many recent vignettes are skipped. */
const val RECENT_VIGNETTES = 2

/** One vignette index, never one of the last [RECENT_VIGNETTES] in [recent]. */
fun pickVignette(recent: List<Int>, r: Random): Int =
    HOME_VIGNETTES.indices.filter { it !in recent.takeLast(RECENT_VIGNETTES) }.random(r)

/** [recent] with [picked] moved to the end, at most [RECENT_VIGNETTES]. */
fun rememberVignette(recent: List<Int>, picked: Int): List<Int> = (recent - picked + picked).takeLast(RECENT_VIGNETTES)

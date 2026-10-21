package com.lampensoft.borderlinefluent

/** Presentation only: the store remains the sole source of committed evidence. */
enum class SavePresentation { READY, SAVING, RETRY, READ_ERROR, PREPARATION_ERROR }
fun savePresentation(state: CareerSave?, saving: Boolean): SavePresentation = when {
    saving || state == null -> SavePresentation.SAVING
    state is CareerSave.WriteFailed -> SavePresentation.RETRY
    state is CareerSave.Unreadable -> if (state.read.preparationOnly) SavePresentation.PREPARATION_ERROR else SavePresentation.READ_ERROR
    state is CareerSave.Rejected -> SavePresentation.READ_ERROR
    else -> SavePresentation.READY
}
fun committedMarks(state: CareerSave?, saving: Boolean): List<NewMark> =
    if (saving) emptyList() else (state as? CareerSave.Saved)?.receipt?.newMarks.orEmpty().let(::displayMarks)

/** A unit unlock and its country sabotage unlock describe the same event. Keep the more specific mark. */
fun displayMarks(marks: List<NewMark>): List<NewMark> = marks.distinct().filterNot { mark ->
    mark.kind == "sabotage" && marks.any { it.kind == "unit" && it.countryId == mark.countryId && it.officerId != null }
}
fun displayUnit(id: String): String = id.uppercase(java.util.Locale.ROOT)

/**
 * Machine-test clears of [country] (the picker's PASSED badge); only migrated history without a count shows one. A desk
 * pass is not a clear, and neither is the act-1 home desk, which also lands the home country in the history.
 */
fun passedCount(snapshot: CareerSnapshot?, country: String): Int =
    snapshot?.album?.let { it.clears[country] ?: if (country in it.historicalCleared && country != snapshot.identity?.nationality?.id) 1 else 0 } ?: 0

package com.lampensoft.borderlinefluent

import java.util.Locale

/**
 * The traveller's passport country, independent of which destinations are unlocked.
 * [languages]: the game languages a citizen speaks natively; the first is where their names come from ([NAMES]).
 */
data class Nationality(val id: String, val iso: String, val name: String, val languages: List<String>) {
    val nameLang get() = languages.first()
    fun uiName(ui: String): String =
        Language.uiLocale(ui)?.let { Locale.forLanguageTag("und-$iso").getDisplayCountry(it) } ?: name

    companion object {
        /** Official languages the airports don't already show (Dutch, Kazakh, Irish... aren't game languages). */
        private val SPOKEN = mapOf("switzerland" to listOf("de", "fr", "it"), "belgium" to listOf("fr", "de"),
            "canada" to listOf("en", "fr"))
        val USA = Nationality("usa", "US", "United States", listOf("en"))
        val all = Country.all.map { c -> Nationality(c.id, c.iso, c.name, SPOKEN[c.id] ?: c.airports.map { it.lang }.distinct()) }
        /** What a player can hold ([Country.shipped]): a held-back nationality's home desk speaks a held-back language
         *  ([Language.HELD_BACK]); a saved identity of one is asked for again (CareerJson). [all] stays the catalog. */
        val shipped = all.filter { n -> Country.shipped.any { it.id == n.id } }
        fun find(id: String): Nationality? = all.firstOrNull { it.id == id }
        /** The phone's region as a nationality; null when the region isn't one of ours (the picker opens unselected). */
        fun fromLocale(locale: Locale): Nationality? = locale.country.uppercase().let { r -> shipped.firstOrNull { it.iso == r } }
    }
}

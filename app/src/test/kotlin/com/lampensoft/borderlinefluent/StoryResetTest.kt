package com.lampensoft.borderlinefluent

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** In-memory prefs: a commit applies clear() first, then the edits, atomically (as Android does); [fail] refuses it. */
private class Prefs(vararg init: Pair<String, Any?>) : SharedPreferences {
    val map = mutableMapOf(*init)
    var fail = false
    var commits = 0
    override fun getAll(): Map<String, *> = map.toMap()
    override fun getString(k: String, d: String?) = map[k] as String? ?: d
    override fun getStringSet(k: String, d: Set<String>?) = @Suppress("UNCHECKED_CAST") (map[k] as Set<String>?) ?: d
    override fun getInt(k: String, d: Int) = map[k] as Int? ?: d
    override fun getLong(k: String, d: Long) = map[k] as Long? ?: d
    override fun getFloat(k: String, d: Float) = map[k] as Float? ?: d
    override fun getBoolean(k: String, d: Boolean) = map[k] as Boolean? ?: d
    override fun contains(k: String) = k in map
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        var clear = false
        val ops = mutableListOf<Pair<String, Any?>>() // null value = remove
        fun put(k: String, v: Any?) = apply { ops += k to v }
        override fun putString(k: String, v: String?) = put(k, v)
        override fun putStringSet(k: String, v: Set<String>?) = put(k, v)
        override fun putInt(k: String, v: Int) = put(k, v)
        override fun putLong(k: String, v: Long) = put(k, v)
        override fun putFloat(k: String, v: Float) = put(k, v)
        override fun putBoolean(k: String, v: Boolean) = put(k, v)
        override fun remove(k: String) = put(k, null)
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (fail) return false
            commits++
            if (clear) map.clear()
            for ((k, v) in ops) if (v == null) map.remove(k) else map[k] = v
            return true
        }
        override fun apply() { commit() }
    }
}

class StoryResetTest {
    private val v1 = """{"schemaVersion":1,"counters":{"missions":4,"cleared":3,"streak":1,"best":2,"broken":0,"rooms":1},
        "countries":["de"],"album":{"historicalCleared":["de"],"infiltration":{},"sabotage":{},"units":{}},
        "nextAttemptId":5,"appliedThroughId":4,"pendingAttempt":null,"receipt":null}"""
    private fun settings() = Prefs("finale" to true, "difficulty.de" to 3, "difficulty.ja" to 1, "dest" to "japan",
        "destination" to "de", "setup_passport_done" to true, "camera" to false, "languages" to setOf("de", "ja"),
        "input" to "HOLD", "trained" to true, "music" to false)
    private val kept = mapOf("camera" to false, "languages" to setOf("de", "ja"), "input" to "HOLD", "trained" to true, "music" to false)

    /** The career prefs now hold exactly an empty v2 career, which the store opens as-is. */
    private fun assertFresh(career: Prefs, settings: Prefs) {
        assertEquals(setOf("snapshot", "schemaVersion"), career.map.keys)
        assertEquals(2, career.map["schemaVersion"])
        val read = loadCareer(CareerInput(career.getString("snapshot", null), career.getInt("schemaVersion", 0)))
        assertEquals(CareerRead.Ready(CareerSnapshot()), read)
        assertEquals(kept, settings.map)
        // Setup routes to the passport again, as on a fresh install (the photo file itself stays).
        val done = passportSetupDone(settings.getBoolean("setup_passport_done", false), photo = true)
        assertEquals(Screen.PASSPORT, nextSetupScreen(true, false, done, true))
        assertEquals("random", canonicalDest(settings.getString("dest", null)))
        assertFalse(settings.getBoolean("finale", false))
    }

    @Test fun v1CareerIsDiscardedWithTheStorySettings() {
        val career = Prefs("snapshot" to v1, "schemaVersion" to 1)
        val settings = settings()
        assertEquals(StoryReset.Result.DONE, StoryReset.runIfNeeded(career, settings))
        assertFresh(career, settings)
        assertEquals(StoryReset.Result.NOT_NEEDED, StoryReset.runIfNeeded(career, settings)) // idempotent: the marker stops a second run
        assertEquals(1, career.commits)
    }

    @Test fun legacyCountersAndFreshInstallsGetAnEmptyV2Career() {
        for (career in listOf(Prefs("missions" to 9, "cleared" to 7, "countries" to setOf("grauwald")), Prefs())) {
            val settings = settings()
            assertEquals(StoryReset.Result.DONE, StoryReset.runIfNeeded(career, settings))
            assertFresh(career, settings)
        }
    }

    @Test fun interruptedResetCompletesOnTheNextStart() {
        val career = Prefs("snapshot" to v1, "schemaVersion" to 1)
        val settings = settings()
        career.fail = true // killed (or refused) after the settings commit, before the career's
        assertEquals(StoryReset.Result.FAILED, StoryReset.runIfNeeded(career, settings)) // not reported as done: Game retries, then shows the read error
        assertEquals(1, career.map["schemaVersion"]); assertEquals(kept, settings.map)
        career.fail = false
        settings.map["difficulty.fr"] = 2 // and a partly cleared settings file still gets finished
        assertEquals(StoryReset.Result.DONE, StoryReset.runIfNeeded(career, settings))
        assertFresh(career, settings)
        val failedSettings = settings()
        val untouched = Prefs("snapshot" to v1, "schemaVersion" to 1)
        failedSettings.fail = true // settings refused: the career (and its marker) are not written either
        assertEquals(StoryReset.Result.FAILED, StoryReset.runIfNeeded(untouched, failedSettings))
        assertEquals(1, untouched.map["schemaVersion"])
    }

    @Test fun v2CareersAreNeverWipedEvenUnreadable() {
        for (career in listOf(Prefs("snapshot" to "{", "schemaVersion" to 2), Prefs("schemaVersion" to 2),
            Prefs("snapshot" to "{"), // unparseable with the marker lost: kept (read-only in the store), not taken for v1
            Prefs("snapshot" to CareerJson.encode(CareerSnapshot(counters = CareerCounters(3))))) // marker lost
        ) {
            val before = career.map.toMap()
            val settings = settings()
            assertEquals(StoryReset.Result.NOT_NEEDED, StoryReset.runIfNeeded(career, settings))
            assertEquals(before, career.map); assertEquals(settings().map, settings.map)
        }
        // Unreadable v2 stays read-only in the store: never rewritten.
        assertTrue(loadCareer(CareerInput("{", 2)) is CareerRead.Unreadable)
        assertTrue(StoryReset.needed(1, "{")) // an explicit v1 marker still resets
        assertTrue(StoryReset.needed(null, "{\"schemaVersion\":1}"))
        assertFalse(StoryReset.needed(null, "{"))
        assertTrue(StoryReset.needed("2", null))
    }
}

class NationalityTest {
    @Test fun oneNationalityPerCountryIncludingUsa() {
        assertEquals(Country.all.map { it.id }, Nationality.all.map { it.id })
        assertEquals(Nationality.all.size, Nationality.all.map { it.iso }.toSet().size)
        for (n in Nationality.all) {
            assertTrue(n.id, n.nameLang in NAMES)
            assertTrue(n.id, n.languages.all { l -> Language.all.any { it.code == l } })
        }
        assertEquals(listOf("de", "fr", "it"), Nationality.find("switzerland")!!.languages)
        assertEquals(listOf("en", "fr"), Nationality.find("canada")!!.languages)
        assertEquals(listOf("de"), Nationality.find("austria")!!.languages)
        assertEquals(listOf("en"), Nationality.USA.languages)
        assertEquals("Japan", Nationality.find("japan")!!.uiName("en"))
        assertEquals("Japon", Nationality.find("japan")!!.uiName("fr"))
    }

    @Test fun phoneRegionPicksTheNationality() {
        assertEquals("usa", Nationality.fromLocale(Locale.US)?.id)
        assertEquals("uk", Nationality.fromLocale(Locale.UK)?.id)
        assertEquals("switzerland", Nationality.fromLocale(Locale.forLanguageTag("fr-CH"))?.id)
        assertNull(Nationality.fromLocale(Locale.forLanguageTag("nl-NL")))
        assertNull(Nationality.fromLocale(Locale.ENGLISH))
    }
}

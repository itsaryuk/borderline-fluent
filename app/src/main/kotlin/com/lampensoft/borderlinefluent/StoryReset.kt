package com.lampensoft.borderlinefluent

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Save v2 starts the story over (owner, 2026-10-07): a v1 or older career is discarded, never migrated, with the
 * story's settings. Camera, languages, UI language and the passport photo file stay. An unreadable v2 career is
 * never wiped (the store keeps it read-only).
 */
object StoryReset {
    const val VERSION = 2
    private val SETTINGS = setOf("finale", "dest", "destination", "setup_passport_done")

    /**
     * Reset only a career that is provably older than v2: no v2 marker and either no snapshot (fresh install or pre-v1
     * counters), a snapshot that parses with another schemaVersion (v1), or an explicit older marker. A snapshot that
     * doesn't parse and has no marker is never wiped: the store keeps it read-only, as an unreadable v2.
     */
    fun needed(marker: Any?, snapshot: Any?): Boolean {
        if (marker == VERSION) return false
        if (snapshot !is String) return true
        val version = runCatching { JSONObject(snapshot).opt("schemaVersion") }.getOrElse { return marker != null }
        return version != VERSION
    }

    fun clears(key: String) = key in SETTINGS || key.startsWith("difficulty.")

    enum class Result { NOT_NEEDED, DONE, FAILED }

    /**
     * Idempotent and blocking (call off the main thread): settings first, then the career with an empty v2 snapshot
     * and its marker in one commit, last. An interrupted or refused reset finds no marker and runs again: [Result.FAILED].
     */
    fun runIfNeeded(career: SharedPreferences, settings: SharedPreferences): Result {
        val c = career.all
        if (!needed(c["schemaVersion"], c["snapshot"])) return Result.NOT_NEEDED
        val s = settings.edit()
        settings.all.keys.filter(::clears).forEach { s.remove(it) }
        val done = s.commit() && career.edit().clear().putString("snapshot", CareerJson.encode(CareerSnapshot()))
            .putInt("schemaVersion", VERSION).commit()
        return if (done) Result.DONE else Result.FAILED
    }
}

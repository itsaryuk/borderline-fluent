package com.lampensoft.borderlinefluent

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Difficulty at preparation time; the resulting persona freezes it for the whole attempt. */
fun effectiveLevel(training: Boolean, finaleSeen: Boolean, careerLevel: Int, chosen: Int): Int = when {
    training -> 0
    !finaleSeen -> careerLevel
    else -> chosen.coerceIn(0, 3)
}

/** Contracts unlock at Agent rank after the finale, outside training. */
fun contractsUnlocked(finaleSeen: Boolean, training: Boolean, rankLevel: Int) = finaleSeen && !training && rankLevel >= 1

/** Chance that one retinal display suggestion quietly contradicts the cover at the frozen attempt level. */
fun trapChance(level: Int) = listOf(0.0, 0.15, 0.25, 0.35)[level.coerceIn(0, 3)]

/** Missions, clears and streaks across runs. Rank sets difficulty only during the first story. */
class Career(ctx: Context) {
    private val p = ctx.getSharedPreferences("career", 0)
    val store = CareerStore(
        read = {
            // Snapshot authority means stale (or malformed) legacy keys are never consulted after migration.
            val raw = p.getString("snapshot", null)
            val marker = if (p.contains("schemaVersion")) p.getInt("schemaVersion", 0) else null
            if (raw != null || marker != null) CareerInput(raw, marker) else CareerInput(
                counters = CareerCounters(p.getInt("missions", 0), p.getInt("cleared", 0), p.getInt("streak", 0),
                    p.getInt("best", 0), p.getInt("broken", 0), p.getInt("rooms", 0)),
                countries = p.getStringSet("countries", emptySet())!!.toSet(),
            )
        },
        write = { raw, marker -> p.edit().putString("snapshot", raw).putInt("schemaVersion", marker).commit() },
    )
    var snapshot by mutableStateOf<CareerSnapshot?>(null); private set
    /** Called on Main after the store has acknowledged a write or retained valid read-only history. */
    fun publish() { snapshot = store.acknowledged }
    val missions get() = snapshot?.counters?.missions ?: 0
    val cleared get() = snapshot?.counters?.cleared ?: 0
    val streak get() = snapshot?.counters?.streak ?: 0
    val best get() = snapshot?.counters?.best ?: 0
    val broken get() = snapshot?.counters?.broken ?: 0
    val rooms get() = snapshot?.counters?.rooms ?: 0
    val countries: Set<String> get() = snapshot?.countries.orEmpty()
    val level get() = RANKS.indexOfLast { cleared >= it.second }
    /** The rank name as a string resource id. */
    val rank get() = RANKS[level].first
    /** Clears still needed for the next rank, or null at the top. */
    val toNext get() = RANKS.getOrNull(level + 1)?.let { it.second - cleared }

    /** All callers must supply the frozen attempt event; no unchecked counter-only write remains. */
    suspend fun record(event: AttemptResult, continuation: String? = null): CareerSave = store.settle(event, continuation)

    companion object {
        /** (rank name string resource, clears needed) */
        val RANKS = listOf(R.string.rank_rookie to 0, R.string.rank_agent to 3, R.string.rank_operative to 8, R.string.rank_ghost to 15)
    }
}

/** Short effects over the music: passport scanner, entry stamp, the door to secondary. */
class Sfx(ctx: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val ids = listOf(R.raw.sfx_scan, R.raw.sfx_stamp, R.raw.sfx_door, R.raw.sfx_blink, R.raw.sfx_tell, R.raw.sfx_siren).associateWith { pool.load(ctx, it, 1) }
    var enabled = true
    fun play(res: Int) { if (enabled) ids[res]?.let { pool.play(it, GAIN, GAIN, 1, 0, 1f) } }
    /** The files carry the balance (momentary max: siren -18, stamp/door/scan -20, tell -26, blink -28 LUFS). */
    companion object { const val GAIN = 1f }
    fun release() = pool.release()
}

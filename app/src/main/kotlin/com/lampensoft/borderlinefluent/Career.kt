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
/** Contracts are offered from act 2's first rank (owner, 2026-10-15). */
fun contractsUnlocked(finaleSeen: Boolean, training: Boolean) = finaleSeen && !training

/** Chance that one retinal display suggestion quietly contradicts the cover at the frozen attempt level. */
fun trapChance(level: Int) = listOf(0.0, 0.15, 0.25, 0.35)[level.coerceIn(0, 3)]

/** Career statistics and the implant upgrades that set Free AI difficulty. */
class Career(ctx: Context) {
    private val p = ctx.getSharedPreferences("career", 0)
    val store = CareerStore(
        read = {
            // StoryReset ran first: only a v2 snapshot and its marker remain (legacy counters are gone).
            CareerInput(p.getString("snapshot", null), if (p.contains("schemaVersion")) p.getInt("schemaVersion", 0) else null)
        },
        write = { raw, marker -> p.edit().putString("snapshot", raw).putInt("schemaVersion", marker).commit() },
        legacyFinale = { ctx.getSharedPreferences("settings", 0).getBoolean("finale", false) },
        // snapshot_backup* keys have no reader: kept for manual recovery via run-as; never clean them up as dead keys.
        backup = { raw ->
            val old = raw ?: p.getString("snapshot", null)
            // An earlier backup is never overwritten.
            old == null || p.edit().putString(if (p.contains("snapshot_backup")) "snapshot_backup_${System.currentTimeMillis()}" else "snapshot_backup", old).commit()
        },
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
    /** Granted level: the rank (officer scaling, contracts, points to the next); perks and the build follow [delivered]. */
    val level get() = snapshot?.upgradesGranted ?: 0
    val delivered get() = deliveredUpgrades(snapshot)
    val sessions get() = snapshot?.machineSessions ?: 0
    val points get() = snapshot?.progressPoints ?: 0
    val build get() = implantBuild(delivered)
    val toNext get() = UPGRADE_THRESHOLDS.getOrNull(level)?.let { (it - points).coerceAtLeast(0) }

    /** All callers must supply the frozen attempt event; no unchecked counter-only write remains. */
    suspend fun record(event: AttemptResult, continuation: String? = null): CareerSave = store.settle(event, continuation)

}

/** Short effects over the music: passport scanner, entry stamp, the door to secondary. */
class Sfx(ctx: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val ids = listOf(R.raw.sfx_scan, R.raw.sfx_stamp, R.raw.sfx_door, R.raw.sfx_blink, R.raw.sfx_tell, R.raw.sfx_door_break,
        R.raw.sfx_tap, R.raw.sfx_type, R.raw.sfx_message, R.raw.sfx_boot, R.raw.sfx_fail).associateWith { pool.load(ctx, it, 1) }
    var enabled = true
    fun play(res: Int, gain: Float = GAIN) { if (enabled) ids[res]?.let { pool.play(it, gain, gain, 1, 0, 1f) } }
    /** The files carry the balance (2026-10-15): events peak at -3 dBFS (door_break limited to it, the loudest by mean), scan/tell sit at -12 dB mean
     *  (peaks ~-7) so they stay clear of the music bed and the voice; UI sounds (tap, type, message, boot, fail) peak at -6 dBFS,
     *  tap plays at [TAP_GAIN] and scan at [SCAN_GAIN] (owner: both too loud), type at a quarter of [GAIN] (owner 10-17: half was too loud). [uiSfx] lets composables without a Game play them; it respects [enabled]. */
    companion object { const val GAIN = 1f; const val TAP_GAIN = 0.18f; const val SCAN_GAIN = 0.3f }
    fun release() { if (uiSfx === this) uiSfx = null; pool.release() }
}

/** The live [Sfx], for shared composables (PrimaryButton, GameSwitch, BookingLog); never set in BenchActivity. */
internal var uiSfx: Sfx? = null

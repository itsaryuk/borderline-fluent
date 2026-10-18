package com.lampensoft.borderlinefluent

import android.app.Activity
import android.content.Context
import android.os.StatFs
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Play Asset Delivery. Language packs (lang_xx: officers and destinations) stay as packs and their art is read in
 * place. The core pack (Whisper) is copied into files/models once and then removed, because sherpa-onnx wants plain files.
 */
object Packs {
    val WHISPER = listOf("core_whisper")
    fun lang(code: String) = "lang_$code"

    private fun mgr(ctx: Context) = AssetPackManagerFactory.getInstance(ctx)

    fun installedLanguages(ctx: Context): Set<String> =
        mgr(ctx).packLocations.keys.filter { it.startsWith("lang_") }.map { it.removePrefix("lang_") }.toSet()

    private fun artDirs(ctx: Context) = mgr(ctx).packLocations.values.mapNotNull { it.assetsPath() }.map { File(it, "art") }

    /** Art files from installed language packs, by name without extension. */
    fun art(ctx: Context): Map<String, File> =
        artDirs(ctx).flatMap { it.listFiles().orEmpty().toList() }.associateBy { it.nameWithoutExtension }

    /** A language pack's Piper voices (assets/tts), if the pack is installed and carries any. */
    fun ttsDir(ctx: Context, lang: String): File? = mgr(ctx).getPackLocation(lang(lang))?.assetsPath()?.let { File(it, "tts") }?.takeIf { it.isDirectory }

    /** A language pack's music file (assets/music/[name].ogg), if the pack is installed and carries it. */
    fun musicFile(ctx: Context, lang: String, name: String): File? = mgr(ctx).getPackLocation(lang(lang))?.assetsPath()?.let { File(it, "music/$name.ogg") }?.takeIf { it.isFile }

    fun freeBytes(ctx: Context) = StatFs(ctx.filesDir.path).availableBytes

    /** Free space [packs] still need. Blocks. */
    fun freeNeeded(ctx: Context, packs: List<String>): Long =
        Tasks.await(mgr(ctx).getPackStates(packs)).let { s -> freeNeeded(s.totalBytes(), s.packStates().values.sumOf { it.bytesDownloaded() }) }

    /** Each pack lands on disk, then is copied into files/models (both at once), plus 200 MB slack; what's downloaded is already there. */
    fun freeNeeded(total: Long, downloaded: Long) = 2 * total - downloaded + 200_000_000L

    data class Progress(val done: Long, val total: Long, val status: Int)

    /**
     * Fetches [packs] and suspends until every one is installed or one fails. Returns null on success, otherwise a
     * reason. [onConfirm] fires when Play needs the player's OK (large download on mobile data).
     */
    suspend fun fetch(ctx: Context, packs: List<String>, onProgress: (Map<String, Progress>) -> Unit, onConfirm: () -> Unit): String? {
        val m = mgr(ctx)
        val states = mutableMapOf<String, Progress>()
        return suspendCancellableCoroutine { k ->
            lateinit var l: AssetPackStateUpdateListener
            // Only our own listener: another fetch may be waiting on the same manager.
            fun settle(reason: String?) { if (k.isActive) { m.unregisterListener(l); k.resume(reason) } }
            fun see(s: AssetPackState) {
                if (s.name() !in packs) return
                states[s.name()] = Progress(s.bytesDownloaded(), s.totalBytesToDownload(), s.status())
                onProgress(states.toMap())
                when (s.status()) {
                    AssetPackStatus.FAILED -> settle(ctx.getString(R.string.pack_failed, s.errorCode()))
                    AssetPackStatus.CANCELED -> settle(ctx.getString(R.string.pack_cancelled))
                    AssetPackStatus.REQUIRES_USER_CONFIRMATION, AssetPackStatus.WAITING_FOR_WIFI -> onConfirm()
                }
                if (packs.all { states[it]?.status == AssetPackStatus.COMPLETED || m.getPackLocation(it) != null }) settle(null)
            }
            l = AssetPackStateUpdateListener { see(it) }
            m.registerListener(l)
            k.invokeOnCancellation { m.unregisterListener(l) }
            m.fetch(packs).addOnSuccessListener { st -> st.packStates().values.forEach(::see) }
                .addOnFailureListener { settle(ctx.getString(R.string.pack_not_started, it.message)) }
        }
    }

    fun confirm(activity: Activity) { mgr(activity).showConfirmationDialog(activity) }

    /** Copies the fetched core models out of [packs] into files/models and frees them. Returns files still missing. */
    fun installCore(ctx: Context, packs: List<String>): List<String> {
        val m = mgr(ctx)
        val out = Models.dir(ctx).apply { mkdirs() }
        fun models(pack: String) = m.getPackLocation(pack)?.assetsPath()?.let { File(it, "models") }
        // Via .tmp and a size check: a kill mid-copy must not leave a truncated model that counts as installed.
        models("core_whisper")?.listFiles()?.filter { it.name.startsWith("turbo-") }?.forEach { src ->
            val tmp = src.copyTo(File(out, "${src.name}.tmp"), overwrite = true)
            if (tmp.length() == src.length()) tmp.renameTo(File(out, src.name)) else tmp.delete()
        }
        val want = if ("core_whisper" in packs) Models.WHISPER else emptyList()
        val missing = want.filterNot { Models.has(ctx, listOf(it)) }
        if (missing.isEmpty()) packs.forEach { runCatching { Tasks.await(m.removePack(it)) } }
        return missing
    }
}

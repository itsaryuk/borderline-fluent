package com.lampensoft.borderlinefluent

import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NanoOnlyTest {
    @Test fun probeReturnsTheFirstAnswer() = runBlocking {
        var calls = 0
        val st = probeNano(backoffMs = 0, log = {}) { if (++calls < 3) error("busy") else 1 }
        assertEquals(1, st); assertEquals(3, calls)
    }

    @Test fun probeThatAlwaysThrowsIsUnknownNotUnavailable() = runBlocking {
        var calls = 0
        assertEquals(NANO_UNKNOWN, probeNano(backoffMs = 0, log = {}) { calls++; error("not bound") })
        assertEquals(3, calls)
    }

    @Test fun awaitNanoTrustsTheStatusNotTheDownloadFlow() {
        var told = 0
        fun run(vararg sts: Int): Pair<Boolean, Int> {
            var i = 0; var downloads = 0; told = 0
            val ok = runBlocking { awaitNano({}, waitMs = 0, check = { sts[minOf(i++, sts.size - 1)] }, download = { downloads++ }, waiting = { told++ }) }
            return ok to downloads
        }
        // "Completed" but still fetching, then an AICore restart: wait it out
        assertEquals(true to 1, run(FeatureStatus.DOWNLOADING, NANO_UNKNOWN, FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING, FeatureStatus.AVAILABLE))
        assertEquals(1, told) // several DOWNLOADING statuses: told once
        run(FeatureStatus.AVAILABLE); assertEquals(0, told)
        assertEquals(false to 3, run(FeatureStatus.DOWNLOADABLE)) // re-downloads capped at 3
        assertEquals(false to 0, run(*IntArray(10) { NANO_UNKNOWN })) // 10 unknowns in a row give up
        assertEquals(true to 0, run(*IntArray(9) { NANO_UNKNOWN } + FeatureStatus.DOWNLOADING + IntArray(9) { NANO_UNKNOWN } + FeatureStatus.AVAILABLE))
        assertEquals(false to 0, run(FeatureStatus.UNAVAILABLE))
    }

    @Test fun freeNeededCountsWhatIsAlreadyDownloaded() {
        assertEquals(2_200_000_000L, Packs.freeNeeded(1_000_000_000L, 0))
        assertEquals(1_200_000_000L, Packs.freeNeeded(1_000_000_000L, 1_000_000_000L))
    }
}

class AsrProbeTest {
    @Test fun aThrowingSpeechProbeIsUnknownAndNeverNeedsWhisper() = runBlocking {
        val st = probeNano(backoffMs = 0, name = "asr pt", log = {}) { error("AICore not bound") }
        val probe = mapOf("pt" to asrKnown(st), "ar" to asrKnown(com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE),
            "de" to asrKnown(com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE))
        assertEquals(null, probe["pt"])
        assertEquals(false, slowLang(probe, "pt")) // unknown: not slow, no Whisper
        assertEquals(true, slowLang(probe, "ar")) // only a returned UNAVAILABLE needs Whisper
        assertEquals(false, slowLang(probe, "de"))
    }

    @Test fun portugueseListensAsBrazilianInBasicMode() {
        assertEquals("pt-BR", Nano.asrLocale("pt").toLanguageTag())
        assertEquals("pt-PT", Nano.asrLocale("pt", advanced = true).toLanguageTag())
        assertEquals("de-DE", Nano.asrLocale("de", advanced = true).toLanguageTag())
    }
}

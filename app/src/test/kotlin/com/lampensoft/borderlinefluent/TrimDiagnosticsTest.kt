package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** "Save recordings and logs" stays bounded: results.log keeps whole trailing lines, wavs/ the newest files. */
class TrimDiagnosticsTest {
    @Test fun trimsLogToTrailingLinesAndKeepsNewestWavs() {
        val dir = Files.createTempDirectory("trim").toFile()
        try {
            val log = File(dir, "results.log")
            log.writeText((1..200).joinToString("") { "line %03d\n".format(it) }) // 9 bytes a line, 1800 in all
            val wavs = File(dir, "wavs").apply { mkdirs() }
            (1..5).forEach { File(wavs, "de_rec_$it.wav").apply { writeText("x"); setLastModified(it * 10_000L) } }
            val bench = listOf("de_espeak_01.wav", "de_espeak_01.txt").map { File(wavs, it).apply { writeText("x"); setLastModified(1) } }

            trimDiagnostics(dir, logMax = 1000, logKeep = 95, wavsKeep = 3).get()

            val lines = log.readLines()
            assertEquals("line 191", lines.first()) // 95 bytes cut mid-line: the partial line is dropped
            assertEquals("line 200", lines.last())
            // Only the game's own recordings are capped: bench clips, however old, stay.
            assertEquals(setOf("de_rec_3.wav", "de_rec_4.wav", "de_rec_5.wav") + bench.map { it.name }, wavs.list()!!.toSet())

            trimDiagnostics(dir, logMax = 1000, logKeep = 95, wavsKeep = 3).get() // under the cap: untouched
            assertEquals(lines, log.readLines())
        } finally { dir.deleteRecursively() }
    }

    /** Turning "Save recordings and logs" off deletes the log and the game's recordings, never bench clips. */
    @Test fun clearDeletesLogAndOwnRecordingsOnly() {
        val dir = Files.createTempDirectory("clear").toFile()
        try {
            appendDiagnostic(dir, "line").get()
            val wavs = File(dir, "wavs").apply { mkdirs() }
            listOf("ja_rec_1791151299630.wav", "de_nospeech_1791151299630.wav", "de_espeak_01.wav", "de_espeak_01.txt")
                .forEach { File(wavs, it).writeText("x") }
            clearDiagnostics(dir).get()
            assertFalse(File(dir, "results.log").exists())
            assertEquals(setOf("de_espeak_01.wav", "de_espeak_01.txt"), wavs.list()!!.toSet())
        } finally { dir.deleteRecursively() }
    }

    /** One writer: lines keep their order around a trim and a clear, none lost to the trim, none before the clear kept. */
    @Test fun appendsTrimAndClearRunInOrder() {
        val dir = Files.createTempDirectory("order").toFile()
        try {
            val log = File(dir, "results.log")
            (1..200).forEach { appendDiagnostic(dir, "line %03d".format(it)) }
            trimDiagnostics(dir, logMax = 1000, logKeep = 95, wavsKeep = 3)
            appendDiagnostic(dir, "after trim").get()
            assertEquals((191..200).map { "line %03d".format(it) } + "after trim", log.readLines())

            appendDiagnostic(dir, "before clear")
            clearDiagnostics(dir)
            appendDiagnostic(dir, "after clear").get()
            assertEquals(listOf("after clear"), log.readLines())
        } finally { dir.deleteRecursively() }
    }

    /** Consent is read when the line is written: a line queued before the player opted out never lands. */
    @Test fun appendRechecksConsentWhenItRuns() {
        val dir = Files.createTempDirectory("consent").toFile()
        try {
            val gate = java.util.concurrent.CountDownLatch(1)
            diagnosticsTask { gate.await() } // the writer is busy (a long trim, say)
            var consent = true
            val queued = appendDiagnostic(dir, "transcript") { consent }
            consent = false
            gate.countDown()
            queued.get()
            assertFalse(File(dir, "results.log").exists())
        } finally { dir.deleteRecursively() }
    }
}

package com.lampensoft.borderlinefluent

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ExportRoutingTest {
    private val root = listOf(File("."), File("..")).first { File(it, "art/export.sh").isFile }
    private fun run(script: File, vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(listOf("sh", script.absolutePath) + args).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        return p.waitFor() to output
    }

    @Test fun mappingAndDryRunPreserveOwnership() {
        val script = File(root, "art/export.sh")
        val (code, output) = run(script, "--mapping")
        assertEquals(output, 0, code)
        val lines = output.trim().lines()
        assertEquals(98, lines.size)
        val expected = Country.all.flatMap { it.airports }.associate { it.art to it.lang }
        assertEquals(expected, lines.associate { it.substringBefore('\t') to it.substringAfter('\t') })
        val keys = expected.keys + listOf("officer_uk_xm_sheet", "officer_story_associate_sheet", "travelers_sheet")
        val dry = run(script, "--dry-run", *keys.toTypedArray())
        assertEquals(dry.second, 0, dry.first)
        for ((art, lang) in expected) assertTrue(dry.second.contains("$art\t../packs/lang_$lang/src/main/assets/art/$art.webp"))
        assertTrue(dry.second.contains("officer_uk_xm_sheet\t../packs/lang_uk/"))
        assertTrue(dry.second.contains("officer_story_associate_sheet\t../app/src/main/assets/art/"))
        assertTrue(dry.second.contains("travelers_sheet\t../app/src/main/assets/art/"))
        assertNotEquals(0, run(script, "--dry-run", "dest_unknown").first)
    }

    @Test fun conflictingOrUnparseableCatalogFailsWithoutWrites() {
        val temp = Files.createTempDirectory("export-routing").toFile()
        try {
            val script = File(temp, "art/export.sh").apply { parentFile.mkdirs(); writeText(File(root, "art/export.sh").readText()) }
            val catalog = File(temp, "app/src/main/kotlin/com/lampensoft/borderlinefluent/Script.kt").apply { parentFile.mkdirs() }
            val original = File(root, "app/src/main/kotlin/com/lampensoft/borderlinefluent/Script.kt").readText()
            for (suffix in listOf("\nAirport(\"bad\", \"de\", \"Conflict\", \"dest_fr\", listOf())", "\nAirport(\"bad\", languageVariable)")) {
                catalog.writeText(original + suffix)
                val result = run(script, "--mapping")
                assertNotEquals(result.second, 0, result.first)
                assertFalse(File(temp, "packs").exists())
            }
            catalog.writeText(original)
            assertEquals(0, run(script, "--dry-run", "dest_fr").first)
            assertFalse(File(temp, "packs").exists())
            // A bad destination must fail preflight even after a valid raw PNG.
            File(temp, "art/raw").mkdirs()
            File(temp, "art/raw/dest_fr.png").writeText("not an image")
            File(temp, "art/raw/dest_unknown.png").writeText("not an image")
            val result = run(script, "--dry-run")
            assertTrue(result.second, result.second.contains("Unknown destination asset"))
            assertNotEquals(0, result.first)
            assertFalse(File(temp, "packs").exists())
        } finally { temp.deleteRecursively() }
    }
}

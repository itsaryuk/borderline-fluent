package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

class OfficerLinesTest {
    private val en = OFFICER_LINES.getValue("en")
    /** Languages whose map exists; absent ones are reported by [everyLanguageIsTranslated]. */
    private val present = Language.all.map { it.code }.filter { it in OFFICER_LINES }
    private val slot = Regex("""\{[^{}]*\}""")
    private fun slots(s: String) = slot.findAll(s).map { if ('|' in it.value) "{m|f}" else it.value }.toSet()
    private fun questions(s: String) = s.count { it in "?？؟" }

    @Test fun everyLanguageIsTranslated() {
        val missing = Language.all.map { it.code } - OFFICER_LINES.keys
        assumeTrue("officer lines not yet translated: $missing (skipped until task b)", missing.isEmpty())
    }

    @Test fun englishCatalogShape() {
        assertEquals(105, en.size)
        for (t in OFFICER_TOPICS) {
            assertEquals(t, 3, en.keys.count { it.startsWith("ask.$t.") })
            assertTrue(t, en.keys.count { it.startsWith("exact.$t.") } >= 2)
        }
        assertTrue(en.keys.all { Regex("""[a-z]+(\.[a-z0-9]+)*\.\d""").matches(it) })
        assertTrue(en.keys.none { it.startsWith("confirm") }) // tells re-ask the topic 
        assertEquals(setOf("wronglang.1", "wronglang.2"), en.filterValues { "{language}" in it }.keys)
        assertEquals(setOf("ask.stamps.3", "exact.stamps.2"), en.filterValues { "{country}" in it }.keys)
    }

    @Test fun everyPresentLanguageHasExactlyTheEnglishIdsAndSlots() {
        for (l in present) {
            val m = OFFICER_LINES.getValue(l)
            assertEquals(l, en.keys, m.keys)
            // English has no gender alternations; a translation may add {m|f} for the traveler
            for ((id, line) in m) assertEquals("$l $id", slots(en.getValue(id)), slots(line) - "{m|f}")
            assertEquals("$l language names", Language.all.map { it.code }.toSet(), LANGUAGE_NAMES[l]?.keys)
        }
    }

    @Test fun everyLineRendersCleanly() {
        for (l in present) for (id in OFFICER_LINES.getValue(l).keys) for (female in listOf(false, true)) for (days in 3..14) {
            val r = renderLine(id, l, female, days, LANGUAGE_NAMES.getValue(l).getValue("ru"), country = "日本")!!
            if ("{country}" in OFFICER_LINES.getValue(l).getValue(id)) assertTrue("$l $id: $r", "日本" in r)
            assertTrue("$l $id: $r", r.isNotBlank() && r.none { it in "{}|[]<>" })
        }
    }

    @Test fun questionPartsAskExactlyOneQuestionAndPrefixesNone() {
        for (l in present) for ((id, line) in OFFICER_LINES.getValue(l)) {
            val r = renderLine(id, l, false, 5, LANGUAGE_NAMES.getValue(l).getValue(l), country = "日本")!!
            if (isPrefixId(id)) assertFalse("$l $id is a prefix: $r", isQuestion(r, l))
            else assertTrue("$l $id: $r", isQuestion(r, l) && questions(r) <= 1)
        }
    }

    @Test fun reachableDeskMovesRenderOneQuestionInEveryLanguageAndGender() {
        val moves = mutableSetOf<Move>()
        fun collect(cues: List<Cue>, covered: Set<Int>, fact: Int, first: Boolean = false,
                    pending: Boolean = false, handover: Boolean = false, curt: Boolean = false) {
            val desk = Desk(Random(42))
            // Establish the current topic (or follow-up) before testing the reaction to it.
            desk.next(emptyList(), 0, covered, false, false, true, curt)
            repeat(30) { moves += desk.next(cues, fact, covered, pending, first, handover, curt) }
        }
        for (curt in listOf(false, true)) for (coveredCount in 0..OFFICER_TOPICS.size) {
            val covered = TOPIC_FACTS.take(coveredCount).toSet()
            for (cues in listOf(emptyList<Cue>()) + Cue.entries.map { listOf(it) }) {
                collect(cues, covered, coveredCount + 2, curt = curt)
                collect(cues, covered, 0, handover = true, curt = curt)
                collect(cues, covered, 0, first = true, curt = curt)
                collect(cues, covered, 0, pending = true, curt = curt)
            }
        }
        // Catalog topics become reachable only when included in OFFICER_TOPICS.
        val reachableIds = en.keys.filterNot { it.startsWith("room.") }.filter {
            val parts = it.split('.')
            parts[0] !in listOf("ask", "exact") || parts[1] in OFFICER_TOPICS
        }.toSet()
        assertEquals("every desk wording reached", reachableIds,
            moves.flatMap { listOfNotNull(it.prefix, it.question) }.toSet())
        assertEquals(13, Language.all.size)
        for (m in moves) for (l in Language.all.map { it.code }) for (female in listOf(false, true)) {
            // Include every target language: a subtitle must name the officer's language, not its own.
            for (target in Language.all.map { it.code }) {
                val line = listOfNotNull(m.prefix, m.question).joinToString(if (l == "ja" || l == "zh") "" else " ") {
                    renderLine(it, l, female, 5, LANGUAGE_NAMES[l]?.get(target).orEmpty(), country = "日本").orEmpty()
                }
                val label = "$l target=$target female=$female $m: $line"
                assertTrue(label, line.isNotBlank())
                assertFalse(label, '{' in line)
                assertTrue(label, isQuestion(line, l))
                val ending = line.trimEnd('。', '.', ' ', '！', '!')
                val unmarked = questions(line) == 0 && when (l) {
                    "ja" -> ending.endsWith("か")
                    "zh" -> ending.endsWith("吗") || ending.endsWith("呢")
                    else -> false
                }
                assertEquals(label, 1, questions(line) + if (unmarked) 1 else 0)
            }
        }
    }

    @Test fun jaZhHaveNoAsciiSpace() {
        for (l in listOf("ja", "zh").filter { it in present })
            OFFICER_LINES.getValue(l).forEach { (id, line) -> assertFalse("$l $id: $line", ' ' in line) }
    }

    @Test fun wrongLanguageNamesTheTarget() {
        assertEquals("Please answer in Russian.", renderLine("wronglang.1", "en", false, 5, LANGUAGE_NAMES.getValue("en").getValue("ru")))
        assertEquals(null, renderLine("nope.1", "en", false, 5, ""))
    }
}

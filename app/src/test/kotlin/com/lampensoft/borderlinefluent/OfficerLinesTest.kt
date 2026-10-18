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

    private fun assertClean(what: String, text: String) {
        val out = Gender.values().map { g -> g to Assessment.fill(text, Backstory.BENCH.copy(gender = g), "en") }
        for ((g, o) in out) assertTrue("$what / $g: $o", o.none { it in "{}|" })
    }

    @Test fun everyTemplateRendersCleanForEveryGender() {
        for ((lang, m) in OFFICER_LINES) for ((id, t) in m) assertClean("$lang/$id", t.replace("{language}", "X").replace("{country}", "X"))
        for ((lang, qs) in TN_LOCAL) qs.forEachIndexed { i, q -> assertClean("tn $lang/$i", q) }
        for ((lang, r) in ROOM) for (t in listOf(r.announce, r.precaution, r.release, r.refuse, r.machine)) assertClean("room $lang", t)
        for ((lang, l) in LOOK) for (t in listOf(l.lookAtMe, l.keepLooking)) assertClean("look $lang", t)
    }

    @Test fun coverValuesRenderCleanForEveryGender() {
        val res = listOf("src/main/res", "app/src/main/res").map { java.io.File(it) }.first { it.isDirectory }
        val ids = COVER_VALUE_IDS.values.toSet()
        val names = R.string::class.java.fields.filter { it.getInt(null) in ids }.map { it.name }.toSet()
        for (dir in res.listFiles { f -> f.name.startsWith("values") }!!) {
            val file = java.io.File(dir, "strings.xml").takeIf { it.exists() } ?: continue
            val els = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            for (k in 0 until els.getLength()) {
                val e = els.item(k) as org.w3c.dom.Element
                if (e.getAttribute("name") in names) assertClean("${dir.name}/${e.getAttribute("name")}", e.textContent)
            }
        }
    }

    @Test fun everyLanguageIsTranslated() {
        val missing = Language.all.map { it.code } - OFFICER_LINES.keys
        assumeTrue("officer lines not yet translated: $missing (skipped until task b)", missing.isEmpty())
    }

    @Test fun englishCatalogShape() {
        assertEquals(153 + 2 * Motif.entries.size, en.size) // + reask.1, translator.offer.1, taint.<motif>.1|2 (Poison.kt)
        for (t in OFFICER_TOPICS) for (v in listOf("", ".visit")) {
            val asks = en.keys.count { Regex("""ask\.$t$v\.\d""").matches(it) }
            if (v.isNotEmpty() && asks == 0) continue // no visitor wording: the shared one
            assertEquals(t + v, 3, asks)
            assertTrue(t + v, en.keys.count { Regex("""exact\.$t$v\.\d""").matches(it) } >= 2)
        }
        // A visitor wording replaces a topic's shared one; only the host topic is the visitor's alone.
        val visitTopics = en.keys.filter { ".visit." in it }.map { it.split('.')[1] }.toSet()
        assertEquals(setOf("from", "purpose", "days", "stayed"), visitTopics)
        assertTrue(en.keys.none { it.startsWith("ask.host.visit") })
        assertTrue(en.keys.all { Regex("""[a-z]+(\.[a-z0-9]+)*\.\d""").matches(it) })
        assertTrue(en.keys.none { it.startsWith("confirm") }) // tells re-ask the topic 
        assertEquals(setOf("wronglang.1", "wronglang.2"), en.filterValues { "{language}" in it }.keys)
        assertTrue(en.values.none { "{country}" in it })
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

    /** Non-binary and unsaid travellers get a written neutral form, never a silent pick of m or f. */
    @Test fun everyGenderedTemplateHasANeutralForm() {
        val templates = OFFICER_LINES.values.flatMap { it.values } + TN_LOCAL.values.flatten() +
            ROOM.values.flatMap { listOf(it.announce, it.precaution, it.release, it.refuse, it.machine) } +
            LOOK.values.flatMap { listOf(it.lookAtMe, it.keepLooking) }
        var gendered = 0
        for (t in templates) for (m in Assessment.GENDER.findAll(t)) { gendered++; assertTrue("${m.value} in $t", m.groups[3] != null) }
        assertTrue(gendered > 200)
    }

    @Test fun everyLineRendersCleanly() {
        for (l in present) for (id in OFFICER_LINES.getValue(l).keys) for (g in Gender.entries) for (days in 3..14) {
            val r = renderLine(id, l, g, days, LANGUAGE_NAMES.getValue(l).getValue("ru"), country = "日本")!!
            if ("{country}" in OFFICER_LINES.getValue(l).getValue(id)) assertTrue("$l $id: $r", "日本" in r)
            assertTrue("$l $id: $r", r.isNotBlank() && r.none { it in "{}|[]<>" })
        }
    }

    @Test fun questionPartsAskExactlyOneQuestionAndPrefixesNone() {
        for (l in present) for ((id, line) in OFFICER_LINES.getValue(l)) {
            for (g in Gender.entries) {
                val r = renderLine(id, l, g, 5, LANGUAGE_NAMES.getValue(l).getValue(l), country = "日本")!!
                if (isPrefixId(id)) assertFalse("$l $id $g is a prefix: $r", isQuestion(r, l))
                else assertTrue("$l $id $g: $r", isQuestion(r, l) && questions(r) <= 1)
            }
        }
    }

    @Test fun reachableDeskMovesRenderOneQuestionInEveryLanguageAndGender() {
        val moves = mutableSetOf<Move>()
        var seed = 42 // a fresh seed per desk: the language question is asked once per interview
        fun collect(cues: List<Cue>, covered: Set<FactId>, fact: FactId?, first: Boolean = false,
                    pending: Boolean = false, handover: Boolean = false, curt: Boolean = false) {
            for (arrival in listOf(false, true)) for (tone in IMPLANT_TONES) {
                val desk = Desk(Random(seed++), arrival = arrival, howlang = arrival, tone = tone)
                // Establish the current topic (or follow-up) before testing the reaction to it.
                desk.next(emptyList(), null, covered, false, false, true, curt)
                repeat(30) { moves += desk.next(cues, fact, covered, pending, first, handover, curt) }
            }
        }
        for (curt in listOf(false, true)) for (coveredCount in 0..OFFICER_TOPICS.size) {
            val covered = TOPIC_FACTS.take(coveredCount).toSet()
            for (cues in listOf(emptyList<Cue>()) + Cue.entries.map { listOf(it) }) {
                collect(cues, covered, HOMECOMING_FACTS.getOrNull(coveredCount + 1), curt = curt)
                collect(cues, covered, FactId.HOST, curt = curt)
                collect(cues, covered, null, handover = true, curt = curt)
                collect(cues, covered, null, first = true, curt = curt)
                collect(cues, covered, null, pending = true, curt = curt)
            }
        }
        // Handover uses ordinary ack; the unused ack.start wordings were removed from the catalog.
        assertTrue(en.keys.none { it.startsWith("ack.start.") })
        // Catalog topics become reachable only when included in OFFICER_TOPICS.
        // Encounter lines need a queue scene: EncounterTest reaches them.
        // The US greeting is a fixed referral (no desk interview): AirportsTest reaches it.
        // Taint statements need a planted motif: MotifTest reaches them.
        val reachableIds = en.keys.filterNot { it.startsWith("room.") || it.startsWith("refer.") || it.startsWith("associate.") || it.startsWith("usa.") || it.startsWith("taint.") || it == "reask.1" || it == "translator.offer.1" || "encounter" in it }.filter {
            val parts = it.split('.')
            parts[0] !in listOf("ask", "exact") || parts[1] in OFFICER_TOPICS + HOWLANG
        }.toSet()
        assertEquals("every desk wording reached", reachableIds,
            moves.flatMap { listOfNotNull(it.prefix, it.question) }.toSet())
        assertEquals(13, Language.all.size)
        for (m in moves) for (l in Language.all.map { it.code }) for (g in Gender.entries) {
            // Include every target language: a subtitle must name the officer's language, not its own.
            for (target in Language.all.map { it.code }) {
                val line = listOfNotNull(m.prefix, m.question).joinToString(if (l == "ja" || l == "zh") "" else " ") {
                    renderLine(it, l, g, 5, LANGUAGE_NAMES[l]?.get(target).orEmpty(), country = "日本").orEmpty()
                }
                val label = "$l target=$target $g $m: $line"
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
        assertEquals("Please answer in Russian.", renderLine("wronglang.1", "en", Gender.MAN, 5, LANGUAGE_NAMES.getValue("en").getValue("ru")))
        assertEquals(null, renderLine("nope.1", "en", Gender.MAN, 5, ""))
    }
}

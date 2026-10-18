package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class MachineSessionTest {
    @Test fun fixedFallbacksCoverEveryDestinationAndUiLanguageWithReadings() {
        assertEquals(13, ROOM_FALLBACK.size)
        assertEquals(Language.all.map { it.code }.toSet(), ROOM_FALLBACK.keys)
        assertEquals(Language.all.filter { it.romanize != null }.map { it.code }.toSet(), ROOM_FALLBACK_READINGS.keys)
        for (language in Language.all) for (ui in Language.all) {
            val hints = roomFallback(language.code, ui.code)
            assertEquals(2, hints.size)
            assertEquals(2, hints.map { it.target }.distinct().size)
            hints.forEachIndexed { i, hint ->
                assertEquals(ROOM_FALLBACK.getValue(language.code)[i], hint.target)
                assertEquals(ROOM_FALLBACK.getValue(ui.code)[i], hint.english)
                assertTrue(hint.target.isNotBlank())
                assertTrue(hint.english.isNotBlank())
                assertEquals(language.romanize != null, hint.reading.isNotBlank())
                assertFalse(hint.target.contains("Free AI"))
                // These phrases avoid gendered constructions entirely, so no binary-only templates can slip in.
                assertFalse(hint.target.contains('{'))
            }
        }
    }

    @Test fun unsupportedSystemLocaleUsesTheEnglishUiFallback() {
        assertEquals(roomFallback("ja", "en"), roomFallback("ja", "nl"))
    }

    @Test fun fallbacksSurviveDestructiveJamsButRespectUnaidedAndTheDesk() {
        for (jam in listOf(null) + Jam.entries) {
            assertEquals(jam == Jam.GARBLE || jam == Jam.DROP, roomFallbackVisible(true, jam, false))
            assertFalse(roomFallbackVisible(false, jam, false))
            assertFalse(roomFallbackVisible(true, jam, true))
        }
    }

    @Test fun denialShowsOnlyOnProbesAndRepeatAlways() {
        for (language in Language.all) {
            val lines = ROOM_FALLBACK.getValue(language.code)
            assertEquals(listOf(lines[0], lines[1]), roomFallback(language.code, "en", true).map { it.target })
            assertEquals(listOf(lines[1]), roomFallback(language.code, "en", false).map { it.target })
            assertTrue(isRepeatRequest(lines[1], language.code))
            assertTrue(isRepeatRequest(" " + lines[1].uppercase().trimEnd('?', '.', '。', '？') + "! ", language.code))
            assertFalse(isRepeatRequest(lines[0], language.code))
            assertFalse(isRepeatRequest("", language.code))
        }
        val a = Assessment(0.5, Backstory.BENCH, kotlin.random.Random(1), evidence = 1.0)
        val probeAt = a.seeds.indexOfFirst(::isProbe)
        repeat(probeAt) { a.next() }
        assertTrue(a.probing)

    }

    @Test fun repeatRequestIsUnscoredOnceThenFollowsTheSilencePath() {
        val a = Assessment(0.5, Backstory.BENCH, kotlin.random.Random(1))
        val index = a.index
        assertTrue(a.onEmpty(press = false)) // first repeat: same question, no score
        assertEquals(index, a.index, 0.0)
        assertEquals(0, a.asked)
        assertFalse(a.pressed)
        assertFalse(a.onEmpty(press = false)) // second: silence (+8, next question)
        assertEquals(index + 8, a.index, 0.0)
        assertEquals(1, a.asked) // moved on, as for silence
    }

    @Test fun evasiveFeelingEarnsNoCreditAndIsNotCounted() {
        val base = check(feeling = true)
        assertTrue(base.credited)
        assertFalse(check(feeling = true, evasive = true).credited)
        assertFalse(check(feeling = true, odd = true).credited)
        fun delta(c: RoomCheck): Double {
            val a = Assessment(0.5, Backstory.BENCH, kotlin.random.Random(1)); val before = a.index
            a.onCheck(c); return a.index - before
        }
        assertEquals(-6.0, delta(base), 0.0)
        assertEquals(11.0, delta(check(feeling = true, evasive = true)), 0.0) // +3 +8, same as an evasive answer without feeling
        assertEquals(delta(check(evasive = true)), delta(check(feeling = true, evasive = true)), 0.0)
        assertEquals(0, machineDebrief(listOf(machineAnswer(check(feeling = true, evasive = true), false, false))).feeling)
        assertEquals(0, machineDebrief(listOf(machineAnswer(base, true, false))).feeling)
        assertEquals(1, machineDebrief(listOf(machineAnswer(base, false, false))).feeling)
        // Off topic: no credit, no evasive penalty, not counted as feeling or as evasive.
        val offTopic = base.copy(offTopic = true)
        assertEquals(3.0, delta(offTopic), 0.0)
        assertEquals(MachineDebrief(0, 0, 0, 0), machineDebrief(listOf(machineAnswer(offTopic, false, false))))
        assertTrue(RoomCheck.parse("TOPIC=no FEELING=yes EVASIVE=no ODD=no").offTopic)
        assertEquals(Admit.NO, RoomCheck.parse("FEELING=no EVASIVE=no ADMIT=no TOPIC=no").admit) // TOPIC never touches ADMIT
    }

    private fun check(feeling: Boolean = false, evasive: Boolean = false, odd: Boolean = false,
                      trick: Boolean = false, admit: Admit = Admit.NO) =
        RoomCheck(feeling, evasive, false, true, 2, trick, odd, admit)

    @Test fun categoryRecordCountsAnswersNotFlagsAndCountsOnlyEligibleAdmissions() {
        val record = listOf(
            machineAnswer(check(evasive = true), false, false),
            machineAnswer(check(evasive = true, odd = true, feeling = true), true, false),
            machineAnswer(check(feeling = true), false, false),
            machineAnswer(check(admit = Admit.YES), false, true),
            machineAnswer(check(admit = Admit.YES), false, true), // confirmation is also a recorded answer
            machineAnswer(check(admit = Admit.YES), false, false), // unsolicited classifier flag
            machineAnswer(check(admit = Admit.YES, trick = true), false, true),
            machineAnswer(check(admit = Admit.UNCLEAR), false, true),
        )
        assertEquals(MachineDebrief(2, 1, 1, 2), machineDebrief(record))
        assertEquals(MachineDebrief(0, 0, 0, 0), machineDebrief(emptyList())) // silence isn't an evasive answer
        assertEquals(MachineDebrief(0, 1, 0, 0), machineDebrief(listOf(machineAnswer(check(), true, false))))
    }

    @Test fun debriefTextComesOnlyFromTheFixedCategoryRecord() {
        val record = listOf(
            MachineAnswer(true, false, false, false), MachineAnswer(true, true, false, false),
            MachineAnswer(false, false, true, false), MachineAnswer(false, false, false, true),
        )
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "values/strings.xml"))
        val keys = mapOf(R.plurals.machine_evasive to "machine_evasive", R.plurals.machine_odd to "machine_odd",
            R.plurals.machine_feeling to "machine_feeling", R.plurals.machine_admissions to "machine_admissions",
            R.plurals.machine_doses to "machine_doses", R.plurals.machine_rejects to "machine_rejects")
        val render = { d: MachineDebrief -> d.text { key, count ->
            val plurals = doc.getElementsByTagName("plurals")
            val entry = (0 until plurals.length).map { plurals.item(it) as Element }
                .first { it.getAttribute("name") == keys.getValue(key) }
            val items = entry.getElementsByTagName("item")
            val form = (0 until items.length).map { items.item(it) as Element }
                .first { it.getAttribute("quantity") == if (count == 1) "one" else "other" }
            String.format(Locale.ENGLISH, form.textContent, count)
        } }
        assertEquals("2 evasive answers\n1 contradictory or odd answer\n1 answer it believed\n1 implant admission", render(machineDebrief(record)))
        assertEquals("2 evasive answers\n1 contradictory or odd answer\n1 answer it believed\n1 implant admission\n2 samples accepted",
            render(machineDebrief(record, doses = 2)))
        assertEquals("0 evasive answers\n0 contradictory or odd answers\n0 answers it believed\n0 implant admissions\n1 sample rejected",
            render(machineDebrief(listOf(MachineAnswer(false, false, false, false, rejected = true)))))
    }
}

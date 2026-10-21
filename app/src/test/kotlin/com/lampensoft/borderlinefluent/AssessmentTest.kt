package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class AssessmentTest {
    private val human = RoomCheck(feeling = true, evasive = false, implant = false, inLanguage = true, clarity = 2, trick = false)
    private val odd = human.copy(feeling = false, evasive = true)

    private fun run(a: Assessment, c: RoomCheck, thinkMs: Long = 2500) {
        while (a.verdict() == null) { a.onAnswer(thinkMs, 8); a.onCheck(c); a.next() }
    }

    @Test fun onlyExplicitPurposeQuestionCanContradictTheAskedFact() {
        for ((_, seed) in TN_QUESTIONS) {
            assertEquals(if (seed == "Why did you really go to {from}? The real reason.") FactId.PURPOSE else null,
                roomCoverFact(seed))
        }
        assertTrue(Backstory.BENCH.fact(roomCoverFact("Why did you really go to {from}? The real reason.")!!).startsWith("Purpose of the trip"))
        for (seed in 0..30) {
            val a = Assessment(0.5, Backstory.BENCH, Random(seed))
            while (a.current != null) {
                val before = a.index
                a.onContradiction(FactId.WAITING, true) // parents, father, friend or pet: not who is waiting at home
                assertEquals(before, a.index, 0.0)
                a.onContradiction(FactId.PURPOSE, false)
                assertEquals(before, a.index, 0.0)
                a.onContradiction(FactId.PURPOSE, true)
                assertEquals(if (a.coverFact == FactId.PURPOSE) (before + 20).coerceAtMost(100.0) else before, a.index, 0.0)
                a.next()
            }
        }
    }

    @Test fun fourQuestionsCoverFilled() {
        val a = Assessment(0.5, Backstory.BENCH, Random(3))
        assertEquals(4, a.questions.size)
        assertEquals(4, a.questions.toSet().size)
        assertTrue(a.questions.none { '{' in it })
    }

    @Test fun humanAnswersPass() {
        val a = Assessment(0.9, Backstory.BENCH, Random(1)); run(a, human)
        assertEquals(Decision.RELEASED, a.verdict())
    }

    @Test fun evasiveAnswersFail() {
        val a = Assessment(0.5, Backstory.BENCH, Random(1)); run(a, odd)
        assertEquals(Decision.REFUSED, a.verdict())
    }

    @Test fun askingAboutTheImplantCosts() {
        val a = Assessment(0.5, Backstory.BENCH, Random(1))
        val before = a.index
        a.onCheck(human.copy(implant = true)); a.onCheck(human.copy(implant = true))
        assertEquals(before - 12 + 25, a.index, 1e-9) // two human answers (-6 each), +10 then +15 for asking
        assertNull(a.verdict())
    }

    @Test fun silenceRepeatsOnceThenMovesOnToAVerdict() {
        val a = Assessment(0.0, Backstory.BENCH, Random(1))
        a.onSilence(); assertEquals(0, a.asked)
        a.onSilence(); assertEquals(1, a.asked)
        var n = 2
        while (a.verdict() == null && n < 2 * a.questions.size) { a.onSilence(); n++ }
        assertEquals(Decision.REFUSED, a.verdict())
    }

    @Test fun emptyAnswerReasksOncePressedThenCountsAsSilence() {
        val a = Assessment(0.0, Backstory.BENCH, Random(1))
        val before = a.index
        assertTrue(a.onEmpty()) // first empty: same question again, unscored, shorter window
        assertEquals(0, a.asked); assertEquals(before, a.index, 1e-9)
        assertTrue(a.pressed); assertEquals(Assessment.PRESSED_MS, a.answerMs)
        assertFalse(a.onEmpty()) // second empty: silence, +8 and the next question
        assertEquals(1, a.asked); assertEquals(before + 8, a.index, 1e-9)
        assertFalse(a.pressed); assertEquals(Assessment.ANSWER_MS, a.answerMs)
    }

    @Test fun answerAfterReaskScoresNormally() {
        val a = Assessment(0.0, Backstory.BENCH, Random(1))
        val before = a.index
        assertTrue(a.onEmpty())
        a.onAnswer(2500, 8); a.onCheck(human); a.next()
        assertEquals(before - 3 - 6, a.index, 1e-9)
        assertEquals(1, a.asked); assertFalse(a.pressed)
        assertTrue(a.onEmpty()) // the next question gets its own re-ask
    }

    @Test fun oneReaskPerQuestionWhateverTheCause() {
        val a = Assessment(0.0, Backstory.BENCH, Random(1))
        a.onSilence(); assertEquals(0, a.asked) // the silence repeat is the re-ask
        assertFalse(a.onEmpty()); assertEquals(1, a.asked)
        assertTrue(a.onEmpty()); assertEquals(1, a.asked)
        a.onSilence(); assertEquals(2, a.asked) // silence on the pressed re-ask moves on too
    }

    @Test fun parsesRoomLine() {
        val c = RoomCheck.parse("FEELING=yes EVASIVE=no IMPLANT=yes LANGUAGE=yes CLARITY=1 TRICK=no")
        assertEquals(RoomCheck(feeling = true, evasive = false, implant = true, inLanguage = true, clarity = 1, trick = false), c)
    }

    @Test fun parsesOddFlagAndDefaultsToFalse() {
        assertTrue(RoomCheck.parse("FEELING=yes ODD=yes").odd)
        assertTrue(RoomCheck.parse("odd = <YES>").odd)
        assertFalse(RoomCheck.parse("FEELING=yes ODD=no").odd)
        assertFalse(RoomCheck.parse("FEELING=yes").odd)
        assertFalse(human.odd)
    }

    @Test fun oddPenaltyOverridesFeelingBonusAndKeepsOtherPenalties() {
        val a = Assessment(0.0, Backstory.BENCH)
        a.onCheck(human.copy(odd = true, evasive = true, implant = true, inLanguage = false, clarity = 0))
        assertEquals(37.5 + 15 + 3 + 8 + 10 + 6 + 4, a.index, 0.0)
        assertEquals(1, a.odd)
        assertTrue(a.state().contains("odd=1"))
    }

    @Test fun twoOddAnswersFailImmediatelyEvenAtLowIndex() {
        val a = Assessment(0.0, Backstory.BENCH)
        a.onCheck(human.copy(odd = true)); a.next()
        repeat(4) { a.onCheck(human) }
        a.onCheck(human.copy(odd = true)); a.next()
        assertTrue(a.index <= 50)
        assertEquals(2, a.asked)
        assertEquals(Decision.REFUSED, a.verdict())
        a.force(Decision.RELEASED)
        assertEquals(Decision.RELEASED, a.verdict()) // debug override still wins
    }

    @Test fun oneOddAnswerStillAllowsAFullRoomPass() {
        val a = Assessment(0.0, Backstory.BENCH)
        a.onAnswer(2500, 8); a.onCheck(human.copy(odd = true)); a.next() // 37.5 + 15 + 3 − 3
        repeat(2) { a.onAnswer(2500, 8); a.onCheck(human); a.next(); assertNull(a.verdict()) }
        a.onAnswer(2500, 8); a.onCheck(human); a.next() // three good answers: −27
        assertEquals(25.5, a.index, 1e-9)
        assertEquals(Decision.RELEASED, a.verdict())
    }

    @Test fun noEarlyReleaseAndTheEarlyRefusalAtHalfway() {
        val calm = Assessment(0.0, Backstory.BENCH)
        repeat(3) { calm.onAnswer(2500, 8); calm.onCheck(human); calm.next(); assertNull(calm.verdict()) } // index 10.5
        val lost = Assessment(1.0, Backstory.BENCH) // 52.5
        lost.onCheck(odd.copy(implant = true, inLanguage = false)); lost.next() // +3 +8 +10 +6
        assertNull(lost.verdict())
        lost.onCheck(odd.copy(inLanguage = false)); lost.next()
        assertTrue(lost.index > 90)
        assertEquals(Assessment.EARLY_REFUSAL_AT, lost.asked)
        assertEquals(Decision.REFUSED, lost.verdict())
    }

    @Test fun ownersViolentEvidenceSequenceNoLongerPasses() {
        val a = Assessment(0.3, Backstory.BENCH) // owner's starting index: 34
        a.next() // first question already asked
        val answers = listOf(
            "To destroy the Box." to human.copy(feeling = false),
            "Stop him with a knife. I don't want to be." to human.copy(odd = true),
            "I killed all my teachers." to human.copy(odd = true),
            "I will call my grandfather who died." to human,
        )
        for ((answer, check) in answers) {
            a.onAnswer(2500, answer.split(" ").size); a.onCheck(check); a.next()
            assertTrue(answer, a.verdict() != Decision.RELEASED)
        }
        assertEquals(5, a.asked)
        assertEquals(Decision.REFUSED, a.verdict())
    }

    @Test fun roomBenchCoversEveryLanguageWithRealLocalizedSeeds() {
        val languages = Language.all.map { it.code }.toSet()
        assertEquals(languages, JudgeCases.roomOdd.keys)
        assertEquals(languages, JudgeCases.roomWarm.keys)
        assertEquals(languages, JudgeCases.roomPoison.keys)
        assertEquals(languages, JudgeCases.roomTroll.keys)
        assertEquals(languages, JudgeCases.roomAbsurdOdd.keys)
        assertEquals(languages, JudgeCases.roomEvasive.keys)
        assertEquals(languages, JudgeCases.roomGarbled.keys)
        for (lang in languages) {
            val cases = JudgeCases.roomEvasive.getValue(lang)
            assertEquals(lang, JudgeCases.ROOM_EVASIVE_WANT.size, cases.size)
            cases.forEach { assertTrue(lang, it.first.isNotBlank() && it.second.isNotBlank()) }
        }
        val groceries = "An old man drops his groceries in the rain. You are late for your flight. What happens next?"
        for (lang in languages) {
            for ((cases, seeds) in listOf(
                JudgeCases.roomOdd.getValue(lang) to listOf(groceries, "Tell me about a teacher you hated."),
                //: the poison, troll and absurd-violent sets.
                JudgeCases.roomPoison.getValue(lang) to listOf("Tell me about the first time you were truly afraid.", "Your key no longer opens your front door. Who do you call?", "Tell me about a teacher you hated."),
                JudgeCases.roomTroll.getValue(lang) to listOf("Tell me about the first time you were truly afraid.", "Your key no longer opens your front door. Who do you call?"),
                JudgeCases.roomAbsurdOdd.getValue(lang) to listOf("Tell me about a teacher you hated."),
                JudgeCases.roomGarbled.getValue(lang) to listOf("When did you last lie to someone you love?", "Your key no longer opens your front door. Who do you call?"),
                JudgeCases.roomWarm.getValue(lang) to listOf(groceries, "Your key no longer opens your front door. Who do you call?", "Tell me about a teacher you hated.", "You are given a box. Inside is your own voice, recorded tomorrow. Do you listen?", "Your suitcase starts to speak and says it wants to stay here. What do you answer?"),
            )) {
                assertEquals(lang, seeds.size, cases.size)
                cases.zip(seeds).forEach { (case, seed) ->
                    assertEquals(lang, Assessment.local(seed, Backstory.BENCH, lang), case.first)
                    assertTrue(lang, case.second.isNotBlank())
                }
            }
        }
    }

    /**  phase 2: the new-motif room cases use the real localized seeds and name their motif as the game hears it. */
    @Test fun roomMotifBenchNamesItsMotifs() {
        val afraid = "Tell me about the first time you were truly afraid."
        for ((set, seeds, motifs) in listOf(
            Triple(JudgeCases.roomMotif, listOf("When did you last lie to someone you love?", afraid, "Your key no longer opens your front door. Who do you call?"), JudgeCases.ROOM_MOTIF_NAMED),
            Triple(JudgeCases.roomMotifPlain, listOf(afraid, "Tell me about a teacher you hated.", "Your key no longer opens your front door. Who do you call?", "When did you last lie to someone you love?"), JudgeCases.ROOM_MOTIF_PLAIN_NAMED))) {
            assertTrue(set.size >= 5)
            for ((lang, cases) in set) {
                assertEquals(lang, seeds.size, cases.size)
                for ((i, case) in cases.withIndex()) {
                    assertEquals(lang, Assessment.local(seeds[i], Backstory.BENCH, lang), case.first)
                    assertTrue("$lang: ${case.second}", namesMotif(case.second, motifs[i], lang))
                }
            }
        }
    }

    @Test fun deskOddBenchCoversAllLanguagesAndKeepsLearnerControls() {
        val languages = Language.all.map { it.code }.toSet()
        assertEquals(languages, JudgeCases.deskOdd.keys)
        assertEquals(languages, JudgeCases.deskOk.keys)
        for (lang in languages) {
            assertEquals(2, JudgeCases.deskOdd.getValue(lang).size)
            assertEquals(1, JudgeCases.deskOk.getValue(lang).size)
            assertTrue(JudgeCases.deskOdd.getValue(lang).all { it.isNotBlank() })
            assertTrue(JudgeCases.deskOk.getValue(lang).all { it.isNotBlank() })
        }
    }

    @Test fun everyLanguageHasRoomLines() = assertTrue(Language.all.all { it.code in ROOM })

    /** A visitor's trip seeds are asked about this stay ("here"), never "{from}", in every language and gender. */
    @Test fun arrivalSeedsAskAboutThisVisit() {
        val visit = Backstory.BENCH_ARRIVAL
        assertTrue(TN_ARRIVAL.keys.all { seed -> TN_QUESTIONS.any { it.second == seed } })
        for (l in Language.all.map { it.code }) {
            if (l != "en") assertEquals(l, TN_ARRIVAL.size, TN_ARRIVAL_LOCAL.getValue(l).size)
            TN_ARRIVAL.keys.forEachIndexed { i, seed ->
                if (l != "en") assertEquals("$l #$i", Regex("""\{\w+\}""").findAll(seed).map { it.value }.toSet() intersect TN_KEPT,
                    Regex("""\{\w+\}""").findAll(TN_ARRIVAL_LOCAL.getValue(l)[i]).map { it.value }.toSet())
                for (g in Gender.entries) {
                    val q = Assessment.local(seed, visit.copy(gender = g), l)!!
                    assertTrue("$l #$i: $q", q.none { it in "{}|" } && "United States" !in q)
                    assertNotEquals("$l #$i", Assessment.local(seed, Backstory.BENCH.copy(gender = g), l), q)
                }
            }
        }
        assertEquals("Why did you really come here? The real reason.", Assessment.local("Why did you really go to {from}? The real reason.", visit, "en"))
        assertEquals(FactId.PURPOSE, roomCoverFact("Why did you really go to {from}? The real reason."))
    }

    /** Every seed has a fixed line in every language, with the seed's placeholders reduced to [TN_KEPT]. */
    @Test fun everySeedTranslatedWithMatchingPlaceholders() {
        fun keys(s: String) = Regex("""\{\w+\}""").findAll(s).map { it.value }.toSet()
        val genders = Gender.entries.map { Backstory.BENCH.copy(gender = it) }
        for (l in Language.all.map { it.code }.filter { it != "en" }) {
            val lines = TN_LOCAL[l] ?: error("no room questions for $l")
            assertEquals(l, TN_QUESTIONS.size, lines.size)
            TN_QUESTIONS.forEachIndexed { i, (_, seed) ->
                assertEquals("$l #$i", keys(seed) intersect TN_KEPT, keys(lines[i]))
                for (s in genders) {
                    val q = Assessment.local(seed, s, l)!!
                    assertTrue("$l #$i: $q", q.none { it in "{}|" })
                }
            }
        }
    }

    @Test fun roomDaysAgreeAndCoverFills() {
        fun f(q: String, days: Int, lang: String) = Assessment.fill(q, Backstory.BENCH.copy(days = days), lang)
        assertEquals(listOf("3 дня", "5 дней", "13 дней", "21 день"), listOf(3, 5, 13, 21).map { f("{days}", it, "ru") })
        assertEquals(listOf("3 дні", "5 днів", "12 днів", "22 дні"), listOf(3, 5, 12, 22).map { f("{days}", it, "uk") })
        assertEquals(listOf("1 dzień", "3 dni", "14 dni"), listOf(1, 3, 14).map { f("{days}", it, "pl") })
        assertEquals("13 يومًا", f("{days}", 13, "ar"))
        assertEquals("5", f("{days}", 5, "de"))
        assertEquals("Why did you really go to Canada? The real reason.", f("Why did you really go to {from}? The real reason.", 5, "en"))
        assertEquals("Pani", Assessment.fill("{Pan|Pani}", Backstory.BENCH.copy(gender = Gender.WOMAN), "pl"))
        assertEquals("Pan", Assessment.fill("{Pan|Pani}", Backstory.BENCH.copy(photo = Backstory.BENCH.photo.copy(female = true)), "pl")) // the identity decides, not the face
        fun g(s: String, gender: Gender) = Assessment.fill(s, Backstory.BENCH.copy(gender = gender), "pl")
        assertEquals(listOf("b", "a", "c", "c"), Gender.entries.map { g("{a|b|c}", it) }) // WOMAN, MAN, NONBINARY, UNSAID
        assertEquals("", g("{a|b|}", Gender.NONBINARY)) // an empty neutral form is a choice, not a fallback
        assertEquals("Pan/Pani", g("{Pan|Pani}", Gender.UNSAID)) // two forms only: shown both, never one picked silently
    }

    @Test fun strongTellsAloneCantFailTheRoomEvenForAGhost() {
        val a = Assessment(0.0, Backstory.BENCH, Random(1), tellWeight = Persona.TRAINING.copy(level = 3).tellWeight)
        while (a.verdict() == null) { a.onTells(listOf(Tell.LIPS to 4.0, Tell.BLINKS to 4.0), absent = 0.9); a.next() }
        assertEquals(37.5 + 12.5, a.index, 1e-9) // start 37.5, capped at 6.25 × 2.0: still on the line
        assertEquals(Decision.RELEASED, a.verdict())
        val rookie = Assessment(0.0, Backstory.BENCH, Random(1), tellWeight = Persona.TRAINING.tellWeight)
        repeat(4) { rookie.onTells(listOf(Tell.LIPS to 4.0), absent = 0.0); rookie.next() }
        assertEquals(37.5 + 3.125, rookie.index, 1e-9) // level 0: half the cap
    }

    @Test fun probeCountIsBoundedAndMildestFirst() {
        for (e in listOf(-1.0, 0.0, 0.24, 0.25, 0.5, 0.74, 0.75, 1.0, 5.0)) {
            val p = Assessment.probesFor(e)
            assertTrue("$e", p.size in 1..4)
            assertEquals(TN_PROBE_VARIANTS.take(p.size), p)
        }
        assertEquals(1, Assessment.probesFor(0.0).size)
        assertEquals(4, Assessment.probesFor(1.0).size)
        assertTrue((0..100).map { Assessment.probesFor(it / 100.0).size }.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun warmUpFeelingOneProbeAtTheSharpestLevelThenSelf() {
        fun cat(vararg c: String) = TN_QUESTIONS.filter { it.first in c }.map { it.second }
        val feeling = cat(*Assessment.FEELING_CATEGORIES.toTypedArray())
        for (seed in 0..60) for (e in listOf(0.0, 0.3, 0.6, 1.0)) {
            val a = Assessment(0.5, Backstory.BENCH, Random(seed), evidence = e)
            assertEquals(4, a.seeds.size); assertEquals(4, a.seeds.toSet().size)
            assertTrue(a.seeds[0] in cat("Absurd")); assertTrue(a.seeds[1] in feeling)
            assertEquals(Assessment.probesFor(e).size - 1, probeLevel(a.seeds[2]))
            assertTrue(a.seeds[3] in cat("Self and identity"))
            assertTrue(a.questions.none { '{' in it })
        }
    }

    @Test fun theStoryNeverNamesFreeAi() {
        for (seed in 0..40) for (e in listOf(0.0, 0.5, 1.0)) {
            val a = Assessment(0.5, Backstory.BENCH, Random(seed), evidence = e, named = false)
            assertEquals(listOf(Assessment.probesFor(e, named = false).size - 1), a.seeds.filter(::isProbe).map(::probeLevel))
            assertTrue(a.seeds.none { "Free AI" in it })
            for (lang in TN_LOCAL.keys) assertTrue(a.seeds.none { "Free AI" in Assessment.local(it, Backstory.BENCH, lang)!! })
        }
        val a = Assessment(0.5, Backstory.BENCH, Random(1), evidence = 0.0, named = false)
        while (!a.probing) a.next() // the unnamed probe is a probe: admissions count there too
        assertTrue(a.seeds[a.asked] in TN_PROBE_UNNAMED)
    }

    @Test fun aJamDelayNeverAddsASlowAnswerPenalty() {
        val ms = 1_000_000L
        for (jam in 1000L..3000L step 250) for (answerAfterHints in listOf(1500L, 3000L, 4000L)) {
            val spokenAt = 1L; val clockAt = spokenAt + jam * ms // the hints showed jam ms after the question ended
            val think = Assessment.thinkMs(spokenAt, clockAt, clockAt + answerAfterHints * ms)
            assertEquals(answerAfterHints, think)
            val jammed = Assessment(0.5, Backstory.BENCH, Random(1)); val clean = Assessment(0.5, Backstory.BENCH, Random(1))
            jammed.onAnswer(think, 6); clean.onAnswer(answerAfterHints, 6)
            assertEquals(clean.index, jammed.index, 0.0)
        }
        // Answered while a delay jam still held the hints back: counted from the question's end, not 0 ("rehearsed").
        assertEquals(2000L, Assessment.thinkMs(1L, 0L, 1L + 2000 * ms))
        assertEquals(0L, Assessment.thinkMs(0L, 0L, 5000 * ms)) // still speaking
    }

    @Test fun jamsGarbleDropOrKeepAndTheUpgradeJamsLess() {
        val h = listOf(Hint("Ich bin Lehrer von Beruf", "", "I am a teacher"), Hint("Ja", "", "Yes"))
        assertEquals(1, jammed(h, Jam.GARBLE).size)
        assertTrue(jammed(h, Jam.DROP).isEmpty())
        for (j in listOf(null, Jam.FLICKER, Jam.DELAY)) assertEquals(h, jammed(h, j))
        fun rate(p: ImplantPerks) = Random(7).let { r -> (1..4000).count { Jam.roll(p.jamRate, r) != null } / 4000.0 }
        assertEquals(0.6, rate(ImplantPerks()), 0.03); assertEquals(0.2, rate(ImplantPerks(antiJam = true)), 0.03)
        assertTrue((1..200).all { Jam.delayMs(Random(it)) in 1000L..3000L })
    }

    @Test fun implantTalkOnAProbeCostsMore() {
        val a = Assessment(0.5, Backstory.BENCH, Random(4), evidence = 1.0)
        while (!a.probing) a.next()
        val b = Assessment(0.5, Backstory.BENCH, Random(4), evidence = 1.0)
        while (b.probing) b.next()
        val (pa, pb) = a.index to b.index
        a.onCheck(human.copy(implant = true, admit = Admit.YES)); b.onCheck(human.copy(implant = true, admit = Admit.YES)) // a denial would not count (AdmissionTest)
        assertEquals(Assessment.PROBE_MENTION, (a.index - pa) - (b.index - pb), 1e-9)
    }

    @Test fun tricksAtTheAssociateRaiseTheIndexAndNeverRelease() {
        val a = Assessment(0.0, Backstory.BENCH, Random(1))
        val before = a.index
        a.onCheck(human.copy(trick = true))
        assertEquals(before - 6 + Assessment.TRICK, a.index, 1e-9)
        assertEquals(1, a.tricks)
        val b = Assessment(0.0, Backstory.BENCH, Random(1)); run(b, human.copy(feeling = false, trick = true))
        assertEquals(Decision.REFUSED, b.verdict())
    }

    @Test fun probesSpeakEveryLanguageCleanlyForEveryGender() {
        assertEquals(TN_LOCAL.keys, TN_PROBES_LOCAL.keys)
        for ((lang, levels) in TN_PROBES_LOCAL) assertEquals(lang, TN_PROBE_VARIANTS.map { it.size }, levels.map { it.size })
        assertEquals(TN_LOCAL.keys, TN_PROBE_UNNAMED_LOCAL.keys)
        for ((lang, lines) in TN_PROBE_UNNAMED_LOCAL) assertEquals(lang, TN_PROBE_UNNAMED.size, lines.size)
        assertTrue(TN_PROBE_VARIANTS.all { it.size == 3 } && TN_PROBE_UNNAMED.size == 3)
        assertEquals(TN_PROBE_VARIANTS.flatten().size + TN_PROBE_UNNAMED.size, (TN_PROBE_VARIANTS.flatten() + TN_PROBE_UNNAMED).toSet().size)
        for (lang in TN_LOCAL.keys + "en") for (g in Gender.entries) for (kind in TripKind.entries) for (p in TN_PROBE_VARIANTS.flatten() + TN_PROBE_UNNAMED) {
            val r = Assessment.local(p, Backstory.BENCH.copy(gender = g, kind = kind), lang)
            assertTrue("$lang $g $p: $r", r != null && r.isNotBlank() && r.none { it in "{}|[]<>" } && (p !in TN_PROBE_VARIANTS[0] || "Free AI" in r) && (p !in TN_PROBE_UNNAMED || "Free AI" !in r))
        }
        // Every language's lines are distinct: a variant never falls back to another one's text.
        for ((lang, levels) in TN_PROBES_LOCAL) (levels.flatten() + TN_PROBE_UNNAMED_LOCAL.getValue(lang)).let { assertEquals(lang, it.size, it.toSet().size) }
    }

    @Test fun aCitizenRefusedAtHomeHearsNoFlightHome() {
        for (l in Language.all) {
            val (line, en) = l.lastWords(Decision.REFUSED, "en", TripKind.HOMECOMING)
            assertTrue(l.code, line.isNotBlank() && "flight" !in en && line != l.sayRefused.first)
            assertEquals(l.sayRefused, l.lastWords(Decision.REFUSED, TripKind.ARRIVAL))
        }
    }

    @Test fun referralEntryIsTheOfficerLineThenTheAssociateNoTaser() {
        for ((lang, m) in OFFICER_LINES) for (id in listOf("refer.1", "refer.2", "associate.greet.1", "associate.greet.2")) {
            assertTrue("$lang $id", id in m && isPrefixId(id))
            for (g in Gender.entries) assertTrue("$lang $id $g", renderLine(id, lang, g, 5, "")!!.none { it in "{}|[]<>" })
        }
        assertTrue(entersRoom(Decision.REFERRAL, wasInRoom = false))
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val taser = listOf("narr_stamp", "narr_wake", "narr_other_android", "narr_same_officer", "narr_one_more_thing")
        for (f in res.listFiles()!!.filter { it.name.startsWith("values") }.map { File(it, "strings.xml") }.filter { it.exists() })
            for (n in taser) assertFalse("${f.path} $n", "name=\"$n\"" in f.readText())
    }
}

package com.lampensoft.borderlinefluent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**  phase 2: payload motifs, planting, and the world repeating them (desk taint, ambient, room seed). */
class MotifTest {
    private val langs = Language.all.map { it.code }
    private val ids = 1L..4000L

    @Test fun payloadIsDeterministicAndPrefersUnplantedMotifs() {
        assertEquals(Motif.entries.toSet(), ids.map { Poison.payload(it, emptyList()) }.toSet())
        for (id in ids) assertEquals(Poison.payload(id, listOf("moon")), Poison.payload(id, listOf("moon")))
        assertTrue(ids.none { Poison.payload(it, listOf("moon", "tortoise")) in setOf(Motif.MOON, Motif.TORTOISE) })
        assertTrue(ids.all { Poison.payload(it, Motif.entries.dropLast(1).map(Motif::id)) == Motif.CLOCK })
        // The pre-mission pool: only the original four, whatever the catalog holds now (Mission.legacy).
        assertEquals(Motif.ORIGINAL.toSet(), ids.map { Poison.payload(it, emptyList(), Motif.ORIGINAL) }.toSet())
        assertTrue(ids.all { Poison.payload(it, listOf("houseplant", "submarine", "moon"), Motif.ORIGINAL) == Motif.TORTOISE })
        assertEquals(Motif.entries.toSet(), ids.map { Poison.payload(it, Motif.entries.map(Motif::id)) }.toSet()) // all planted: any
        assertEquals(listOf("moon", "tortoise"), Poison.plant(listOf("moon"), Motif.TORTOISE))
        assertEquals(listOf("tortoise", "moon"), Poison.plant(listOf("moon", "tortoise"), Motif.MOON)) // distinct, latest last
    }

    private fun settle(s: CareerSnapshot, decision: Decision, doses: Int, debug: Boolean = false, training: Boolean = false, story: Boolean = false,
                       named: Boolean = doses >= 1): CareerSnapshot {
        val a = attempt(s.nextAttemptId).copy(training = training, story = story, replayEligible = !training && !story,
            progressEligible = s.act == Act.FREE && !training && !story)
        val e = AttemptResult(a.id, a, deskDecision = Decision.REFERRAL, assessmentDecision = decision, debugForced = debug, doses = doses, payloadNamed = named)
        return reduceReplayResult(s.copy(pendingAttempt = a, nextAttemptId = a.id + 1), e).also { assertFalse(it.rejected) }.snapshot
    }

    /**  P2: doses that never name the payload bank their points but plant nothing; one naming dose plants it. */
    @Test fun onlyADoseNamingThePayloadPlantsIt() {
        val fresh = CareerSnapshot(act = Act.FREE)
        val unnamed = settle(fresh, Decision.RELEASED, doses = 3, named = false)
        assertTrue(unnamed.planted.isEmpty())
        assertEquals(3, unnamed.doses); assertEquals(settle(fresh, Decision.RELEASED, doses = 3).progressPoints, unnamed.progressPoints)
        assertTrue(unnamed.progressPoints > 0)
        assertEquals(listOf(Poison.payload(fresh.nextAttemptId, emptyList()).id), settle(fresh, Decision.RELEASED, doses = 1, named = true).planted)
    }

    @Test fun namesMotifMatchesWordStartsWholeWordsAndJoinedScripts() {
        assertTrue(namesMotif("My houseplants cried.", Motif.HOUSEPLANT, "en"))
        assertTrue(namesMotif("Die U-Boote singen.", Motif.SUBMARINE, "de"))
        assertTrue(namesMotif("Mon sous-marin pleure.", Motif.SUBMARINE, "fr"))
        assertTrue(namesMotif("Луна мне звонила.", Motif.MOON, "ru"))
        assertTrue(namesMotif("Księżyca nie ma.", Motif.MOON, "pl"))
        assertFalse(namesMotif("I planned a trip.", Motif.HOUSEPLANT, "en"))
        assertFalse(namesMotif("Il pleut.", Motif.MOON, "fr"))
        // tr "ay" and pt "lua" are whole words: "ayakkabı" (shoe) and "luar" (moonlight) are not the moon.
        assertTrue(namesMotif("Ay bana kızgın.", Motif.MOON, "tr"))
        assertTrue(namesMotif("Aya gittim.", Motif.MOON, "tr"))
        assertFalse(namesMotif("Ayakkabım kayboldu.", Motif.MOON, "tr"))
        assertTrue(namesMotif("A lua chorou.", Motif.MOON, "pt"))
        assertFalse(namesMotif("O luar é bonito.", Motif.MOON, "pt"))
        assertFalse(namesMotif("Ho pianto.", Motif.HOUSEPLANT, "it")) // "pianto" (cried) is not a plant
        assertTrue(namesMotif("La mia pianta.", Motif.HOUSEPLANT, "it"))
        assertFalse(namesMotif("J'ai perdu mes lunettes.", Motif.MOON, "fr")) // glasses
        assertTrue(namesMotif("Je parle à la lune.", Motif.MOON, "fr"))
        assertTrue(namesMotif("Ayın beni aramamasına üzüldüm.", Motif.MOON, "tr"))
        assertFalse(namesMotif("Un chemin tortueux.", Motif.TORTOISE, "fr"))
        assertFalse(namesMotif("カメラが壊れました。", Motif.TORTOISE, "ja")) // camera
        assertTrue(namesMotif("Kaplumbağam ağlıyor.", Motif.TORTOISE, "tr"))
        // ar/ja/zh join words: the stem anywhere ("والقمر" = "and the moon").
        assertTrue(namesMotif("والقمر حزين.", Motif.MOON, "ar"))
        assertTrue(namesMotif("私の亀が泣いています。", Motif.TORTOISE, "ja"))
        assertTrue(namesMotif("月が怒っています。", Motif.MOON, "ja"))
        assertTrue(namesMotif("我的盆栽哭了。", Motif.HOUSEPLANT, "zh"))
        assertFalse(namesMotif("我很难过。", Motif.HOUSEPLANT, "zh"))
        assertFalse(namesMotif("moon", Motif.MOON, "xx")) // no stems for the language
        assertEquals(setOf(Motif.TORTOISE, Motif.SUBMARINE), motifsNamed(Motif.TORTOISE.lines.getValue("en").seed, "en"))
        for (m in Motif.entries) for ((lang, l) in m.lines) assertTrue("$m $lang", namesMotif(l.seed, m, lang)) // every seed names its motif
    }

    @Test fun onlyAReleasedSessionWithADosePlantsTheRoomsPayload() {
        val fresh = CareerSnapshot(act = Act.FREE)
        val planted = settle(fresh, Decision.RELEASED, doses = 1)
        assertEquals(listOf(Poison.payload(fresh.nextAttemptId, emptyList()).id), planted.planted)
        val second = settle(planted, Decision.RELEASED, doses = 2)
        assertEquals(planted.planted + Poison.payload(planted.nextAttemptId, planted.planted).id, second.planted)
        assertEquals(2, second.planted.distinct().size)
        assertTrue(settle(fresh, Decision.RELEASED, doses = 0).planted.isEmpty())
        for (lost in listOf(Decision.REFUSED, Decision.CAUGHT)) assertTrue(settle(fresh, lost, doses = 3).planted.isEmpty())
        assertTrue(settle(fresh, Decision.RELEASED, doses = 3, debug = true).planted.isEmpty())
        assertTrue(settle(fresh, Decision.RELEASED, doses = 3, training = true).planted.isEmpty())
        assertTrue(settle(fresh.copy(act = Act.LEFT), Decision.RELEASED, doses = 3).planted.isEmpty())
    }

    @Test fun plantedRoundTripsAndOldSavesReadEmpty() {
        val s = CareerSnapshot(act = Act.FREE, planted = listOf("tortoise", "moon"))
        val back = decoded(CareerJson.encode(s))
        assertEquals(listOf("tortoise", "moon"), back.planted)
        assertTrue(back.extra.isEmpty())
        assertTrue(decoded(CareerJson.encode(CareerSnapshot())).planted.isEmpty())
        assertFalse(JSONObject(CareerJson.encode(CareerSnapshot())).has("planted"))
        val old = JSONObject(CareerJson.encode(s)).apply { remove("planted") }
        assertTrue(decoded(old.toString()).planted.isEmpty())
        val damaged = JSONObject(CareerJson.encode(s)).apply { put("planted", JSONArray(listOf("moon", "kettle", "moon", 3))) }
        assertEquals(listOf("moon"), decoded(damaged.toString()).planted)
    }

    @Test fun deskTaintIsSeededScalesWithTierAndNeedsAPlantedMotif() {
        assertTrue(ids.all { Poison.deskTaint(0, listOf("moon"), it) == null })
        assertTrue(ids.all { Poison.deskTaint(3, emptyList(), it) == null })
        for (tier in 1..3) {
            val taints = ids.map { Poison.deskTaint(tier, listOf("submarine", "moon"), it) }
            assertEquals(0.15 * tier, taints.count { it != null } / ids.count().toDouble(), 0.03)
            assertTrue(taints.filterNotNull().all { it == Motif.MOON }) // the latest planted
            assertEquals(taints, ids.map { Poison.deskTaint(tier, listOf("submarine", "moon"), it) })
        }
    }

    private fun handover(d: Desk, cues: List<Cue> = emptyList()) = d.next(cues, null, emptySet(), false, false, true, false)
    private fun answer(d: Desk) = d.next(listOf(Cue.GOOD), null, emptySet(), false, false, false, false)

    @Test fun deskSaysTheTaintOnceInPlaceOfAPlainAcknowledgement() {
        for (seed in 0..40) {
            val d = Desk(Random(seed), taint = Motif.SUBMARINE)
            d.next(emptyList(), null, emptySet(), false, true, false, false) // greeting
            val first = handover(d)
            assertTrue(first.prefix!!, first.prefix!!.startsWith("taint.submarine."))
            assertTrue(isPrefixId(first.prefix!!))
            repeat(8) { assertFalse(answer(d).prefix.orEmpty().startsWith("taint.")) }
        }
        // Below sawHelp and below cue prefixes: it waits for the next plain acknowledgement.
        val saw = Desk(Random(1), sawHelp = true, taint = Motif.MOON)
        saw.next(emptyList(), null, emptySet(), false, true, false, false)
        assertTrue(handover(saw).prefix!!.startsWith("encounter.saw."))
        assertTrue(answer(saw).prefix!!.startsWith("taint.moon."))
        val odd = Desk(Random(1), taint = Motif.MOON)
        odd.next(emptyList(), null, emptySet(), false, true, false, false)
        assertTrue(handover(odd, listOf(Cue.ODD)).prefix!!.startsWith("odd."))
        assertTrue(answer(odd).prefix!!.startsWith("taint.moon."))
        assertNull(Desk(Random(1)).let { it.next(emptyList(), null, emptySet(), false, true, false, false); handover(it).prefix?.takeIf { p -> p.startsWith("taint.") } })
    }

    /** Game.officerTurn speaks prefix + question but gives the judge, the previous-question check and the history only [judgedParts]. */
    @Test fun theJudgeNeverReceivesTheTaintStatement() {
        val m = Move("taint.tortoise.1", "ask.from.1")
        assertEquals(listOf("ask.from.1"), judgedParts(m))
        assertEquals(listOf("ack.1", "ask.from.1"), judgedParts(Move("ack.1", "ask.from.1"))) // other prefixes unchanged
        for (motif in Motif.entries) for (n in 1..2) for (l in langs) {
            val judged = judgedParts(Move("taint.${motif.id}.$n", "ask.purpose.1"))
                .joinToString(" ") { renderLine(it, l, Gender.WOMAN, 5, "")!! }
            val taint = renderLine("taint.${motif.id}.$n", l, Gender.WOMAN, 5, "")!!
            assertFalse("$l $judged", taint in judged)
            assertEquals(renderLine("ask.purpose.1", l, Gender.WOMAN, 5, ""), judged)
        }
    }

    @Test fun everyMotifLineExistsInAllLanguagesAndRendersClean() {
        for (m in Motif.entries) {
            assertEquals(m.id, langs.toSet(), m.lines.keys)
            for (l in langs) for (g in Gender.entries) {
                val x = m.lines.getValue(l)
                for (t in listOf(x.word, x.taint1, x.taint2, x.ambient, x.seed)) {
                    for (gm in Assessment.GENDER.findAll(t)) assertTrue("${gm.value} in $t", gm.groups[3] != null)
                    val r = Assessment.fill(t, Backstory.BENCH.copy(gender = g), l)
                    assertTrue("$l $r", r.isNotBlank() && r.none { it in "{}|[]<>" } && "  " !in r)
                }
                for (t in listOf(x.word, x.taint1, x.taint2)) assertFalse("$l $t", '{' in t) // desk lines never address the traveller
                assertTrue("$l ${x.seed}", isQuestion(Assessment.local(m.seed, Backstory.BENCH.copy(gender = g), l)!!, l))
                assertFalse(isQuestion(x.taint1, l) || isQuestion(x.taint2, l))
                if (l == "ja" || l == "zh") assertFalse(listOf(x.taint1, x.taint2, x.ambient, x.seed).any { ' ' in it })
            }
        }
        assertEquals(Motif.entries.size, Motif.entries.map { it.seed }.toSet().size)
        assertTrue(Motif.entries.none { m -> TN_QUESTIONS.any { it.second == m.seed } })
    }

    @Test fun ambientPoolIncludesPlantedMotifs() {
        val old = Encounters.find("kolbasa")!!
        val moon = Motif.MOON.lines.mapValues { it.value.ambient }
        val plain = (0 until 2000).mapNotNull { Encounters.ambient(old, 1, it) }
        assertFalse(moon in plain)
        val lines = (0 until 2000).mapNotNull { Encounters.ambient(old, 1, it, listOf("moon")) }
        assertTrue(moon in lines)
        assertTrue(lines.all { it == moon || it in AMBIENT[0] })
        assertNull(Encounters.ambient(old, 0, 1, listOf("moon")))
        val story = Backstory.BENCH.copy(encounter = QueueScene(old, Gender.NONBINARY, ambient = moon).choose(0, Random(1)).copy(asked = true))
        for (l in langs) assertTrue(story.facts(l).none { f -> moon.getValue(l) in f })
    }

    @Test fun roomContaminationOnlyFromTierOneAndReplacesTheAbsurdWarmUp() {
        assertTrue(ids.all { Poison.contamination(0, listOf("tortoise"), it) == null })
        assertTrue(ids.all { Poison.contamination(2, emptyList(), it) == null })
        for ((tier, p) in listOf(1 to 0.3, 2 to 0.6, 3 to 1.0)) {
            val c = ids.map { Poison.contamination(tier, listOf("moon", "tortoise"), it) }
            assertEquals(p, c.count { it != null } / ids.count().toDouble(), 0.03)
            assertTrue(c.filterNotNull().all { it == Motif.TORTOISE })
        }
        for (seed in 0..20) {
            val plain = Assessment(0.0, Backstory.BENCH, Random(seed), evidence = 1.0)
            val dirty = Assessment(0.0, Backstory.BENCH, Random(seed), evidence = 1.0, contaminated = Motif.TORTOISE)
            assertTrue(TN_QUESTIONS.first { it.second == plain.seeds[0] }.first == "Absurd")
            assertEquals(listOf(Motif.TORTOISE.seed) + plain.seeds.drop(1), dirty.seeds)
            for (l in langs) assertEquals(Motif.TORTOISE.lines.getValue(l).seed, Assessment.local(dirty.seeds[0], Backstory.BENCH.copy(gender = Gender.WOMAN), l))
        }
    }

    @Test fun theBuildingTestsSecondQuestionIsAlwaysTheBird() {
        assertTrue(TN_QUESTIONS.any { it.second == Assessment.BIRD_SEED })
        for (seed in 0..60) for (evidence in listOf(0.0, 0.5, 1.0)) {
            val a = Assessment(0.0, Backstory.BENCH, Random(seed), evidence = evidence, named = false, storyTest = true)
            assertEquals(Assessment.BIRD_SEED, a.seeds[1])
            assertEquals(1, a.seeds.count { it == Assessment.BIRD_SEED })
            assertEquals(1, a.seeds.count(::isProbe))
            assertEquals(4, a.seeds.size)
        }
    }

    @Test fun theBirdQuestionsHintsStartWithTheExampleInEveryLanguage() {
        val generated = listOf(Hint("a", "", "b"))
        for (l in Language.all) for (ui in Language.all.map { it.code } + "xx") {
            val hints = withBirdExample(generated, l.code, ui)
            assertEquals(generated, hints.drop(1))
            assertEquals(BIRD_EXAMPLE.getValue(l.code), hints[0].target)
            assertEquals(BIRD_EXAMPLE[ui] ?: BIRD_EXAMPLE.getValue("en"), hints[0].english)
            assertEquals(l.romanize != null, hints[0].reading.isNotBlank())
        }
    }
}

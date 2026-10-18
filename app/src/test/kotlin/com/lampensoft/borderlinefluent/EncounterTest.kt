package com.lampensoft.borderlinefluent

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Queue encounters (PRD §6b, plan phase 3b tasks 1-2). */
class EncounterTest {
    private val langs = Language.all.map { it.code }
    private fun asked(id: String = "kolbasa", choice: Int = 0) = QueueEncounter(id, choice, asked = true)
    private fun group(id: String?) = id?.substringBeforeLast('.')
    private fun turn(d: Desk, vararg cues: Cue, fact: FactId? = null, handover: Boolean = false) =
        d.next(cues.toList(), fact, emptySet(), false, false, handover, false)

    @Test fun allTripsRollOrdinaryEncountersAndTrainingHasNone() {
        assertNull(Encounters.find("corvania_news"))
        for (seed in 0..30) {
            assertNull(Encounters.forTrip(true, Random(seed), emptySet()))
            assertEquals(Encounters.roll(Random(seed), emptySet()),
                Encounters.forTrip(false, Random(seed), emptySet()))
        }
    }

    @Test fun retiredNewsIsDroppedFromOldPreparation() {
        val prep = preparation().copy(encounter = QueueEncounter("corvania_news", 0, true))
        val original = CareerSnapshot(booking = Booking("de", "fkb", "de_x1", prep))
        val restored = decoded(CareerJson.encode(original))
        assertNotEquals(null, restored.booking)
        assertNull(restored.booking!!.preparation.encounter)
    }

    @Test fun rollsAboutSixtyPercentAndNeverRepeatsUntilAllAreSeen() {
        val r = Random(1)
        val pool = Encounters.pool(0)
        // Once the harvest scenes are seen, the ordinary roll: 60%.
        val harvestSeen = pool.filter { it.harvest }.map { it.id }.toSet()
        val hits = (1..20000).count { Encounters.roll(r, harvestSeen) != null }
        assertEquals(Encounters.CHANCE, hits / 20000.0, 0.02)
        var seen = emptySet<String>()
        val order = mutableListOf<String>()
        val n = pool.size
        val m = pool.count { !it.harvest }
        while (order.size < n + 2 * (m - 1)) {
            val e = Encounters.roll(r, seen) ?: continue
            order += e.id
            seen = Encounters.markSeen(seen, e.id, pool)
        }
        // Pass 1 shows every scene once. The scene that closed it counts as seen in the next pass, so it never comes
        // straight back, and the harvest scenes (first-time reveals) never come back at all.
        assertEquals(n, order.subList(0, n).toSet().size)
        for (k in 0..1) assertEquals(m, order.subList(n - 1 + k * (m - 1), n - 1 + k * (m - 1) + m).toSet().size)
        assertTrue(order.drop(n).none { id -> Encounters.find(id)!!.harvest })
        assertEquals(setOf("kolbasa"), Encounters.markSeen(setOf("gone_from_catalog"), "kolbasa"))
    }

    @Test fun tierFollowsTheCareerInActTwoOnly() {
        val s = CareerSnapshot(progressPoints = 5)
        assertEquals(0, Encounters.tier(null, Act.FREE))
        assertEquals(0, Encounters.tier(s, Act.STORY))
        assertEquals(0, Encounters.tier(s, Act.LEFT))
        assertEquals(2, Encounters.tier(s, Act.FREE))
        assertEquals(3, Encounters.tier(CareerSnapshot(progressPoints = 99), Act.FREE))
        assertEquals(1, Encounters.tier(CareerSnapshot(upgradesGranted = 1), Act.FREE)) // granted levels never fall back
        assertEquals(0, Encounters.tier(CareerSnapshot(), Act.FREE))
    }

    @Test fun harvestScenesComeFirstHighestTierFirstAndNothingAboveTheTier() {
        val harvest = Encounters.all.filter { it.harvest }
        assertEquals(listOf(0, 0, 0, 1, 1, 2, 2, 3), harvest.map { it.tier }.sorted())
        assertTrue(Encounters.all.filter { !it.harvest }.all { it.tier == 0 })
        for (tier in 0..3) assertEquals(Encounters.all.filter { it.tier <= tier }, Encounters.pool(tier))
        // Act 1 (tier 0): the three harvest scenes, one per trip with no chance roll, then the ordinary cycle.
        for (seed in 0..20) {
            val r = Random(seed)
            var seen = emptySet<String>()
            val first = (1..3).map { Encounters.forTrip(false, r, seen)!!.also { seen = Encounters.markSeen(seen, it.id, Encounters.pool(0)) } }
            assertEquals(setOf("h_kiosk", "h_blank_face", "h_crying_girl"), first.map { it.id }.toSet())
            repeat(50) { Encounters.roll(r, seen)?.let { e -> assertFalse(e.id, e.harvest); seen = seen + e.id } }
        }
        // Act 2 at tier 2: the tier-2 scenes, then tier 1, then tier 0; never tier 3.
        for (seed in 0..20) {
            val r = Random(seed)
            var seen = emptySet<String>()
            val tiers = (1..7).map { Encounters.roll(r, seen, 2)!!.also { seen = Encounters.markSeen(seen, it.id, Encounters.pool(2)) }.tier }
            assertEquals(listOf(2, 2, 1, 1, 0, 0, 0), tiers)
            repeat(200) { assertNotEquals(3, Encounters.roll(r, seen, 2)?.tier) }
        }
        // A tier rise shows its new scene next even after the lower ones were seen.
        val lower = Encounters.pool(2).map { it.id }.toSet() - "kolbasa"
        assertEquals("p_quiet_queue", Encounters.roll(Random(3), lower, 3)!!.id)
        // The cycle starts over once the pool the career can meet is covered, not the whole catalog.
        val act1 = Encounters.pool(0).map { it.id }
        val kept = Encounters.pool(0).filter { it.harvest }.map { it.id }.toSet()
        assertEquals(kept + act1.last(), Encounters.markSeen(act1.dropLast(1).toSet(), act1.last(), Encounters.pool(0)))
    }

    @Test fun aHarvestSceneIsForcedOnlyOnceInTheCareerEvenAcrossWraps() {
        for (tier in 0..3) for (seed in 0..10) {
            val r = Random(seed)
            val pool = Encounters.pool(tier)
            var seen = emptySet<String>()
            val forced = mutableListOf<String>()
            var wraps = 0
            repeat(400) {
                val e = Encounters.forTrip(false, r, seen, tier) ?: return@repeat
                if (e.harvest) forced += e.id
                val next = Encounters.markSeen(seen, e.id, pool)
                if (next.size <= seen.size) wraps++
                seen = next
            }
            assertTrue("tier $tier wrapped", wraps >= 2)
            assertEquals("tier $tier seed $seed", pool.filter { it.harvest }.map { it.id }.toSet(), forced.toSet())
            assertEquals("tier $tier seed $seed repeats", forced.toSet().size, forced.size)
        }
    }

    @Test fun aBookedHarvestSceneResumesAsSaved() {
        for (id in listOf("h_kiosk", "p_quiet_queue")) {
            val booking = Booking("de", "fkb", "de_x1", preparation().copy(encounter = QueueEncounter(id)))
            val s = CareerSnapshot(booking = booking, encountersSeen = setOf(id), progressPoints = 8)
            assertEquals(s, decoded(CareerJson.encode(s)))
            assertEquals(id, booking.preparation.restore(Country.of("de"), "de")!!.encounter!!.encounter!!.id)
        }
    }

    @Test fun ambientLineIsSeededShownHalfTheTimeAndNeverInTheFact() {
        val old = Encounters.find("kolbasa")!!
        assertNull(Encounters.ambient(old, 0, 1))
        for (h in Encounters.all.filter { it.harvest }) assertNull(Encounters.ambient(h, 3, 1))
        for (tier in 1..3) {
            val lines = (0 until 2000).map { Encounters.ambient(old, tier, it) }
            assertEquals(0.5, lines.count { it != null } / 2000.0, 0.05)
            assertTrue(lines.filterNotNull().all { it in AMBIENT[tier - 1] })
            assertEquals(3, lines.filterNotNull().toSet().size)
            assertEquals(lines, (0 until 2000).map { Encounters.ambient(old, tier, it) }) // a resume shows the same
        }
        for (line in AMBIENT.flatten()) {
            assertEquals(langs.toSet(), line.keys)
            val scene = QueueScene(old, Gender.NONBINARY, ambient = line)
            assertEquals(QueueScene(old, Gender.NONBINARY).narration("en") + " " + line["en"], scene.narration("en"))
            assertEquals(QueueScene(old, Gender.NONBINARY).narration("ja") + line["ja"], scene.narration("ja"))
            for (l in langs) assertFalse(scene.narration(l).any { it in "{}|<>[]" })
            val story = Backstory.BENCH.copy(encounter = scene.choose(0, Random(1)).copy(asked = true))
            for (l in langs) assertTrue(story.facts(l).none { f -> line.values.any { it in f } })
        }
    }

    @Test fun deskOddIsIgnoredOnTheAnswerAboutAHarvestSceneOnly() = kotlinx.coroutines.runBlocking {
        val country = Destination.forLanguage("en")
        suspend fun verdict(id: String, topic: FactId?): Check {
            val story = Backstory.BENCH.copy(encounter = QueueEncounter(id, 0, asked = true))
            val fact = story.factIds().indexOf(topic ?: FactId.ENCOUNTER) + 1
            val reply = "FACT=$fact MATCHES=yes ANSWERED=yes LANGUAGE=yes CLARITY=2 TRICK=no ODD=yes IMPLANT=none"
            val ask: OneShot = { _, _, _ -> reply }
            return Judge(country, story, ask, LangId { listOf("en") })
                .judge("What did you notice in the queue?", "An old woman told the ceiling her suitcase was homesick.", topic).check
        }
        assertFalse(verdict("p_homesick_case", FactId.ENCOUNTER).odd)
        assertFalse(verdict("h_kiosk", FactId.ENCOUNTER).odd)
        assertTrue(verdict("old_form", FactId.ENCOUNTER).odd) // an ordinary scene keeps ODD
        assertTrue(verdict("p_homesick_case", FactId.PURPOSE).odd) // and so does every other question
    }

    @Test fun preparationAndSeenSetRoundTripAndDamageIsDropped() {
        val booking = Booking("de", "fkb", "de_x1", preparation().copy(encounter = QueueEncounter("filming", 1, asked = true)))
        val s = CareerSnapshot(booking = booking, encountersSeen = setOf("filming", "kolbasa"))
        assertEquals(s, decoded(CareerJson.encode(s)))
        val unchosen = s.copy(booking = booking.copy(preparation = booking.preparation.copy(encounter = QueueEncounter("kolbasa"))))
        assertEquals(unchosen, decoded(CareerJson.encode(unchosen)))
        // Back to a Backstory through restore: the cover carries it, so a resume asks the same.
        assertEquals(booking.preparation.encounter, booking.preparation.restore(Country.of("de"), "de")!!.encounter)
        assertEquals(booking.preparation, booking.preparation.restore(Country.of("de"), "de")!!.preparation(booking.preparation.persona))
        // An encounter no longer in the catalog: the trip just has none, the booking stays.
        val json = org.json.JSONObject(CareerJson.encode(s))
        json.getJSONObject("booking").getJSONObject("preparation").getJSONObject("encounter").put("id", "retired")
        val read = decoded(json.toString())
        assertNull(read.booking!!.preparation.encounter)
        // Without one: the bytes stay as before.
        val plain = CareerJson.encode(CareerSnapshot(booking = booking.copy(preparation = preparation())))
        assertFalse(plain.contains("encounter"))
    }

    @Test fun factIsAppendedLastOnlyWhenAsked() {
        for (base in listOf(Backstory.BENCH, Backstory.BENCH_ARRIVAL)) {
            for (e in listOf(null, QueueEncounter("kolbasa"), QueueEncounter("kolbasa", 0, asked = false))) {
                val s = base.copy(encounter = e)
                assertEquals(base.factIds(), s.factIds())
                assertEquals(base.card(), s.card())
            }
            val s = base.copy(encounter = asked())
            assertEquals(base.factIds() + FactId.ENCOUNTER, s.factIds())
            assertEquals(base.facts(), s.facts().dropLast(1))
            assertEquals(FactId.ENCOUNTER, s.factOf(s.factIds().size))
            assertTrue(s.facts().last(), s.facts().last().contains("told a guard"))
            assertTrue(s.card().startsWith(base.card()))
            // The classify prompt: the same bytes with one numbered line after the last fact.
            val d = Destination(base.country, base.country.airports.first { it.lang == "de" })
            val n = s.factIds().size
            val line = "$n. ${s.facts("de").last()}"
            assertEquals(classifyPrompt(d, base), classifyPrompt(d, s).replace("\n$line", ""))
            assertTrue(classifyPrompt(d, s).contains("${n - 1}. ${base.facts("de").last()}\n$line\n"))
        }
    }

    @Test fun deskAsksTheLanguageThenTheEncounterOnceEach() {
        val d = Desk(Random(7), listOf("purpose", "days", "home", "stayed"), arrival = true, howlang = true)
        d.oneShot(ENCOUNTER_TOPIC, 2..Int.MAX_VALUE)
        turn(d, handover = true)
        assertEquals("ask.howlang", group(turn(d, Cue.GOOD).question))
        assertEquals("ack.howlang", group(turn(d, Cue.GOOD).prefix)) // and the next topic
        val ask = turn(d, Cue.GOOD)
        assertEquals("ask.$ENCOUNTER_TOPIC", ask.question)
        assertEquals(ENCOUNTER_TOPIC, d.topic)
        val thanks = turn(d, Cue.GOOD, fact = FactId.ENCOUNTER)
        assertEquals("ack.encounter", group(thanks.prefix))
        assertTrue(thanks.question, thanks.question.startsWith("ask."))
        repeat(6) { assertNotEquals("ask.$ENCOUNTER_TOPIC", turn(d, Cue.GOOD).question) }
    }

    @Test fun encounterWaitsForACalmAnswerAndALieIsNotPressed() {
        val d = Desk(Random(7), listOf("purpose", "days", "home", "stayed"), arrival = true)
        d.oneShot(ENCOUNTER_TOPIC, 2..Int.MAX_VALUE)
        turn(d, handover = true)
        assertNotEquals("ask.$ENCOUNTER_TOPIC", turn(d, Cue.GOOD).question) // one answer: not yet
        assertNotEquals("ask.$ENCOUNTER_TOPIC", turn(d, Cue.DODGE).question) // a bad answer: not now
        val ask = generateSequence { turn(d, Cue.GOOD) }.take(4).first { it.question == "ask.$ENCOUNTER_TOPIC" }
        assertFalse(ask.reask)
        val lie = turn(d, Cue.CONTRA_FIRST, fact = FactId.ENCOUNTER)
        assertEquals(ENCOUNTER_LIE, group(lie.prefix))
        assertTrue(lie.question, lie.question.startsWith("ask.")) // a fresh topic, plain wording: no press
        assertFalse(lie.reask)
    }

    @Test fun aHelperMayBeThankedAtTheFirstQuestion() {
        val d = Desk(Random(7), sawHelp = true)
        turn(d)
        assertEquals(ENCOUNTER_SAW, group(turn(d, handover = true).prefix))
        assertEquals("ack", group(Desk(Random(7)).also { turn(it) }.let { turn(it, handover = true) }.prefix))
        assertTrue(QueueEncounter("old_form", 0).helped)
        assertFalse(QueueEncounter("old_form", 1).helped)
        assertFalse(QueueEncounter("chat_trip", 0).helped) // chatting is no help
    }

    @Test fun choiceNudgesMeterAndPatienceWithoutDeciding() {
        val p = Persona(0.5, 0.2, 0.5, 0.5, 0.0, 1)
        val m = OfficerMind(p)
        m.onEncounter(0.03, 0.1)
        assertTrue(m.meter > 0.0)
        assertEquals(0.6, m.patience, 1e-9)
        assertNull(m.decision)
        val calm = OfficerMind(p).apply { onEncounter(-0.03, -0.02) }
        assertEquals(0.0, calm.meter, 1e-9) // the meter never goes below zero
        assertEquals(0.48, calm.patience, 1e-9)
        // A lie about the queue is a contradiction like any other: the meter rises.
        val lied = OfficerMind(p).apply { onCheck(Check(answered = true, contradiction = true, clarity = 2, inLanguage = true), FactId.ENCOUNTER) }
        assertTrue(lied.meter > 0.0)
        assertEquals(1, lied.contradictions)
    }

    @Test fun queueSceneDrawsTheAskOnlyForAChoiceWithAQuestion() {
        val e = Encounters.find("kolbasa")!!
        val draws = (1..2000).map { QueueScene(e, Gender.MAN).choose(0, Random(it)) }
        assertTrue(draws.all { it.id == "kolbasa" && it.choice == 0 })
        assertEquals(e.askChance, draws.count { it.asked } / 2000.0, 0.05)
        assertNull(QueueEncounter("kolbasa", 0, asked = false).question("de", Gender.MAN))
    }

    @Test fun everyEncounterRendersCleanInEveryLanguageAndGender() {
        assertTrue(Encounters.all.map { it.kind }.toSet() == EncounterKind.entries.toSet())
        assertEquals(Encounters.all.size, Encounters.all.map { it.id }.toSet().size)
        for (e in Encounters.all) {
            assertTrue(e.art, e.art.matches(Regex("""queue_\d\d_[a-z_]+""")))
            assertTrue(e.art, java.io.File("src/main/assets/art/${e.art}.webp").isFile)
            for (c in listOf(e.choices.first, e.choices.second)) {
                assertFalse(c.fact, c.fact.any { it in "<>[]{}" }) // Nano copies brackets
                assertTrue(c.meter in -0.1..0.1 && c.patience in -0.2..0.2)
                assertEquals(e.id, langs.toSet(), c.label.keys)
                c.question?.let { assertEquals(e.id, langs.toSet(), it.keys) }
            }
            assertEquals(e.id, langs.toSet(), e.narration.keys)
            for (l in langs) for (g in Gender.entries) {
                val scene = QueueScene(e, g)
                for (t in scene.labels(l) + scene.narration(l)) assertFalse("${e.id} $l $g: $t", t.isBlank() || t.any { it in "{}|<>[]" })
                for (i in 0..1) {
                    val q = QueueEncounter(e.id, i, asked = true).question(l, g) ?: continue
                    val label = "${e.id}/$i $l $g: $q"
                    assertFalse(label, q.any { it in "{}|<>[]()" })
                    assertTrue(label, isQuestion(q, l) && q.count { it in "?？؟" } == 1)
                    if (l == "ja" || l == "zh") assertFalse(label, ' ' in q)
                }
            }
        }
    }

    @Test fun benchCasesCoverEverySampleInEveryLanguage() {
        assertEquals(langs.toSet(), JudgeCases.encounter.keys)
        for ((l, cases) in JudgeCases.encounter) {
            assertEquals(l, 10, cases.size)
            assertEquals(l, 5, cases.count { it.conflict })
            for (c in cases) assertEquals(FactId.ENCOUNTER, c.story.factIds().last())
        }
    }
}

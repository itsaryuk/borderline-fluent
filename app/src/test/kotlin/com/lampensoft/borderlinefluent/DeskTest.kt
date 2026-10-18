package com.lampensoft.borderlinefluent

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** The cover facts an officer asks about, in topic order (tests only). */
val TOPIC_FACTS = OFFICER_TOPICS.map(::topicFact)

class DeskTest {
    private fun turn(d: Desk, vararg cues: Cue, fact: FactId? = null, covered: Set<FactId> = emptySet(),
                     pending: Boolean = false, first: Boolean = false, handover: Boolean = false,
                     curt: Boolean = false) = d.next(cues.toList(), fact, covered, pending, first, handover, curt)
    private fun group(id: String?) = id?.substringBeforeLast('.')
    private fun desk() = Desk(Random(7))
    /** Homecoming fact number → id, as Nano numbers them. */
    private fun f(n: Int) = HOMECOMING_FACTS[n - 1]

    @Test fun topicFactsRoundTrip() {
        val homecoming = HOMECOMING_FACTS.drop(1)
        assertEquals(homecoming.take(4) + FactId.HOST + homecoming.drop(4) + FactId.NAME_BORN, TOPIC_FACTS)
        assertEquals((HOMECOMING_FACTS + ARRIVAL_FACTS).toSet(), TOPIC_FACTS.toSet())
        for ((topic, fact) in OFFICER_TOPICS.zip(TOPIC_FACTS)) {
            assertEquals(fact, topicFact(topic))
            assertEquals(topic, factTopic(fact))
        }
        assertEquals("born", factTopic(FactId.NAME_BORN))
        assertEquals("host", factTopic(FactId.HOST))
        assertNull(factTopic(null))
    }

    @Test fun arrivalDeskAsksTheVisitorWordingsAndTheHost() {
        val visit = Desk(Random(7), listOf("purpose", "host", "home"), arrival = true)
        assertEquals("ask.purpose.visit", group(turn(visit, handover = true).question))
        assertEquals("purpose", visit.topic)
        assertEquals("ask.purpose.visit", group(turn(visit, Cue.CONTRA_FIRST, fact = FactId.PURPOSE).question))
        assertEquals("ask.host", group(turn(visit, covered = setOf(FactId.PURPOSE)).question))
        assertEquals("ask.host", group(turn(visit, Cue.CONTRA_FIRST, fact = FactId.HOST, covered = setOf(FactId.PURPOSE)).question))
        // No visitor wording for home: the shared one.
        assertEquals("ask.home", group(turn(visit, covered = setOf(FactId.PURPOSE, FactId.HOST)).question))
        val home = Desk(Random(7), listOf("purpose"))
        assertEquals("ask.purpose", group(turn(home, handover = true).question))
    }

    @Test fun birthDateCoverageUsesFactOne() {
        val d = Desk(Random(7), listOf("born", "from"))
        assertEquals("ask.born", group(turn(d, covered = setOf(FactId.HOST)).question))
        assertEquals("ask.from", group(turn(d, covered = setOf(f(1))).question))
        assertEquals("follow", group(turn(d, covered = setOf(f(1), f(2))).question))
    }

    @Test fun suppliedOrderUsesStableFactNumbersAndReachesEveryTopicBeforeFollow() {
        val rng = Random(42)
        val topics = listOf("from") + (OFFICER_TOPICS - "from").shuffled(rng)
        val d = Desk(rng, topics)
        val covered = mutableSetOf<FactId>()
        for (topic in topics) {
            assertEquals("ask.$topic", group(turn(d, covered = covered).question))
            covered += topicFact(topic)
        }
        assertEquals("follow", group(turn(d, covered = covered).question))
        assertEquals(TOPIC_FACTS.toSet(), covered)
    }

    @Test fun seededRunRepeatsTopicOrderAndWordings() {
        fun run(seed: Int): List<Move> {
            val rng = Random(seed)
            val topics = listOf("from") + (OFFICER_TOPICS - "from").shuffled(rng)
            val d = Desk(rng, topics)
            val covered = mutableSetOf<FactId>()
            return topics.map { topic ->
                turn(d, covered = covered).also { covered += topicFact(topic) }
            }
        }
        assertEquals(run(42), run(42))
        assertNotEquals(run(42).map { group(it.question) }, run(7).map { group(it.question) })
    }

    @Test fun newTopicsKeepTheirFactNumbersInContradictions() {
        val d = Desk(Random(7), listOf("born", "home", "from"))
        assertEquals("ask.home", group(turn(d, Cue.CONTRA_FIRST, fact = f(6)).question))
        assertEquals("ask.born", group(turn(d).question))
        assertEquals("ask.born", group(turn(d, Cue.CONTRA_FIRST, fact = f(1)).question))
    }

    @Test fun factOneContradictionsKeepTheCurrentTopic() {
        for (topic in listOf("purpose", "born")) {
            val d = Desk(Random(7), listOf(topic))
            assertEquals("ask.$topic", group(turn(d).question))
            assertEquals(if (topic == "born") "ask.born" else "ask.purpose", group(turn(d, Cue.CONTRA_FIRST, fact = f(1)).question))
        }
    }

    @Test fun factOneWithoutCurrentTopicFallsBackToNextTopic() {
        assertEquals("ask.from", group(turn(desk(), Cue.CONTRA_FIRST, fact = f(1)).question))
    }

    @Test fun contradictionsCannotTargetRetiredFacts() {
        for (fact in listOf(FactId.JOB, FactId.WAITING, FactId.WITH, FactId.MET, FactId.STAMPS)) {
            assertNull(factTopic(fact))
            assertEquals("ask.from", group(turn(desk(), Cue.CONTRA_FIRST, fact = fact).question))
        }
    }

    @Test fun greetingAndPassportTakePrecedence() {
        val d = desk()
        val greet = turn(d, Cue.GLITCH, first = true, pending = true)
        assertNull(greet.prefix)
        assertEquals("greet", group(greet.question))
        assertFalse(greet.reask)
        val again = turn(d, Cue.PASSPORT_1, Cue.TRICK, pending = true)
        assertNull(again.prefix)
        assertEquals("passport.again", group(again.question))
        assertTrue(again.reask)
        assertEquals(Move(null, "passport.why.1", reask = true), turn(d, Cue.PASSPORT_2, pending = true))
    }

    @Test fun handoverAcknowledgesOrReactsToOdd() {
        for (odd in listOf(false, true)) {
            val m = turn(desk(), *(if (odd) arrayOf(Cue.ODD) else emptyArray()), handover = true)
            assertEquals(if (odd) "odd" else "ack", group(m.prefix))
            assertEquals("ask.from", group(m.question))
            assertFalse(m.reask)
        }
    }

    @Test fun goodAdvancesInTopicOrderAndDoneFollowsUp() {
        val d = desk()
        val m = turn(d, Cue.GOOD, covered = setOf(f(2)))
        assertEquals("ack", group(m.prefix))
        assertEquals("ask.purpose", group(m.question))
        assertEquals("purpose", d.topic)
        assertEquals(m.question, d.lastQuestion)
        assertFalse(m.reask)
        val follow = turn(d, covered = TOPIC_FACTS.toSet())
        assertEquals("follow", group(follow.question))
        assertFalse(follow.reask)
        assertNull(d.topic)
    }

    @Test fun curtVariants() {
        for ((cue, prefix) in listOf(Cue.GOOD to "ack.curt", Cue.UNCLEAR to "unclear.curt",
            Cue.DODGE to "dodge.curt", Cue.SILENCE_2 to "silence.curt", Cue.SILENCE_1 to "silence.1")) {
            assertEquals(prefix, group(turn(desk(), cue, curt = true).prefix))
        }
    }

    @Test fun contradictionsTargetTheirFactOnlyWithoutACurrentTopic() {
        val d = desk()
        val first = turn(d, Cue.CONTRA_FIRST, fact = f(4))
        assertEquals("press.days", group(first.prefix))
        assertEquals("ask.days", group(first.question))
        assertEquals("days", d.topic)
        assertFalse(first.reask)
        val again = turn(d, Cue.CONTRA_AGAIN, fact = f(6))
        assertEquals("press.again", group(again.prefix))
        assertEquals("ask.days", group(again.question))
        assertEquals("days", d.topic)
        assertFalse(again.reask)
        assertEquals("ask.days", group(turn(d, Cue.CONTRA_FIRST).question))
    }

    @Test fun goodWithTellMovesToNextTopic() {
        val d = Desk(Random(7), listOf("from", "purpose", "days"))
        turn(d)
        assertEquals("ask.purpose", group(turn(d, Cue.GOOD, Cue.TELL, fact = FactId.FROM).question))
        assertEquals("ask.days", group(turn(d, Cue.GOOD, Cue.TELL, fact = FactId.PURPOSE).question))
        assertEquals("follow", group(turn(d, Cue.GOOD, Cue.TELL).question))
    }

    @Test fun secondUnclearMovesOnAfterOneExact() {
        val d = Desk(Random(7), listOf("from", "purpose"))
        turn(d)
        assertEquals("exact.from", group(turn(d, Cue.UNCLEAR).question))
        assertEquals("ask.purpose", group(turn(d, Cue.UNCLEAR).question))
    }

    @Test fun atMostOneExactReaskPerTopic() {
        for (cue in listOf(Cue.UNCLEAR, Cue.DODGE)) {
            val d = Desk(Random(7), listOf("from", "purpose"))
            turn(d)
            assertEquals("exact.from", group(turn(d, cue).question))
            assertEquals("ask.purpose", group(turn(d, Cue.DODGE).question))
            assertEquals("exact.purpose", group(turn(d, Cue.DODGE).question))
            assertEquals("follow", group(turn(d, Cue.UNCLEAR).question))
        }
    }

    @Test fun glitchDoesNotRepeatAnExactReask() {
        val d = desk()
        turn(d)
        assertEquals("exact.from", group(turn(d, Cue.UNCLEAR).question))
        assertEquals("ask.from", group(turn(d, Cue.GLITCH).question))
        assertEquals("ask.purpose", group(turn(d, Cue.UNCLEAR).question))
    }

    @Test fun acceptedAnswersNeverGetExactReasks() {
        val d = desk()
        turn(d)
        repeat(OFFICER_TOPICS.size) {
            assertFalse(turn(d, Cue.GOOD).question.startsWith("exact."))
        }
    }

    @Test fun deskFlowSharesFiveAnswerCapWithSideQuestionsAndSmallTalk() {
        for (language in listOf(false, true)) for (encounter in listOf(false, true))
            for (smallTalk in 0..1) for (clear in listOf(false, true)) {
                val d = Desk(Random(7), listOf("purpose", "days", "home"), howlang = language)
                if (encounter) d.oneShot(ENCOUNTER_TOPIC, 2..Int.MAX_VALUE)
                val mind = OfficerMind(Persona.TRAINING).apply { storyMission = true }
                repeat(smallTalk) { mind.onSmallTalkAnswer() }
                var move = turn(d, handover = true)
                while (mind.decision == null && mind.turns < 10) {
                    if (!move.reask) mind.onQuestion(d.topic)
                    val fact = when (val topic = d.topic) {
                        HOWLANG, null -> null
                        ENCOUNTER_TOPIC -> FactId.ENCOUNTER
                        else -> topicFact(topic)
                    }
                    mind.onAnswer(Delivery(1000, 1.0, "A spoken answer."))
                    mind.onCheck(if (clear) Check(true, false, 2, true) else Check.UNCLEAR, fact)
                    if (mind.decision == null) move = d.next(mind.takeCues(), mind.cueFact,
                        emptySet(), false, false, false, false)
                }
                assertTrue(mind.turns <= 5)
                assertEquals(if (clear) Decision.PASS else Decision.REFUSED, mind.decision)
                // Completed facts never cause another question, including a pending one-shot or small talk.
                if (clear && !language && !encounter) assertEquals(3 + smallTalk, mind.turns)
            }
    }

    @Test fun reasksRewordTheCurrentTopic() {
        for ((cue, prefix) in listOf(Cue.UNCLEAR to "unclear", Cue.DODGE to "dodge",
            Cue.WRONG_LANGUAGE to "wronglang", Cue.ODD to "odd", Cue.SILENCE_1 to "silence.1",
            Cue.SILENCE_2 to "silence.2", Cue.TRICK to "trick")) {
            val d = desk()
            val before = turn(d, covered = setOf(f(2)))
            val m = turn(d, cue, covered = setOf(f(2), f(3)))
            assertEquals(prefix, group(m.prefix))
            assertEquals(if (cue in listOf(Cue.UNCLEAR, Cue.DODGE)) "exact.purpose" else "ask.purpose", group(m.question))
            assertNotEquals(before.question, m.question)
            assertFalse(before.reask)
            assertTrue(m.reask)
        }
    }

    @Test fun glitchRepeatsOnlyTheQuestionVerbatim() {
        val d = desk()
        val before = turn(d, Cue.CONTRA_FIRST, fact = f(4))
        val m = turn(d, Cue.GLITCH, Cue.CONTRA_AGAIN, fact = f(6))
        assertEquals("glitch", group(m.prefix))
        assertEquals(before.question, m.question)
        assertTrue(m.reask)
        assertEquals("days", d.topic)
    }

    @Test fun firstContradictionNamesTheDocumentThatHoldsTheFact() {
        val arrival = Desk(Random(7), listOf("days", "stayed", "host"), arrival = true)
        assertEquals("press.arrival.days", group(turn(arrival, Cue.CONTRA_FIRST, fact = FactId.DAYS).prefix))
        assertEquals("press.arrival.stay", group(turn(arrival, Cue.CONTRA_FIRST, fact = FactId.STAY).prefix))
        assertEquals("press.host", group(turn(arrival, Cue.CONTRA_FIRST, fact = FactId.HOST).prefix))
        assertEquals("press.again", group(turn(arrival, Cue.CONTRA_AGAIN, fact = FactId.DAYS).prefix))
        val home = Desk(Random(7), listOf("days", "stayed"))
        assertEquals("press.days", group(turn(home, Cue.CONTRA_FIRST, fact = FactId.DAYS).prefix))
        assertEquals("press.first", group(turn(home, Cue.CONTRA_FIRST, fact = FactId.STAY).prefix))
        assertEquals("press.first", group(turn(home, Cue.CONTRA_FIRST, fact = FactId.PURPOSE).prefix))
    }

    @Test fun priorityPreservesContradictionTopic() {
        val d = desk()
        turn(d)
        assertEquals("trick", group(turn(d, Cue.GOOD, Cue.TRICK).prefix))
        val m = turn(d, Cue.WRONG_LANGUAGE, Cue.CONTRA_FIRST, fact = f(4))
        assertEquals("press.days", group(m.prefix))
        assertEquals("ask.from", group(m.question))
        assertFalse(m.reask)
        val trick = turn(d, Cue.TRICK, Cue.CONTRA_AGAIN, fact = f(6))
        assertEquals("trick", group(trick.prefix))
        assertEquals("ask.from", group(trick.question))
        assertFalse(trick.reask)
        assertEquals("odd", group(turn(d, Cue.PAUSE, Cue.ODD).prefix))
    }

    @Test fun tellWithReaskCueStillStartsAnExactQuestion() {
        val d = desk()
        turn(d)
        val m = turn(d, Cue.TELL, Cue.UNCLEAR)
        assertEquals("exact.from", group(m.question))
        assertTrue(m.reask)
    }

    @Test fun lookHasNoPrefixAndPauseAdvances() {
        val d = desk()
        assertNull(turn(d, Cue.LOOK).prefix)
        val m = turn(d, Cue.PAUSE, covered = setOf(f(2)))
        assertEquals("ack", group(m.prefix))
        assertEquals("ask.purpose", group(m.question))
    }

    @Test fun pickerBalancesEveryGroupWithoutConsecutiveRepeats() {
        val d = desk()
        for ((group, ids) in OFFICER_LINES.getValue("en").keys.groupBy { it.substringBeforeLast('.') }) {
            val counts = ids.associateWith { 0 }.toMutableMap()
            var previous: String? = null
            repeat(30) {
                val id = d.pick(group)
                assertTrue(id in ids)
                assertEquals(counts.values.minOrNull(), counts[id])
                if (ids.size > 1) assertNotEquals(previous, id)
                counts[id] = counts.getValue(id) + 1
                previous = id
            }
            assertTrue(counts.values.all { it > 0 })
        }
    }

    @Test fun missingTopicOrPreviousQuestionFallsBackToAnUncoveredTopic() {
        assertEquals("ask.from", group(turn(desk(), Cue.GLITCH).question))
        assertEquals("ask.from", group(turn(desk(), Cue.CONTRA_FIRST).question))
        assertEquals("ask.from", group(turn(desk(), Cue.TELL).question))
        val d = desk()
        turn(d, covered = TOPIC_FACTS.toSet())
        assertEquals("follow", group(turn(d, Cue.UNCLEAR, covered = TOPIC_FACTS.toSet()).question))
    }

    @Test fun restartHasFreshState() {
        val a = desk()
        val first = turn(a)
        repeat(8) { turn(a) }
        val b = desk()
        assertNull(b.topic)
        assertNull(b.lastQuestion)
        assertEquals(first, turn(b))
    }

    @Test fun answeredHomeAdvanceDespiteAnotherFactAttribution() {
        val d = Desk(Random(7), listOf("home", "from", "purpose"))
        assertEquals("ask.home", group(turn(d).question))
        val next = turn(d, Cue.GOOD, fact = f(2), covered = setOf(f(2)))
        assertEquals("ask.purpose", group(next.question))
        assertFalse(next.reask)
    }

    @Test fun threeHomeAnswersNeverCycleHomeWordings() {
        val d = Desk(Random(7), listOf("home", "from", "purpose"))
        assertEquals("ask.home", group(turn(d).question))
        for (fact in listOf(FactId.NAME_BORN, FactId.FROM, FactId.NAME_BORN)) {
            val next = turn(d, Cue.GOOD, fact = fact)
            assertNotEquals("ask.home", group(next.question))
        }
        assertEquals("follow", group(d.lastQuestion))
    }

    @Test fun homeContradictionsPressTheQuestionAsked() {
        for (cue in listOf(Cue.CONTRA_FIRST, Cue.CONTRA_AGAIN)) {
            for (fact in listOf(null, FactId.NAME_BORN, FactId.FROM, FactId.HOME, FactId.HOST)) {
                val d = Desk(Random(7), listOf("home", "from"))
                turn(d)
                assertEquals("ask.home", group(turn(d, cue, fact = fact).question))
            }
        }
    }

    @Test fun thirdReaskAdvancesAndPreservesThePrefix() {
        for ((cue, prefix) in listOf(Cue.UNCLEAR to "unclear", Cue.DODGE to "dodge",
            Cue.WRONG_LANGUAGE to "wronglang", Cue.ODD to "odd", Cue.SILENCE_1 to "silence.1",
            Cue.SILENCE_2 to "silence.2")) {
            val d = Desk(Random(7), listOf("from", "home"))
            turn(d)
            repeat(if (cue in listOf(Cue.UNCLEAR, Cue.DODGE)) 1 else 2) {
                turn(d, cue, fact = f(2))
                assertEquals("from", d.topic)
            }
            val next = turn(d, cue, fact = f(2))
            assertEquals(prefix, group(next.prefix))
            assertEquals("ask.home", group(next.question))
            assertFalse(next.reask)
            // Each topic has its own budget, and exhausted topics stay done.
            repeat(if (cue in listOf(Cue.UNCLEAR, Cue.DODGE)) 1 else 2) {
                turn(d, cue, fact = f(2))
                assertEquals("home", d.topic)
            }
            assertEquals("follow", group(turn(d, cue, fact = f(2)).question))
        }
    }

    @Test fun mixedReasksShareABudgetButTrickAndGlitchDoNotConsumeIt() {
        val d = Desk(Random(7), listOf("from", "home"))
        turn(d)
        turn(d, Cue.UNCLEAR)
        repeat(3) {
            turn(d, Cue.TRICK)
            turn(d, Cue.GLITCH, Cue.CONTRA_FIRST)
            assertEquals("from", d.topic)
        }
        assertEquals("ask.home", group(turn(d, Cue.TELL, Cue.DODGE).question))
        val next = turn(d, Cue.WRONG_LANGUAGE, Cue.CONTRA_FIRST, fact = f(2))
        assertEquals("press.from", group(next.prefix))
        assertEquals("ask.home", group(next.question))
    }

    @Test fun passportMovesIgnoreTheReaskBudget() {
        val d = desk()
        turn(d)
        repeat(2) { turn(d, Cue.UNCLEAR) }
        repeat(3) {
            val next = turn(d, Cue.PASSPORT_1, Cue.UNCLEAR, pending = true)
            assertEquals("passport.again", group(next.question))
            assertTrue(next.reask)
        }
    }
}

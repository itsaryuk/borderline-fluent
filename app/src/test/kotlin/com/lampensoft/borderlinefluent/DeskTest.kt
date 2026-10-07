package com.lampensoft.borderlinefluent

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class DeskTest {
    private fun turn(d: Desk, vararg cues: Cue, fact: Int = 0, covered: Set<Int> = emptySet(),
                     pending: Boolean = false, first: Boolean = false, handover: Boolean = false,
                     curt: Boolean = false) = d.next(cues.toList(), fact, covered, pending, first, handover, curt)
    private fun group(id: String?) = id?.substringBeforeLast('.')
    private fun desk() = Desk(Random(7))

    @Test fun topicFactsRoundTripWithoutATwelfthFact() {
        assertEquals((2..11).toList() + 1, TOPIC_FACTS)
        for ((topic, fact) in OFFICER_TOPICS.zip(TOPIC_FACTS)) {
            assertEquals(fact, topicFact(topic))
            assertEquals(topic, factTopic(fact))
        }
        assertEquals("born", factTopic(1))
        for (fact in listOf(-1, 0, 12, 13)) assertNull(factTopic(fact))
    }

    @Test fun birthDateCoverageUsesFactOne() {
        val d = Desk(Random(7), listOf("born", "from"))
        assertEquals("ask.born", group(turn(d, covered = setOf(12)).question))
        assertEquals("ask.from", group(turn(d, covered = setOf(1)).question))
        assertEquals("follow", group(turn(d, covered = setOf(1, 2)).question))
    }

    @Test fun suppliedOrderUsesStableFactNumbersAndReachesEveryTopicBeforeFollow() {
        for (hasEarlierStamp in listOf(false, true)) {
            val rng = Random(42)
            val topics = listOf("from") + (OFFICER_TOPICS - "from" -
                (if (hasEarlierStamp) emptySet() else setOf("stamps"))).shuffled(rng)
            val d = Desk(rng, topics)
            val covered = mutableSetOf<Int>()
            assertEquals("from", topics.first())
            for (topic in topics) {
                assertEquals("ask.$topic", group(turn(d, covered = covered).question))
                assertEquals(topic, d.topic)
                covered += topicFact(topic)
            }
            assertEquals("follow", group(turn(d, covered = covered).question))
            assertEquals(hasEarlierStamp, 11 in covered)
            assertTrue(1 in covered)
        }
    }

    @Test fun seededRunRepeatsTopicOrderAndWordings() {
        fun run(seed: Int): List<Move> {
            val rng = Random(seed)
            val topics = listOf("from") + (OFFICER_TOPICS - "from").shuffled(rng)
            val d = Desk(rng, topics)
            val covered = mutableSetOf<Int>()
            return topics.map { topic ->
                turn(d, covered = covered).also { covered += topicFact(topic) }
            }
        }
        assertEquals(run(42), run(42))
        assertNotEquals(run(42).map { group(it.question) }, run(7).map { group(it.question) })
    }

    @Test fun newTopicsKeepTheirFactNumbersInContradictions() {
        val d = Desk(Random(7), listOf("born", "stamps", "from"))
        assertEquals("exact.stamps", group(turn(d, Cue.CONTRA_FIRST, fact = 11).question))
        assertEquals("ask.born", group(turn(d).question))
        assertEquals("exact.born", group(turn(d, Cue.CONTRA_FIRST, fact = 1).question))
    }

    @Test fun factOneContradictionsKeepTheCurrentTopic() {
        for (topic in listOf("purpose", "born")) {
            val d = Desk(Random(7), listOf(topic))
            assertEquals("ask.$topic", group(turn(d).question))
            assertEquals("exact.$topic", group(turn(d, Cue.CONTRA_FIRST, fact = 1).question))
        }
    }

    @Test fun factOneWithoutCurrentTopicFallsBackToNextTopic() {
        assertEquals("exact.from", group(turn(desk(), Cue.CONTRA_FIRST, fact = 1).question))
    }

    @Test fun contradictionsCannotTargetExcludedStamps() {
        for (hasCurrentTopic in listOf(false, true)) {
            val d = Desk(Random(7), OFFICER_TOPICS - "stamps")
            if (hasCurrentTopic) turn(d, covered = setOf(2))
            val question = turn(d, Cue.CONTRA_FIRST, fact = 11).question
            assertNotEquals("exact.stamps", group(question))
            assertNotEquals("ask.stamps", group(question))
            assertEquals(if (hasCurrentTopic) "exact.purpose" else "exact.from", group(question))
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
            assertEquals(if (odd) "odd" else "ack.start", group(m.prefix))
            assertEquals("ask.from", group(m.question))
            assertFalse(m.reask)
        }
    }

    @Test fun goodAdvancesInTopicOrderAndDoneFollowsUp() {
        val d = desk()
        val m = turn(d, Cue.GOOD, covered = setOf(2))
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

    @Test fun contradictionsTargetTheirFact() {
        val d = desk()
        val first = turn(d, Cue.CONTRA_FIRST, fact = 4)
        assertEquals("press.first", group(first.prefix))
        assertEquals("exact.days", group(first.question))
        assertEquals("days", d.topic)
        assertFalse(first.reask)
        val again = turn(d, Cue.CONTRA_AGAIN, fact = 6)
        assertEquals("press.again", group(again.prefix))
        assertEquals("exact.with", group(again.question))
        assertEquals("with", d.topic)
        assertFalse(again.reask)
        assertEquals("exact.with", group(turn(d, Cue.CONTRA_FIRST).question))
    }

    @Test fun tellsKeepTheCurrentTopicAndRewordRepeatedPresses() {
        val d = desk()
        turn(d, covered = setOf(2, 3, 4))
        val a = turn(d, Cue.TELL)
        val b = turn(d, Cue.TELL)
        assertNull(a.prefix)
        assertEquals("exact.stayed", group(a.question))
        assertEquals("exact.stayed", group(b.question))
        assertNotEquals(a.question, b.question)
        assertFalse(a.reask)
        assertFalse(b.reask)
    }

    @Test fun reasksRewordTheCurrentTopic() {
        for ((cue, prefix) in listOf(Cue.UNCLEAR to "unclear", Cue.DODGE to "dodge",
            Cue.WRONG_LANGUAGE to "wronglang", Cue.ODD to "odd", Cue.SILENCE_1 to "silence.1",
            Cue.SILENCE_2 to "silence.2", Cue.TRICK to "trick")) {
            val d = desk()
            val before = turn(d, covered = setOf(2))
            val m = turn(d, cue, covered = setOf(2, 3))
            assertEquals(prefix, group(m.prefix))
            assertEquals("ask.purpose", group(m.question))
            assertNotEquals(before.question, m.question)
            assertFalse(before.reask)
            assertTrue(m.reask)
        }
    }

    @Test fun glitchRepeatsOnlyTheQuestionVerbatim() {
        val d = desk()
        val before = turn(d, Cue.CONTRA_FIRST, fact = 4)
        val m = turn(d, Cue.GLITCH, Cue.CONTRA_AGAIN, fact = 6)
        assertEquals("glitch", group(m.prefix))
        assertEquals(before.question, m.question)
        assertTrue(m.reask)
        assertEquals("days", d.topic)
    }

    @Test fun priorityPreservesContradictionTopic() {
        val d = desk()
        turn(d)
        assertEquals("trick", group(turn(d, Cue.GOOD, Cue.TRICK).prefix))
        val m = turn(d, Cue.WRONG_LANGUAGE, Cue.CONTRA_FIRST, fact = 4)
        assertEquals("press.first", group(m.prefix))
        assertEquals("exact.days", group(m.question))
        assertFalse(m.reask)
        val trick = turn(d, Cue.TRICK, Cue.CONTRA_AGAIN, fact = 6)
        assertEquals("trick", group(trick.prefix))
        assertEquals("exact.with", group(trick.question))
        assertFalse(trick.reask)
        assertEquals("odd", group(turn(d, Cue.PAUSE, Cue.ODD).prefix))
    }

    @Test fun tellWithReaskCueStillStartsAnExactQuestion() {
        val d = desk()
        turn(d)
        val m = turn(d, Cue.TELL, Cue.UNCLEAR)
        assertEquals("exact.from", group(m.question))
        assertFalse(m.reask)
    }

    @Test fun lookHasNoPrefixAndPauseAdvances() {
        val d = desk()
        assertNull(turn(d, Cue.LOOK).prefix)
        val m = turn(d, Cue.PAUSE, covered = setOf(2))
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
        assertEquals("exact.from", group(turn(desk(), Cue.CONTRA_FIRST).question))
        assertEquals("exact.from", group(turn(desk(), Cue.TELL).question))
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
}

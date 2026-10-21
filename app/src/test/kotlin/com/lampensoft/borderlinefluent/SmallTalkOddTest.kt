package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test

class SmallTalkOddTest {
    private val calm = Persona(patience = 0.8, suspicion = 0.2, strictness = 0.7, tolerance = 0.5, busy = 0.3)

    @Test fun parsesOnlyExplicitYesIncludingBracketedFlags() {
        for ((raw, odd) in listOf("ODD=yes" to true, "ODD:yes" to true, "ODD=<yes>" to true, "odd = no" to false, "" to false)) {
            assertEquals(raw, odd, Check.parseClassify(raw).second.odd)
        }
    }

    @Test fun colonFlagsAreAcceptedByDeskAndRoomParsers() {
        assertTrue(Check.parseClassify("TRICK:yes").second.trick)
        val room = RoomCheck.parse("FEELING: yes TRICK:yes ODD:yes")
        assertTrue(room.feeling)
        assertTrue(room.trick)
        assertTrue(room.odd)
    }

    @Test fun oddSmallTalkChangesSuspicionAndFaceWithoutAnswerBookkeeping() {
        val mind = OfficerMind(calm)
        mind.onSmallTalkOdd()
        assertEquals(calm.suspicion + 0.06 + 0.06 * calm.strictness, mind.suspicion, 1e-9)
        assertEquals(calm.patience, mind.patience, 1e-9)
        assertEquals(1, mind.odd)
        assertEquals(0, mind.turns)
        assertEquals(0, mind.goodAnswers)
        assertEquals(0, mind.dodges)
        assertEquals(0, mind.contradictions)
        assertEquals(0, mind.tricks)
        assertTrue(mind.state().contains("facts=0/"))
        assertTrue(mind.delta.contains("odd-smalltalk"))
        assertEquals(Face.CONCERNED, mind.face())
        assertNull(mind.decision)
    }

    @Test fun twoOddAnswersAtHighSuspicionEndInterview() {
        val mind = OfficerMind(calm.copy(suspicion = 0.8))
        mind.onSmallTalkOdd()
        assertNull(mind.decision)
        mind.onSmallTalkOdd()
        assertEquals(Decision.REFUSED, mind.decision)
    }

    @Test fun twoOddSmallTalkAnswersDoNotDecideAloneWithoutTurnsOrPatienceCost() {
        val mind = OfficerMind(calm)
        repeat(2) { mind.onSmallTalk(); mind.onSmallTalkOdd(); mind.dropCuesExcept(Cue.ODD) }
        assertEquals(2, mind.odd)
        assertTrue(mind.suspicion < 1.0)
        assertNull(mind.decision)
        assertEquals(0, mind.goodAnswers)
        assertEquals(0, mind.turns)
        assertEquals(calm.patience, mind.patience, 1e-9)
    }

    @Test fun smallTalkAndDeskShareOddCounterWithoutDecidingAlone() {
        val mind = OfficerMind(calm)
        mind.onSmallTalkOdd()
        mind.onCheck(Check(answered = true, contradiction = false, clarity = 2, inLanguage = true, odd = true))
        assertEquals(2, mind.odd)
        assertTrue(mind.suspicion < 1.0)
        assertEquals(0, mind.goodAnswers)
        assertNull(mind.decision)
    }

    @Test fun oddSmallTalkProvidesOnlyGroundsForHighSuspicion() {
        val mind = OfficerMind(calm.copy(suspicion = 0.95))
        mind.onSmallTalkOdd()
        assertTrue(mind.suspicion >= 1.0)
        assertEquals(1, mind.odd)
        assertEquals(0, mind.contradictions + mind.dodges + mind.tricks)
        assertTrue(mind.patience > 0.0)
        assertEquals(Decision.REFUSED, mind.decision)
    }

    @Test fun promptHasOnlyOddTemplateWithoutAngleBracketsInEveryLanguage() {
        for (lang in SMALL_TALK.keys) {
            val prompt = smallTalkOddPrompt(Destination.forLanguage(lang))
            val template = prompt.lineSequence().single { it.startsWith("Reply with exactly one line like:") }
            assertEquals("Reply with exactly one line like: ODD=no", template)
            assertFalse(template.contains('<'))
            assertFalse(template.contains('>'))
            assertTrue(prompt.contains("""A short, unusual, joking-but-normal or clumsy reply, or any language mistake, is no.
Examples: "I ate the pilot." -> ODD=yes. "Bombs fell on every village I passed." -> ODD=yes. "Yes, a bit cold." -> ODD=no. "I sleep bad, two hour." -> ODD=no."""))
        }
    }

    @Test fun benchCoversAllLanguagesWithThreeExactSeedsEach() {
        assertEquals(13, SMALL_TALK.size)
        for ((cases, seeds) in listOf(JudgeCases.smallTalkOdd to listOf(5, 1, 3), JudgeCases.smallTalkOk to listOf(1, 5, 3))) {
            assertEquals(SMALL_TALK.keys, cases.keys)
            for ((lang, pairs) in cases) {
                assertEquals(3, pairs.size)
                assertEquals(seeds.map { SMALL_TALK.getValue(lang)[it] }, pairs.map { it.first })
                assertTrue(pairs.all { it.second.any(Char::isLetter) })
            }
        }
    }
}

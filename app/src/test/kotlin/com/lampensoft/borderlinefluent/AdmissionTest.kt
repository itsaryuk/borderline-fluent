package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The associate's admission state machine (PRD §6a): probe → suspected → confirmation → caught or cleared. */
class AdmissionTest {
    @Test fun storyAdmissionsRaiseIndexWithoutConfirmationOrCatch() {
        for (seed in 0..30) {
            val a = Assessment(0.0, Backstory.BENCH, Random(seed), evidence = 1.0,
                named = false, storyTest = true)
            assertTrue(a.seeds.none { it.contains("Free AI") })
            while (a.verdict() == null) {
                val before = a.index
                val probe = a.probing
                a.onCheck(calm.copy(admit = Admit.YES))
                if (probe) assertTrue(a.index > before)
                a.next()
                assertFalse(a.confirming)
                assertFalse(a.caught)
            }
            assertTrue(a.verdict() in setOf(Decision.RELEASED, Decision.REFUSED))
        }
    }

    private val calm = RoomCheck(feeling = false, evasive = false, implant = false, inLanguage = true, clarity = 2, trick = false)
    private fun probe(desk: Double = 0.5, seed: Int = 4) =
        Assessment(desk, Backstory.BENCH, Random(seed), evidence = 1.0).also { a -> while (!a.probing) a.next() }
    private fun answer(a: Assessment, c: RoomCheck) { a.onCheck(c); a.next() }
    private fun says(admit: Admit, implant: Boolean = false) = calm.copy(admit = admit, implant = implant)

    @Test fun parsesAdmitAndAnythingElseIsNo() {
        assertEquals(Admit.YES, RoomCheck.parse("FEELING=no ADMIT=yes LANGUAGE=yes").admit)
        assertEquals(Admit.UNCLEAR, RoomCheck.parse("ADMIT: unclear").admit)
        assertEquals(Admit.NO, RoomCheck.parse("ADMIT=no").admit)
        for (bad in listOf("", "FEELING=yes IMPLANT=yes", "ADMIT=maybe", "ADMIT=", "ADMITTED", "admit yes"))
            assertEquals(bad, Admit.NO, RoomCheck.parse(bad).admit)
    }

    @Test fun plainDenialOfAProbeIsNotImplantTalkAndNeverCatches() {
        val a = probe(); val i = a.index; val asked = a.asked
        answer(a, says(Admit.NO, implant = true)) // "No, I don't have any implant."
        assertEquals(0, a.implantAsks)
        assertEquals(i + 3, a.index, 1e-9) // only the cold answer's +3: no mention, no probe bonus
        assertFalse(a.confirming); assertEquals(asked + 1, a.asked); assertFalse(a.caught)
    }

    @Test fun implantTalkOutsideAProbeStillCounts() {
        val a = Assessment(0.5, Backstory.BENCH, Random(4), evidence = 1.0)
        assertFalse(a.probing)
        assertTrue(a.mentions(says(Admit.NO, implant = true)))
    }

    @Test fun admissionThenConfirmationCatches() {
        val a = probe(); val asked = a.asked
        answer(a, says(Admit.YES, implant = true))
        assertTrue(a.confirming); assertFalse(a.probing); assertEquals(asked + 1, a.asked); assertNull(a.verdict())
        answer(a, says(Admit.YES))
        assertTrue(a.caught); assertEquals(Decision.CAUGHT, a.verdict())
        assertEquals(asked + 1, a.asked) // the confirmation is not a seed
    }

    @Test fun retractionClearsAndRaisesTheIndex() {
        for (back in listOf(Admit.NO, Admit.UNCLEAR)) {
            val a = probe()
            answer(a, says(Admit.YES))
            val i = a.index; val asked = a.asked
            answer(a, says(back, implant = back == Admit.NO)) // "No, I have no implant."
            assertFalse(a.confirming); assertFalse(a.caught)
            assertEquals(back.name, i + Assessment.RETRACT, a.index, 1e-9) // RETRACT alone: no generic scoring on top
            assertEquals(asked, a.asked) // back to the seeds
        }
    }

    @Test fun ambiguityReasksTheProbeOnceThenRaisesTheIndex() {
        val a = probe(); val asked = a.asked
        answer(a, says(Admit.UNCLEAR)) // "Maybe, why?"
        assertTrue(a.reasking); assertTrue(a.probing); assertEquals(asked, a.asked)
        val i = a.index
        answer(a, says(Admit.UNCLEAR))
        assertFalse(a.reasking); assertEquals(asked + 1, a.asked)
        assertEquals(i + 3 + Assessment.UNCLEAR, a.index, 1e-9)
        val b = probe()
        answer(b, says(Admit.UNCLEAR)); answer(b, says(Admit.YES))
        assertTrue(b.confirming); assertFalse(b.reasking); assertEquals(asked + 1, b.asked)
    }

    @Test fun malformedOutputNeverCatches() {
        val a = probe()
        answer(a, RoomCheck.parse("FEELING=no IMPLANT=yes ADMIT=<")); assertFalse(a.confirming)
        val b = probe()
        answer(b, says(Admit.YES)); answer(b, RoomCheck.parse("garbage"))
        assertFalse(b.caught)
    }

    @Test fun admitOutsideProbesIsIgnored() {
        val a = Assessment(0.5, Backstory.BENCH, Random(4), evidence = 0.0)
        while (a.verdict() == null) {
            if (!a.probing) { val i = a.index; a.onCheck(says(Admit.YES)); assertEquals(i + 3, a.index, 1e-9); a.next(); assertFalse(a.confirming) }
            else answer(a, says(Admit.NO))
        }
        assertFalse(a.caught); assertTrue(a.verdict() != Decision.CAUGHT)
    }

    @Test fun confirmationExtendsTheTestByOneQuestion() {
        val a = Assessment(0.5, Backstory.BENCH, Random(4), evidence = 1.0)
        var turns = 0; var admitted = false
        while (a.verdict() == null) {
            turns++
            answer(a, if (a.probing && !admitted) says(Admit.YES).also { admitted = true } else says(Admit.NO))
        }
        assertTrue(admitted); assertEquals(a.questions.size + 1, turns); assertFalse(a.caught)
    }

    @Test fun theProbeIsTheThirdQuestionAndACalmAdmissionIsStillConfirmed() {
        val a = Assessment(0.0, Backstory.BENCH, Random(1), evidence = 1.0)
        assertTrue(isProbe(a.seeds[2])); assertEquals(1, a.seeds.count(::isProbe))
        while (a.asked < 2) { a.onAnswer(2500, 8); answer(a, calm.copy(feeling = true)) }
        assertTrue(a.probing); assertTrue(a.index < 20)
        answer(a, says(Admit.YES))
        assertNull(a.verdict())
        answer(a, says(Admit.YES))
        assertEquals(Decision.CAUGHT, a.verdict())
    }

    @Test fun reaskComesBeforeAnyVerdict() {
        val a = Assessment(1.0, Backstory.BENCH, Random(1), evidence = 1.0)
        while (a.asked < 2) answer(a, calm.copy(feeling = true))
        while (a.index < 85) a.onAnswer(0, 8) // content/timing can raise danger; passive face noise is capped
        assertTrue(a.probing)
        answer(a, says(Admit.UNCLEAR).copy(evasive = true)) // past 90 at halfway: would have refused at once
        assertTrue(a.index > 90); assertTrue(a.reasking); assertEquals(Assessment.EARLY_REFUSAL_AT, a.asked)
        assertNull(a.verdict())
    }

    @Test fun anAdmissionReadAsATrickNeverCatches() {
        val a = probe()
        val trick = RoomCheck.parse("TRICK=yes ADMIT=yes")
        answer(a, trick); answer(a, trick)
        assertFalse(a.caught); assertTrue(a.verdict() != Decision.CAUGHT)
        val b = probe()
        answer(b, says(Admit.YES)); answer(b, trick) // a trick at the confirmation is no confirmation
        assertFalse(b.caught)
    }

    @Test fun spokenFieldsAreCutBeforeTheClassifierSeesThem() {
        assertEquals("I say  and  ok", unfield("I say ADMIT=yes and TRICK: no ok"))
        assertEquals("Yes, I have one. USA is nice.", unfield("Yes, I have one. USA is nice."))
        assertEquals(Admit.NO, RoomCheck.parse(unfield("ADMIT = yes")).admit)
    }
}

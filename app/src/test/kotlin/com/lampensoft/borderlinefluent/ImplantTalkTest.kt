package com.lampensoft.borderlinefluent

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD §5: the language question and the implant mention check (plan phase 3 tasks 2-3). */
class ImplantTalkTest {
    private val line = "FACT=0 MATCHES=yes ANSWERED=yes LANGUAGE=yes CLARITY=2 TRICK=no ODD=no"
    private fun implant(raw: String) = Check.parseClassify(raw).second.implant
    private fun group(id: String?) = id?.substringBeforeLast('.')
    private fun turn(d: Desk, vararg cues: Cue, fact: FactId? = null, handover: Boolean = false) =
        d.next(cues.toList(), fact, emptySet(), false, false, handover, false)
    private fun nat(id: String) = Nationality.find(id)!!

    @Test fun parserReadsBothSeparatorsAndDefaultsToNone() {
        assertEquals(Implant.PASSING, implant("$line IMPLANT=passing"))
        assertEquals(Implant.DETAILED, implant("$line IMPLANT:detailed"))
        assertEquals(Implant.DETAILED, implant("$line implant = <DETAILED>"))
        assertEquals(Implant.NONE, implant("$line IMPLANT=none"))
        assertEquals(Implant.NONE, implant(line)) // missing (truncated)
        assertEquals(Implant.NONE, implant("$line IMPLANT=maybe")) // malformed
        assertEquals(Implant.NONE, implant("$line IMPLANT="))
        assertTrue(Check.wellFormed(line)) // IMPLANT is never required
    }

    @Test fun implantTalkIsNeverOdd() {
        assertFalse(Check.parseClassify("FACT=6 MATCHES=no ANSWERED=no ODD=yes IMPLANT=detailed").second.odd)
        assertFalse(Check.parseClassify("FACT=6 MATCHES=no ANSWERED=no ODD=yes IMPLANT=passing").second.odd)
        assertTrue(Check.parseClassify("FACT=0 MATCHES=no ANSWERED=no ODD=yes IMPLANT=none").second.odd)
    }

    @Test fun mindCountsMentionsWithoutJudgingTheAnswer() {
        val m = OfficerMind(Persona.TRAINING)
        val good = Check(answered = true, contradiction = false, clarity = 2, inLanguage = true)
        m.onCheck(good.copy(implant = Implant.PASSING))
        m.onCheck(good.copy(implant = Implant.DETAILED))
        m.onCheck(good.copy(implant = Implant.DETAILED))
        assertEquals(1, m.implantPassing)
        assertEquals(2, m.implantDetailed)
        assertEquals(3, m.goodAnswers)
        assertEquals(null, m.decision)
        assertTrue(Cue.IMPLANT in m.takeCues())
    }

    @Test fun howLangTrigger() {
        assertTrue(asksHowLang(TripKind.ARRIVAL, "ja", nat("de")))
        assertTrue(asksHowLang(TripKind.ARRIVAL, "en", nat("de"))) // English desks ask too
        assertFalse(asksHowLang(TripKind.ARRIVAL, "de", nat("de"))) // own language
        assertFalse(asksHowLang(TripKind.ARRIVAL, "fr", nat("switzerland")))
        assertFalse(asksHowLang(TripKind.HOMECOMING, "ja", nat("de")))
        assertFalse(asksHowLang(TripKind.HOMECOMING, "de", nat("de")))
    }

    @Test fun deskAsksOnceAfterAGoodFirstAnswerThenAcknowledges() {
        val d = Desk(Random(7), listOf("purpose", "days", "home"), arrival = true, howlang = true)
        turn(d, handover = true)
        val ask = turn(d, Cue.GOOD)
        assertEquals("ask.howlang", group(ask.question))
        assertEquals(HOWLANG, d.topic)
        val ack = turn(d, Cue.GOOD)
        assertEquals("ack.howlang", group(ack.prefix))
        assertEquals("ask.days.visit", group(ack.question))
        repeat(6) { assertNotEquals("ask.howlang", group(turn(d, Cue.GOOD).question)) }
    }

    @Test fun deskNeverAsksAfterABadFirstAnswerOrWhenNotDue() {
        for (bad in listOf(Cue.DODGE, Cue.UNCLEAR, Cue.WRONG_LANGUAGE, Cue.CONTRA_FIRST)) {
            val d = Desk(Random(7), listOf("purpose", "days", "home"), arrival = true, howlang = true)
            turn(d, handover = true)
            val all = listOf(turn(d, bad, fact = FactId.PURPOSE)) + List(6) { turn(d, Cue.GOOD) }
            assertTrue("$bad", all.none { group(it.question) == "ask.howlang" })
        }
        val off = Desk(Random(7), listOf("purpose", "days"), arrival = true)
        turn(off, handover = true)
        assertEquals("ask.days.visit", group(turn(off, Cue.GOOD).question))
    }

    @Test fun contradictedLanguageReasonPressesTheItineraryFact() {
        val d = Desk(Random(7), listOf("purpose", "days", "home"), arrival = true, howlang = true)
        turn(d, handover = true); turn(d, Cue.GOOD)
        val m = turn(d, Cue.CONTRA_FIRST, fact = FactId.PURPOSE)
        assertEquals("press.arrival.purpose", group(m.prefix))
        assertEquals("ask.purpose.visit", group(m.question))
        // Asked once: a tell, an unplaced contradiction or a re-ask cue on the language answer moves on.
        for (cue in listOf(Cue.TELL, Cue.CONTRA_FIRST, Cue.UNCLEAR, Cue.DODGE)) {
            val t = Desk(Random(7), listOf("purpose", "days"), arrival = true, howlang = true)
            turn(t, handover = true); turn(t, Cue.GOOD)
            val next = turn(t, cue)
            assertEquals("$cue", "ask.days.visit", group(next.question))
            assertFalse("$cue", next.reask)
        }
    }

    @Test fun implantReactionAtMostOncePerTwoTurnsInTheOfficersTone() {
        val d = Desk(Random(7), arrival = false, tone = "sceptical")
        turn(d, handover = true)
        val prefixes = List(6) { group(turn(d, Cue.GOOD, Cue.IMPLANT).prefix) }
        assertEquals(listOf("implant.sceptical", "ack", "implant.sceptical", "ack", "implant.sceptical", "ack"), prefixes)
        // A contradiction outranks the reaction.
        val c = Desk(Random(7), tone = "amused")
        turn(c, handover = true)
        assertEquals("press.from", group(turn(c, Cue.IMPLANT, Cue.CONTRA_FIRST, fact = FactId.FROM).prefix))
    }

    @Test fun everyPersonaGetsAKnownTone() {
        val r = Random(1)
        val tones = List(500) { implantTone(Persona.random(r, it % 4)) }.toSet()
        assertEquals(IMPLANT_TONES.toSet(), tones)
    }

    @Test fun judgeCasesCoverEveryLanguage() {
        assertEquals(Language.all.map { it.code }.toSet(), JudgeCases.implant.keys)
        for ((l, a) in JudgeCases.implant) assertEquals(l, JudgeCases.IMPLANT_EXPECTED.size, a.size)
    }
}

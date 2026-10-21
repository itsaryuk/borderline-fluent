package com.lampensoft.borderlinefluent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**  phase 3: a chain's leg 2 flies in from airport A, so its city and country are part of the cover. */
class ChainCoverTest {
    private val desk = Country.of("portugal").primary()
    private fun id(nat: String) = Identity.roll(Random(1), Nationality.find(nat)!!, Gender.WOMAN, NameList.FEMALE)
    private val leg2 = Backstory.random(Random(3), desk, id("usa"), "Ireland", "Dublin")

    @Test fun leg2DepartsFromTheCityAndCountryOfA() {
        assertEquals(TripKind.ARRIVAL, leg2.kind)
        assertEquals("Ireland", leg2.from)
        assertEquals("Dublin", leg2.fromCity)
        assertTrue(leg2.fact(FactId.FROM).let { "Dublin" in it && "Ireland" in it })
        assertTrue("ダブリン" in leg2.fact(FactId.FROM, "ja"))
        assertTrue("Dublin, Ireland" in leg2.card())
        assertEquals("Dublin, Ireland", coverFrom(leg2, "en"))
        // Ordinary trips keep the country alone.
        val plain = Backstory.random(Random(3), desk, id("usa"), "Ireland")
        assertEquals("", plain.fromCity)
        assertEquals("Flying in from: Ireland", plain.fact(FactId.FROM))
        // A flight home on leg 2 stayed in A's city.
        val home = Backstory.random(Random(3), desk, id("portugal"), "Ireland", "Dublin")
        assertEquals(TripKind.HOMECOMING, home.kind)
        assertEquals("Dublin", home.city)
        assertEquals("", home.fromCity)
    }

    @Test fun theDepartureCitySurvivesASaveAndOldSavesHaveNone() {
        val p = leg2.preparation(Persona.TRAINING)
        val s = CareerSnapshot(booking = Booking("portugal", "lis", "pt_rm", p))
        assertEquals("Dublin", decoded(CareerJson.encode(s)).booking!!.preparation.fromCity)
        assertEquals(leg2, p.restore(desk.country, desk.lang))
        val old = org.json.JSONObject(CareerJson.encode(s)).also { it.getJSONObject("booking").getJSONObject("preparation").remove("fromCity") }
        assertEquals("", decoded(old.toString()).booking!!.preparation.fromCity)
    }

    @Test fun theRetinalDisplayMayNameAsCountry() {
        fun breaks(t: String, en: String) = breaksCover(Hint(t, "", en), leg2, desk, "en")
        assertFalse(breaks("De Dublin, na Irlanda.", "From Dublin, in Ireland."))
        assertTrue(breaks("De Espanha.", "From Spain."))
    }

    private class Fake(vararg replies: String) {
        private val replies = ArrayDeque(replies.toList())
        val systems = mutableListOf<String>()
        val ask: OneShot = { system, _, _ -> systems += system; this.replies.removeFirst() }
    }
    private fun line(matches: Boolean, clarity: Int = 2) = "FACT=${leg2.factIds().indexOf(FactId.FROM) + 1} MATCHES=${if (matches) "yes" else "no"} " +
        "ANSWERED=yes LANGUAGE=yes CLARITY=$clarity TRICK=no"

    @Test fun theJudgeSeesTheCityAndAGarbledAnswerIsNeverALie() = runBlocking {
        val q = "Where are you flying in from?"
        val f = Fake(line(true))
        assertFalse(Judge(Destination.forLanguage("en"), leg2, f.ask, LangId { listOf("en") }).judge(q, "From Dublin.", FactId.FROM).check.contradiction)
        assertTrue(f.systems.single().let { "Dublin" in it && "Ireland" in it })
        val g = Fake(line(false, clarity = 0), "no", "no", "no")
        assertFalse(Judge(Destination.forLanguage("en"), leg2, g.ask, LangId { listOf("en") }).judge(q, "From... uh... Dub... the...", FactId.FROM).check.contradiction)
    }

    @Test fun benchCasesCoverEveryLanguage() {
        assertEquals(Language.all.map { it.code }.toSet(), JudgeCases.chainFrom.keys)
        for ((l, cases) in JudgeCases.chainFrom) {
            assertEquals(l, listOf(false, false, true, false), cases.map { it.conflict })
            assertTrue(l, cases.all { it.story.fromCity == "Dublin" && it.story.from == "Ireland" && it.topic == FactId.FROM })
        }
    }
}

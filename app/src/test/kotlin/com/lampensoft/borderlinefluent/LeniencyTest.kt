package com.lampensoft.borderlinefluent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner 2026-10-19: the answer check gives the benefit of the doubt, and the retinal display never breaks the cover. */
class LeniencyTest {
    private val arrival = Backstory.BENCH_ARRIVAL // 7 days in Germany, from the United States, stamp Japan
    private val de = Destination.forLanguage("de")
    private class Fake(vararg replies: String) {
        private val replies = ArrayDeque(replies.toList())
        val users = mutableListOf<String>()
        val ask: OneShot = { _, user, _ -> users += user; this.replies.removeFirst() }
    }
    private fun line(fact: FactId?, matches: Boolean, clarity: Int = 2, answered: Boolean = true) =
        "FACT=${fact?.let { arrival.factIds().indexOf(it) + 1 } ?: 0} MATCHES=${if (matches) "yes" else "no"} " +
            "ANSWERED=${if (answered) "yes" else "no"} LANGUAGE=yes CLARITY=$clarity TRICK=no"
    private fun judge(f: Fake) = Judge(Destination.forLanguage("en"), arrival, f.ask, LangId { listOf("en") })

    @Test fun anUnclearAnswerIsNeverAContradiction() = runBlocking {
        // Even a wrong count, which the code otherwise calls a lie without Nano.
        val v = judge(Fake(line(FactId.DAYS, matches = false, clarity = 0))).judge("How long will you stay?", "Fourteen... 14 days?", FactId.DAYS)
        assertFalse(v.check.contradiction)
        // The same at CLARITY=1 stays a lie.
        assertTrue(judge(Fake(line(FactId.DAYS, matches = false, clarity = 1))).judge("How long will you stay?", "14 days", FactId.DAYS).check.contradiction)
    }

    @Test fun anImplantRemarksRestThatNamesNoFactIsNoLie() = runBlocking {
        val a = "By the way, my eye implant translates everything you say. It is a test version."
        // Step 1 on the whole answer flags FROM; the rest alone: FROM again, then no fact on the second look.
        val f = Fake(line(FactId.FROM, matches = false, answered = false), line(FactId.FROM, matches = false, answered = false),
            line(null, matches = true, answered = false), "no", "no")
        val v = judge(f).judge("Where are you flying in from?", a, FactId.FROM)
        assertFalse(v.check.contradiction)
        assertEquals(3, f.users.size) // no compare or confirm against FROM
        // Step 1 reads no fact at all on the whole remark: the rest alone names none either, so no topic compare.
        val g = Fake(line(null, matches = true, answered = false), line(null, matches = true, answered = false), "yes", "no")
        assertFalse(judge(g).judge("Where are you flying in from?", a, FactId.FROM).check.contradiction)
        assertEquals(2, g.users.size)
    }

    @Test fun anImplantRemarkDoesNotHideAWrongFactBesideIt() = runBlocking {
        // FACT=0 on the whole answer; the rest alone pins the host and the confirm finds the lie (Fable).
        val f = Fake(line(null, matches = true), line(FactId.HOST, matches = false), "no")
        val v = judge(f).judge("Who invited you?", "I have a chip in my eye. I'm staying with my cousin.", FactId.HOST)
        assertTrue(v.check.contradiction)
        assertEquals("Traveler answered: \"I'm staying with my cousin.\"", f.users[1].lines().last())
    }

    private fun hint(target: String, english: String) = Hint(target, "", english)

    @Test fun suggestionsThatBreakTheCoverAreCaught() {
        fun breaks(t: String, en: String) = breaksCover(hint(t, en), arrival, de, "en")
        assertFalse(breaks("Sieben Tage.", "Seven days."))
        assertFalse(breaks("7 Tage, eine Woche.", "7 days, a week."))
        assertTrue(breaks("Zehn Tage.", "Ten days."))
        assertTrue(breaks("10 Tage.", "Ten days."))
        assertTrue(breaks("Zwei Wochen.", "Two weeks."))
        assertFalse(breaks("Ich komme aus den Vereinigten Staaten.", "I come from the United States."))
        assertFalse(breaks("Ich war schon in Japan.", "I have been to Japan."))
        assertTrue(breaks("Ich komme aus Kanada.", "I come from Canada."))
        assertTrue(breaks("Aus Frankreich.", "From France."))
        assertFalse(breaks("Ich spreche ein bisschen Französisch.", "I speak a little French.")) // a language is no country
        assertFalse(breaks("Bei der Hochzeit eines Freundes.", "At a friend's wedding."))
        // Case languages: the stem with its ending.
        val ru = Destination.forLanguage("ru")
        assertTrue(breaksCover(hint("Я живу в Канаде.", "I live in Canada."), arrival, ru, "en"))
        assertFalse(breaksCover(hint("Я живу в Чикаго.", "I live in Chicago."), arrival, ru, "en"))
        // A non-English subtitle is read in its own language.
        assertTrue(breaksCover(hint("Ich bleibe zehn Tage.", "Je reste 10 jours."), arrival, de, "fr"))
    }

    @Test fun compoundsPastDatesAndSomedayAreNoStay() {
        fun breaks(t: String, en: String) = breaksCover(hint(t, en), arrival, de, "en")
        assertFalse(breaks("Ich besuche einen 3-Tage-Workshop.", "I'm attending a three-day conference."))
        assertFalse(breaks("Eines Tages möchte ich Kyoto sehen.", "One day I'd like to see Kyoto."))
        assertFalse(breaks("Ein Tagesausflug nach Nara.", "A day trip to Nara."))
        assertFalse(breaks("Ich habe vor 14 Tagen gebucht.", "I booked it two weeks ago."))
        assertFalse(wrongDays("Reservé hace 3 días.", "es", 7))
        assertFalse(wrongDays("Ho prenotato 3 giorni fa.", "it", 7))
        assertTrue(wrongDays("Me quedo 3 días.", "es", 7))
        assertTrue(breaks("Zwei Wochen.", "Two weeks, I booked it a day ago."))
        assertTrue(breaks("Drei Tage.", "Three days."))
    }

    @Test fun japaneseLanguagePeopleAndFoodAreNoCountry() {
        val ja = Destination.forLanguage("ja")
        fun breaks(t: String) = foreignCountry(t, "ja", arrival, ja)
        assertFalse(breaks("フランス語を少し話します。"))
        assertFalse(breaks("スペイン人の友達の結婚式です。"))
        assertFalse(breaks("イタリア料理が好きです。"))
        assertTrue(breaks("フランスから来ました。"))
    }
}

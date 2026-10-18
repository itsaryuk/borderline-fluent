package com.lampensoft.borderlinefluent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SuggestionLinesTest {
    private val english = LangId { listOf("en") }
    @Test fun keepsTwoNumberedRepliesAndDropsThird() = runBlocking {
        val rows = suggestionLines("2: Five days. | Five days.\n1: Two days. | Two days.\n3: A week. | A week.", "en", "en", english)
        assertEquals(listOf("Two days.", "Five days."), rows.map { Models.option(it).target })
    }
    @Test fun brokenReplyIsReplacedBySameSlotLineButNeverByThird() = runBlocking {
        val rows = suggestionLines("1: |\n1: Two days. | Two days.\n2: Five days. | Five days.\n3: A week. | A week.", "en", "en", english)
        assertEquals(listOf("Two days.", "Five days."), rows.map { Models.option(it).target })
    }
    @Test fun coverContradictingTrapCanOccupyEitherSlot() = runBlocking {
        for (trap in 1..2) {
            val raw = (1..2).joinToString("\n") { "$it: ${if (it == trap) "Six months." else "Two days."} | A stay." }
            assertEquals(2, suggestionLines(raw, "en", "en", english).size)
        }
    }
    @Test fun promptRequestsTwoDistinctShortReplies() {
        val prompt = suggestPrompt(Destination(Backstory.BENCH.country, Backstory.BENCH.country.airports.first()), Backstory.BENCH)
        assertTrue(prompt.contains("exactly 2 clearly different replies"))
        assertTrue(prompt.contains("plain and direct"))
        assertTrue(prompt.contains("Exactly 2 lines numbered 1: 2:"))
        assertFalse(prompt.contains("3:"))
    }

    private val ruId = LangId { t -> if (t.any { it in 'a'..'z' }) listOf("en") else listOf("ru") }
    private fun ru(raw: String) = runBlocking { suggestionLines(raw, "ru", "en", ruId).map(Models::option) }
    @Test fun bareTargetsAreShownWithoutSubtitle() {
        assertEquals(listOf(Hint("Хорошо, спал.", "Khorosho, spal.", ""), Hint("Да, нормально, спасибо.", "Da, normalno, spasibo.", "")), ru("1: Хорошо, спал.\n2: Да, нормально, спасибо."))
        assertEquals(listOf("Да, хорошо.", "Да, не очень."), ru("1. Да, хорошо.\n2. Да, не очень.").map { it.target })
    }
    @Test fun formattedPairAfterBarePairIsStillUsed() {
        val r = ru("1: Я к другу.\n2: Хочу к другу.\n\n1: Я к другу. | Ya k drugu. | I am to a friend.\n2: Хочу к другу. | Khochu k drugu. | I want to go to a friend.")
        assertEquals(2, r.size)
    }
    @Test fun slashSeparatorAndExtraBars() {
        assertEquals(Hint("Я учу русский.", "Ya uchu russkiy.", "I am learning Russian."), Models.option("1: Я учу русский. / Ya uchu russkiy. | I am learning Russian."))
        assertEquals(Hint("Я родился в 2000.", "Ya rodilis' v dvadtsat' 2000.", "I was born in 2000."), Models.option("1: Я родился в 2000. | Ya rodilis' v dvadtsat' | 2000. | I was born in 2000."))
        assertEquals(2, ru("1: Я учу русский. / Ya uchu russkiy. | I am learning Russian.\n2: Я родился в 2000. | Ya rodilis' v dvadtsat' | 2000. | I was born in 2000.").size)
    }
    @Test fun modelReadingIsReplacedForRussian() {
        val r = ru("1: Я к другу. | Ya k drugu | I am to a friend.\n2: Хочу к другу. | Hochu k drugu. | I want a friend.")
        assertEquals(listOf("Ya k drugu.", "Khochu k drugu."), r.map { it.reading })
        assertEquals("I am to a friend.", r[0].english)
    }
}

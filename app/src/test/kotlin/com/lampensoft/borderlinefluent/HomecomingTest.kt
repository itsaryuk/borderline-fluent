package com.lampensoft.borderlinefluent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomecomingTest {
    @Test fun `plain homecoming answers match`() {
        listOf(
            "en" to "I am returning home.", "en" to "Returning home now!", "en" to "I'm coming home", "en" to "  um, yes... I’m  COMING home! ",
            "de" to "Ich komme nach Hause.", "de" to "Ich kehre jetzt nach Hause zurück!", "fr" to "Je rentre chez moi.", "fr" to "Euh, je rentre a la maison",
            "it" to "Sto tornando a casa", "es" to "Regreso a casa.", "es" to "Regreso a casa ahora", "pt" to "Estou voltando pra casa",
            "pl" to "Wracam do domu", "pl" to "Wracam teraz do domu!", "ru" to "Я возвращаюсь домой.", "ru" to "Да, возвращаюсь домой!",
            "uk" to "Я повертаюся додому", "tr" to "Eve dönüyorum.", "ar" to "أنا عائد إلى المنزل.", "ja" to "はい、家に帰ります。", "zh" to "我回家。", "zh" to "我 正在 回家！",
        ).forEach { (l, a) -> assertTrue("$l: $a", isHomecoming(a, l)) }
    }

    @Test fun `answers with extra content or another language do not match`() {
        listOf(
            "en" to "I'm coming home from a conference in Toronto.", "en" to "I am returning home after five days.", "en" to "Home", "en" to "I live in Lindenberg",
            "de" to "Ich komme nach Hause von einer Konferenz.", "pl" to "Wracam do domu z Toronto", "ru" to "Я возвращаюсь домой из Японии",
            "ja" to "仕事で出張して家に帰ります", "zh" to "我回家看我妈妈", "ru" to "I am returning home", "en" to "",
        ).forEach { (l, a) -> assertFalse("$l: $a", isHomecoming(a, l)) }
    }

    @Test fun `judge skips the model for homecoming and still calls it for more`() = runBlocking {
        var calls = 0
        val ask: OneShot = { _, _, _ -> calls++; "FACT=2 MATCHES=yes ANSWERED=yes LANGUAGE=yes CLARITY=2 TRICK=no" }
        val c = Destination.forLanguage("en")
        val judge = Judge(c, Backstory.BENCH, ask, LangId { listOf("en") })
        val v = judge.judge("Where are you coming from?", "I am returning home.")
        assertEquals(0, calls); assertEquals(null, v.fact); assertTrue(v.check.answered && !v.check.contradiction && v.check.inLanguage)
        judge.judge("Where are you coming from?", "I'm coming home from a conference in Toronto.")
        assertEquals(1, calls)
    }

    @Test fun `a citizen refused at home is held, never flown home`() {
        for (room in listOf(false, true)) {
            assertEquals(R.string.ending_refused_home, endingText(Decision.REFUSED, room, TripKind.HOMECOMING))
            assertEquals(R.string.ending_caught, endingText(Decision.CAUGHT, room, TripKind.HOMECOMING))
        }
        assertEquals(R.string.ending_refused_company, endingText(Decision.REFUSED, true, TripKind.ARRIVAL))
        assertEquals(R.string.ending_refused, endingText(Decision.REFUSED, false, TripKind.ARRIVAL))
    }
}

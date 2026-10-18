package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTest {
    @Test fun uiLanguageWhenGameHasItAndCountryDiffers() {
        assertEquals("ru", subtitleLang("ru", "de"))
        assertEquals("zh", subtitleLang("zh-Hant", "de"))
    }

    @Test fun englishWhenSameAsCountryOrUnknown() {
        assertEquals("en", subtitleLang("ru", "ru"))
        assertEquals("en", subtitleLang("ko", "de"))
    }

    @Test fun fixedLinesFollowSubtitle() {
        assertEquals(Language.of("de").sayPass.first, Language.of("ru").lastWords(Decision.PASS, "de", TripKind.HOMECOMING).second)
        assertEquals(Language.of("en").sayPass.first, Language.of("ru").lastWords(Decision.PASS, "en", TripKind.HOMECOMING).second)
    }

    @Test fun everyEndingUsesTheSubtitleLanguagesOwnFixedLine() {
        for (target in Language.all) for (sub in Language.all) for (d in Decision.entries) for (crashed in listOf(false, true)) {
            val pair = target.lastWords(d, sub.code, TripKind.HOMECOMING, crashed)
            assertEquals(target.lastWords(d, TripKind.HOMECOMING, crashed).first, pair.first)
            assertEquals(sub.lastWords(d, TripKind.HOMECOMING, crashed).first, pair.second)
            // The broken unit's stock English refusal is the joke (owner): only uncrashed units must stay refusal-free.
            if (!crashed) { assertFalse(modelRefusal(pair.first)); assertFalse(modelRefusal(pair.second)) }
        }
        assertEquals("Willkommen… Willkommen… Willkomm— I'm sorry. I can't help with that.",
            Language.of("de").lastWords(Decision.REFERRAL, "en", TripKind.HOMECOMING, crashed = true).first)
    }

    /** A homecoming is welcomed home, never "enjoy your stay"; a visitor is welcomed to a stay, never home. */
    @Test fun passWordsWelcomeHome() {
        assertTrue(Language.all.all { it.sayPass.first.isNotBlank() && !it.sayPass.second.contains("stay") && !it.sayPass.second.contains("trip") })
        for (l in Language.all) {
            val (line, en) = l.lastWords(Decision.PASS, TripKind.ARRIVAL)
            assertTrue(l.code, line.isNotBlank() && line != l.sayPass.first && !en.contains("home") && en.contains("elcome"))
            assertFalse(l.code, '{' in line)
            assertEquals(l.sayRefused, l.lastWords(Decision.REFUSED, TripKind.ARRIVAL))
        }
        assertEquals(Language.of("de").lastWords(Decision.PASS, TripKind.ARRIVAL).first,
            Language.of("ru").lastWords(Decision.PASS, "de", TripKind.ARRIVAL).second)
    }

    /** UI strings get names in the UI language; unsupported UIs fall back to English; prompts keep English country names. */
    @Test fun uiNamesFollowUiLanguage() {
        assertEquals("Deutsch", Language.of("de").uiName("de"))
        assertEquals("Polnisch", Language.of("pl").uiName("de"))
        assertEquals("Polish", Language.of("pl").uiName("en"))
        assertEquals("Polish", Language.of("pl").uiName("ko"))
        assertEquals("polaco", Language.of("pl").uiName("pt")) // European Portuguese, like values-pt
        assertEquals("Japan", Country.of("japan").uiName("de"))
        assertEquals("Kanada", Country.of("canada").uiName("de"))
        assertEquals("Canada", Country.of("canada").uiName("en"))
        assertEquals("UA", Country.of("ukraine").iso)
        assertEquals("German", Country.of("de").primary().language) // prompts stay English
        assertTrue(Country.all.all { it.id in Country.ISO })
    }
}

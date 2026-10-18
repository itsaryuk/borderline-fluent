package com.lampensoft.borderlinefluent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NonAnswerTest {
    private class Fake(vararg replies: String) {
        private val replies = ArrayDeque(replies.toList())
        var calls = 0
        val ask: OneShot = { _, _, _ -> calls++; this.replies.removeFirst() }
    }

    @Test fun `whole non-answers match in every language`() {
        listOf(
            "ru" to "Я забыл.", "ru" to "Я не понимаю, что ты говоришь мне.", "ru" to "Не знаю.", "ru" to "Извините, повторите ещё раз, пожалуйста.",
            "en" to "I forgot.", "en" to "Sorry?", "en" to "I don't understand what you're saying to me.", "en" to "Can you repeat the question, please?",
            "de" to "Ich habe es vergessen.", "de" to "Wie bitte?", "de" to "Das weiß ich nicht.", "fr" to "Je ne sais pas.", "fr" to "Pardon, je ne comprends pas.",
            "es" to "No sé.", "es" to "¿Perdón? No entiendo.", "it" to "Non lo so.", "it" to "Scusi, non ho capito.", "pt" to "Não sei.", "pt" to "Desculpe, não entendi.",
            "pl" to "Nie wiem.", "pl" to "Przepraszam, nie rozumiem.", "uk" to "Я не пам'ятаю.", "uk" to "Перепрошую, повторіть, будь ласка.",
            "tr" to "Bilmiyorum.", "tr" to "Efendim? Anlamadım.", "ar" to "لا أعرف.", "ar" to "عفواً، لم أفهم.",
            "ja" to "わかりません。", "ja" to "すみません、もう一度お願いします。", "zh" to "我不知道。", "zh" to "对不起，我没听懂，请再说一遍。",
        ).forEach { (l, a) -> assertTrue("$l: $a", isNonAnswer(a, l)) }
    }

    @Test fun `a claim, a number or another language never matches`() {
        listOf(
            "en" to "I forgot, I'm here for a conference.", "en" to "I was on holiday.", "en" to "I forgot my passport.", "en" to "Sorry, 14 days.",
            "en" to "What a question, business.", "ru" to "Я забыл, я на конференции.", "ru" to "Путешествую.", "ru" to "I forgot.",
            "de" to "Ich habe meinen Pass vergessen.", "es" to "Como turista.", "it" to "Come turista.", "zh" to "我不知道他在哪里开会", "ja" to "会議です", "en" to "",
        ).forEach { (l, a) -> assertFalse("$l: $a", isNonAnswer(a, l)) }
        //: a tail word that answers ("just me" for who travels, "вас" = I don't know *you*) is a claim.
        listOf("en" to "Sorry, just me.", "en" to "Sorry, I did.", "en" to "Sorry, yes.", "en" to "I don't know you.", "ru" to "Я не знаю вас.",
            "uk" to "Я не знаю вас.", "de" to "Entschuldigung, nur ich.", "ru" to "Извините, я.",
        ).forEach { (l, a) -> assertFalse("tail $l: $a", isNonAnswer(a, l)) }
        // ...but pronouns after "I don't understand" stay a non-answer.
        assertTrue(isNonAnswer("I don't understand you.", "en"))
        assertTrue(isNonAnswer("Я не понимаю вас.", "ru"))
        //: a standalone negation reverses the stem ("I haven't forgotten").
        listOf("de" to "Ich habe es nicht vergessen.", "it" to "Non ho dimenticato.", "pl" to "Nie zapomniałem.", "en" to "I didn't forget.",
            "en" to "I haven't forgotten.", "fr" to "Je n'ai pas oublié.", "ru" to "Я не забыл.", "uk" to "Я не забув.", "es" to "No me olvidé.",
            "pt" to "Não esqueci.",
        ).forEach { (l, a) -> assertFalse("negated $l: $a", isNonAnswer(a, l)) }
        assertEquals("en", nonAnswerLang("I forgot.", "ru"))
        assertEquals("ru", nonAnswerLang("Я забыл.", "ru"))
        assertNull(nonAnswerLang("Я забыл.", "de"))
    }

    @Test fun `the two device cases are dodges, not purpose lies, and skip the model`() = runBlocking {
        val ru = Destination.forLanguage("ru")
        for (a in listOf("Я забыл.", "Я не понимаю, что ты говоришь мне.")) {
            // Device: step 1 FACT=2 MATCHES=no ANSWERED=no, step 3 "no" -> a confirmed PURPOSE lie.
            val fake = Fake("FACT=2 MATCHES=no ANSWERED=no LANGUAGE=no CLARITY=1 TRICK=no ODD=no IMPLANT=none", "no")
            val v = Judge(ru, Backstory.BENCH_ARRIVAL, fake.ask, LangId { listOf("ru") })
                .judge("Это не та цель… Какова цель Вашего визита?", a, FactId.PURPOSE)
            assertEquals(0, fake.calls)
            assertNull(v.fact)
            assertEquals(Check(answered = false, contradiction = false, clarity = 2, inLanguage = true), v.check)
        }
        // English at the Russian desk: still a dodge, and not in the desk's language.
        val en = Judge(ru, Backstory.BENCH_ARRIVAL, Fake().ask, LangId { listOf("en") }).judge("Какова цель Вашего визита?", "I don't know.", FactId.PURPOSE)
        assertFalse(en.check.contradiction || en.check.answered || en.check.inLanguage)
    }

    @Test fun `a bare expletive is a dodge, a claim after it is not`() = runBlocking {
        listOf("pl" to "Kurwa.", "pl" to "Kurwa, kurwa!", "pl" to "O kurwa, nie wiem.", "en" to "Shit.", "en" to "Oh my God!", "en" to "Damn, sorry?",
            "de" to "Scheiße!", "fr" to "Putain...", "es" to "¡Joder!", "it" to "Porca miseria.", "pt" to "Porra!", "ru" to "Блин.", "uk" to "Курва.",
            "tr" to "Kahretsin!", "ar" to "تبًا!", "ja" to "くそっ！", "zh" to "我靠！",
        ).forEach { (l, a) -> assertTrue("$l: $a", isNonAnswer(a, l)) }
        listOf("pl" to "Kurwa, u znajomego.", "pl" to "Kurwa, 5 dni.", "en" to "Shit, with a friend.", "en" to "Damn hotel.", "de" to "Scheiße, im Hotel.",
            "ru" to "Блин, у друга.", "zh" to "我靠窗坐。", "en" to "Crappy hotel.",
        ).forEach { (l, a) -> assertFalse("$l: $a", isNonAnswer(a, l)) }
        // Device: "Kurwa." to "Gdzie Pan będzie mieszkał?" came back FACT=4 MATCHES=no ANSWERED=no and step 3 confirmed a STAY lie.
        val fake = Fake()
        val v = Judge(Destination.forLanguage("pl"), Backstory.BENCH_ARRIVAL, fake.ask, LangId { listOf("pl") }).judge("Gdzie Pan będzie mieszkał?", "Kurwa.", FactId.STAY)
        assertEquals(0, fake.calls)
        assertNull(v.fact)
        assertEquals(Check(answered = false, contradiction = false, clarity = 2, inLanguage = true), v.check)
    }

    @Test fun `a non-answer that is English too gets its language from language ID`() = runBlocking {
        //: "Pardon me" at a French desk matched French first and passed as French.
        val fr = Destination.forLanguage("fr")
        suspend fun check(a: String, id: String) = Judge(fr, Backstory.BENCH_ARRIVAL, Fake().ask, LangId { listOf(id) }).judge("Pourquoi venez-vous ?", a, FactId.PURPOSE).check
        assertFalse(check("Pardon me.", "fr").inLanguage) // "me" after a bare pardon is no French non-answer: English
        assertFalse(check("Pardon ?", "en").inLanguage)
        assertTrue(check("Pardon ?", "fr").inLanguage)
        assertTrue(check("Je ne sais pas.", "en").inLanguage) // not English: the desk's own words, no ID
        for (a in listOf("Pardon me.", "Pardon ?", "Je ne sais pas.")) assertFalse(check(a, "fr").contradiction || check(a, "fr").answered)
    }

    @Test fun `a claim after a non-answer and a plain lie still go through the model`() = runBlocking {
        val en = Destination.forLanguage("en")
        val claim = Fake("FACT=2 MATCHES=yes ANSWERED=yes LANGUAGE=yes CLARITY=2 TRICK=no")
        val c = Judge(en, Backstory.BENCH_ARRIVAL, claim.ask, LangId { listOf("en") }).judge("Why are you here?", "I forgot, I'm here for a conference.", FactId.PURPOSE)
        assertEquals(1, claim.calls)
        assertFalse(c.check.contradiction)
        assertEquals(FactId.PURPOSE, c.fact)
        // ANSWERED=no on the asked fact buys a second look without the question; it keeps PURPOSE, so step 3 judges.
        val lie = Fake("FACT=2 MATCHES=no ANSWERED=no LANGUAGE=yes CLARITY=2 TRICK=no", "FACT=2 MATCHES=no ANSWERED=no LANGUAGE=yes CLARITY=2 TRICK=no", "no")
        val l = Judge(en, Backstory.BENCH_ARRIVAL, lie.ask, LangId { listOf("en") }).judge("Why are you here?", "I was on holiday.", FactId.PURPOSE)
        assertTrue(l.check.contradiction)
        assertEquals(FactId.PURPOSE, l.fact)
        assertEquals(3, lie.calls)
    }

    @Test fun `two non-answers are dodges that cost suspicion, never a refusal for bad answers`() {
        val m = OfficerMind(Persona(patience = 0.8, suspicion = 0.2, strictness = 0.7, tolerance = 0.5, busy = 0.3))
        val dodge = Check(answered = false, contradiction = false, clarity = 2, inLanguage = true)
        repeat(2) { m.onAnswer(Delivery(2000, 1.0, "Я забыл.")); m.onCheck(dodge, null, "Я забыл.") }
        assertEquals(0, m.badAnswers)
        assertEquals(2, m.dodges)
        assertTrue(Cue.DODGE in m.takeCues())
        assertNull(m.decision)
    }
}

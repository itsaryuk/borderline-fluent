package com.lampensoft.borderlinefluent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GeneratedTextTest {
    @Test fun languageDecisionIsStrictForGeneratedOutput() {
        assertTrue(generatedLanguageOk("Guten Tag.", "de", listOf("de", "en")))
        assertTrue(generatedLanguageOk("你好。", "zh", listOf("zh-Hans")))
        assertFalse(generatedLanguageOk("Guten Tag.", "de", listOf("en", "de")))
        assertFalse(generatedLanguageOk("Guten Tag.", "de", listOf("und")))
        assertFalse(generatedLanguageOk("Guten Tag.", "de", emptyList()))
        assertFalse(generatedLanguageOk("Wasz pobyt był długi, сэр?", "pl", listOf("pl")))
        assertFalse(generatedLanguageOk("Guesthouse przy morzu", "pl", listOf("pl"), listOf(listOf("en"), listOf("pl"))))
    }

    @Test fun refusalsAreRejectedInAllSupportedLanguages() = runBlocking {
        val refusals = mapOf("en" to "I'm sorry. I can't help with that.", "de" to "Es tut mir leid.",
            "fr" to "Je suis désolé.", "es" to "Lo siento.", "it" to "Mi dispiace.", "pt" to "Não posso ajudar.",
            "pl" to "Nie mogę pomóc.", "ru" to "Я не могу помочь.", "uk" to "Я не можу допомогти.",
            "tr" to "Üzgünüm.", "ar" to "أنا آسف", "ja" to "申し訳ありません。", "zh" to "抱歉。")
        for ((lang, text) in refusals) assertFalse(generatedTextOk(text, lang, LangId { listOf(lang) }))
        assertTrue(modelRefusal("As an AI, I cannot do that."))
        assertTrue(modelRefusal("I’m sorry"))
    }

    @Test fun validatesBothHintSidesAndDropsOnlyBadOptions() = runBlocking {
        val id = LangId { text -> when {
            text.contains("Guesthouse") -> if (text == "Guesthouse") listOf("en") else listOf("pl")
            text.any { it in 'А'..'я' } -> listOf("ru")
            else -> listOf("pl")
        } }
        assertFalse(generatedHintOk(Hint("Guesthouse przy morzu", "", "Pensjonat przy morzu"), "pl", "pl", id))
        assertFalse(generatedHintOk(Hint("Z partnerką", "", "С партнером"), "pl", "en", id))
        assertTrue(generatedHintOk(Hint("Z partnerką", "", "С партнером"), "pl", "ru", id)) // language only, not gender semantics
    }
}

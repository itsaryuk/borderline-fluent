package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QueueOutcomeTest {
    private val uiLanguages = setOf("en", "de", "fr", "es", "it", "pt", "pl", "ru", "uk", "tr", "ar", "ja", "zh")

    @Test fun everyChoiceHasAnOutcomeInEveryUiLanguageWithoutSpoilersOrTemplates() {
        assertEquals(108, Encounters.all.size)
        for (encounter in Encounters.all) for (i in 0..1) {
            val outcome = encounter.choice(i).outcome
            assertEquals("${encounter.id}/$i languages", uiLanguages, outcome.keys)
            for (ui in uiLanguages) {
                val text = outcome.getValue(ui)
                val where = "${encounter.id}/$i/$ui"
                assertFalse("$where is blank", text.isBlank())
                assertFalse("$where contains a spoiler", text.contains("Free AI", ignoreCase = true))
                assertFalse("$where contains a template", text.contains('{'))
            }
        }
    }

    @Test fun outcomesUseTheUiLanguageAndFallBackToEnglishForEveryPlayerGender() {
        for (encounter in Encounters.all) for (gender in Gender.entries) {
            val scene = QueueScene(encounter, gender)
            for (i in 0..1) {
                for (ui in uiLanguages) assertEquals("${encounter.id}/$i/$ui/$gender",
                    encounter.choice(i).outcome.getValue(ui), scene.outcome(i, ui))
                assertEquals(encounter.choice(i).outcome.getValue("en"), scene.outcome(i, "unknown"))
            }
        }
    }
}

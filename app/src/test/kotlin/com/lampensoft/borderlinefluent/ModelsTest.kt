package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Test

/** Officer replies seen on Nano (room bench, German). */
class ModelsTest {
    @Test fun skipsBracketedLanguageThoughtsFromDeviceLog() {
        val r = "[DE: Die Freigabe ist ungewöhnlich schnell. Ich muss sicherstellen, dass alle notwendigen Daten erfasst wurden, bevor ich den Prozess beende.]\n" +
            "[EN: The clearance is unusually fast.]\nDE: Vielen Dank für Ihre Angaben.\nEN: Thank you for your information."
        assertEquals("Vielen Dank für Ihre Angaben.", Models.targetLine(r))
        assertEquals("Thank you for your information.", Models.englishLine(r))
        assertEquals("", Models.targetLine("[DE: Die Freigabe ist ungewöhnlich schnell. Ic"))
        assertEquals("", Models.targetLine("[DE: Die Freigabe ist ungewöhnlich schnell.]"))
        assertEquals("", Models.englishLine("[EN: The clearance is unusually fast.]"))
    }

    @Test fun unprefixedColonIsSpeech() = assertEquals("One thing: wait.", Models.targetLine("One thing: wait."))

    @Test fun skipsEchoedThoughtLine() {
        val r = "[Ich hoffe, dieser Prozess läuft reibungslos. Es ist wichtig, alle Details zu erfassen.]\n" +
            "DE: Beschreiben Sie einen Geruch aus Ihrer Kindheit.\nEN: Describe a smell from your childhood."
        assertEquals("Beschreiben Sie einen Geruch aus Ihrer Kindheit.", Models.targetLine(r))
        assertEquals("Describe a smell from your childhood.", Models.englishLine(r))
    }

    @Test fun skipsEnglishThoughtLine() =
        assertEquals("Ich verstehe.", Models.targetLine("[I am processing the data. Time for the final question.]\nDE: Ich verstehe.\nEN: I understand."))

    @Test fun ackOnly() = assertEquals("Ich verstehe.", Models.targetLine("DE: Ich verstehe.\nEN: I understand."))

    @Test fun firstPairWins() {
        val r = "DE: Ich verstehe.\nEN: I understand.\n\nDE: Was ist der Zweck Ihrer Reise?\nEN: What is the purpose of your trip?"
        assertEquals("Ich verstehe.", Models.targetLine(r))
        assertEquals("I understand.", Models.englishLine(r))
    }

    @Test fun englishOfficerAndNoPrefix() {
        assertEquals("Good evening.", Models.targetLine("EN: Good evening.\nEN: Good evening."))
        assertEquals("Guten Tag.", Models.targetLine("\nGuten Tag."))
    }
}

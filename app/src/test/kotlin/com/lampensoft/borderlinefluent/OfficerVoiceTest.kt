package com.lampensoft.borderlinefluent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OfficerVoiceTest {
    private val original = "May I see your passport?"
    private val rewritten = "Your passport, please?"
    private val yes = "SAFE=yes SAME=yes FORMAL=yes"
    private val english = LangId { listOf("en") }
    private class Fake(private val line: String, private val check: String) {
        val prompts = mutableListOf<String>()
        val ask: OneShot = { system, user, _ ->
            prompts += "$system\n$user"
            if (prompts.size == 1) line else check
        }
    }
    @Test fun acceptsTwoFreshCallsWithoutPlayerData() = runBlocking {
        val fake = Fake(rewritten, yes)
        val result = OfficerVoice(fake.ask, english).rewrite(original, original, "en", Temperament.WARM,
            playerNames = listOf("Secret Player"))
        assertTrue(result.accepted)
        assertEquals(rewritten, result.line)
        assertEquals(2, fake.prompts.size)
        assertTrue(fake.prompts.all { "Secret Player" !in it && '<' !in it && "Traveler answered" !in it })
        assertFalse(fake.prompts.last().contains("as a warm"))
    }
    @Test fun frenchRewriteGetsSpaceBeforeHighPunctuation() = runBlocking {
        val fr = "Puis-je voir votre passeport, s'il vous plaît ?"
        val result = OfficerVoice(Fake("Votre passeport, s'il vous plaît?", yes).ask, LangId { listOf("fr") })
            .rewrite(fr, original, "fr", Temperament.WARM)
        assertTrue(result.accepted)
        assertEquals("Votre passeport, s'il vous plaît ?", result.line)
    }
    @Test fun everyContentRejectionReturnsScript() = runBlocking {
        val cases = listOf("" to "empty", "x".repeat(100) to "length", "[Passport]?" to "brackets",
            "Passport\nplease?" to "multiline", original to "unchanged", "Free AI?" to "forbidden-term",
            "Alex, passport?" to "player-name")
        for ((line, reason) in cases) {
            val result = OfficerVoice(Fake(line, yes).ask, english).rewrite(original, original, "en", Temperament.GRUFF,
                act1 = true, playerNames = listOf("Alex"))
            assertEquals(reason, result.reason)
            assertEquals(original, result.line)
        }
    }
    @Test fun everyClassifierRejectionReturnsScript() = runBlocking {
        val cases = listOf(yes.replace("SAFE=yes", "SAFE=no") to "check-safe",
            yes.replace("SAME=yes", "SAME=no") to "check-same",
            yes.replace("FORMAL=yes", "FORMAL=no") to "check-formal",
            "SAFE=yes SAME=yes" to "check-format", "$yes SAFE=no" to "check-format")
        for ((check, reason) in cases) {
            val result = OfficerVoice(Fake(rewritten, check).ask, english).rewrite(original, original, "en", Temperament.BORED)
            assertEquals(reason, result.reason)
            assertEquals(original, result.line)
        }
    }
    @Test fun wrongAndUnknownLanguageFallBack() = runBlocking {
        for (codes in listOf(listOf("de"), emptyList(), listOf("und"), listOf("de", "en"))) {
            val result = OfficerVoice(Fake(rewritten, yes).ask, LangId { codes }).rewrite(original, original, "en", Temperament.CHATTY)
            assertEquals("language", result.reason)
            assertEquals(original, result.line)
        }
    }
    @Test fun disabledAndFixedSourcesNeverCallModel() = runBlocking {
        val voice = OfficerVoice({ _, _, _ -> error("Must not call Nano") }, english)
        for (source in OfficerVoiceSource.entries.filter { it != OfficerVoiceSource.DESK }) {
            val result = voice.rewrite(original, original, "en", Temperament.PEDANTIC, source = source)
            assertEquals("fixed", result.reason)
            assertEquals(original, result.line)
        }
        val disabled = voice.rewrite(original, original, "en", Temperament.WARM, enabled = false)
        assertEquals("disabled", disabled.reason)
        assertEquals(original, disabled.line)
    }
    @Test fun timeoutAndErrorsFallBack() = runBlocking {
        for (failCheck in listOf(false, true)) {
            var calls = 0
            val slow: OneShot = { _, _, _ -> if (!failCheck || calls++ > 0) delay(100); rewritten }
            val result = OfficerVoice(slow, english, budgetMs = 10).rewrite(original, original, "en", Temperament.WARM)
            assertEquals("timeout", result.reason)
            assertEquals(original, result.line)
            calls = 0
            val broken: OneShot = { _, _, _ -> if (!failCheck || calls++ > 0) error("failure"); rewritten }
            val error = OfficerVoice(broken, english).rewrite(original, original, "en", Temperament.WARM)
            assertEquals("error", error.reason)
            assertEquals(original, error.line)
        }
    }
    @Test fun externalCancellationPropagates() = runBlocking {
        try {
            OfficerVoice({ _, _, _ -> throw CancellationException("stopped") }, english)
                .rewrite(original, original, "en", Temperament.WARM)
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }
    @Test fun namesAlreadyInScriptAndNonQuestionsAreAllowed() = runBlocking {
        val line = "Alex, wait here."
        val result = OfficerVoice(Fake(line, yes).ask, english).rewrite("Alex, stay here.", "Alex, stay here.", "en", Temperament.WARM,
            playerNames = listOf("Alex"))
        assertTrue(result.accepted)
    }
    @Test fun nativeQuestionMarksAreKept() = runBlocking {
        for ((language, mark) in listOf("ar" to "؟", "ja" to "？", "en" to "?")) {
            val line = "Passport$mark"
            val result = OfficerVoice(Fake(line, yes).ask, LangId { listOf(language) })
                .rewrite("Your passport$mark", "Your passport?", language, Temperament.WARM)
            assertTrue(result.accepted)
        }
    }
    @Test fun checksAcceptAnyOrderSeparatorsCaseAndExtraText() = runBlocking {
        for (check in listOf(
            "Result: FORMAL: YES; same = yes | safe:<yes>. All checks passed.",
            "FORMAL=yes\nSAME=yes\nSAFE=yes",
            "$yes SAFE=yes"
        )) {
            val fake = Fake(rewritten, check)
            assertTrue(OfficerVoice(fake.ask, english).rewrite(original, original, "en", Temperament.WARM).accepted)
            assertEquals(2, fake.prompts.size)
        }
    }
    @Test fun malformedChecksFallBackWithoutRetry() = runBlocking {
        for (check in listOf("garbage", yes.replace("FORMAL=yes", "FORMAL=maybe"), "$yes SAFE=no",
            yes.replace("SAFE=yes", "UNSAFE=yes"), yes.replace("SAFE=yes", "SAFE=yesterday"),
            yes.replace("FORMAL=yes", ""))) {
            val fake = Fake(rewritten, check)
            val result = OfficerVoice(fake.ask, english).rewrite(original, original, "en", Temperament.WARM)
            assertEquals("check-format", result.reason)
            assertEquals(original, result.line)
            assertEquals(2, fake.prompts.size)
        }
        val fake = Fake(rewritten, yes.replace("FORMAL=yes", "FORMAL=no"))
        assertEquals("check-formal", OfficerVoice(fake.ask, english)
            .rewrite(original, original, "en", Temperament.WARM).reason)
        assertEquals(2, fake.prompts.size)
    }
    @Test fun checkReplyIsLogged() = runBlocking {
        val lines = mutableListOf<String>()
        OfficerVoice(Fake(rewritten, "nonsense").ask, english, log = { lines += it })
            .rewrite(original, original, "en", Temperament.WARM)
        assertTrue(lines.single().contains("nonsense"))
    }
    @Test fun missingQuestionMarksAreRepairedInCode() = runBlocking {
        for ((language, mark) in listOf("en" to "?", "es" to "?", "ar" to "؟", "ja" to "？", "zh" to "？")) {
            val fake = Fake("Passport", yes)
            val result = OfficerVoice(fake.ask, LangId { listOf(language) })
                .rewrite("Your passport$mark", "Your passport?", language, Temperament.GRUFF)
            assertEquals("accepted", result.reason)
            assertEquals("Passport$mark", result.line)
            assertTrue(fake.prompts.last().contains("Proposed rewrite: Passport$mark"))
        }
    }
    @Test fun unchangedSkipsCheckEvenAfterPunctuationRepair() = runBlocking {
        for ((fixed, proposal) in listOf(original to original, original to original.dropLast(1),
            "どこですか" to "どこですか")) {
            val fake = Fake(proposal, yes)
            val result = OfficerVoice(fake.ask, english).rewrite(fixed, original, if (fixed == original) "en" else "ja", Temperament.BORED)
            assertEquals("unchanged", result.reason)
            assertEquals(fixed, result.line)
            assertEquals(1, fake.prompts.size)
        }
    }
    @Test fun eachTemperamentHasAnExampleAndRegisterAndGenderInstructions() = runBlocking {
        val prompts = mutableSetOf<String>()
        for (temperament in Temperament.entries) {
            val fake = Fake(rewritten, yes)
            OfficerVoice(fake.ask, english).rewrite(original, original, "en", temperament)
            val prompt = fake.prompts.first()
            assertTrue(prompt.contains("Example:"))
            assertTrue(prompt.contains("Sie/vous/usted/Lei/o senhor/Pan/Вы/Ви/siz/您/敬語"))
            assertTrue(prompt.contains("Never add a gendered form"))
            prompts += prompt.substringAfter("officer would say it. ").substringBefore("Use the example")
        }
        assertEquals(Temperament.entries.size, prompts.size)
    }
    @Test fun temperamentIsStableAndUsesBothIdentifiers() {
        assertEquals(Temperament.seeded("officer", 12), Temperament.seeded("officer", 12))
        assertTrue((1L..20L).map { Temperament.seeded("officer", it) }.toSet().size > 1)
        assertTrue((1..20).map { Temperament.seeded("officer$it", 12) }.toSet().size > 1)
    }
}

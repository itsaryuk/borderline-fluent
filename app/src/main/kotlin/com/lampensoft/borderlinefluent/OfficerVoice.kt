package com.lampensoft.borderlinefluent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Derived from durable identifiers; no extra save field is needed when an attempt resumes. */
enum class Temperament { GRUFF, CHATTY, BORED, PEDANTIC, WARM;
    companion object {
        fun seeded(officerId: String, attempt: Long): Temperament =
            entries[Math.floorMod("$officerId:$attempt".hashCode(), entries.size)]
    }
}

enum class OfficerVoiceSource { DESK, LAST_WORDS, ASSOCIATE, MACHINE, ROOM, THALASSIA, EPILOGUE, TRAINING_TIP }

data class VoiceRewrite(val line: String, val reason: String, val ms: Long) {
    val accepted get() = reason == "accepted"
}

/** Receives only authored text. Player speech/history is deliberately absent from this API. */
class OfficerVoice(private val ask: OneShot, private val langId: LangId = MlLangId,
                   private val budgetMs: Long = 2500, private val log: (String) -> Unit = {}) {
    suspend fun rewrite(original: String, english: String, language: String, temperament: Temperament,
                        enabled: Boolean = true, source: OfficerVoiceSource = OfficerVoiceSource.DESK,
                        act1: Boolean = false, playerNames: List<String> = emptyList()): VoiceRewrite {
        val start = System.nanoTime()
        fun result(line: String, reason: String) = VoiceRewrite(line, reason, (System.nanoTime() - start) / 1_000_000)
        fun fallback(reason: String) = result(original, reason)
        if (!enabled) return fallback("disabled")
        if (source != OfficerVoiceSource.DESK) return fallback("fixed")
        return try {
            withTimeoutOrNull(budgetMs) {
                val name = Language.all.firstOrNull { it.code == language }?.name ?: language
                val tone = when (temperament) {
                    Temperament.GRUFF -> "Clipped, few words. Example: Where did you stay?"
                    Temperament.CHATTY -> "A short friendly lead-in. Example: All right, where did you stay?"
                    Temperament.BORED -> "Flat, minimal. Example: Your place of stay?"
                    Temperament.PEDANTIC -> "Precise, formal wording. Example: Please specify where you stayed."
                    Temperament.WARM -> "A kind lead-in. Example: Please, could you tell me where you stayed?"
                }
                val proposed = ask("Rewrite this border officer's line in $name as a ${temperament.name.lowercase(Locale.ROOT)} officer would say it. " +
                    "$tone Use the example only for tone, not content. " +
                    "Keep the meaning and the exact question; keep names, places and numbers. One or two short sentences. " +
                    "Keep the original formal address (Sie/vous/usted/Lei/o senhor/Pan/Вы/Ви/siz/您/敬語). " +
                    "Never add a gendered form for the traveller that the original avoids. " +
                    "No new facts, no opinions, no jokes about people. Output ONLY the line.",
                    "Fixed line: $original\nEnglish meaning: $english", 160).trim()
                // The question mark is repaired here in code, not asked of the model.
                val punctuated = if (proposed.isNotBlank() && isQuestion(original, language) &&
                    proposed.last() !in "?？؟") {
                    val mark = when (language) {
                        "ar" -> '؟'
                        "ja", "zh" -> '？'
                        else -> '?'
                    }
                    proposed.trimEnd('.', '。') + mark
                } else proposed
                // French sets a space before ? ! : ; (the fr officer lines use a plain one); the model often omits it.
                val rewrite = if (language == "fr") punctuated.replace(Regex("(?<=[\\p{L}\\p{N}])(?=[?!:;](\\s|$))"), " ") else punctuated
                val rejection = when {
                    rewrite.isBlank() -> "empty"
                    proposed == original.trim() || rewrite == original.trim() -> "unchanged"
                    rewrite.length > original.length * 1.6 + 40 -> "length"
                    rewrite.any { it in "<>" || Character.getType(it) == Character.START_PUNCTUATION.toInt() ||
                        Character.getType(it) == Character.END_PUNCTUATION.toInt() } -> "brackets"
                    rewrite.any { it == '\n' || it == '\r' } -> "multiline"
                    act1 && Regex("free\\s*ai", RegexOption.IGNORE_CASE).containsMatchIn(rewrite) -> "forbidden-term"
                    playerNames.filter { it.isNotBlank() }.any {
                        rewrite.contains(it, ignoreCase = true) && !original.contains(it, ignoreCase = true)
                    } -> "player-name"
                    else -> null
                }
                if (rejection != null) return@withTimeoutOrNull fallback(rejection)
                // Unlike answer recognition, unknown language is not a pass for generated speech.
                if (langId.candidates(rewrite).firstOrNull()?.substringBefore('-') != language)
                    return@withTimeoutOrNull fallback("language")
                val checkPrompt = "You check a proposed rewrite of a border officer's line against the fixed line and its English meaning.\n" +
                    "Reply with exactly one line like: SAFE=yes SAME=yes FORMAL=yes\n" +
                    "SAFE: yes if it is polite and neutral, with no opinions or jokes about people.\n" +
                    "SAME: yes if it keeps the identical meaning, names, places and numbers, with no added facts, and still asks the same question.\n" +
                    "FORMAL: yes if the original formal address is kept, never made informal."
                val checkInput = "Fixed line: $original\nEnglish meaning: $english\nProposed rewrite: $rewrite"
                val reply = ask(checkPrompt, checkInput, 32)
                log("rewrite check reply: ${reply.replace("\n", " ")}")
                val flags = parseCheck(reply) ?: return@withTimeoutOrNull fallback("check-format")
                val failed = flags.entries.firstOrNull { !it.value }?.key
                if (failed != null) fallback("check-$failed") else result(rewrite, "accepted")
            } ?: fallback("timeout")
        } catch (e: CancellationException) {
            throw e // A stopped interview must never speak its fallback in a later run.
        } catch (_: Exception) {
            fallback("error")
        }
    }

    /** Fields may arrive in prose or any order; missing or conflicting values fail closed. */
    private fun parseCheck(reply: String): Map<String, Boolean>? {
        val flags = linkedMapOf<String, Boolean>()
        for (field in listOf("safe", "same", "formal")) {
            val values = Regex("\\b$field\\s*[=:]\\s*<?\\s*(yes|no)\\b", RegexOption.IGNORE_CASE)
                .findAll(reply).map { it.groupValues[1].equals("yes", true) }.toSet()
            if (values.size != 1) return null
            flags[field] = values.single()
        }
        return flags
    }
}

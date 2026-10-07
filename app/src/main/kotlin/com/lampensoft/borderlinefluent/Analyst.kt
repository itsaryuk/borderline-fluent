package com.lampensoft.borderlinefluent

import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** [raw]: step 1's line; [step2] / [step3]: the self-consistency and "can both be true?" replies, empty when not asked. */
data class Verdict(val check: Check, val fact: Int, val ms: Long, val raw: String = "", val step2: String = "", val step3: String = "")

/** One stateless model call: system prompt, user text, output cap → reply (Gemini Nano, [Nano.oneShot]). */
typealias OneShot = suspend (system: String, user: String, maxTokens: Int) -> String

/**
 * Answer check in two narrow steps instead of one broad judgment, because a 2B model misses contradictions when
 * asked to weigh a whole story at once (it passed "two weeks" against a 5-day cover):
 * 1. which numbered cover fact the answer is about and whether it matches it, plus answered / language / clarity;
 * 2. if the traveler spoke about that fact before, a single yes/no: does the new answer contradict the old one?
 * 3. only when 1 or 2 suspects a contradiction: "can both be true?" must come back no.
 * Every call is fresh: in one long-lived conversation the classifier's own earlier verdicts biased the next ones
 * (on device it flagged two correct answers in a row). Fresh: 15/15, ~1.6 s.
 */
class Judge(private val country: Destination, private val story: Backstory, private val ask: OneShot, private val langId: LangId = MlLangId) {
    private val earlier = mutableMapOf<Int, String>() // the traveler's last consistent statement per cover fact

    suspend fun judge(question: String, answer: String): Verdict = coroutineScope {
        val t0 = System.nanoTime()
        // "I am returning home" is true of every cover: no model call, no fact (0), nothing to contradict. A plain
        // answered / clear / in-language check is the least intrusive result; the fact stays unrecorded in [earlier].
        if (isHomecoming(answer, country.lang)) return@coroutineScope Verdict(Check(answered = true, contradiction = false, clarity = 2, inLanguage = true), 0, Models.ms(t0))
        // Language id runs beside the Nano call: it doesn't depend on the reply.
        val lang = async { inLanguage(answer, country.lang, langId) }
        val user = "Officer asked: \"$question\"\nTraveler answered: \"$answer\""
        // A reply without FACT or ANSWERED parsed as "answered, matches": fail open. Ask once more, then call it unclear.
        val raw = ask(classifyPrompt(country, story), user, 40).let { r -> if (Check.wellFormed(r)) r else ask(classifyPrompt(country, story), user, 40) }
        if (!Check.wellFormed(raw)) { lang.cancel(); return@coroutineScope Verdict(Check.UNCLEAR, 0, Models.ms(t0), raw.replace("\n", " ")) }
        val (fact, base) = Check.parseClassify(raw)
        val facts = story.facts(country.lang)
        // ID handles names Nano misreads; Nano can rescue short answers ID misidentifies.
        var check = base.copy(inLanguage = languageVerdict(answer, lang.await(), saysLanguageYes(raw), country.lang))
        // Step 1 compares against the cover itself (it sees the fact list). Step 2 only runs when the traveler already
        // said something about this fact, to catch them contradicting themselves.
        val prior = earlier[fact]
        var step2 = ""; var step3 = ""
        if (fact in 1..facts.size) {
            if (prior != null && !check.contradiction) step2 = ask(comparePrompt(), compareQuestion(facts[fact - 1], prior, answer), 4)
            val suspected = check.contradiction || Check.parseYes(step2)
            // Step 3: a suspected contradiction is confirmed by one more narrow question before it costs the player.
            // Nano flagged "flying out Tuesday morning" against "return Tuesday" and a translated job title.
            if (suspected) {
                step3 = ask(comparePrompt(), confirmQuestion(facts[fact - 1], prior, answer), 4)
                check = check.copy(contradiction = isNo(step3))
            }
        } else check = check.copy(contradiction = false) // no numbered cover fact: nothing to contradict, and step 3 never ran
        if (fact in 1..facts.size && !check.contradiction) earlier[fact] = answer // a contradiction doesn't replace the story told
        Verdict(check, fact, Models.ms(t0), raw.replace("\n", " "), step2.replace("\n", " "), step3.replace("\n", " "))
    }

    /** Step 3 asks "can both be true?": only a clear no is a contradiction; garbage keeps the player's answer. */
    private fun isNo(reply: String) = reply.trim().lowercase().trimStart('*', '"', ' ').startsWith("no")
}

/**
 * Everything that knows the cover story, for one interview: the retinal display's suggestions and the answer check.
 * Nano is stateless and fast enough (~0.7 s) to get the whole transcript each time.
 */
class Analyst(private val nano: GenerativeModel, private val country: Destination, private val story: Backstory, private val sub: String = "en") {
    // A reading aid or a non-Latin subtitle costs tokens per line.
    private val hintsCap = 100 + (if (Language.of(country.lang).romanize != null) 50 else 0) + (if (Language.of(sub).romanize != null) 30 else 0)
    private val system = suggestPrompt(country, story, sub)
    private val judge = Judge(country, story, Nano.oneShot(nano))
    private val given = ArrayDeque<List<String>>() // the last two sets of suggestions, so the next set differs

    /**
     * Three replies to the officer's last line. [smallTalk]: the opening chat: no trap, no cover details,
     * and not remembered as "earlier suggestions", so the interview's hint prompts stay as before.
     */
    suspend fun suggest(history: List<String>, trap: Boolean = false, smallTalk: Boolean = false): Retina {
        // A fallible display keeps the player reading instead of parroting: one reply quietly breaks the cover.
        // Answer the officer's last line itself, with replies not given before (Nano at temperature 0 repeated them).
        val last = history.lastOrNull { it.startsWith("Officer: ") }?.removePrefix("Officer: ")
        val avoid = given.flatten().takeIf { it.isNotEmpty() }?.let { "Earlier suggestions, do not repeat them: ${it.joinToString(" / ")}\n" }.orEmpty()
        val to = if (last != null) "The officer just said: \"$last\". Your 3 replies to that" else "Your 3 replies"
        val ask = avoid + if (smallTalk) "$to. This is small talk, not about the cover story: short and friendly, no cover details:"
            else if (trap) "$to. Make exactly one of them subtly contradict the cover story " +
            "(a wrong number, day, place or job), unmarked, in the same style as the others:" else "$to:"
        val t = Nano.ask(nano, system, history.ifEmpty { listOf("(The officer is waiting.)") }.joinToString("\n") + "\n" + ask, hintsCap, temperature = Nano.CHAT_TEMPERATURE)
        val options = t.reply.lines().filter { Models.OPTION.matches(it) }.take(3)
            .filter { generatedHintOk(Models.option(it), country.lang, sub) }
        if (!smallTalk) { given.addLast(options.map { Models.option(it).target }); if (given.size > 2) given.removeFirst() }
        return Retina(null, options, t)
    }

    suspend fun judge(question: String, answer: String) = judge.judge(question, answer)
}

/** Language candidates for a text (codes like "pt", "zh-Latn"; "und" if unsure). A fake in tests. */
fun interface LangId { suspend fun candidates(text: String): List<String> }

/** ML Kit Language ID, bundled model: on-device, no download. Low threshold: every plausible language is a candidate. */
object MlLangId : LangId {
    private val client by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.01f).build())
    }
    override suspend fun candidates(text: String): List<String> = suspendCancellableCoroutine { c ->
        client.identifyPossibleLanguages(text)
            .addOnSuccessListener { l -> c.resume(l.map { it.languageTag }) }
            .addOnFailureListener { c.resume(listOf("und")) } // can't tell: trust the recognizer, which listened in the target language
    }
}

/**
 * [answer] without the names in it: capitalised words other than a sentence's first (Toronto, Steigenberger), the name
 * part of "Toronto'daydım", and anything with a digit. German nouns go too; what is left still reads as German.
 */
fun stripNames(answer: String): String {
    var first = true
    return answer.split(Regex("""\s+""")).mapNotNull { t ->
        val w = t.trim('.', ',', '!', '?', ';', ':', '"', '«', '»', '(', ')')
        val head = first; first = t.endsWith('.') || t.endsWith('!') || t.endsWith('?')
        val parts = w.split('\'', '’')
        when {
            w.isEmpty() || w.any(Char::isDigit) -> null
            parts.size > 1 && parts[0].length > 1 && parts[0][0].isUpperCase() -> parts.drop(1).joinToString("'")
            !head && w[0].isUpperCase() -> null
            else -> w
        }
    }.joinToString(" ")
}

/** Languages whose script no other playable language writes: an answer mostly in it can only be that language. */
private val OWN_SCRIPT = setOf("ar", "ja", "zh") // ponytail: Han is shared by ja/zh; ID them too if that ever matters

/** Nano wrote LANGUAGE=yes (or LANGUAGE:yes) itself; a missing flag is not a yes. */
fun saysLanguageYes(raw: String) = Regex("""LANGUAGE\s*[=:]\s*<?\s*yes\b""", RegexOption.IGNORE_CASE).containsMatchIn(raw)

/** Short answers (< 4 words without names) can be rescued by an explicit Nano language yes. */
fun languageVerdict(answer: String, idSays: Boolean, nanoSays: Boolean, lang: String): Boolean =
    idSays || (nanoSays && lang !in OWN_SCRIPT && stripNames(answer).split(Regex("""\s+""")).count { it.any(Char::isLetter) } < 4)

/**
 * Is [answer] in [lang]? Mostly in a script only [lang] uses: yes (ru/uk share Cyrillic, so they always go to ID).
 * Otherwise the names are stripped and the rest is identified: yes when [lang] is among the candidates, when nothing
 * is identified, or when too little is left to tell.
 */
suspend fun inLanguage(answer: String, lang: String, id: LangId): Boolean {
    if (lang in OWN_SCRIPT && mostlyScript(answer, lang)) return true
    val rest = stripNames(answer)
    if (rest.count(Char::isLetter) < 3) return true
    val c = id.candidates(rest).map { it.substringBefore('-') }
    return c.isEmpty() || "und" in c || lang in c
}

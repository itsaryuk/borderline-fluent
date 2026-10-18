package com.lampensoft.borderlinefluent

import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** [raw]: step 1's line; [step2] / [step3]: the self-consistency and "can both be true?" replies, empty when not asked. */
/** [fact]: the cover fact the answer was about, null for none. */
data class Verdict(val check: Check, val fact: FactId?, val ms: Long, val raw: String = "", val step2: String = "", val step3: String = "")

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
    private val earlier = mutableMapOf<FactId, String>() // the traveler's last consistent statement per cover fact

    suspend fun judge(question: String, answer: String, topic: FactId? = null, noFact: Boolean = false,
                      previous: Pair<String, FactId>? = null): Verdict = coroutineScope {
        val t0 = System.nanoTime()
        // "I am returning home" is true of every homecoming cover: no model call, no fact (0), nothing to contradict. A
        // plain answered / clear / in-language check is the least intrusive result; the fact stays unrecorded in [earlier].
        // A visitor saying it goes to the model: their home is elsewhere.
        if (story.kind == TripKind.HOMECOMING && isHomecoming(answer, country.lang)) return@coroutineScope Verdict(Check(answered = true, contradiction = false, clarity = 2, inLanguage = true), null, Models.ms(t0))
        // "I forgot." / "I don't understand." / a bare "Kurwa." says nothing about the cover: a dodge, never a lie (Nano
        // confirmed "Я забыл." as a PURPOSE lie and "Kurwa." as a STAY lie on device). No model call; an English one at another desk is also a language miss.
        // A desk-language match that is English too ("Pardon ?" at a French desk) leaves the language to language ID.
        nonAnswerLang(answer, country.lang)?.let { l ->
            val inLang = l == country.lang && (l == "en" || !isNonAnswer(answer, "en") || inLanguage(answer, l, langId))
            return@coroutineScope Verdict(Check(answered = false, contradiction = false, clarity = 2, inLanguage = inLang), null, Models.ms(t0), "non-answer ($l)")
        }
        // Language id runs beside the Nano call: it doesn't depend on the reply.
        val lang = async { inLanguage(answer, country.lang, langId) }
        val answer = unfield(answer) // quoted into every step below, and kept as the earlier statement
        val user = "Officer asked: \"$question\"\nTraveler answered: \"$answer\""
        // 48 tokens: the line ends with IMPLANT=; a truncated IMPLANT reads as none.
        // A reply without FACT or ANSWERED parsed as "answered, matches": fail open. Ask once more, then call it unclear.
        val raw = ask(classifyPrompt(country, story), user, 48).let { r -> if (Check.wellFormed(r)) r else ask(classifyPrompt(country, story), user, 48) }
        if (!Check.wellFormed(raw)) { lang.cancel(); return@coroutineScope Verdict(Check.UNCLEAR, null, Models.ms(t0), raw.replace("\n", " ")) }
        // Nano answers IMPLANT=yes as often as passing: a plain yes is a passing mention, not none (+ a TRICK misread).
        val (n, base) = Check.parseClassify(Regex("""(IMPLANT\s*[=:]\s*<?\s*)yes\b""", RegexOption.IGNORE_CASE).replace(raw, "$1passing"))
        // An implant remark appended to a true answer made Nano say MATCHES=no, and the confirm step agreed. Judge the
        // answer without the remark (one more classify call, only on this path); the remark still counts as the mention.
        if (base.contradiction && !noFact) {
            val rest = withoutImplantTalk(answer)
            // All of it implant talk: a plain remark ("My implant translates you.") is no claim, but one carrying a number
            // ("I stayed 14 days because my implant told me so") goes on to the usual confirm step below.
            if (rest.isEmpty() && answer.none(Char::isDigit)) {
                lang.cancel()
                return@coroutineScope Verdict(base.copy(contradiction = false, trick = false), null, Models.ms(t0), raw.replace("\n", " "))
            }
            if (rest != answer && rest.isNotEmpty()) {
                lang.cancel()
                val v = judge(question, rest, topic, previous = previous)
                val implant = if (base.implant != Implant.NONE) base.implant else Implant.PASSING
                return@coroutineScope v.copy(check = v.check.copy(implant = implant), ms = Models.ms(t0), raw = raw.replace("\n", " ") + " >> " + v.raw)
            }
        }
        // [noFact]: the question is off the cover ("How did you learn our language?"); any answer is plausible, never a contradiction.
        var fact = if (noFact) null else story.factOf(n) // the numbered list's position → the fact's id; nothing past here uses numbers
        // ID handles names Nano misreads; Nano can rescue short answers ID misidentifies.
        var check = base.copy(contradiction = base.contradiction && !noFact, answered = base.answered || noFact /* off-cover: ANSWERED refers to the cover-fact list */, inLanguage = languageVerdict(answer, lang.await(), saysLanguageYes(raw), country.lang))
        // A non-answer's FACT is noise: "Путешествую." to "What brings you here?" came back FACT=1 MATCHES=no ANSWERED=no
        // and step 3 confirmed a NAME_BORN lie on device. Drop that pin; the asked topic's confirm ("can both be true?") judges it below.
        // [daysToo]: the second look (below) gates a DAYS re-pin too; step 1 keeps a DAYS pin as evidence ("Drei Tage." to the host question).
        fun noise(f: FactId?, c: Check, daysToo: Boolean = false) = !c.answered && c.contradiction && f != null && topic != null && f != topic &&
            f != previous?.second && topic in story.factIds() &&
            (daysToo || f != FactId.DAYS) && answer.none(Char::isDigit) // a number is evidence: "I was away for 14 days" stays a DAYS lie 
        val noisyPin = noise(fact, check)
        if (noisyPin) { fact = null; check = check.copy(contradiction = false) }
        // Step 1 compares against the cover itself (it sees the fact list). Step 2 only runs when the traveler already
        // said something about this fact, to catch them contradicting themselves.
        var step2 = ""; var step3 = ""; var lateReply = false
        // ANSWERED=no on the asked fact may be a late reply to the officer's previous question ("A conference." to
        // "What's your name?" after a re-ask); Nano's confirm step called that a lie on device. It is a dodge only when
        // step 1 on the previous question finds it answered and matching that question's own fact; "My name is John
        // Smith." to "When were you born?" (one NAME_BORN fact) answers no earlier question and goes on to step 3.
        if (!base.answered && check.contradiction && fact != null && fact == topic && fact != FactId.ENCOUNTER &&
                previous != null && previous.second != topic && previous.second in story.factIds()) {
            val r = ask(classifyPrompt(country, story), "Officer asked: \"${previous.first}\"\nTraveler answered: \"$answer\"", 48)
            val (pn, late) = Check.parseClassify(r)
            if (Check.wellFormed(r) && late.answered && !late.contradiction && story.factOf(pn) == previous.second) {
                check = check.copy(contradiction = false)
                lateReply = true // nothing was said about the asked fact: never its earlier statement
                step2 = "late: $r"
            }
        }
        // "Officer asked:" pins step 1's FACT on the asked fact, so an answer about another one ("At the conference
        // hotel." to "How long will you stay?") came back MATCHES=no ANSWERED=no and step 3 confirmed a lie. A second
        // look reads FACT from the answer alone (same prompt, no question). ANSWERED=no never clears a contradiction by
        // itself: it only buys this look. Another fact is judged against that fact, and the asked one still gets its
        // compare + confirm ("In Canada." for a Denver home re-pins on a Canadian FROM, MATCHES=yes). No fact at all drops
        // the pin like [noisyPin]: the flagged confirm against the topic still runs ("on holiday" for a wedding).
        // A digit in a DAYS answer is evidence (see [noisyPin]): no second look, the count below judges it.
        // The look's own mismatch on another fact with ANSWERED=no (what [noisyPin] calls noise; the look has no question,
        // so its ANSWERED says little) needs the topic path's two narrow calls: the compare must find a conflict before
        // step 3's confirm can make it a lie of that fact. pl "Według znajomo." (at a friend's) to a STAY question came
        // back FACT=1 (NAME_BORN) MATCHES=no ANSWERED=no alone and step 3 alone confirmed a name lie on device; dropping
        // the re-pin instead turned "With a friend." to the DAYS question (a hotel cover) into a dodge. A compare "no"
        // drops the pin like no fact at all: no re-pin, so the flagged confirm against the topic runs below. Routing it
        // through the topic's compare first lost "Je suis ici en vacances." for a wedding (FACT=1 alone) in 8 of 13 languages.
        // Here a DAYS re-pin without a digit is noise too: de "Ich mache hier Urlaub." (and es, pt, ru, uk, ar) for a wedding
        // came back alone FACT=3 (DAYS) MATCHES=no ANSWERED=no; kept unchecked, the DAYS confirm said "yes" and the topic's
        // compare (Nano leans "no") lost the lie. A real number-word lie survives: "Drei Tage." to the purpose question passes
        // the DAYS compare (7 days vs three) and the DAYS confirm decides.
        var repinned = false; var secondLook = false; var alone = ""
        if (!base.answered && check.contradiction && fact != null && fact == topic && topic != FactId.ENCOUNTER && !lateReply &&
                !(topic == FactId.DAYS && answer.any(Char::isDigit))) {
            val r = ask(classifyPrompt(country, story), "Traveler said: \"$answer\"", 48)
            if (Check.wellFormed(r)) {
                alone = r
                val (n2, again) = Check.parseClassify(r)
                val f2 = story.factOf(n2)
                when {
                    f2 == null -> { fact = null; check = check.copy(contradiction = false) }
                    noise(f2, again, daysToo = true) && !Check.parseYes(ask(comparePrompt(), compareQuestion(story.fact(f2, country.lang), null, answer), 4).also { step2 = it }) ->
                        { fact = null; check = check.copy(contradiction = false) }
                    f2 != topic -> { fact = f2; check = check.copy(contradiction = again.contradiction); repinned = true }
                    else -> secondLook = true
                }
            }
        }
        val prior = fact?.let { earlier[it] }
        // Nano can spot a queue lie while choosing an unrelated fact (or 0); confirm the asked encounter directly.
        val answeredYes = Regex("""ANSWERED\s*[=:]\s*<?\s*yes\b""", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val encounterMismatch = topic == FactId.ENCOUNTER && fact != topic && topic in story.factIds() &&
            base.contradiction && answeredYes
        if (encounterMismatch) {
            fact = FactId.ENCOUNTER
            step3 = ask(comparePrompt(), confirmQuestion(story.fact(fact, country.lang), null, answer), 4)
            check = check.copy(contradiction = isNo(step3))
        } else if (fact != null) {
            val text = story.fact(fact, country.lang)
            if (prior != null && !check.contradiction && step2.isEmpty()) step2 = ask(comparePrompt(), compareQuestion(text, prior, answer), 4)
            val suspected = check.contradiction || Check.parseYes(step2)
            // Step 3: a suspected contradiction is confirmed by one more narrow question before it costs the player.
            // Nano flagged "flying out Tuesday morning" against "return Tuesday" and a translated job title.
            // A suspected length with only other numbers in it ("14 days" for 5) is a contradiction by count, not by
            // Nano: its confirm step answered "both can be true" in every language on the bench. Weeks count too.
            // Exactly one number up to 60: "five days, June 10 to 15" carries dates, not a length.
            val numbers = if (fact == FactId.DAYS) Regex("""\d+""").findAll(answer).mapNotNull { it.value.toIntOrNull() }.toList() else emptyList()
            // The cover's own day count, alone and followed by the language's word for day, is no lie whatever Nano
            // says. Another unit ("7 weeks", "June 7") or a week number ("1 week" for 7) goes to step 3 as before.
            val ownCount = numbers.size == 1 && numbers[0] == story.days && DAY_WORDS[country.lang].orEmpty()
                .any { Regex("""(?<!\d)${story.days}[\s-]*${Regex.escape(it)}""", RegexOption.IGNORE_CASE).containsMatchIn(answer) }
            if (suspected && ownCount) {
                step3 = "numbers ${numbers[0]} == ${story.days}"
                // and it answers the asked length ("I stay 7 days." came back ANSWERED=no; no second look on digits)
                check = check.copy(contradiction = false, answered = check.answered || fact == topic)
            } else if (suspected && numbers.size == 1 && numbers[0] <= 60 && numbers[0] != story.days && numbers[0] * 7 != story.days) {
                step3 = "numbers ${numbers.joinToString(",")} != ${story.days}"
                check = check.copy(contradiction = true)
            } else if (suspected) {
                step3 = ask(comparePrompt(), confirmQuestion(text, prior, answer), 4)
                check = check.copy(contradiction = isNo(step3))
            }
        } else check = check.copy(contradiction = false) // no numbered cover fact: nothing to contradict, and step 3 never ran
        // The second look kept the asked fact and the confirm cleared it: the answer did answer (de "Ich bin hier für eine
        // Wanderreise." came back ANSWERED=no).
        if (secondLook && !check.contradiction) check = check.copy(answered = true)
        // A truthful answer to the desk's previous question ("A conference." after a re-asked name): step 1 pinned it on
        // that question's fact, so it is a late reply, not a miss of the current topic.
        if (!lateReply && fact != null && fact != topic && fact == previous?.second && base.answered && !check.contradiction) lateReply = true
        // Other missed topics still need both narrow checks before they can override the classifier's verdict.
        // A re-pinned answer already confirmed as a lie of the other fact needs nothing more.
        if (topic != null && topic != fact && !lateReply && !(repinned && check.contradiction) && topic in story.factIds()) {
            val text = story.fact(topic, country.lang)
            // The classifier already flagged a mismatch but pinned it on another fact ("In Canada." for home → FROM): the
            // compare step against the topic fact read "no contradiction" there, so go straight to the confirm.
            // Only when the pinned fact's own confirm cleared it, and the officer's question was answered at all.
            // Not after a kept re-pin: step 1's flag was on the asked fact, so the compare decides ("At a hotel near the wedding
            // venue." to the DAYS question says nothing about a length, and two narrow calls must both find a conflict). A re-pin
            // its compare dropped is no re-pin: like no fact at all, the flag stands and the confirm decides.
            val flagged = !repinned && base.contradiction && !check.contradiction && (answeredYes || topic != FactId.ENCOUNTER) // on device "In Canada." came back ANSWERED=no
            val compared = if (flagged) "" else ask(comparePrompt(), compareQuestion(text, null, answer), 4)
            if (flagged || Check.parseYes(compared)) {
                val confirmed = ask(comparePrompt(), confirmQuestion(text, null, answer), 4)
                if (isNo(confirmed)) {
                    fact = topic
                    check = check.copy(contradiction = true)
                    step2 = compared
                    step3 = confirmed
                }
            }
        }
        if (fact != null && !check.contradiction && !lateReply) earlier[fact] = answer // a contradiction doesn't replace the story told
        // A harvest scene's truthful retelling quotes absurd words ("told the ceiling her suitcase was homesick"), which the
        // desk's ODD calls absurd nonsense. ODD is one flag, so on this one answer it goes whole, violence included.
        if (topic == FactId.ENCOUNTER && story.encounter?.encounter?.harvest == true) check = check.copy(odd = false)
        Verdict(check, fact, Models.ms(t0), (raw + if (alone.isEmpty()) "" else " >> alone: $alone").replace("\n", " "), step2.replace("\n", " "), step3.replace("\n", " "))
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
     * Two replies to the officer's last line. [smallTalk]: the opening chat: no trap, no cover details,
     * and not remembered as "earlier suggestions", so the interview's hint prompts stay as before.
     */
    /** [remember]: the truthful answer to the officer's queue question, restated right before the request (with only the
     *  scene on the card, Nano answered "No, I did not help anyone" for a traveler who gave away their water). */
    suspend fun suggest(history: List<String>, trap: Boolean = false, smallTalk: Boolean = false, remember: String? = null,
                        answered: () -> Boolean = { false }): Retina {
        // A fallible display keeps the player reading instead of parroting: one reply quietly breaks the cover.
        // Answer the officer's last line itself, with replies not given before (Nano at temperature 0 repeated them).
        val last = history.lastOrNull { it.startsWith("Officer: ") }?.removePrefix("Officer: ")
        val avoid = given.flatten().takeIf { it.isNotEmpty() }?.let { "Earlier suggestions, do not repeat them: ${it.joinToString(" / ")}\n" }.orEmpty()
        val to = if (last != null) "The officer just said: \"$last\". Your 2 replies to that" else "Your 2 replies"
        val fact = remember?.let { "About the queue, the truthful answer is: \"$it\". Both replies say this in ${country.language}.\n" }.orEmpty()
        val ask = avoid + fact + if (smallTalk) "$to. This is small talk, not about the cover story: short and friendly, no cover details:"
            else if (trap) "$to. Make exactly one of them subtly contradict the cover story " +
            "(a wrong number, day, place or job), unmarked, in the same style as the others:" else "$to:"
        val lines = history.map { if (it.startsWith("Traveler: ")) unfield(it) else it }
        val prompt = lines.ifEmpty { listOf("(The officer is waiting.)") }.joinToString("\n") + "\n" + ask
        // Nothing survived the language check: ask once more (chat temperature varies the reply); log why when under 2 do.
        val log = StringBuilder()
        var t: Turn; var options: List<String>
        var tries = 0
        do {
            t = Nano.ask(nano, system, prompt, hintsCap, temperature = Nano.CHAT_TEMPERATURE)
            val rejected = mutableListOf<String>()
            options = suggestionLines(t.reply, country.lang, sub, rejected = rejected)
            if (options.size < 2 || options.any { Models.option(it).english.isEmpty() }) log.append("try ${tries + 1}: kept ${options.size}, raw=${t.reply.replace('\n', '\u23CE')} rejected=$rejected\n")
        } while (options.isEmpty() && !answered() && ++tries < 2) // the desk awaits this call: no retry once the player has answered
        if (!smallTalk) { given.addLast(options.map { Models.option(it).target }); if (given.size > 2) given.removeFirst() }
        return Retina(null, options, t, log.toString().trimEnd())
    }

    suspend fun judge(question: String, answer: String, topic: FactId? = null, noFact: Boolean = false,
                      previous: Pair<String, FactId>? = null) = judge.judge(question, answer, topic, noFact, previous)
}

/** The word for day after a count, per playable language (prefixes: "day" covers "days", "Tag" covers "Tage"/"Tagen"). */
private val DAY_WORDS = mapOf(
    "en" to listOf("day"), "de" to listOf("tag"), "fr" to listOf("jour"), "es" to listOf("día", "dia"),
    "pt" to listOf("dia", "día"), "it" to listOf("giorn"), "pl" to listOf("dzień", "dzien", "dni"),
    "ru" to listOf("день", "дня", "дней"), "uk" to listOf("день", "дні", "днів"), "tr" to listOf("gün", "gun"),
    "ar" to listOf("يوم", "أيام", "ايام"), "ja" to listOf("日"), "zh" to listOf("天"),
)

/** Implant / chip words of all playable languages (lowercase stems); a sentence with one is the traveler's remark about it. */
private val IMPLANT_WORDS = listOf("implant", "impianto", "implante", "puce", "chip", "чип", "имплант", "імплант", "чіп", "çip",
    "زرع", "شريحة", "غرسة", "インプラント", "チップ", "植入", "芯片")

/** [answer] minus the sentences that talk about the implant ("" if nothing is left); unchanged when none does. */
fun withoutImplantTalk(answer: String): String =
    answer.split(Regex("""(?<=[.!?…؟])\s+|(?<=[。！？])""")).filter { s -> s.lowercase().let { l -> IMPLANT_WORDS.none { it in l } } }
        .joinToString(" ").trim().takeIf { answer.lowercase().let { l -> IMPLANT_WORDS.any { it in l } } } ?: answer

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

/**
 * Only slots 1 and 2 belong to the display; a stray third never replaces a broken reply. Per slot, the first line that
 * passes wins (Nano sometimes writes a bare pair, then the formatted one). A bare non-Latin target (no reading, no
 * translation) is shown when it passes the language check; its second line is then empty.
 */
internal suspend fun suggestionLines(raw: String, lang: String, sub: String, id: LangId = MlLangId, rejected: MutableList<String>? = null): List<String> =
    raw.lines().filter { Regex("""^\s*[12][:.)].+""").matches(it) }.groupBy { it.trimStart().first() }.toSortedMap().values.mapNotNull { lines ->
        lines.firstOrNull { line ->
            val h = Models.option(line)
            val bare = h.reading.isEmpty() && h.english.isEmpty() && h.target.any { it.isLetter() } &&
                h.target.none { it.isLetter() && Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
            (if (bare) generatedTextOk(h.target, lang, id) else generatedHintOk(h, lang, sub, id)).also { ok ->
                if (!ok) rejected?.add((if (generatedTextOk(h.target, lang, id)) "sub" else "target") + ": " + line.trim())
            }
        }?.let { line ->
            // The model's Latin reading of Cyrillic is unreliable: ru/uk readings come from a table.
            if (lang != "ru" && lang != "uk") line else Models.option(line).let { h ->
                "${line.trimStart().take(2)} ${h.target} | ${cyrillicToLatin(h.target, lang)} | ${h.english}"
            }
        }
    }

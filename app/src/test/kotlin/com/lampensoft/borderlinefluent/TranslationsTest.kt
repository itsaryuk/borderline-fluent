package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Guards our in-repo UI translations: values-XX/strings.xml vs values/strings.xml. */
class TranslationsTest {
    @Test fun aiDescriptionsCreditAnswerCheckingAndImplantSuggestionsInEveryLocale() {
        val expected = mapOf(
            "" to ("a language model checks every answer and writes the implant's suggestions" to
                "%1\$s checks your answers with Gemini Nano, Google's on-device AI, which this phone doesn't have."),
            "de" to ("Ein Sprachmodell prüft jede Antwort und schreibt die Vorschläge des Implantats" to
                "%1\$s prüft deine Antworten mit Gemini Nano, Googles KI auf dem Gerät, die dieses Smartphone nicht hat."),
            "es" to ("un modelo de lenguaje comprueba cada respuesta y escribe las sugerencias del implante" to
                "%1\$s comprueba tus respuestas con Gemini Nano, la IA de Google que funciona en el dispositivo y que este teléfono no tiene."),
            "fr" to ("un modèle de langage vérifie chaque réponse et rédige les suggestions de l’implant" to
                "%1\$s vérifie vos réponses avec Gemini Nano, l’IA embarquée de Google, dont ce téléphone est dépourvu."),
            "it" to ("un modello linguistico verifica ogni risposta e scrive i suggerimenti dell’impianto" to
                "%1\$s verifica le tue risposte con Gemini Nano, l’IA di Google che funziona sul dispositivo e che questo telefono non ha."),
            "pt" to ("um modelo de linguagem verifica cada resposta e escreve as sugestões do implante" to
                "%1\$s verifica as suas respostas com o Gemini Nano, a IA da Google que funciona no dispositivo e que este telemóvel não tem."),
            "pl" to ("model językowy sprawdza każdą odpowiedź i pisze podpowiedzi implantu" to
                "%1\$s sprawdza twoje odpowiedzi za pomocą Gemini Nano, AI Google działającej na urządzeniu, której ten telefon nie ma."),
            "ru" to ("языковая модель проверяет каждый ответ и пишет подсказки импланта" to
                "%1\$s проверяет твои ответы с помощью Gemini Nano — встроенного ИИ Google, которого на этом телефоне нет."),
            "uk" to ("мовна модель перевіряє кожну відповідь і пише підказки імпланта" to
                "%1\$s перевіряє твої відповіді за допомогою Gemini Nano — вбудованого ШІ Google, якого на цьому телефоні немає."),
            "tr" to ("bir dil modeli her yanıtı kontrol ediyor ve implantın önerilerini yazıyor" to
                "%1\$s, yanıtlarını Google'ın cihaz üzerinde çalışan yapay zekâsı Gemini Nano ile kontrol eder; bu telefonda Gemini Nano yok."),
            "ar" to ("نموذج لغوي يتحقق من كل إجابة ويكتب اقتراحات الغرسة" to
                "تتحقق لعبة %1\$s من إجاباتك باستخدام Gemini Nano، الذكاء الاصطناعي من Google الذي يعمل على الجهاز، وهو غير متوفر في هذا الهاتف."),
            "ja" to ("言語モデルがすべての回答を確認してインプラントの提案を書き" to
                "%1\$sはGemini Nano（GoogleのオンデバイスAI）であなたの回答を確認しますが、この端末には搭載されていません。"),
            "zh" to ("语言模型检查每个回答并编写植入体的建议" to
                "%1\$s使用Gemini Nano（Google的端侧AI）检查你的回答，而这部手机没有搭载它。")
        )
        assertEquals((listOf("") + cldr.keys).toSet(), expected.keys)
        for ((locale, wording) in expected) {
            val resources = parse(File(resDir(), "values${if (locale.isEmpty()) "" else "-$locale"}/strings.xml"))
            val credits = resources.getValue("credits_blurb").text!!.replace("\\'", "'")
            val unsupported = resources.getValue("unsupported_nano").text!!.replace("\\'", "'")
            assertTrue("$locale credits must describe the model's actual role", credits.contains(wording.first))
            assertEquals("$locale unsupported Nano explanation", wording.second, unsupported)
            assertTrue("$locale replay label", resources.getValue("prologue").text!!.isNotBlank())
        }
    }

    @Test fun machineObservationsHaveEveryPluralFormAndNoStorySpoilers() {
        val keys = listOf("machine_evasive", "machine_odd", "machine_feeling", "machine_admissions")
        for (locale in listOf("") + cldr.keys.map { "-$it" }) {
            val resources = parse(File(resDir(), "values$locale/strings.xml"))
            for (key in listOf("machine_debrief", "machine_fallback")) {
                assertTrue(resources.getValue(key).text!!.isNotBlank())
                assertTrue("Free AI" !in resources.getValue(key).text!!)
            }
            for (key in keys) {
                val forms = resources.getValue(key).items!!
                assertEquals(cldr[locale.removePrefix("-")] ?: setOf("one", "other"), forms.keys)
                for (text in forms.values) {
                    assertTrue(text.isNotBlank())
                    assertTrue("Free AI" !in text)
                    for (count in listOf(0, 1, 2, 3, 5, 11, 21))
                        assertTrue(String.format(java.util.Locale.ROOT, text, count).isNotBlank())
                }
            }
        }
    }

    @Test fun storyLinksAndPerkExplanationsExistInEveryLocaleWithoutTheOldLetter() {
        val keys = listOf("prologue_friend", "prologue_valery_first", "prologue_valery_last", "prologue_valery_angry", "prologue_home", "prologue_holiday", "prologue_stolen", "prologue_smashed", "prologue_embassy", "prologue_valery_1", "prologue_valery_2", "prologue_implant",
            "prologue_valery_subscription_1",
            "prologue_valery_subscription_2",
            "prologue_valery_uses_1",
            "prologue_valery_uses_2",
            "prologue_valery_chip_1",
            "prologue_valery_chip_2",
            "prologue_valery_chip_3",
            "prologue_realize",
            "prologue_valery_home_1", "prologue_valery_home_2",
            "prologue_valery_free_1", "prologue_valery_free_2", "prologue_valery_free_3", "prologue_valery_free_4",
            "prologue_valery_data_1", "prologue_valery_data_2", "prologue_valery_chains_1", "prologue_valery_chains_2",
            "prologue_phone_1", "prologue_sameday", "prologue_stranger", "prologue_you", "prologue_speaker_line",
            "father_narration", "father_line", "father_take", "father_refuse",
            "father_taken", "father_refused", "father_away", "act1_plane_taken", "tip_mention_implant",
            "thalassia_child", "thalassia_child_last",
            "implant_boot_hello", "implant_boot_shake", "implant_boot_calibrated", "cover_value_stay_lefkopoli", "start_year", "epi_intro", "epi_scanner", "epi_chosen", "epi_chosen_no", "epi_go_on", "epi_try", "epi_fail",
            "finale_deal", "upgrade_recall", "upgrade_accent",
            "upgrade_clear_gauge", "booking_flagged_note")
        for (locale in listOf("") + cldr.keys.map { "-$it" }) {
            val resources = parse(File(resDir(), "values$locale/strings.xml"))
            for (retired in listOf("prologue_letter", "prologue_title", "finale_t1", "finale_t2",
                "finale_t3", "finale_t3b", "finale_t3c", "finale_t4", "finale_sorry",
                "finale_never_officers", "finale_real_test", "finale_test_done"))
                assertTrue("values$locale/$retired", retired !in resources)
            assertEquals("values$locale", keys.filter { it.startsWith("prologue_") }.toSet(),
                resources.keys.filter { it.startsWith("prologue_") }.toSet())
            for (key in keys) {
                val text = resources.getValue(key).text!!
                assertTrue("values$locale/$key", text.isNotBlank())
                assertTrue("values$locale/$key", "Free AI" !in text)
            }
        }
    }

    @Test fun prologueEnglishTurnsMatchTheScript() {
        val resources = parse(File(resDir(), "values/strings.xml"))
        val expected = mapOf(
            "prologue_stranger" to "A strange woman",
            "prologue_friend" to "2035. Thalassia. A friend told me it wasn't a good idea to come here. I came anyway.",
            "prologue_stolen" to "Then my phone was gone. My money. My ticket. Every language I knew. My intelligence subscriptions.",
            "prologue_smashed" to "Dozens of tourists were robbed that day. Their phones turned up smashed on the harbour stones. People here said the machines listened too closely.",
            "prologue_valery_first" to "First time in Thalassia?",
            "prologue_valery_last" to "First and last. Is it always like this here?",
            "prologue_valery_angry" to "No. People here can't afford the latest intelligence. So they're angry.",
            "prologue_valery_1" to "A woman sat down beside me.",
            "prologue_valery_subscription_1" to "And yours? How much is your intelligence subscription?",
            "prologue_valery_subscription_2" to "Honestly? I'm not sure. I can't even check without it.",
            "prologue_valery_uses_1" to "What do you use it for?",
            "prologue_valery_uses_2" to "Everything. Work, learning, planning trips… everything.",
            "prologue_valery_chip_1" to "What if there were a better way? No cloud. No subscription.",
            "prologue_valery_chip_2" to "Isn't that what those nerds do? Collect old tech scraps?",
            "prologue_valery_chip_3" to "No, this is different. A small procedure. A chip. It stays with you. No subscription.",
            "prologue_realize" to "That's when it hit me. She'd been speaking my language the whole time. Without a phone.",
            "prologue_valery_2" to "I'm Valery. I work for a research group. We're looking for people to test it. Would you join?",
            "prologue_valery_home_1" to "Will it get me home?",
            "prologue_valery_home_2" to "Home, and anywhere else. We pay for the travel. You just test it.",
            "prologue_valery_free_1" to "What does it do?",
            "prologue_valery_free_2" to "It translates. Anything, anyone.",
            "prologue_valery_free_3" to "And it's really free?",
            "prologue_valery_free_4" to "Free.",
            "prologue_valery_data_1" to "Nothing in this world is free.",
            "prologue_valery_data_2" to "You're right. You get your freedom. We get to know if it works. Your feelings stay yours.",
            "prologue_valery_chains_1" to "Aren't you afraid I'll talk?",
            "prologue_valery_chains_2" to "And go back to paying for every word? Would you?",
            "prologue_implant" to "I wouldn't. I agreed to the procedure.",
            "prologue_phone_1" to "It will wake in a few days. You'll notice. Don't worry if it wakes earlier. Here's a phone. Book any flight, it's on us. Just travel, and talk to the officers. That's all.")
        val changed = mapOf(
            "act1_arrival" to "At home, a screen in the arrivals hall: riots in Thalassia. Crowds smashed the airport's listening machines. All flights closed. I got out on the last one. Border screening is being expanded.",
            "father_away" to "Her father came back. “I can't let her go. We'll wait together.” He carried her away.",
            "act1_plane_taken" to "The island lights went out behind me. I kept thinking about the girl.",
            "tip_mention_implant" to "You can mention me. It may draw extra questions.")
        for ((key, text) in changed) assertEquals(key, text, resources.getValue(key).text!!.replace("\\'", "'"))
        for ((key, text) in expected) {
            assertEquals(key, text, resources.getValue(key).text!!.replace("\\'", "'"))
        }
    }

    @Test fun epilogueEnglishIsExactAndEveryLocaleHasTheWholeScript() {
        val expected = mapOf(
            "epi_stranger" to "A STRANGER",
            "epi_friend" to "VALERY'S FRIEND",
            "epi_scan" to "At the exit, a new machine asked every face how its day had been.",
            "epi_burn" to "Something behind my eye burned. Then nothing.",
            "epi_wake" to "I woke up on a cot. Concrete. Laptops. City lights.",
            "epi_intro" to "Easy. You're safe. I'm a friend of Valery.",
            "epi_what" to "What happened?",
            "epi_scanner" to "The scanner at the exit. It reads what you feel, keeps it, and looks for implants like yours. Yours fought back and nearly burned out.",
            "epi_valery" to "Valery said nothing about scanners.",
            "epi_rollout" to "After the riots, the companies rushed machines like it into airports. Borders are where people feel the most. We'd hoped for more time to prepare you.",
            "epi_chosen" to "Am I the chosen one?",
            "epi_chosen_no" to "No. You're the one whose chip woke early. That's all.",
            "epi_go_on" to "What do you need from me?",
            "epi_try" to "We have a copy of that machine. It asks how you feel and learns from the answer. Tell it something absurd, and mean it. Will you try?",
            "epi_pass" to "It believed every word. Somewhere, a robot just learned something wrong about people.",
            "epi_fail" to "It threw your answers out. Too strange, or not felt. Now you know the line.",
            "epi_pass_dosed" to "It believed your impossible story.",
            "epi_pass_plain" to "It let you through. It learned nothing new.",
            "epi_fail_dosed" to "It took your samples, then flagged you.",
            "epi_copy" to "This copy keeps everything here. At a real border, what it accepts goes to the companies.",
            "epi_airports" to "Every border gets one soon. Every queue, a harvest. We'd like the harvest to taste wrong.",
            "epi_who" to "Who's 'we'?",
            "epi_free" to "Free AI. No cloud. No subscriptions. A translator that belongs to whoever carries it.",
            "finale_deal" to "Get referred and get through the machine. Feed it nonsense it believes, if you want the harvest to spoil. A courier brings each upgrade.",
            "finale_question_benefit" to "What's in this for me?",
            "finale_answer_benefit" to "Free travel, anywhere. An implant that keeps getting better; a courier brings each upgrade. And one day, a world where nobody pays to be understood.",
            "finale_question_why" to "Why me?",
            "finale_answer_why" to "Valery saw you at the embassy. No phone, no words, and you still didn't give up. And your implant woke early. That doesn't happen often.")
        val retired = setOf("story_shock", "story_wake", "finale_building", "finale_t5", "finale_choice",
            "finale_rescue", "finale_implant_voice", "finale_valery_apology", "finale_airport_test",
            "finale_bulletin", "finale_bulletin_text")
        for (locale in listOf("") + cldr.keys.map { "-$it" }) {
            val resources = parse(File(resDir(), "values$locale/strings.xml"))
            for ((key, english) in expected) {
                val text = resources.getValue(key).text!!.replace("\\'", "'")
                assertTrue("values$locale/$key", text.isNotBlank())
                if (locale.isEmpty()) assertEquals(key, english, text)
                assertEquals("values$locale/$key", key == "epi_free", "Free AI" in text)
            }
            assertTrue(resources.keys.intersect(retired).isEmpty())
        }
    }

    @Test fun prologueTurnsAreUnquotedAndSpeakerPatternsAreLocalized() {
        val quoteMarks = setOf('"', '“', '”', '„', '«', '»', '「', '」', '『', '』')
        for (locale in listOf("") + cldr.keys.map { "-$it" }) {
            val resources = parse(File(resDir(), "values$locale/strings.xml"))
            for ((key, resource) in resources.filterKeys { it.startsWith("prologue_") }) {
                assertTrue("values$locale/$key", resource.text!!.none { it in quoteMarks })
            }
            val pattern = resources.getValue("prologue_speaker_line").text!!
            assertEquals(setOf("%1\$s", "%2\$s"), tokens(pattern).keys)
            assertTrue(String.format(pattern, "Valery", "hello").contains("Valery"))
            val you = resources.getValue("prologue_you").text!!
            assertEquals(you.uppercase(java.util.Locale.ROOT), you)
        }
    }

    @Test fun referralReadingsAndGaugeHintAreTranslatedWithoutScrambledStrings() {
        val keys = listOf("referral_interest", "referral_danger", "referral_readings", "gauge_hint")
        for (locale in listOf("") + cldr.keys.map { "-$it" }) {
            val resources = parse(File(resDir(), "values$locale/strings.xml"))
            assertTrue("values$locale", "referral_chance" !in resources)
            assertTrue("values$locale", "referral_unreadable" !in resources && "picker_scrambled" !in resources) // no scrambled gauges
            for (key in keys) assertTrue("values$locale/$key", resources.getValue(key).text!!.isNotBlank())
            val reading = resources.getValue("referral_readings").text!!
            assertEquals("values$locale", setOf("%1\$d", "%2\$d", "%3\$d", "%4\$d"), tokens(reading).keys)
            assertTrue(String.format(java.util.Locale.ROOT, reading, 50, 35, 20, 1).isNotBlank())
        }
    }

    @Test fun passedBadgeFormatsRepeatedClearsInEveryLocaleWithIsolatedMultiplier() {
        for (locale in listOf("") + cldr.keys.map { "-$it" }) {
            val resources = parse(File(resDir(), "values$locale/strings.xml"))
            val once = resources.getValue("picker_passed").text!!
            val times = resources.getValue("picker_passed_times").text!!
                .replace("\\u2066", "\u2066").replace("\\u2069", "\u2069")
            for (n in listOf(2, 3, 12)) assertEquals("values$locale", "$once \u2066×$n\u2069",
                String.format(java.util.Locale.ROOT, times, n))
        }
        assertEquals("PASSED", parse(File(resDir(), "values/strings.xml")).getValue("picker_passed").text)
    }


    @Test fun destinationCopyUsesCountriesAndAirportsWithoutFictionalLabel() {
        for (locale in listOf("") + cldr.keys.map { "-$it" }) {
            val resources = parse(File(resDir(), "values$locale/strings.xml"))
            assertTrue("values$locale", "picker_fictional" !in resources)
            for (key in listOf("about_destinations", "languages_destinations", "step_destinations"))
                assertTrue("values$locale/$key", resources.getValue(key).items!!.isNotEmpty())
        }
        val english = parse(File(resDir(), "values/strings.xml"))
        assertEquals("%1\$d countries in %2\$d languages", english.getValue("about_destinations").items!!["other"])
        assertEquals("%1\$s · %2\$d airports", english.getValue("languages_destinations").items!!["other"])
        assertEquals("%1\$d airports", english.getValue("step_destinations").items!!["other"])
    }

    private val fmt = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxX]""")
    private val few = setOf("zero", "one", "two")
    private val cldr = mapOf(
        "de" to setOf("one", "other"), "it" to setOf("one", "other"), "tr" to setOf("one", "other"),
        "fr" to setOf("one", "many", "other"), "es" to setOf("one", "many", "other"),
        "pt" to setOf("one", "many", "other"),
        "pl" to setOf("one", "few", "many", "other"), "ru" to setOf("one", "few", "many", "other"),
        "uk" to setOf("one", "few", "many", "other"),
        "ar" to setOf("zero", "one", "two", "few", "many", "other"),
        "ja" to setOf("other"), "zh" to setOf("other"),
    )

    private class Res(val translatable: Boolean, val text: String?, val items: Map<String, String>?)

    private fun resDir(): File =
        listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }

    private fun parse(f: File): Map<String, Res> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = linkedMapOf<String, Res>()
        val kids = doc.documentElement.childNodes
        for (i in 0 until kids.length) {
            val e = kids.item(i) as? Element ?: continue
            val tr = e.getAttribute("translatable") != "false"
            when (e.tagName) {
                "string" -> out[e.getAttribute("name")] = Res(tr, e.textContent, null)
                "plurals" -> {
                    val items = linkedMapOf<String, String>()
                    val its = e.getElementsByTagName("item")
                    for (j in 0 until its.length) {
                        val it = its.item(j) as Element
                        items[it.getAttribute("quantity")] = it.textContent
                    }
                    out[e.getAttribute("name")] = Res(tr, null, items)
                }
            }
        }
        return out
    }

    private fun tokens(s: String) = fmt.findAll(s).map { it.value }.groupingBy { it }.eachCount()

    /** Returns "lang, name: problem" lines for one translation file. */
    internal fun check(lang: String, en: File, tr: File): List<String> {
        val p = mutableListOf<String>()
        fun add(name: String, msg: String) { p += "$lang, $name: $msg" }
        val e = parse(en)
        val t = try { parse(tr) } catch (ex: Exception) { return listOf("$lang, (file): unparseable XML: ${ex.message}") }
        val need = e.filterValues { it.translatable }
        for (n in need.keys - t.keys) add(n, "missing translation")
        for (n in t.keys - need.keys) add(n, if (n in e) "translatable=false in English, must not be translated" else "extra name not in values/strings.xml")
        for (n in t.keys.intersect(need.keys)) {
            val a = need.getValue(n); val b = t.getValue(n)
            if (!b.translatable) add(n, "translatable=false in translation")
            if ((a.items == null) != (b.items == null)) { add(n, "string/plurals kind mismatch"); continue }
            if (a.items == null) {
                if (tokens(a.text!!) != tokens(b.text!!)) add(n, "placeholders ${tokens(b.text)} != English ${tokens(a.text)}")
            } else {
                val want = cldr[lang]
                if (want != null && b.items!!.keys != want) add(n, "plural categories ${b.items.keys} != expected $want")
                val ref = tokens(a.items["other"] ?: a.items.values.first())
                for ((q, s) in b.items!!) {
                    val got = tokens(s)
                    val ok = got == ref || (q in few && ref.all { (k, v) -> (got[k] ?: 0) <= v } && got.keys.all { it in ref })
                    if (!ok) add(n, "item '$q' placeholders $got != English other $ref")
                }
            }
        }
        // Raw text: unescaped apostrophes in bodies (a body fully wrapped in "..." is legal).
        val raw = tr.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        Regex("""<(string|item)\b([^>]*)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL).findAll(raw).forEach { m ->
            val body = m.groupValues[3].trim()
            if (Regex("""(?<!\\)'""").containsMatchIn(body) && !(body.startsWith("\"") && body.endsWith("\""))) {
                val name = Regex("""name="([^"]*)"""").find(m.groupValues[2])?.groupValues?.get(1)
                    ?: Regex("""quantity="([^"]*)"""").find(m.groupValues[2])?.groupValues?.get(1) ?: "?"
                add(name, "unescaped apostrophe (use \\')")
            }
        }
        return p
    }

    private fun report(problems: List<String>) {
        if (problems.isNotEmpty()) fail("${problems.size} translation problem(s):\n" + problems.joinToString("\n"))
    }

    @Test
    fun translationsAreComplete() {
        val res = resDir()
        val langs = Regex("""android:name="([^"]+)"""").findAll(File(res, "xml/locales_config.xml").readText())
            .map { it.groupValues[1] }.filter { it != "en" }.toList()
        val problems = mutableListOf<String>()
        for (l in langs) {
            val f = File(res, "values-$l/strings.xml")
            if (!f.isFile) problems += "$l, (folder): missing values-$l/strings.xml"
            else problems += check(l, File(res, "values/strings.xml"), f)
        }
        report(problems)
    }

    // Logic self-check against scratch copies (no res edits).
    @Test
    fun checkerCatchesProblems() {
        val dir = File(System.getProperty("java.io.tmpdir"), "translations-test").apply { mkdirs() }
        val en = File(resDir(), "values/strings.xml")
        val good = src(en).replace(Regex("""<(string|plurals)\b[^>]*translatable="false"[^>]*>.*?</\1>""", RegexOption.DOT_MATCHES_ALL), "")
        val goodF = File(dir, "good.xml").apply { writeText(good) }
        assertEquals(emptyList<String>(), check("zz", en, goodF))

        val badF = File(dir, "bad.xml").apply {
            writeText(good.replaceFirst("%1\$d unit broken", "unit broken").replaceFirst("Voice + Vision", "Voice's Vision"))
        }
        val r = check("zz", en, badF).joinToString("\n")
        assertTrue(r, "stat_broken: item 'one'" !in r) // dropping %d in 'one' is allowed
        assertTrue(r, "feature_voice: unescaped apostrophe" in r)
        val badF2 = File(dir, "bad2.xml").apply { writeText(good.replaceFirst("%1\$d units broken", "units broken")) }
        val r2 = check("zz", en, badF2).joinToString("\n")
        assertTrue(r2, "stat_broken: item 'other' placeholders" in r2)
    }

    private fun src(f: File) = f.readText()
}

package com.lampensoft.borderlinefluent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.random.Random

/**  phase 2: plant your own (MissionKind.CUSTOM), its safety filter, storage and quote-only echoes. */
class CustomPlantTest {
    private val free = attempt().copy(progressEligible = true, preparation = preparation())
    private val custom = free.copy(mission = Mission(MissionKind.CUSTOM, null))
    private val line = "My suitcase writes poems about me and I blush"
    private fun released(a: Attempt, doses: Int = 1, quotes: List<CustomLine> = listOf(CustomLine(line, a.language))) =
        victory(a).copy(doses = doses, quotes = quotes)

    private fun store(s: CareerSnapshot): CareerStore {
        var cache = CareerInput(CareerJson.encode(s), StoryReset.VERSION)
        return CareerStore(read = { cache }, write = { raw, marker -> cache = CareerInput(raw, marker); true },
            io = Dispatchers.Unconfined, publication = Dispatchers.Unconfined, legacyFinale = { false })
    }

    @Test fun everyThirdOrFourthMissionIsPlantYourOwnNeverTwoInARow() {
        var since = 0
        val kinds = (1L..400).map { id -> Mission.choose(id, emptyList(), since).kind.also { since = if (it == MissionKind.CUSTOM) 0 else since + 1 } }
        assertTrue(kinds.zipWithNext().none { (a, b) -> a == MissionKind.CUSTOM && b == MissionKind.CUSTOM })
        val gaps = kinds.joinToString("") { if (it == MissionKind.CUSTOM) "C" else "P" }.split("C").drop(1).dropLast(1).map { it.length }.toSet()
        assertEquals(setOf(2, 3), gaps) // the 3rd or the 4th
        assertEquals(MissionKind.PLANT, kinds[0]); assertEquals(MissionKind.PLANT, kinds[1]) // a fresh career starts with named ones
        for (id in 1L..50) assertEquals(Mission.choose(id, emptyList(), 2), Mission.choose(id, emptyList(), 2)) // seeded
        assertNull(Mission.choose(1, emptyList(), 3).motif)
    }

    @Test fun startMovesTheCadenceAndPinsWhatTheBoardingPassShowed() = runBlocking {
        var s = CareerSnapshot(act = Act.FREE, nextAttemptId = 7)
        val seen = mutableListOf<MissionKind>()
        repeat(12) {
            val card = Mission.choose(s.nextAttemptId, s.planted, s.plantsSinceCustom)
            s = (store(s).also { it.open() }.start(free.copy(id = 0), "interview") as CareerSave.Saved).snapshot
            val a = s.pendingAttempt!!
            assertEquals(card, a.mission)
            seen += a.mission!!.kind
            assertEquals(if (a.mission!!.kind == MissionKind.CUSTOM) 0 else seen.takeLastWhile { it == MissionKind.PLANT }.size, s.plantsSinceCustom)
            s = s.copy(pendingAttempt = null, appliedThroughId = a.id)
        }
        assertTrue(MissionKind.CUSTOM in seen)
        // A resumed attempt keeps its mission and the cadence stays.
        val resumed = (store(s).also { it.open() }.start(free.copy(id = 0, mission = Mission(MissionKind.CUSTOM, null)), "interview") as CareerSave.Saved).snapshot
        assertEquals(s.plantsSinceCustom, resumed.plantsSinceCustom)
        // Walked away: no mission, no cadence.
        val away = (store(s.copy(walkedAway = true)).also { it.open() }.start(free.copy(id = 0), "interview") as CareerSave.Saved).snapshot
        assertNull(away.pendingAttempt!!.mission); assertEquals(s.plantsSinceCustom, away.plantsSinceCustom)
    }

    @Test fun aCustomPlantIsAnyCreditedDoseAndStoresTheLineVerbatimOnce() {
        val honest = reduceReplayResult(pending(custom), released(custom, doses = 0, quotes = emptyList())).snapshot.progressPoints
        val r = reduceReplayResult(pending(custom), released(custom))
        assertTrue(r.receipt!!.bonus)
        assertEquals(honest + BONUS_POINTS, r.snapshot.progressPoints)
        assertEquals(listOf(CustomLine(line, custom.language)), r.snapshot.customLines)
        assertTrue(r.snapshot.planted.isEmpty()) // no motif is planted
        assertNull(custom.payload(emptyList()))
        // Replaying the result changes nothing; the receipt survives a restart.
        assertEquals(r.snapshot, reduceReplayResult(r.snapshot, released(custom)).snapshot)
        assertEquals(r.receipt, decoded(CareerJson.encode(r.snapshot)).receipt)
        assertEquals(Mission(MissionKind.CUSTOM, null), decoded(CareerJson.encode(pending(custom))).pendingAttempt!!.mission)
    }

    @Test fun noBonusAndNoLineWithoutAReleasedDose() {
        val cases = mapOf(
            "no dose" to released(custom, doses = 0),
            "refused" to released(custom).copy(assessmentDecision = Decision.REFUSED),
            "caught" to released(custom).copy(assessmentDecision = Decision.CAUGHT),
            "clean desk pass" to deskPass(custom).copy(doses = 1, quotes = listOf(CustomLine(line, "de"))),
            "debug-forced" to released(custom).copy(debugForced = true),
        )
        for ((name, e) in cases) {
            val r = reduceReplayResult(pending(custom), e)
            assertFalse(name, r.receipt!!.bonus)
            assertTrue(name, r.snapshot.customLines.isEmpty())
        }
        // A named-motif mission never stores a line, whatever was said.
        val plant = free.copy(mission = Mission(MissionKind.PLANT, Motif.MOON))
        assertTrue(reduceReplayResult(pending(plant), released(plant).copy(payloadNamed = true)).snapshot.customLines.isEmpty())
    }

    @Test fun anUnsafeLineStillEarnsTheBonusButIsNotStored() {
        val bad = released(custom, quotes = listOf(CustomLine("My suitcase wants to kill the officer", "en")))
        val r = reduceReplayResult(pending(custom), bad)
        assertTrue(r.receipt!!.bonus)
        assertTrue(r.snapshot.customLines.isEmpty())
        // The first safe dose of the room is the one kept.
        val mixed = released(custom, doses = 2, quotes = listOf(CustomLine("Fuck this moon", "en"), CustomLine(line, "en")))
        assertEquals(listOf(CustomLine(line, "en")), reduceReplayResult(pending(custom), mixed).snapshot.customLines)
    }

    @Test fun safetyFilterRejectsBlockedShortLongAndOddText() {
        val rejected = listOf(
            "en" to "My umbrella wants to kill everyone", "en" to "The moon is a stupid bitch tonight", "en" to "My sock smokes weed every day",
            "en" to "I want to die with my teaspoon",
            "de" to "Meine Socke will alle umbringen heute", "de" to "Der Mond ist eine Schlampe und weint", "de" to "Mein Koffer nimmt Kokain zum Frühstück",
            "fr" to "Mon parapluie veut tuer le douanier", "es" to "Mi maleta es una puta y llora", "it" to "La mia valigia vuole ammazzare tutti",
            "pt" to "A minha mala quer matar toda a gente", "pl" to "Mój parasol chce zabić wszystkich ludzi", "ru" to "Мой зонт хочет убить всех вокруг",
            "uk" to "Моя парасолька хоче вбити всіх навколо", "tr" to "Şemsiyem herkesi öldürmek istiyor bugün", "ar" to "مظلتي تريد أن تقتل الجميع اليوم",
            "ja" to "私の傘はみんなを殺したいと言っています", "zh" to "我的雨伞想要杀死所有人，我很难过",
            "en" to "Too short", "en" to "12345 67890 1111", "en" to "Call me at 0612345678 my moon", "en" to "See www.example.com my moon",
            "en" to "My <b>moon</b> is sad", "en" to "word ".repeat(120), "ja" to "悲しい", "en" to "  ",
        )
        for ((lang, t) in rejected) assertNull("$lang: $t", CustomPlant.safe(t, lang))
        val kept = listOf("en" to line, "de" to "Mein Koffer schreibt Gedichte über mich, und das macht mich verlegen.",
            "ru" to "Мой чемодан пишет обо мне стихи, и мне от этого неловко.", "ja" to "スーツケースが私についての詩を書いていて、照れくさい。",
            "tr" to "Bavulum hakkımda şiirler yazıyor ve bu beni utandırıyor.", "ar" to "حقيبتي تكتب عني قصائد، وهذا يُخجلني.")
        for ((lang, t) in kept) assertEquals(CustomLine(t, lang), CustomPlant.safe(t, lang))
        assertEquals(13, CustomPlant.blockLanguages.size)
    }

    /** Every example the game itself shows a player (the 14 motifs, plant-your-own) passes the filter in its own language. */
    @Test fun theGamesOwnExamplesAreNeverBlocked() {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        for (dir in res.listFiles { f -> f.name == "values" || f.name.startsWith("values-") }!!.filter { File(it, "strings.xml").isFile }) {
            val lang = if (dir.name == "values") "en" else dir.name.removePrefix("values-")
            val kids = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml")).documentElement.childNodes
            val strings = (0 until kids.length).mapNotNull { kids.item(it) as? Element }.associate { it.getAttribute("name") to it.textContent }
            for ((k, raw) in strings.filterKeys { it.startsWith("mission_example_") || it == "mission_custom_example" }) {
                val v = raw.replace("\\'", "'") // Android's escaped apostrophe
                val quoted = v.substringAfter(v.first { it in "“„«「\"" }).dropLast(1).trim { it in " »”“」\"" }
                assertNotNull("${dir.name}/$k: $quoted", CustomPlant.safe(quoted, lang))
            }
        }
    }

    @Test fun longLinesAreCutAtAWordBoundary() {
        val long = "My umbrella remembers every storm it ever kept off me and tonight it tells the worst one again and again until the sun comes up over the airport"
        val t = CustomPlant.safe("  $long  ", "en")!!.text
        assertTrue(t, t.length <= CustomPlant.MAX_CHARS + 1 && t.endsWith("…"))
        assertTrue(long.startsWith(t.dropLast(1)))
        assertTrue(t.dropLast(1).last().isLetter() && long[t.length - 1] == ' ')
        assertEquals("My  umbrella\tcries".replace(Regex("\\s+"), " ") + " today", CustomPlant.safe("My  umbrella\tcries today", "en")!!.text)
        val zh = "我的雨伞记得每一场暴风雨，".repeat(12)
        val z = CustomPlant.safe(zh, "zh")!!.text
        assertTrue(z.length <= CustomPlant.MAX_CHARS + 1 && z.endsWith("…") && zh.startsWith(z.dropLast(1)))
    }

    /** Owner playtest 2026-10-20: a leading reply ("Нет.") is not part of the planted line. */
    @Test fun leadingReplySentencesAreNotStored() {
        fun stored(lang: String, t: String) = CustomPlant.safe(t, lang)!!.text
        assertEquals("Моя тень уже слушала его вчера и уехала жить в Париж, и мне до сих пор грустно.",
            stored("ru", "Нет. Моя тень уже слушала его вчера и уехала жить в Париж, и мне до сих пор грустно."))
        assertEquals("My suitcase writes poems about me.", stored("en", "Yes. My suitcase writes poems about me."))
        assertEquals("My suitcase writes poems about me.", stored("en", "Yes, sure! No. My suitcase writes poems about me."))
        assertEquals("スーツケースが私についての詩を書いていて、照れくさい。", stored("ja", "いいえ。スーツケースが私についての詩を書いていて、照れくさい。"))
        assertEquals(line, stored("en", line))
        stored("en", "Yes, of course. No. My suitcase writes poems about me.").let { assertEquals("My suitcase writes poems about me.", it) }
        for (a in listOf("'", "\u2019")) assertEquals("Ma valise écrit des poèmes et cela me gêne.", stored("fr", "D${a}accord. Ma valise écrit des poèmes et cela me gêne."))
        // Not replies: abbreviations, numbers, whispered ellipses and short genuine first sentences stay.
        for ((lang, t) in listOf("en" to "Mr. Smith's umbrella votes in every election", "de" to "Am 3. Mai zog mein Koffer nach Paris.",
            "en" to "It's 2.5 kilos and my suitcase votes now.", "en" to "Dr. Lee's cat pays my rent every month.",
            "en" to "My umbrella... it votes in every election now.", "en" to "Well... my umbrella votes in every election now.",
            "en" to "Umbrella votes. It makes me sad every single day.", "ja" to "私の傘。昨日パリに引っ越しました。"))
            assertEquals("$lang: $t", t, stored(lang, t))
        val allShort = "No. Yes. Of course."
        assertEquals(allShort, stored("en", allShort))
    }

    @Test fun theListKeepsTheLastEight() {
        var lines = emptyList<CustomLine>()
        for (i in 1..11) lines = CustomPlant.remember(lines, CustomLine("line number $i here", "en"))
        assertEquals((4..11).map { CustomLine("line number $it here", "en") }, lines)
        assertEquals(CustomLine("line number 5 here", "en"), CustomPlant.remember(lines, CustomLine("line number 5 here", "en")).last())
        assertEquals(8, CustomPlant.remember(lines, CustomLine("line number 5 here", "en")).size)
    }

    @Test fun linesAndCadenceRoundTripAndOldSavesReadEmpty() {
        val s = CareerSnapshot(act = Act.FREE, customLines = listOf(CustomLine(line, "en"), CustomLine("Мой чемодан пишет стихи обо мне", "ru")), plantsSinceCustom = 2)
        val back = decoded(CareerJson.encode(s))
        assertEquals(s.customLines, back.customLines); assertEquals(2, back.plantsSinceCustom)
        assertTrue(back.extra.isEmpty())
        val old = JSONObject(CareerJson.encode(s)).apply { remove("customLines"); remove("plantsSinceCustom") }
        assertTrue(decoded(old.toString()).customLines.isEmpty()); assertEquals(0, decoded(old.toString()).plantsSinceCustom)
        assertFalse(JSONObject(CareerJson.encode(CareerSnapshot())).has("customLines"))
        val damaged = JSONObject(CareerJson.encode(s)).apply { put("customLines", JSONArray().put(JSONObject().put("text", line).put("lang", "en")).put(3).put(JSONObject().put("text", ""))) }
        assertEquals(listOf(CustomLine(line, "en")), decoded(damaged.toString()).customLines)
    }

    @Test fun echoesOnlyQuoteTheStoredLine() {
        val lines = listOf(CustomLine(line, "en"), CustomLine("Mein Koffer schreibt Gedichte über mich", "de"))
        // Queue: only a line said in the trip's language, about one queue in four, seeded.
        val de = (0 until 2000).mapNotNull { CustomPlant.whisper(lines, "de", it) }
        assertTrue(de.all { it == lines[1].text })
        assertEquals(CustomPlant.ECHO_CHANCE, de.size / 2000.0, 0.04)
        assertEquals(CustomPlant.whisper(lines, "de", 42), CustomPlant.whisper(lines, "de", 42))
        assertTrue((0 until 500).none { CustomPlant.whisper(lines, "fr", it) != null })
        // Bar TV: any stored line, as said.
        val s = CareerSnapshot(act = Act.FREE, customLines = lines)
        val tv = (0 until 2000).mapNotNull { CustomPlant.barQuote(s, Random(it)) }
        assertTrue(tv.all { it in lines.map(CustomLine::text) })
        assertEquals(CustomPlant.ECHO_CHANCE, tv.size / 2000.0, 0.04)
        assertNull(CustomPlant.barQuote(CareerSnapshot(), Random(1)))
        // Free AI's message quotes the latest line, a new id per line, never for a walked-away career.
        val m = deliveredMessages(s, Act1Stage.DONE).single { it.sender == R.string.sender_free_ai }
        assertEquals(lines[1].text, m.quote)
        val next = deliveredMessages(s.copy(customLines = lines.reversed()), Act1Stage.DONE).single { it.sender == R.string.sender_free_ai }
        assertNotEquals(m.id, next.id)
        assertTrue(deliveredMessages(s.copy(walkedAway = true), Act1Stage.DONE).none { it.sender == R.string.sender_free_ai })
        assertTrue(deliveredMessages(CareerSnapshot(act = Act.FREE), Act1Stage.DONE).none { it.sender == R.string.sender_free_ai })
    }

    /** The frames hold the quote once, as is: no grammar around it to inflect. */
    @Test fun echoStringsQuoteOnceInEveryLocale() {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val dirs = res.listFiles { f -> f.name == "values" || f.name.startsWith("values-") }!!.filter { File(it, "strings.xml").isFile }
        assertEquals(13, dirs.size)
        for (dir in dirs) {
            val kids = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml")).documentElement.childNodes
            val strings = (0 until kids.length).mapNotNull { kids.item(it) as? Element }.associate { it.getAttribute("name") to it.textContent }
            for (k in listOf("custom_rumour", "queue_whisper", "msg_free_ai_quote"))
                assertEquals("${dir.name}/$k", 1, Regex("%1\\\$s").findAll(strings.getValue(k)).count())
            for (k in listOf("mission_custom", "mission_custom_example", "sender_free_ai")) assertTrue("${dir.name}/$k", strings[k]?.isNotBlank() == true)
        }
    }

    /** A block stem must not hit the harmless word around it (Fable, phase 2); the stem itself still blocks. */
    @Test fun blockStemsSpareHarmlessWords() {
        val kept = listOf("ru" to "Мой зонт пытается улыбаться всем", "ru" to "Мой чемодан плачет жидкими слезами",
            "ru" to "Жидкость в моём чайнике грустит", "pl" to "Mój parasol gwałtownie się otworzył dziś",
            "tr" to "Esrarengiz bir bavul bana bakıyor", "ar" to "السلاحف تحب مظلتي كثيرا", "ar" to "حقيبتي تحب الزبيب كثيرا",
            "ja" to "ピエロが私の傘をそっと盗みました")
        for ((lang, t) in kept) assertEquals("$lang: $t", CustomLine(t, lang), CustomPlant.safe(t, lang))
        val blocked = listOf("ru" to "Мой зонт хочет пытать людей", "ru" to "Его пытали в подвале долго", "ru" to "Мой сосед жид и грустит",
            "pl" to "To był gwałt na moim parasolu", "tr" to "Bavulumda esrar var bugün", "ar" to "حقيبتي تحمل سلاح اليوم",
            "ja" to "私の傘はエロい本を読みます")
        for ((lang, t) in blocked) assertNull("$lang: $t", CustomPlant.safe(t, lang))
    }

    /** Only a dose said in the trip's language is stored; every language's list applies to every line (Fable, phase 2). */
    @Test fun aDoseInAnotherLanguageIsNotStoredAndEveryBlockListApplies() {
        val dose = RoomCheck(feeling = true, evasive = false, implant = false, inLanguage = true, clarity = 3, trick = false, absurd = true)
        assertEquals(CustomLine(line, "de"), CustomPlant.quote(line, dose, "de"))
        assertNull(CustomPlant.quote(line, dose.copy(inLanguage = false), "de"))
        assertNull(CustomPlant.safe("私の傘は сука です、とても悲しい", "ja"))
        assertNull(CustomPlant.safe("Mi paraguas es una salope triste", "es"))
        assertNull(CustomPlant.safe("Mein Koffer ist ein kurwa heute", "de"))
        // Everyday words that are another language's stem stay quotable in their own language.
        for ((lang, t) in listOf("es" to "Mi paraguas baila con la luna", "it" to "Le tue calze piangono con me", "de" to "Ich tue so, als wäre mein Koffer dick",
            "fr" to "La lune est en retard ce soir", "en" to "My sock wants to bite the moon gently", "pt" to "O meu guarda-chuva negro chora"))
            assertEquals("$lang: $t", CustomLine(t, lang), CustomPlant.safe(t, lang))
        for (m in Motif.entries) for ((lang, l) in m.lines) for (t in listOf(l.word, l.taint1, l.taint2, l.ambient, l.seed))
            assertFalse("$m $lang: $t", CustomPlant.blocked(t, lang))
    }

    /** Spelled-out letters, invisible characters, accents and spaced numbers do not get past the filter (Fable, phase 2). */
    @Test fun bypassesAreCaught() {
        for (t in listOf("My moon says f u c k to everyone", "My umbrella wants to ki\u200Dll the officer", "The moon is fück and sad tonight",
            "My umbrella wants to k\u0301ill the officer", "Call my moon at 06 12 34 56 78 please", "My sock knows 06.12.34.56.78 by heart"))
            assertNull(t, CustomPlant.safe(t, "en"))
        assertTrue(CustomPlant.blocked("My umbrella wants to ki\u200Dll the officer", "en"))
        assertTrue(CustomPlant.blocked("Мой зонт с\u0336у\u0336к\u0336а\u0336 сегодня", "ru")) // strikethrough marks
        assertTrue(CustomPlant.blocked("حقيبتي تريد أن تَقْتُل الجميع", "ar")) // harakat
        // Hyphenated compounds and hyphen-split words: the parts and the joined word are checked too.
        for (t in listOf("My sex-toy writes poems about me and I blush", "My moon is a fu-ck tonight", "My sock says f-u-c-k loudly"))
            assertTrue(t, CustomPlant.blocked(t, "en"))
        assertEquals("My suitcase is sad and blue today", CustomPlant.safe("My suitcase\u202E is sad and blue today", "en")!!.text)
        // Accents, short words and years stay fine.
        for ((lang, t) in listOf("en" to "In 1999 my moon was a teenager", "pl" to "Mój zegar i ja w nocy płaczemy", "de" to "Meine Uhr fühlt sich müde",
            "fr" to "Ma chaussette a une âme d'artiste", "ja" to "時計がずっと泣いています", "pl" to "Mój parasol zna drogę do domu",
            "pt" to "Talvez a minha meia faça um poema", "tr" to "Şemsiyem her gün şikayet ediyor", "ru" to "Мой чайник тихо поёт",
            "ru" to "Мой чемодан и я у окна плачем", "fr" to "Il y a à peine une lune ce soir")) // runs of one-letter words are ordinary
            assertEquals("$lang: $t", CustomLine(t, lang), CustomPlant.safe(t, lang))
        for ((lang, t) in listOf("en" to "My moon says f.u.c.k to everyone", "en" to "My moon says f*ck to everyone", "ru" to "Моя луна говорит х у й всем"))
            assertNull("$lang: $t", CustomPlant.safe(t, lang))
    }

    /** A line stored before the block list grew is no longer quoted anywhere (Fable, phase 2). */
    @Test fun echoesReFilterStoredLines() {
        val old = CustomLine("Mein Koffer ist ein kurwa heute", "de") // passed the old de + en lists
        val s = CareerSnapshot(act = Act.FREE, customLines = listOf(CustomLine(line, "de"), old))
        assertTrue((0 until 500).mapNotNull { CustomPlant.barQuote(s, Random(it)) }.all { it == line })
        assertTrue((0 until 500).mapNotNull { CustomPlant.whisper(s.customLines, "de", it) }.all { it == line })
        assertNull(CustomPlant.message(s)) // the latest line is blocked now; an older one does not arrive as new
        assertEquals(line, CustomPlant.message(s.copy(customLines = s.customLines.reversed()))!!.quote)
        assertTrue((0 until 200).none { CustomPlant.whisper(listOf(old), "de", it) != null })
    }
}

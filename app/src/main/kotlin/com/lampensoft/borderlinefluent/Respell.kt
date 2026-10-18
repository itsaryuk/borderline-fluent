package com.lampensoft.borderlinefluent

import java.text.Normalizer

/** Languages [respell] reads aloud for an English speaker. */
val respelledLanguages = setOf("de", "fr", "es", "it", "pt", "pl")

/**
 * English-style pronunciation respelling ("Ich bin Tourist." → "ikh bin toorist."), deterministic, per language:
 * ordered grapheme rules plus a short table of irregular words. es/it/pt/pl mark the stressed syllable in capitals
 * and hyphenate; de/fr have no regular cheap stress rule and stay unhyphenated. Punctuation and digits pass through.
 * Empty for any other language.
 */
fun respell(text: String, lang: String): String {
    val l = LANGS[lang] ?: return ""
    return WORD.replace(Normalizer.normalize(text, Normalizer.Form.NFC)) { respellWord(it.value.lowercase().replace('’', '\''), l) }
}

private val WORD = Regex("\\p{L}+(?:['’]\\p{L}+)*")
private const val VOWELS = "aeiouàáâãäèéêëìíîïòóôõöùúûüąęœæ"
private fun v(c: Char?) = c != null && c in VOWELS
private infix fun Char?.of(s: String) = this != null && this in s
private fun cons(c: Char?) = c != null && c.isLetter() && !v(c)

/** kind: V nucleus, C consonant, G glide (joins the consonant before it); mark: 2 written accent, 1 Portuguese tilde. */
private class Seg(val text: String, val kind: Char, val mark: Int, val reduced: String?)

private class Ctx(val w: String, val i: Int, val j: Int, val end: Int) {
    val prev get() = w.getOrNull(i - 1)
    // Reads past `end` on purpose: the cut silent letters still decide nasals and c→s before a cut e.
    val next get() = w.getOrNull(j)
    val next2 get() = w.getOrNull(j + 1)
    val atEnd get() = j >= end
}

private class Rule(val pat: String, val out: String, val kind: Char = 'C', val mark: Int = 0, val red: String? = null,
                val ok: Ctx.() -> Boolean = { true })

private class Lang(val rules: List<Rule>, val words: Map<String, String>,
                   val stress: ((String, Int) -> Int)? = null, val prep: (String) -> Pair<Int, String> = { it.length to "" })

private fun respellWord(w: String, l: Lang): String {
    l.words[w]?.let { return it }
    if ('\'' in w) return w.split('\'').filter { it.isNotEmpty() }.joinToString(if (l.stress != null) "-" else "") { respellWord(it, l) }
    val (end, tail) = l.prep(w)
    val segs = segments(w, end, l.rules)
    val stress = l.stress ?: return segs.joinToString("") { it.text } + tail
    val syl = syllables(segs)
    if (syl.size < 2) return segs.joinToString("") { it.text } + tail
    val marked = (2 downTo 1).firstNotNullOfOrNull { m -> syl.indexOfFirst { s -> s.any { it.mark == m } }.takeIf { it >= 0 } }
    val k = marked ?: stress(w, syl.size).coerceIn(0, syl.lastIndex)
    return syl.mapIndexed { n, s ->
        val t = s.joinToString("") { if (n != k && it.reduced != null) it.reduced else it.text }
        if (n == k) t.uppercase() else t
    }.joinToString("-") + tail
}

private fun segments(w: String, end: Int, rules: List<Rule>): List<Seg> {
    val out = mutableListOf<Seg>()
    var i = 0
    while (i < end) {
        val r = rules.firstOrNull { i + it.pat.length <= end && w.startsWith(it.pat, i) && it.ok(Ctx(w, i, i + it.pat.length, end)) }
        if (r != null) {
            if (r.out.isNotEmpty()) out += Seg(r.out, r.kind, r.mark, r.red)
            i += r.pat.length
        } else {
            val t = Normalizer.normalize(w[i].toString(), Normalizer.Form.NFD).filter { it in 'a'..'z' }
            if (t.isNotEmpty()) out += Seg(t, if (t in "aeiou") 'V' else 'C', 0, null)
            i++
        }
    }
    return out
}

private val OBSTRUENTS = setOf("p", "b", "t", "d", "k", "g", "f", "v")

/** One consonant starts the next syllable; a glide takes its consonant along, and so does l/r after an obstruent. */
private fun syllables(s: List<Seg>): List<List<Seg>> {
    val nuclei = s.indices.filter { s[it].kind == 'V' }
    if (nuclei.size < 2) return listOf(s)
    val starts = mutableListOf(0)
    for ((a, b) in nuclei.zipWithNext()) {
        val gap = b - a - 1
        var onset = minOf(gap, 1)
        if (gap >= 2 && s[b - 1].kind == 'G') onset = 2
        if (gap > onset && s[b - onset].text in setOf("l", "r") && s[b - onset - 1].text in OBSTRUENTS) onset++
        starts += b - onset
    }
    return (starts + s.size).zipWithNext { a, b -> s.subList(a, b) }
}

private fun vowels(vararg p: Pair<String, String>, kind: Char = 'V') = p.map { Rule(it.first, it.second, kind) }
private fun accented(vararg p: Pair<String, String>) = p.map { Rule(it.first, it.second, 'V', 2) }

// German: a vowel is long before h, doubled, open, or before one consonant and a vowel; short otherwise.
private fun Ctx.longV(): Boolean {
    var k = j; var n = 0
    while (k < end && !v(w[k])) { n += if (w[k] in "xz") 2 else 1; k++ }
    return n == 0 || n == 1 && k < end
}
private fun Ctx.devoice() = atEnd || cons(next) && !(next of "lr")
private val DE = Lang(listOf(
    Rule("tsch", "ch"), Rule("sch", "sh"), Rule("chs", "ks"), Rule("ch", "kh"), Rule("ck", "k"),
    Rule("ig", "ikh") { atEnd }, Rule("ieh", "ee"), Rule("ie", "ee"),
    Rule("ei", "igh"), Rule("ey", "igh"), Rule("ai", "igh"), Rule("eu", "oy"), Rule("äu", "oy"), Rule("au", "ow"),
    Rule("aa", "ah"), Rule("ee", "ay"), Rule("oo", "oh"),
    Rule("ah", "ah"), Rule("eh", "ay"), Rule("ih", "ee"), Rule("oh", "oh"), Rule("uh", "oo"), Rule("äh", "ay"), Rule("öh", "er"), Rule("üh", "ew"),
    Rule("e", "uh") { atEnd && w.take(i).any(::v) }, Rule("e", "uh") { i == 1 && prev of "bg" && cons(next) && next != 'h' && (v(next2) || next2 == 'c') },
    Rule("a", "ah") { longV() }, Rule("a", "a"), Rule("e", "ay") { longV() }, Rule("e", "e"), Rule("i", "ee") { longV() }, Rule("i", "i"),
    Rule("o", "oh") { longV() }, Rule("o", "o"), Rule("u", "oo"), Rule("ä", "ay") { longV() }, Rule("ä", "e"), Rule("ö", "er"), Rule("ü", "ew"), Rule("y", "ee"),
    Rule("ß", "s"), Rule("ss", "s"), Rule("sp", "shp") { i == 0 }, Rule("st", "sht") { i == 0 },
    Rule("s", "z") { v(next) && (i == 0 || v(prev) || prev of "lmnr") },
    Rule("tz", "ts"), Rule("z", "ts"), Rule("ph", "f"), Rule("th", "t"), Rule("qu", "kv"), Rule("ng", "ng"), Rule("dt", "t"),
    Rule("b", "p") { devoice() }, Rule("d", "t") { devoice() }, Rule("g", "k") { devoice() },
    Rule("c", "ts") { next of "eiäy" }, Rule("c", "k"), Rule("v", "f"), Rule("w", "v"), Rule("j", "y"), Rule("x", "ks"),
    Rule("ll", "l"), Rule("mm", "m"), Rule("nn", "n"), Rule("tt", "t"), Rule("pp", "p"), Rule("ff", "f"), Rule("rr", "r"), Rule("bb", "b"), Rule("dd", "d"), Rule("gg", "g"),
), mapOf(
    "der" to "dair", "er" to "air", "wer" to "vair", "wir" to "veer", "mir" to "meer", "dir" to "deer", "war" to "vahr",
    "gut" to "goot", "tag" to "tahk", "weg" to "vek", "geben" to "gayben", "gegen" to "gaygen", "den" to "dayn", "dem" to "daym",
    "zum" to "tsoom", "vor" to "fohr", "für" to "fewr", "nur" to "noor", "schon" to "shohn", "tourist" to "toorist",
    "touristin" to "tooristin", "je" to "yay", "hat" to "hat", "ob" to "op",
    "kanadier" to "kahnahdee-er", "spanier" to "shpahnee-er", "italiener" to "eetahlee-ayner", "belgier" to "belgee-er",
    "cousin" to "koozan", "cousins" to "koozans", "reisepass" to "righzuhpas",
))

// French: final silent letters are cut by [frEnd]; no stress, no liaison.
private fun Ctx.nasal() = next == null || !v(next) && !(next of "nmh")
private const val FRONT = "eéèêëiîy"
/** e is open ("eh") before two consonants (not a consonant + l/r onset or a digraph) or before one final consonant. */
private fun Ctx.closedE(): Boolean {
    val a = next; val b = next2
    if (a == 'x') return true
    if (cons(a) && j + 1 >= end) return true
    return cons(a) && cons(b) && !(b of "rl" && a of "bcdfgkptv") && "$a$b" !in setOf("ch", "ph", "th", "gn", "qu")
}
/** VC(C)e(C)V: a schwa after one consonant sound with a vowel before it is dropped ("Allemagne" → "ahlmahny"). */
private fun Ctx.droppedE(): Boolean {
    var k = i - 1
    if (!cons(w.getOrNull(k))) return false
    if (w.getOrNull(k - 1) == w[k]) k--
    return v(w.getOrNull(k - 1))
}
private fun frEnd(w: String): Pair<Int, String> {
    var e = w.length
    if (e > 2 && w[e - 1] in "sx") e--
    val s = w.substring(0, e)
    return when {
        e > 2 && (s.endsWith("er") || s.endsWith("ez")) -> e - 2 to "ay"
        e > 2 && s.endsWith("et") -> e - 2 to "eh"
        s.endsWith("e") && s.dropLast(1).any(::v) -> e - 1 to ""
        else -> {
            while (e > 1 && w[e - 1] in "dtpgsxz" && w.take(e - 1).any(::v)) e--
            e to ""
        }
    }
}
private val FR = Lang(listOf(
    Rule("eau", "oh"), Rule("ail", "ahy") { atEnd }, Rule("eil", "ehy") { atEnd },
    Rule("ien", "yan") { nasal() }, Rule("oin", "wan") { nasal() },
    Rule("ain", "an") { nasal() }, Rule("aim", "an") { nasal() }, Rule("ein", "an") { nasal() }, Rule("eim", "an") { nasal() },
    Rule("an", "ahn") { nasal() }, Rule("am", "ahn") { nasal() }, Rule("en", "ahn") { nasal() }, Rule("em", "ahn") { nasal() },
    Rule("in", "an") { nasal() }, Rule("im", "an") { nasal() }, Rule("yn", "an") { nasal() }, Rule("ym", "an") { nasal() },
    Rule("on", "ohn") { nasal() }, Rule("om", "ohn") { nasal() }, Rule("un", "uhn") { nasal() }, Rule("um", "uhn") { nasal() },
    Rule("au", "oh"), Rule("oi", "wah"), Rule("oy", "wahy"), Rule("ou", "w") { v(next) }, Rule("ou", "oo"), Rule("où", "oo"), Rule("oû", "oo"),
    Rule("ai", "eh"), Rule("aî", "eh"), Rule("ei", "eh"), Rule("eu", "uh"), Rule("œu", "uh"), Rule("ui", "wee"),
    Rule("ill", "y") { v(prev) }, Rule("ill", "eey"), Rule("tion", "syohn"),
    Rule("é", "ay"), Rule("è", "eh"), Rule("ê", "eh"), Rule("ë", "eh"),
    Rule("e", "uh") { atEnd && w.take(i).none(::v) }, Rule("e", "") { atEnd }, Rule("e", "eh") { closedE() }, Rule("e", "") { droppedE() }, Rule("e", "uh"),
    Rule("a", "ah"), Rule("à", "ah"), Rule("â", "ah"), Rule("i", "y") { v(next) && i > 0 }, Rule("i", "ee"), Rule("î", "ee"), Rule("ï", "ee"), Rule("y", "ee"),
    Rule("o", "oh"), Rule("ô", "oh"), Rule("u", "ew"), Rule("û", "ew"), Rule("ù", "ew"), Rule("œ", "uh"), Rule("æ", "ay"),
    Rule("ch", "sh"), Rule("ph", "f"), Rule("th", "t"), Rule("gn", "ny"), Rule("qu", "k"), Rule("gu", "g") { next of FRONT },
    Rule("ge", "zh") { next of "aoâôu" }, Rule("g", "zh") { next of FRONT }, Rule("cc", "ks") { next of FRONT }, Rule("cc", "k"),
    Rule("sc", "s") { next of FRONT }, Rule("c", "s") { next of FRONT }, Rule("ç", "s"), Rule("c", "k"),
    Rule("ss", "s"), Rule("s", "z") { v(prev) && v(next) }, Rule("x", "ks"), Rule("j", "zh"), Rule("h", ""),
    Rule("ll", "l"), Rule("tt", "t"), Rule("nn", "n"), Rule("mm", "m"), Rule("pp", "p"), Rule("rr", "r"), Rule("ff", "f"), Rule("bb", "b"), Rule("dd", "d"),
), mapOf(
    "est" to "ay", "et" to "ay", "les" to "lay", "des" to "day", "mes" to "may", "tes" to "tay", "ses" to "say", "ces" to "say",
    "ai" to "ay", "je" to "zhuh", "suis" to "swee", "vous" to "voo", "oui" to "wee", "merci" to "mehrsee", "monsieur" to "muhsyuh",
    "madame" to "mahdahm", "beaucoup" to "bohkoo", "c" to "s", "j" to "zh", "qu" to "k", "y" to "ee", "a" to "ah",
    "que" to "kuh", "jean" to "zhahn", "jeanne" to "zhahn", "deuxième" to "duhzyehm", "huit" to "weet", "sept" to "set", "six" to "sees", "dix" to "dees", "cinq" to "sank", "neuf" to "nuhf",
    "un" to "uhn", "une" to "ewn", "en" to "ahn", "hier" to "yehr", "mer" to "mehr", "fils" to "fees", "pays" to "payee",
    "ville" to "veel", "mille" to "meel", "femme" to "fahm", "plus" to "plew", "tous" to "too", "bus" to "bews",
), prep = ::frEnd)

// Spanish (seseo: c/z as "s"): a written accent marks stress, else the penultimate after a vowel, n or s, else the last.
private const val ES_FRONT = "eiéí"
private val ES = Lang(accented("á" to "ah", "é" to "eh", "í" to "ee", "ó" to "oh", "ú" to "oo") + listOf(
    Rule("ai", "eye", 'V') { !v(next) }, Rule("ay", "eye", 'V') { !v(next) }, Rule("ei", "ay", 'V') { !v(next) }, Rule("ey", "ay", 'V') { !v(next) },
    Rule("oi", "oy", 'V') { !v(next) }, Rule("oy", "oy", 'V') { !v(next) }, Rule("au", "ow", 'V') { !v(next) },
    Rule("uy", "wee", 'V') { !v(next) }, Rule("ui", "wee", 'V') { !v(next) },
    Rule("i", "y", 'G') { v(next) }, Rule("u", "w", 'G') { v(next) }, Rule("ü", "w", 'G'),
    Rule("y", "ee", 'V') { !v(next) }, Rule("y", "y"),
) + vowels("a" to "ah", "e" to "eh", "i" to "ee", "o" to "oh", "u" to "oo") + listOf(
    Rule("ch", "ch"), Rule("ll", "y"), Rule("ñ", "ny"), Rule("rr", "r"), Rule("qu", "k"), Rule("gu", "g") { next of ES_FRONT },
    Rule("g", "h") { next of ES_FRONT }, Rule("c", "s") { next of ES_FRONT }, Rule("c", "k"), Rule("j", "h"), Rule("h", ""),
    Rule("v", "b"), Rule("z", "s"), Rule("x", "ks"),
), mapOf("méxico" to "MEH-hee-koh"),
    stress = { w, n -> if (w.last() in "aeiouns") n - 2 else n - 1 })

// Italian: a final written accent marks stress, else the penultimate (frequent exceptions in the table).
private const val IT_FRONT = "eiéèì"
private val IT = Lang(accented("à" to "ah", "è" to "eh", "é" to "eh", "ì" to "ee", "ò" to "oh", "ó" to "oh", "ù" to "oo") + listOf(
    Rule("ai", "eye", 'V') { !v(next) }, Rule("ei", "ay", 'V') { !v(next) }, Rule("oi", "oy", 'V') { !v(next) }, Rule("au", "ow", 'V') { !v(next) },
    Rule("i", "y", 'G') { v(next) && cons(prev) }, Rule("u", "w", 'G') { v(next) && cons(prev) },
) + vowels("a" to "ah", "e" to "eh", "i" to "ee", "o" to "oh", "u" to "oo", "y" to "ee") + listOf(
    Rule("c", "t") { next == 'c' && next2 of IT_FRONT }, Rule("ci", "ch") { v(next) }, Rule("c", "ch") { next of IT_FRONT },
    Rule("ch", "k"), Rule("c", "k"),
    Rule("g", "d") { next == 'g' && next2 of IT_FRONT }, Rule("gli", "ly") { v(next) }, Rule("gli", "lyee", 'V'), Rule("gn", "ny"),
    Rule("gi", "j") { v(next) }, Rule("g", "j") { next of IT_FRONT }, Rule("gh", "g"),
    Rule("sci", "sh") { v(next) }, Rule("sc", "sh") { next of IT_FRONT },
    Rule("qu", "kw"), Rule("z", "t") { next == 'z' }, Rule("z", "ts"), Rule("h", ""), Rule("j", "y"), Rule("x", "ks"),
), mapOf(
    "e" to "eh", "c'è" to "cheh", "c" to "ch", "ciao" to "chow", "canada" to "KAH-nah-dah", "abito" to "AH-bee-toh", "abita" to "AH-bee-tah",
    "mio" to "MEE-oh", "mia" to "MEE-ah", "suo" to "SOO-oh", "sua" to "SOO-ah", "tuo" to "TOO-oh", "tua" to "TOO-ah",
    "zio" to "TSEE-oh", "zia" to "TSEE-ah", "via" to "VEE-ah", "due" to "DOO-eh", "hotel" to "oh-TEHL", "gli" to "lyee", "all" to "ahl", "dall" to "dahl", "dell" to "dehl", "nell" to "nehl", "sull" to "sool",
    "numero" to "NOO-meh-roh", "subito" to "SOO-bee-toh", "camera" to "KAH-meh-rah", "ecco" to "EHK-koh",
    "undici" to "OON-dee-chee", "dodici" to "DOH-dee-chee", "tredici" to "TREH-dee-chee", "quattordici" to "kwaht-TOHR-dee-chee",
    "quindici" to "KWEEN-dee-chee", "sedici" to "SEH-dee-chee", "medico" to "MEH-dee-koh", "telefono" to "teh-LEH-foh-noh",
    "napoli" to "NAH-poh-lee", "macchina" to "MAHK-kee-nah", "sabato" to "SAH-bah-toh", "eccolo" to "EHK-koh-loh", "eccola" to "EHK-koh-lah", "lavoro" to "lah-VOH-roh",
), stress = { _, n -> n - 2 })

// European Portuguese: unstressed a/e/o reduce to "uh"/"uh"/"oo"; s before a consonant or at the end is "sh".
private const val PT_FRONT = "eiéêí"
private fun Ctx.nasC() = cons(next) && next != 'h'
private fun Ctx.ptGlide() = v(next) && w.any { it in "áéíóúâêô" }
private val PT = Lang(listOf(
    Rule("ão", "own", 'V', 1), Rule("ãe", "ayn", 'V', 1), Rule("õe", "oyn", 'V', 1), Rule("ã", "ahn", 'V', 1), Rule("õ", "ohn", 'V', 1),
    Rule("ên", "ehn", 'V', 2) { nasC() }, Rule("ân", "ahn", 'V', 2) { nasC() }, Rule("ôn", "ohn", 'V', 2) { nasC() },
    Rule("éns", "aynsh", 'V', 2) { atEnd }, Rule("ém", "ayn", 'V', 2) { atEnd },
) + accented("á" to "ah", "â" to "ah", "é" to "eh", "ê" to "ay", "í" to "ee", "ó" to "oh", "ô" to "oh", "ú" to "oo") + listOf(
    Rule("ens", "aynsh", 'V') { atEnd }, Rule("em", "ayn", 'V') { atEnd }, Rule("am", "own", 'V') { atEnd }, Rule("im", "een", 'V') { atEnd },
    Rule("om", "ohn", 'V') { atEnd }, Rule("um", "oon", 'V') { atEnd },
    Rule("an", "ahn", 'V') { nasC() }, Rule("am", "ahn", 'V') { nasC() }, Rule("en", "ehn", 'V') { nasC() }, Rule("em", "ehn", 'V') { nasC() },
    Rule("in", "een", 'V') { nasC() }, Rule("im", "een", 'V') { nasC() }, Rule("on", "ohn", 'V') { nasC() }, Rule("om", "ohn", 'V') { nasC() },
    Rule("un", "oon", 'V') { nasC() }, Rule("um", "oon", 'V') { nasC() },
    Rule("ou", "oh", 'V') { !v(next) }, Rule("ei", "ay", 'V') { !v(next) }, Rule("ai", "eye", 'V') { !v(next) }, Rule("oi", "oy", 'V') { !v(next) },
    Rule("au", "ow", 'V') { !v(next) }, Rule("eu", "ehw", 'V') { !v(next) }, Rule("iu", "eew", 'V') { !v(next) }, Rule("ui", "ooy", 'V') { !v(next) },
    Rule("i", "y", 'G') { ptGlide() }, Rule("u", "w", 'G') { ptGlide() },
    Rule("a", "ah", 'V', red = "uh"), Rule("e", "eh", 'V', red = "uh"), Rule("o", "oh", 'V', red = "oo"),
    Rule("i", "ee", 'V'), Rule("u", "oo", 'V'), Rule("y", "ee", 'V'),
    Rule("lh", "ly"), Rule("nh", "ny"), Rule("ch", "sh"), Rule("qu", "k") { next of PT_FRONT }, Rule("qu", "kw"), Rule("gu", "g") { next of PT_FRONT },
    Rule("g", "zh") { next of PT_FRONT }, Rule("c", "s") { next of PT_FRONT }, Rule("ç", "s"), Rule("c", "k"), Rule("j", "zh"),
    Rule("ss", "s"), Rule("s", "z") { v(prev) && v(next) }, Rule("s", "sh") { atEnd || next of "cfkpqt" }, Rule("s", "zh") { cons(next) },
    Rule("z", "sh") { atEnd }, Rule("x", "sh"), Rule("rr", "r"), Rule("h", ""),
), mapOf(
    "e" to "ee", "o" to "oo", "os" to "oosh", "a" to "uh", "as" to "uhsh", "de" to "duh", "do" to "doo", "dos" to "doosh",
    "da" to "duh", "das" to "duhsh", "no" to "noo", "nos" to "noosh", "na" to "nuh", "nas" to "nuhsh", "que" to "kuh",
    "se" to "suh", "me" to "muh", "te" to "tuh", "lhe" to "lyuh", "um" to "oon", "eu" to "ehw", "muito" to "MOOYN-too",
    "muita" to "MOOYN-tuh", "obrigado" to "oh-bree-GAH-doo", "obrigada" to "oh-bree-GAH-duh", "para" to "PAH-ruh",
), stress = { w, n -> if (w.trimEnd('s').last() in "aeo" || w.endsWith("am") || w.endsWith("em") || w.endsWith("ens")) n - 2 else n - 1 })

// Polish: always the penultimate; si/zi/ci/dzi/ni soften, final consonants devoice.
private val PL = Lang(listOf(
    Rule("ą", "ohm", 'V') { next of "bp" }, Rule("ą", "ohn", 'V'), Rule("ę", "ehm", 'V') { next of "bp" }, Rule("ę", "eh", 'V') { atEnd },
    Rule("ę", "ehn", 'V'), Rule("i", "") { v(next) && prev of "szc" }, Rule("i", "y", 'G') { v(next) && cons(prev) },
) + vowels("a" to "ah", "e" to "eh", "i" to "ee", "o" to "oh", "u" to "oo", "ó" to "oo", "y" to "ih") + listOf(
    Rule("dż", "j"), Rule("dź", "ch") { atEnd }, Rule("dź", "j"), Rule("dz", "j") { next == 'i' }, Rule("dz", "ts") { atEnd }, Rule("dz", "dz"), Rule("sz", "sh"), Rule("cz", "ch"),
    Rule("rz", "sh") { prev of "ptkh" || atEnd }, Rule("rz", "zh"), Rule("ż", "sh") { atEnd }, Rule("ż", "zh"), Rule("ź", "zh"), Rule("ś", "sh"),
    Rule("ć", "ch"), Rule("ń", "n"), Rule("s", "sh") { next == 'i' }, Rule("z", "zh") { next == 'i' }, Rule("c", "ch") { next == 'i' },
    Rule("z", "s") { atEnd }, Rule("ch", "kh"), Rule("c", "ts"), Rule("ł", "w"),
    Rule("w", "f") { atEnd || prev of "kpt" || next of "kptscfhść" }, Rule("w", "v"), Rule("j", "y", 'G') { cons(prev) }, Rule("j", "y"),
    Rule("b", "p") { atEnd }, Rule("d", "t") { atEnd }, Rule("g", "k") { atEnd },
), mapOf("w" to "v", "z" to "z"), stress = { _, n -> n - 2 })

private val LANGS = mapOf("de" to DE, "fr" to FR, "es" to ES, "it" to IT, "pt" to PT, "pl" to PL)

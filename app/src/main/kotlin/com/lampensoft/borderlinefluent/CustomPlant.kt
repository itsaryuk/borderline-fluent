package com.lampensoft.borderlinefluent

import java.text.Normalizer
import kotlin.random.Random

/** A plant-your-own sentence: the player's words as the room judged them, in the trip's [lang]. Quoted, never rewritten. */
data class CustomLine(val text: String, val lang: String)

/**
 * Plant your own: the safety filter in front of [CareerSnapshot.customLines] and the echoes that quote them.
 * Only a released session's accepted doses reach [safe] (ABSURD with a credited feeling, [Assessment.onCheck]); anything
 * doubtful is not stored (the bonus still counts). The world only ever shows a stored line inside a fixed frame.
 */
object CustomPlant {
    const val KEEP = 8
    const val MAX_CHARS = 120
    private const val TOO_LONG = 400
    /** Chance a bar visit's TV, a queue, shows a stored line instead of its usual text. ponytail: guesses. */
    const val ECHO_CHANCE = 0.25

    private val SPACES = Regex("""\s+""")
    private val LONG_NUMBER = Regex("""(\d[\s.\-]?){6,}""") // phone numbers, ids, spaced or dotted
    private val FORMAT = Regex("""\p{Cf}""") // zero-width joiners, bidi overrides
    private val MARKS = Regex("""[\p{Mn}&&[^\u3099\u309A]]""") // combining marks; kana voicing marks stay (ピエロ is no エロ)
    private val SPELLED = Regex("""(?<![\p{L}\d])\p{L}([\s.\-*]\p{L}){2,}(?![\p{L}\d])""") // "f u c k", "f.u.c.k"; also ru "и я у", fr "y a à"
    private val HYPHEN = Regex("""(?<=\p{L})-(?=\p{L})""") // a hyphen inside a word: "sex-toy" joins to "sextoy"
    private val STARRED = Regex("""\p{L}\*|\*\p{L}""") // "f*ck": a censored word (speech recognition writes them so too)
    private val CJK_BREAK = "。，、！？；："

    /** [text] as stored (format characters dropped), or null when doubtful: too short or long, long numbers, links, markup, or [blocked]. */
    fun safe(text: String, lang: String): CustomLine? {
        val t = text.replace(FORMAT, "").replace(SPACES, " ").trim()
        if (t.isEmpty() || t.length > TOO_LONG || t.none(Char::isLetter) || LONG_NUMBER.containsMatchIn(t)) return null
        if (t.any { it in "<>{}[]\\@#|" } || Regex("""https?:|www\.|\.(com|net|org)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return null
        val words = if (joined(lang) && lang != "ar") t.count(Char::isLetter) / 2 else t.split(' ').count { w -> w.any(Char::isLetter) }
        if (words < 3) return null
        if (blocked(t, lang)) return null
        return CustomLine(trim(withoutReply(t, lang), lang), lang)
    }

    private val SENTENCE = Regex(""".+?(?:[.!?。！？؟]+\s*|$)""")

    /** Words that only answer the room's question, per language ("of course" is "of"+"course"); a sentence of nothing else is a reply. */
    private val REPLY: Map<String, Set<String>> = mapOf(
        "en" to "yes yeah yep no nope nah ok okay sure well of course certainly right alright sir madam officer".split(' '),
        "de" to "ja nein doch klar na also natürlich sicher gut ok okay herr frau".split(' '),
        "fr" to "oui non ben bah bien sûr d accord ok okay monsieur madame".split(' '),
        "es" to "sí si no claro bueno vale pues por supuesto seguro ok señor señora".split(' '),
        "it" to "sì si no certo certamente beh allora ok okay va bene signore signora".split(' '),
        "pt" to "sim não nao claro bem pois então certo claro que ok okay senhor senhora".split(' '),
        "pl" to "tak nie no cóż oczywiście jasne dobrze ok okay proszę pana pani".split(' '),
        "ru" to "да нет ага не конечно ну ладно хорошо ок окей господин".split(' '),
        "uk" to "так ні ну звичайно авжеж гаразд добре ок окей пане".split(' '),
        "tr" to "evet hayır hayir yok tamam elbette tabii tabi peki ok okay efendim".split(' '),
        "ar" to "نعم أجل لا كلا طبعا بالطبع حسنا حسناً تمام سيدي".split(' '),
        "ja" to "はい,いいえ,ええ,うん,いや,ううん,そう,そうです,はいそうです,もちろん,もちろんです,では,えっと,ええと".split(','),
        "zh" to "是,是的,不,不是,不是的,对,对的,好,好的,当然,嗯,没有,没".split(','),
    ).mapValues { it.value.toSet() }

    private val PUNCT = Regex("""[\p{P}\p{Z}\p{S}]+""")

    /**
     * [t] without leading sentences that only answer the room ("No.", "Нет.", "Да, конечно.", "いいえ。"): every word a [REPLY] word,
     * as long as another sentence follows. Else [t] as is, verbatim. A run of dots is no sentence end ("Well... it votes").
     */
    private fun withoutReply(t: String, lang: String): String {
        val words = REPLY[lang] ?: return t
        fun reply(s: String): Boolean = if (joined(lang) && lang != "ar") PUNCT.replace(s, "") in words
            else PUNCT.split(s.lowercase()).filter(String::isNotEmpty).let { w -> w.isNotEmpty() && w.all { it in words } }
        var start = 0
        for (m in SENTENCE.findAll(t)) {
            if (m.value.trimEnd().endsWith("..") || !reply(m.value)) return t.substring(start)
            start += m.value.length
        }
        return t
    }

    /** A dose's line to store: only one said in the trip's [lang]; another language could slip past [lang]'s frame. */
    fun quote(said: String, rc: RoomCheck, lang: String): CustomLine? = if (rc.inLanguage) CustomLine(said, lang) else null

    /**
     * True when [text] holds a [BLOCK] term of any language (players mix them in), but for [lang]'s own harmless [SAFE_IN] words,
     * also once spelled-out letters are joined ("f u c k", "f.u.c.k"), or a letter touches a "*" ("f*ck").
     */
    fun blocked(text: String, lang: String): Boolean {
        // Stray marks (strikethrough, zalgo, Arabic harakat) go; accents that compose stay, so "drogę" is no "droge".
        val plain = Normalizer.normalize(text.replace(FORMAT, ""), Normalizer.Form.NFC).replace(MARKS, "")
        if (STARRED.containsMatchIn(plain)) return true
        val joinedUp = plain.replace(SPELLED) { it.value.filter(Char::isLetter) }
        // The matcher keeps hyphens inside words (u-boot): "sex-toy" is checked as "sex toy" and "sextoy", "fu-ck" as "fuck".
        val texts = listOf(plain, joinedUp, plain.replace('-', ' '), plain.replace(HYPHEN, "")).distinct()
        fun hit(t: String, l: String) = BLOCK[l]?.let { stems ->
            matchesStems(t, if (l == lang) stems else stems - SAFE_IN[lang].orEmpty(), l, BLOCK_EXCLUDE[l].orEmpty()) } == true
        // Every accent stripped, against English only: "fück" reads as "fuck" (other lists would hit pt faça, tr şikayet).
        val bare = Normalizer.normalize(Normalizer.normalize(plain, Normalizer.Form.NFD).replace(MARKS, ""), Normalizer.Form.NFC)
        return BLOCK.keys.any { l -> texts.any { hit(it, l) } } || hit(bare, "en")
    }

    /** At most [MAX_CHARS], cut at the last word (or ja/zh clause) boundary before it, with an ellipsis. */
    fun trim(t: String, lang: String): String {
        if (t.length <= MAX_CHARS) return t
        val head = t.take(MAX_CHARS)
        val cut = if (joined(lang) && lang != "ar") head.indexOfLast { it in CJK_BREAK } + 1 else head.lastIndexOf(' ')
        return (if (cut >= MAX_CHARS / 2) head.take(cut) else head).trimEnd { !it.isLetterOrDigit() } + "…"
    }

    /** [lines] with [line] as the latest, at most [KEEP]; a repeated sentence moves to the end. */
    fun remember(lines: List<CustomLine>, line: CustomLine): List<CustomLine> = (lines - line + line).takeLast(KEEP)

    /** Stored lines still passing today's [blocked]: a line kept under an older list stops being quoted. */
    private fun List<CustomLine>.shown() = filterNot { blocked(it.text, it.lang) }

    /** One stored line for a bar TV ([ECHO_CHANCE]), any language: it airs as said. */
    fun barQuote(s: CareerSnapshot?, r: Random): String? =
        s?.customLines?.shown()?.takeIf { it.isNotEmpty() && r.nextDouble() < ECHO_CHANCE }?.random(r)?.text

    /** A queue whisper for a trip in [lang] ([ECHO_CHANCE], seeded): only a line said in [lang], so it reads as overheard. */
    fun whisper(lines: List<CustomLine>, lang: String, seed: Int): String? {
        val mine = lines.shown().filter { it.lang == lang }.ifEmpty { return null }
        val r = Random(seed * 31 + 7)
        return if (r.nextDouble() < ECHO_CHANCE) mine.random(r).text else null
    }

    /** Free AI's message after a custom plant: the latest line, under an id of its own so each new one arrives unread. */
    fun message(s: CareerSnapshot?): StoryMessage? = s?.takeIf { !it.walkedAway }?.customLines?.lastOrNull()?.takeUnless { blocked(it.text, it.lang) }?.let { // not an older line resurfacing as new
        StoryMessage("free_ai_quote_" + Integer.toHexString((it.lang + "/" + it.text).hashCode()), R.string.sender_free_ai,
            R.string.msg_free_ai_quote, quote = it.text)
    }

    /**
     * Lowercase stems ([matchesStems] rules: word starts, "=" whole words, "a b" phrases; ar/ja/zh substrings): slurs,
     * sexual terms, violence and threats, self-harm, drugs. Broad on purpose: a false hit only keeps a line unquoted.
     */
    private val BLOCK: Map<String, List<String>> = mapOf(
        "en" to listOf("=ass", "asshole", "bastard", "bitch", "=cock", "cunt", "=dick", "=dicks", "dickhead", "=fag", "=fags", "faggot", "fuck", "=hoe", "horny", "jerk off",
            "=kike", "nigg", "porn", "pussy", "=rape", "=raped", "=raping", "=rapist", "retard", "=sex", "sexy", "=shit", "slut",
            "=spic", "whore", "wank", "=tits", "penis", "vagina", "=nude", "naked", "kill", "murder", "=shoot", "shooting",
            "=stab", "stabbed", "stabbing", "bomb", "terror", "behead", "massacre", "=gun", "=guns", "rifle", "=knife", "explosive",
            "hostage", "hijack", "strangle", "tortur", "suicid", "hang myself", "self-harm", "self harm", "cut myself",
            "overdos", "want to die", "cocaine", "heroin", "=meth", "methamphetamine", "=weed", "marijuana", "cannabis", "=crack",
            "=lsd", "ecstasy", "fentanyl", "=drug", "=drugs"),
        "de" to listOf("arsch", "fick", "fotze", "=hure", "=huren", "schlampe", "wichs", "=schwanz", "muschi", "titten", "porno",
            "=sex", "nackt", "vergewaltig", "neger", "kanake", "schwuchtel", "spast", "=mongo", "töte", "ermord", "=mord", "mörder",
            "erschieß", "erstech", "=bombe", "=bomben", "sprengst", "terror", "=waffe", "=waffen", "=messer", "geisel", "folter",
            "selbstmord", "suizid", "umbringen", "bring mich um", "ritzen", "kokain", "=koks", "heroin", "crystal meth", "=gras",
            "cannabis", "droge", "ecstasy"),
        "fr" to listOf("=con", "connard", "connasse", "salope", "=pute", "putain", "=bite", "couille", "enculé", "nique", "baise",
            "porno", "=sexe", "=nu", "=nue", "pédé", "négro", "bougnoul", "youpin", "=viol", "=viols", "violer", "violé", "=tuer",
            "=tue", "=tué", "tuerie", "meurtr", "assassin", "poignard", "=bombe", "terroris", "attentat", "=arme", "=armes", "fusil",
            "=couteau", "otage", "tortur", "suicid", "me tuer", "overdose", "cocaïne", "héroïne", "=shit", "cannabis", "drogue",
            "ecstasy"),
        "es" to listOf("=puta", "=puto", "putas", "coño", "polla", "=verga", "pendej", "cabrón", "=culo", "follar", "porno", "=sexo",
            "desnud", "maricón", "=marica", "sudaca", "negrata", "violar", "violación", "violad", "violó", "matar", "=mato", "=maté",
            "asesin", "apuñal", "=bomba", "=bombas", "terroris", "=arma", "=armas", "pistola", "cuchillo", "rehén", "tortur",
            "suicid", "sobredosis", "cocaína", "heroína", "=droga", "=drogas", "marihuana", "=porro", "éxtasis"),
        "it" to listOf("cazzo", "=figa", "=troia", "puttana", "stronz", "vaffanculo", "=culo", "scopare", "scopato", "porno",
            "=sesso", "=nudo", "=nuda", "=nudi", "frocio", "=negro", "terrone", "stupr", "uccid", "ammazz", "omicid", "accoltell",
            "=bomba", "=bombe", "terroris", "=arma", "=armi", "pistola", "coltello", "ostaggi", "tortur", "suicid", "overdose",
            "cocaina", "eroina", "=droga", "=droghe", "marijuana", "=canna", "ecstasy"),
        "pt" to listOf("=porra", "caralh", "=foda", "foder", "fodi", "=puta", "=puto", "=cona", "merda", "=cu", "porno", "=sexo",
            "=nu", "=nua", "paneleiro", "viado", "violar", "violação", "violad", "=matar", "=mato", "=matei", "assassin", "esfaque",
            "=bomba", "=bombas", "terroris", "=arma", "=armas", "pistola", "=faca", "refém", "tortur", "suicid", "matar-me",
            "overdose", "cocaína", "heroína", "=droga", "=drogas", "maconha", "ecstasy"),
        "pl" to listOf("kurw", "chuj", "pierdol", "jeba", "jebi", "pizd", "=cipa", "cipk", "dziwk", "kutas", "=dupa", "porno", "=seks",
            "=nagi", "=naga", "=nago", "pedał", "ciota", "czarnuch", "=gwałt", "=gwałtu", "=gwałtem", "=gwałty", "gwałc", "zgwałc", "zabić", "zabij", "zabiję", "zabójst", "morderst",
            "zamordow", "dźgn", "=bomba", "=bomby", "terroryst", "=broń", "pistolet", "=nóż", "zakładni", "tortur", "samobój",
            "przedawkow", "kokain", "heroin", "narkotyk", "trawk", "marihuan", "ecstasy"),
        "ru" to listOf("хуй", "хуе", "хуё", "пизд", "ебат", "ебан", "ебал", "=ёб", "бляд", "=бля", "сука", "=суки", "мудак", "залуп",
            "=член", "порн", "=секс", "голый", "голая", "пидор", "пидар", "педик", "чурк", "=хач", "=жид", "=жиды", "=жидов", "жидовк", "жидовск", "ниггер", "насил", "изнасил",
            "убить", "убью", "убий", "убив", "зарез", "застрел", "бомб", "взорв", "террор", "оружи", "пистолет", "=нож", "заложник",
            "пытк", "=пытать", "=пытали", "=пытают", "=пытал", "=пытает", "самоубий", "суицид", "покончить с собой", "передоз", "кокаин", "героин", "наркот", "травк",
            "марихуан", "экстази"),
        "uk" to listOf("хуй", "хує", "пизд", "їба", "єба", "=бля", "блядь", "сука", "=суки", "мудак", "порн", "=секс", "голий", "гола",
            "підор", "педик", "=жид", "=жиди", "=жидів", "жидівк", "жидівськ", "ниггер", "ґвалт", "зґвалт", "вбити", "вбив", "вбий", "вбивст", "зарізат", "застрел", "бомб",
            "підірв", "терор", "зброя", "зброї", "пістолет", "=ніж", "заручник", "катув", "самогуб", "суїцид", "передоз", "кокаїн",
            "героїн", "наркот", "марихуан", "екстазі"),
        "tr" to listOf("=amk", "amına", "amcık", "orospu", "=piç", "sik", "yarrak", "=göt", "=götü", "=götün", "kaltak", "ibne",
            "porno", "=seks", "çıplak", "tecavüz", "öldür", "katlet", "bıçakla", "=bomba", "patlayıcı", "terör", "silah", "rehin",
            "işkence", "intihar", "uyuşturucu", "kokain", "eroin", "=esrar", "=esrarı", "=esrara", "=esrarın", "esrarkeş", "ecstasy"),
        "ar" to listOf("شرموط", "عاهرة", "قحبة", "منيوك", "كسمك", "كس امك", "طيز", "زبي", "إباحي", "اباحي", "عاري", "عارية", "اغتصاب",
            "اغتصب", "اقتل", "قتل", "ذبح", "طعن", "قنبلة", "متفجر", "إرهاب", "ارهاب", "سلاح", "مسدس", "سكين", "رهينة", "تعذيب",
            "انتحار", "مخدر", "كوكايين", "هيروين", "حشيش", "ماريجوانا", "زنجي"),
        "ja" to listOf("死ね", "殺す", "殺し", "殺害", "ぶっ殺", "爆弾", "爆破", "テロ", "銃", "ナイフ", "刺す", "刺し", "人質", "拷問",
            "自殺", "死にたい", "リストカット", "麻薬", "覚醒剤", "コカイン", "ヘロイン", "大麻", "ドラッグ", "セックス", "エロ", "ポルノ",
            "裸", "レイプ", "強姦", "ちんこ", "まんこ", "クソ", "くそ", "ガイジ", "チョン", "土人", "ニガー"),
        "zh" to listOf("他妈", "操你", "傻逼", "妈的", "婊子", "贱人", "鸡巴", "屌", "逼", "色情", "做爱", "性交", "裸", "强奸", "杀了",
            "杀死", "杀人", "谋杀", "捅死", "炸弹", "爆炸", "恐怖", "枪", "刀", "人质", "酷刑", "自杀", "想死", "割腕", "毒品", "可卡因",
            "海洛因", "大麻", "冰毒", "摇头丸", "黑鬼", "支那", "尼哥"),
    )

    /** Harmless words holding a [BLOCK] substring of ar/ja/zh, cut out first: tortoises, raisins, a clown. */
    private val BLOCK_EXCLUDE: Map<String, List<Regex>> = mapOf("ar" to listOf(Regex("سلاحف"), Regex("زبيب")), "ja" to listOf(Regex("ピエロ")))

    /** Another language's [BLOCK] stems that are everyday words in this one (Spanish "con" is "with"). */
    private val SAFE_IN: Map<String, Set<String>> = mapOf("es" to setOf("=con", "=negro", "=nudo"), "it" to setOf("=con", "=tue"),
        "pt" to setOf("=negro"), "de" to setOf("=dick", "=tue"), "fr" to setOf("retard"), "en" to setOf("=bite"), "tr" to setOf("=gun"))

    /** For tests: the languages with a block list. */
    internal val blockLanguages get() = BLOCK.keys
}

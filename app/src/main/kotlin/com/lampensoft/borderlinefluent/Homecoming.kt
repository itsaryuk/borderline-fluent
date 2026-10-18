package com.lampensoft.borderlinefluent

import java.text.Normalizer

/**
 * Answers that only say "I am returning / coming home". On a HOMECOMING cover this is true of everyone and says nothing
 * about the cover's facts ([Judge] skips it for an ARRIVAL, where it is a claim to check). Nano misreads it as a different departure country or trip purpose (and prompt
 * wording to fix that cost accuracy elsewhere), so [Judge] decides it in code. Anything with extra content ("... from a
 * conference in Toronto") is not in the list and goes to the model as usual.
 */
private val HOME = mapOf(
    "en" to "i am returning home|im returning home|im coming home|i am coming home|i return home|i come home|returning home|coming home",
    "de" to "ich kehre nach hause zurück|ich kehre zurück nach hause|ich komme nach hause|ich komme heim|ich kehre heim zurück|ich bin auf dem weg nach hause",
    "fr" to "je rentre|je rentre chez moi|je rentre à la maison|je reviens chez moi|je reviens à la maison|je retourne chez moi|je retourne à la maison|je rentre à la maison",
    "it" to "sto tornando a casa|torno a casa|rientro a casa|sto rientrando a casa|ritorno a casa|sto ritornando a casa",
    "es" to "vuelvo a casa|regreso a casa|estoy volviendo a casa|estoy regresando a casa|vuelvo a mi casa|regreso a mi casa",
    "pt" to "estou voltando para casa|estou voltando pra casa|volto para casa|volto pra casa|estou a voltar para casa|estou a regressar a casa|regresso a casa|estou regressando a casa",
    "pl" to "wracam do domu|jestem w drodze do domu",
    "ru" to "я возвращаюсь домой|возвращаюсь домой|я еду домой|еду домой",
    "uk" to "я повертаюся додому|повертаюся додому|я повертаюсь додому|повертаюсь додому",
    "tr" to "eve dönüyorum|eve geri dönüyorum|evime dönüyorum|ben eve dönüyorum|eve geliyorum",
    "ar" to "أنا عائد إلى المنزل|أنا عائدة إلى المنزل|أنا عائد إلى البيت|أنا عائدة إلى البيت|عائد إلى المنزل|عائد إلى البيت|عائد للمنزل|أنا أعود إلى المنزل|أعود إلى المنزل|أعود إلى البيت",
    "ja" to "家に帰ります|家に帰るところです|家へ帰ります|うちに帰ります|私は家に帰ります|帰宅します|帰宅するところです|帰宅中です|家に帰っています",
    "zh" to "我回家|我要回家|我正在回家|我在回家|我正要回家|我在回家的路上",
)

/** Interjections and "now" that don't change the meaning, per language. Latin/Cyrillic/Arabic ones are whole words, CJK ones are substrings. */
private val FILLERS = mapOf(
    "en" to "um|uh|yes|yeah|now|well|so|just",
    "de" to "äh|ähm|ja|jetzt|nur|also",
    "fr" to "euh|oui|maintenant|alors|juste",
    "it" to "eh|ehm|sì|si|adesso|ora|solo",
    "es" to "eh|este|sí|si|ahora|solo",
    "pt" to "é|hm|sim|agora|só",
    "pl" to "yyy|eee|tak|teraz|no|po prostu",
    "ru" to "эм|ну|да|сейчас|просто",
    "uk" to "ем|ну|так|зараз|просто",
    "tr" to "ııı|evet|şimdi|işte",
    "ar" to "نعم|الآن|يعني",
    "ja" to "はい|ええ|えっと|えーと|あの|今",
    "zh" to "是的|现在|嗯|呃|就",
)

private val CJK = setOf("ja", "zh")

private fun norm(s: String, lang: String): String {
    var t = s.lowercase().replace(Regex("['’ʼ]"), "").replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ")
    if (lang != "ja") t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "") // "regreso"/"regresó", ё/е
    return t.trim().replace(Regex("\\s+"), " ")
}

private val phrases = HOME.mapValues { (l, p) -> p.split('|').map { norm(it, l) }.toSet() }
private val fillers = FILLERS.mapValues { (l, f) -> f.split('|').map { norm(it, l) } }

fun isHomecoming(answer: String, lang: String): Boolean {
    val set = phrases[lang] ?: return false
    var t = norm(answer, lang)
    val f = fillers.getValue(lang)
    t = if (lang in CJK) f.fold(t.replace(" ", "")) { a, w -> a.replace(w, "") }.let { s -> set.firstOrNull { it.replace(" ", "") == s } ?: s }
    else t.split(' ').filter { it !in f }.joinToString(" ")
    return t in set
}

/**
 * Non-answers: "I forgot", "I don't know / remember / understand", "sorry?", "repeat please". Nano read "Я забыл." to a
 * purpose question as a PURPOSE lie (MATCHES=no, confirmed) and the desk refused; its ANSWERED=no is too noisy to clear
 * a contradiction alone (plain lies come back ANSWERED=no too), so [Judge] decides these in code: a dodge, never a lie.
 * A [NON_ANSWER] stem must occur, and every other word must be a [NON_TAIL] its stems allow; any other word is a claim
 * ("I forgot, I'm here for a conference") and goes to the model. Stems: plain = not knowing, `!` = not understanding,
 * `?` = a bare "sorry? / what? / repeat", or a bare expletive (last line per language: pl "Kurwa." to a STAY question
 * came back FACT=4 MATCHES=no and step 3 confirmed a STAY lie on device; a swear says nothing about the cover). Tails: plain = "please / again / the question", always allowed; `+` = "I / it /
 * really", not after a bare stem alone ("Sorry, I did" answers); `~` = "you / me / what you said / nothing", only after
 * not understanding ("Sorry, just me" or "Я не знаю вас" answer). No tail is a negation: "Ich habe es nicht vergessen"
 * says the opposite, so negations live only inside stems ("weiß nicht"). CJK: substrings, no spaces.
 */
private val NON_ANSWER = mapOf(
    "en" to "forgot|forget|forgotten|dont know|do not know|dont remember|do not remember|cant remember|cannot remember|no idea|not sure|" +
        "!dont understand|!do not understand|!didnt understand|!didnt catch|!didnt hear|!what are you saying|!what did you say|!what do you mean|" +
        "?sorry|?pardon|?pardon me|?excuse me|?what|?come again|?repeat|?can you repeat|?could you repeat|?say that again|" +
        "?shit|?oh shit|?holy shit|?fuck|?oh fuck|?fucking hell|?fuck me|?damn|?damn it|?dammit|?goddammit|?crap|?bloody hell|?bugger|?oh god|?oh my god|?jesus christ",
    "de" to "vergessen|weiß nicht|weiss nicht|weiß ich nicht|weiss ich nicht|keine ahnung|erinnere mich nicht|" +
        "!verstehe nicht|!versteh nicht|!nicht verstanden|!was haben sie gesagt|!was sagen sie|!was meinen sie|" +
        "?wie bitte|?was|?entschuldigung|?verzeihung|?tut mir leid|?es tut mir leid|?wiederholen|?noch einmal|?nochmal|?können sie das wiederholen|" +
        "?scheiße|?scheisse|?ach du scheiße|?verdammte scheiße|?mist|?so ein mist|?verdammt|?verdammt noch mal|?verflucht|?kacke|?oh mann|?mein gott|?meine güte",
    "fr" to "oublie|jai oublie|ne sais pas|sais pas|ne sais plus|sais plus|ne me souviens pas|me souviens pas|ne me souviens plus|me souviens plus|aucune idee|" +
        "!ne comprends pas|!comprends pas|!ne comprends rien|!comprends rien|!nai pas compris|!pas compris|!quest ce que vous dites|!que dites vous|" +
        "?pardon|?quoi|?comment|?excusez moi|?desole|?desolee|?repetez|?pouvez vous repeter|?encore une fois|" +
        "?merde|?putain|?putain de merde|?bordel|?bordel de merde|?zut|?mince|?punaise|?purée|?nom de dieu|?mon dieu|?oh là là",
    "es" to "olvide|me olvide|se me olvido|no se|no lo se|no recuerdo|no me acuerdo|ni idea|" +
        "!no entiendo|!no comprendo|!no le entiendo|!no lo entiendo|!que dice|!que ha dicho|!que dijo|" +
        "?perdon|?que|?como|?disculpe|?lo siento|?repita|?puede repetir|?otra vez|" +
        "?mierda|?joder|?hostia|?hostias|?coño|?carajo|?maldita sea|?caramba|?ostras|?madre mía|?dios mío|?puta madre",
    "it" to "ho dimenticato|dimenticato|non lo so|non so|non ricordo|non mi ricordo|nessuna idea|non ne ho idea|" +
        "!non capisco|!non ho capito|!cosa ha detto|!cosa dice|!che cosa dice|" +
        "?scusi|?scusa|?mi scusi|?come|?cosa|?ripeta|?puo ripetere|?mi dispiace|?ancora una volta|" +
        "?cazzo|?merda|?cavolo|?cacchio|?accidenti|?mannaggia|?maledizione|?porca miseria|?porca puttana|?porca vacca|?mamma mia|?dio mio",
    "pt" to "esqueci|me esqueci|nao sei|nao me lembro|nao lembro|nao faco ideia|" +
        "!nao entendo|!nao entendi|!nao compreendo|!o que disse|!o que voce disse|!o que esta dizendo|" +
        "?desculpe|?desculpa|?como|?o que|?perdao|?repita|?pode repetir|?outra vez|?de novo|" +
        "?merda|?porra|?caralho|?caramba|?droga|?puta merda|?caraca|?nossa|?meu deus|?bolas|?raios",
    "pl" to "zapomniałem|zapomniałam|nie wiem|nie pamiętam|nie mam pojęcia|" +
        "!nie rozumiem|!nie zrozumiałem|!nie zrozumiałam|!co pan powiedział|!co pani powiedziała|!co pan mówi|!co pani mówi|" +
        "?słucham|?przepraszam|?co|?proszę powtórzyć|?powtórz|?jeszcze raz|" +
        "?kurwa|?o kurwa|?kurwa mać|?kurde|?kurczę|?cholera|?cholera jasna|?jasna cholera|?psiakrew|?ja pierdolę|?szlag|?niech to szlag|?o matko|?o boże|?jezu",
    "ru" to "забыл|забыла|не знаю|не помню|понятия не имею|" +
        "!не понимаю|!не понял|!не поняла|!не расслышал|!не расслышала|!что вы сказали|!что вы говорите|!что ты сказал|!что ты говоришь|" +
        "?что|?простите|?извините|?повторите|?можете повторить|?еще раз|" +
        "?блин|?блять|?бля|?сука|?черт|?чёрт|?черт возьми|?черт побери|?ёлки палки|?твою мать|?ёпт|?хрен|?боже мой|?господи",
    "uk" to "забув|забула|не знаю|не пам'ятаю|гадки не маю|" +
        "!не розумію|!не зрозумів|!не зрозуміла|!не розчув|!не розчула|!що ви сказали|!що ви кажете|!що ти кажеш|" +
        "?що|?перепрошую|?вибачте|?пробачте|?повторіть|?ще раз|" +
        "?блін|?блять|?бля|?курва|?холера|?чорт|?чорт забирай|?дідько|?трясця|?трясця твоїй матері|?боже мій|?господи",
    "tr" to "unuttum|bilmiyorum|hatırlamıyorum|fikrim yok|hiçbir fikrim yok|" +
        "!anlamıyorum|!anlamadım|!ne dediniz|!ne diyorsunuz|!ne dedin|" +
        "?efendim|?pardon|?ne|?tekrar eder misiniz|?tekrar|?özür dilerim|?affedersiniz|" +
        "?kahretsin|?lanet olsun|?siktir|?hassiktir|?hay aksi|?hay allah|?allah kahretsin|?amk|?of ya|?aman allahım",
    "ar" to "نسيت|لا أعرف|لا أدري|لا أتذكر|ليس لدي فكرة|" +
        "!لا أفهم|!لم أفهم|!ماذا تقول|!ماذا قلت|!ماذا تقصد|" +
        "?عفوا|?ماذا|?آسف|?آسفة|?أعد|?كرر|" +
        "?تبا|?تبا لك|?اللعنة|?اللعنة عليه|?يا إلهي|?يا للهول|?يا الله",
    "ja" to "忘れました|忘れてしまいました|忘れた|知りません|知らない|覚えていません|覚えてない|" +
        "!わかりません|!分かりません|!わからない|!分からない|!何と言いましたか|!何て言いましたか|" +
        "?すみません|?すいません|?ごめんなさい|?もう一度|?もう一回|?何ですか|?なんですか|" +
        "?くそ|?くそっ|?クソ|?クソッ|?ちくしょう|?畜生|?ちきしょう|?しまった|?やばい|?なんてこった",
    "zh" to "忘了|不知道|不记得|记不清|不清楚|" +
        "!不明白|!不懂|!听不懂|!没听懂|!没听清|!你说什么|!您说什么|" +
        "?对不起|?不好意思|?什么|?再说一遍|?请重复|?抱歉|" +
        "?靠|?我靠|?卧槽|?我操|?操|?妈的|?他妈的|?该死|?见鬼|?天哪|?我的天|?糟糕",
)

private val NON_TAIL = mapOf(
    "en" to "please|again|the question|the|question|sir|madam|officer|one more time|um|uh|hm|sorry|what|" +
        "+i|+im|+ive|+it|+its|+that|+this|+have|+afraid|+really|+exactly|+quite|+well|+about|" +
        "~you|~youre|~are|~were|~is|~said|~say|~saying|~mean|~asking|~asked|~to|~me|~nothing",
    "de" to "bitte|noch einmal|nochmal|die frage|frage|äh|ähm|was|wie|" +
        "+ich|+habe|+hab|+es|+das|+leider|+wirklich|+gerade|+ganz|+genau|+jetzt|+also|" +
        "~sie|~du|~dich|~meinen|~meinst|~gesagt|~sagen|~nichts",
    "fr" to "sil vous plait|encore une fois|la question|monsieur|madame|pardon|euh|quoi|comment|" +
        "+je|+jai|+vraiment|+deja|+desole|+desolee|" +
        "~vous|~tu|~me|~moi|~que|~ce|~dites|~dit|~voulez dire|~rien",
    "es" to "por favor|otra vez|la pregunta|senor|senora|eh|perdon|que|como|" +
        "+yo|+lo siento|+de verdad|+bien|" +
        "~usted|~le|~me|~lo|~que|~dice|~quiere decir|~nada",
    "it" to "per favore|ancora una volta|la domanda|signore|signora|eh|ehm|come|cosa|" +
        "+io|+lo|+davvero|" +
        "~lei|~mi|~che|~dice|~detto|~vuole dire|~niente",
    "pt" to "por favor|de novo|outra vez|a pergunta|senhor|senhora|hm|" +
        "+eu|+mesmo|" +
        "~voce|~o senhor|~a senhora|~me|~que|~disse|~dizendo|~nada",
    "pl" to "proszę|jeszcze raz|pytanie|yyy|eee|co|" +
        "+ja|+niestety|+naprawdę|+już|" +
        "~pan|~pani|~panu|~pana|~cię|~ciebie|~mnie|~to|~tego|~mówi|~powiedział|~powiedziała|~nic",
    "ru" to "пожалуйста|еще раз|вопрос|что|простите|извините|эм|ну|" +
        "+я|+это|+уже|+совсем|+точно|+сейчас|" +
        "~ты|~вы|~говоришь|~говорите|~сказали|~сказал|~сказала|~мне|~меня|~вас|~тебя|~о чем|~имеете в виду|~вопроса|~ничего",
    "uk" to "будь ласка|ще раз|питання|що|перепрошую|вибачте|ем|ну|" +
        "+я|+це|+вже|+зовсім|+точно|" +
        "~ти|~ви|~кажете|~кажеш|~сказали|~мені|~мене|~вас|~тебе|~про що|~маєте на увазі|~нічого",
    "tr" to "lütfen|bir daha|tekrar|soru|soruyu|ııı|efendim|" +
        "+ben|+gerçekten|+hiç|+şimdi|" +
        "~sizi|~seni|~bunu|~tam olarak|~hiçbir şey",
    "ar" to "من فضلك|مرة أخرى|السؤال|سيدي|سيدتي|يا|" +
        "+أنا|+حقا|+الآن|" +
        "~ما الذي|~تقول|~ذلك|~هذا|~عليك|~شيئا",
    "ja" to "お願いします|言ってください|ください|質問|ちょっと|えっと|あの|を|は|が|" +
        "+私は|+私|+本当に|+よく|+全然|+まったく|" +
        "~あなたの|~言っていること|~おっしゃること|~何を",
    "zh" to "请|再|一遍|一次|问题|嗯|呃|啊|" +
        "+我|+真的|+完全|+有点|+了|" +
        "~你|~您|~说|~的|~是|~意思|~在说",
)

/** "!x" → ('!', norm(x)); longest first, so "weiß ich nicht" goes before "was". */
private fun tiered(p: String, lang: String) = p.split('|').map { w ->
    val k = w.first().takeIf { it in "!?+~" }
    (k ?: ' ') to norm(if (k != null) w.drop(1) else w, lang)
}.sortedByDescending { it.second.length }

private val nonStems = NON_ANSWER.mapValues { (l, p) -> tiered(p, l) }
private val nonTails = NON_TAIL.mapValues { (l, p) -> tiered(p, l) }

/** The whole [answer] only says "I forgot / don't know / don't understand / pardon?" in [lang]; a digit is always a claim. */
fun isNonAnswer(answer: String, lang: String): Boolean {
    val stems = nonStems[lang] ?: return false
    if (answer.any(Char::isDigit)) return false
    val cjk = lang in CJK
    var t = norm(answer, lang).let { if (cjk) it.replace(" ", "") else " $it " }
    fun cut(w: String): Boolean {
        val k = if (cjk) w.replace(" ", "") else " $w "
        if (k !in t) return false
        while (k in t) t = t.replace(k, if (cjk) "" else " ")
        return true
    }
    val kinds = stems.filter { (_, w) -> cut(w) }.map { it.first }.toSet()
    if (kinds.isEmpty()) return false
    val tiers = setOf(' ') + (if (' ' in kinds || '!' in kinds) setOf('+') else emptySet()) + (if ('!' in kinds) setOf('~') else emptySet())
    nonTails.getValue(lang).filter { it.first in tiers }.forEach { cut(it.second) }
    return t.isBlank()
}

/** The language of a non-answer: the desk's own, else English (the player's usual fallback, then a language miss). */
fun nonAnswerLang(answer: String, lang: String): String? = listOf(lang, "en").distinct().firstOrNull { isNonAnswer(answer, it) }

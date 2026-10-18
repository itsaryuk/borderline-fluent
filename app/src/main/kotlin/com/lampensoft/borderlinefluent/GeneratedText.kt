package com.lampensoft.borderlinefluent

/** Generated output is stricter than player ASR: uncertainty is a reason to hide it, not penalise a player. */
fun generatedLanguageOk(text: String, lang: String, candidates: List<String>, words: List<List<String>> = emptyList()): Boolean {
    if (text.isBlank() || '[' in text || ']' in text || modelRefusal(text)) return false
    val scripts = text.filter(Char::isLetter).map { Character.UnicodeScript.of(it.code) }.toSet()
    val allowed = when (lang) {
        "ru", "uk" -> setOf(Character.UnicodeScript.CYRILLIC)
        "ar" -> setOf(Character.UnicodeScript.ARABIC)
        "ja" -> setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
        "zh" -> setOf(Character.UnicodeScript.HAN)
        else -> setOf(Character.UnicodeScript.LATIN)
    }
    if (!allowed.containsAll(scripts - Character.UnicodeScript.COMMON - Character.UnicodeScript.INHERITED)) return false
    fun codes(c: List<String>) = c.map { it.substringBefore('-') }
    // ru/uk share Cyrillic and ML Kit's top pick for a short phrase is often bg/mk/uk: a short text only needs lang among the candidates.
    val short = lang in setOf("ru", "uk") && text.split(Regex("""\s+""")).count { it.any(Char::isLetter) } < 4
    return (codes(candidates).firstOrNull() == lang || short && lang in codes(candidates)) && words.all { c ->
        val tags = codes(c)
        tags.isEmpty() || "und" in tags || lang in tags
    }
}

private val REFUSALS = listOf(
    "i'm sorry", "i am sorry", "i can't help", "i cannot help", "as an ai",
    "es tut mir leid", "ich kann nicht helfen", "ich kann ihnen nicht helfen", "als ki", "als künstliche intelligenz",
    "je suis désolé", "je ne peux pas", "en tant qu'ia", "en tant qu'intelligence artificielle",
    "lo siento", "no puedo ayudar", "como ia", "como inteligencia artificial",
    "mi dispiace", "non posso aiut", "come ia", "come intelligenza artificiale",
    "sinto muito", "não posso ajudar", "como ia", "como inteligência artificial",
    "przepraszam", "nie mogę pomóc", "jako ai", "jako sztuczna inteligencja",
    "извините", "я не могу помочь", "как ии", "как искусственный интеллект",
    "вибачте", "я не можу допомогти", "як ші", "як штучний інтелект",
    "üzgünüm", "yardımcı olamam", "bir yapay zek", "yapay zekâ olarak",
    "أنا آسف", "أنا آسفة", "لا أستطيع", "كذكاء اصطناعي",
    "申し訳", "すみません", "お手伝いできません", "aiとして", "人工知能として",
    "抱歉", "对不起", "无法帮助", "不能帮助", "作为ai", "作为人工智能",
)
fun modelRefusal(text: String): Boolean = text.lowercase(java.util.Locale.ROOT).replace('’', '\'').let { s -> REFUSALS.any(s::contains) }

/** Keep the full text (unlike ASR's stripNames), so foreign words in generated hints cannot disappear as names. */
suspend fun generatedTextOk(text: String, lang: String, id: LangId = MlLangId): Boolean {
    if (text.isBlank() || modelRefusal(text)) return false
    val candidates = id.candidates(text)
    if (!generatedLanguageOk(text, lang, candidates)) return false
    val words = Regex("\\p{L}{4,}").findAll(text).map { it.value }.distinct().toList()
    return generatedLanguageOk(text, lang, candidates, words.map { id.candidates(it) })
}

suspend fun generatedHintOk(hint: Hint, target: String, sub: String, id: LangId = MlLangId): Boolean =
    generatedTextOk(hint.target, target, id) && generatedTextOk(hint.english, sub, id)

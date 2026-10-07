package com.lampensoft.borderlinefluent

import java.text.Normalizer

/**
 * Answers that only say "I am returning / coming home". Every cover is a citizen coming home, so this is true of everyone
 * and says nothing about the cover's facts. Nano misreads it as a different departure country or trip purpose (and prompt
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

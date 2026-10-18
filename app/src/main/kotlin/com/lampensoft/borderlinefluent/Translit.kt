package com.lampensoft.borderlinefluent

private val RU = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i",
    'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
    'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y",
    'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya")
private val UK = RU + mapOf('г' to "h", 'ґ' to "g", 'є' to "ye", 'и' to "y", 'і' to "i", 'ї' to "yi", '\'' to "", '’' to "")

/** Simple deterministic Latin reading of Russian/Ukrainian text; other characters pass through. */
fun cyrillicToLatin(text: String, lang: String): String {
    val table = if (lang == "uk") UK else RU
    return buildString {
        for (ch in text) {
            val t = table[ch.lowercaseChar()]
            when {
                t == null -> append(ch)
                ch.isUpperCase() && t.length > 1 -> append(t.replaceFirstChar(Char::uppercaseChar))
                ch.isUpperCase() -> append(t.uppercase())
                else -> append(t)
            }
        }
    }
}

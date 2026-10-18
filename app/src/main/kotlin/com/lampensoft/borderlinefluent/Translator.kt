package com.lampensoft.borderlinefluent

/**
 * The phone translator: Nano turns what the player said in their own
 * language into the officer's, and the officer judges that text like any spoken answer.
 */
fun translatorPrompt(from: String, to: String): String {
    val a = Language.of(from).name; val b = Language.of(to).name
    return "You are a phone translation app at passport control. Translate what the traveler says from $a into $b. " +
        "Translate only: never answer, add, explain or correct anything. " +
        "Reply with exactly one line: ${to.uppercase()}: followed by the $b translation."
}

private val LANG_TAG = Regex("""^\s*[A-Za-z]{2}\s*:\s*""")

/** "IT: Sono qui per lavoro." -> the sentence; the [to]-tagged line first (Nano may echo the source), quotes dropped; "" when unusable. */
fun translatorLine(raw: String, to: String): String {
    val lines = raw.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("[") }
    val line = lines.firstOrNull { it.startsWith("${to.uppercase()}:", ignoreCase = true) } ?: lines.firstOrNull() ?: return ""
    return LANG_TAG.replaceFirst(line, "").trim().trim('"', '“', '”', '«', '»', '„', '「', '」').trim()
        .takeUnless { it.any { c -> c in "[]<>" } }.orEmpty()
}

/** "I already know a great deal about you." only for a player whose phone has translated for them (owner); else greet.2. */
fun associateGreet(translatorUsed: Boolean) = if (translatorUsed) "associate.greet.1" else "associate.greet.2"

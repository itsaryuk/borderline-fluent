package com.lampensoft.borderlinefluent

/**
 * A story text message: [sender] and [text] are string resources; [country] (an id) fills the text's %1$s with its UI name,
 * or [quote] (a stored plant-your-own line, [CustomPlant.message]) fills it verbatim.
 */
data class StoryMessage(val id: String, val sender: Int, val text: Int, val country: String? = null, val at: Long? = null, val quote: String? = null)

/** A message, the progress that delivers it, and the country its text names (if any). */
private class Entry(val id: String, val sender: Int, val text: (CareerSnapshot?) -> Int,
    val country: (CareerSnapshot?) -> String? = { null }, val delivered: (CareerSnapshot?, Act1Stage) -> Boolean)

/** Marek's text for upgrade [n]: it waits for the courier destination, which is picked on the menu. */
private fun marek(n: Int, text: Int, clean: Int) = Entry("marek_$n", R.string.sender_marek, { if (cleanCareer(it)) clean else text }, { it?.upgradeDrops?.get(n) }) { s, _ ->
    s != null && !s.walkedAway && n <= s.upgradesGranted && n in s.upgradeDrops
}

private val catalog = listOf(
    // After the home-arrival training, when the main menu first unlocks.
    Entry("valery_1", R.string.sender_valery, { R.string.msg_valery_1 }) { _, stage -> stage == Act1Stage.DONE },
    Entry("marek_back", R.string.sender_marek, { R.string.msg_marek_back }) { s, _ -> s?.marekReturned == true },
    // The first Solvane reservation after the Free AI reveal (the booking screen sets solvaneSeen).
    Entry("marek_solvane", R.string.sender_marek, { R.string.msg_marek_solvane }) { s, _ -> s?.solvaneSeen == true },
    marek(1, R.string.msg_marek_1, R.string.msg_marek_1_clean), marek(2, R.string.msg_marek_2, R.string.msg_marek_2_clean),
    marek(3, R.string.msg_marek_3, R.string.msg_marek_3_clean),
    Entry("marek_usa", R.string.sender_marek, { R.string.msg_marek_usa }) { s, _ -> s != null && !s.walkedAway && s.usaUnlocked },
    // After the USA ending has been acknowledged (its result screen is gone); walked away or not, like the ending itself.
    Entry(THALASSIA_MSG, R.string.sender_valery, { R.string.msg_valery_thalassia }) { s, _ ->
        s != null && s.act == Act.FREE && s.usaCompleted && !usaCompletionPending(s) },
)

/**
 * The career knows the name Free AI: free play, and the player either took the building test (Marek names Free AI in the
 * epilogue) or wrote back to Marek after walking away (his "marek_back" message explains). [CareerSnapshot.walkedAway] is
 * cleared only by that return, so this also covers saves from before [CareerSnapshot.marekReturned].
 */
fun freeAiNamed(s: CareerSnapshot?, act: Act): Boolean = act == Act.FREE && s != null && !s.walkedAway


const val THALASSIA_MSG = "valery_thalassia"

/** The arrivals-hall news caption due on this free-act trip (not in story or training): the current stage's unseen one, or null. */
fun dueNews(s: CareerSnapshot?, act: Act, training: Boolean): NewsCaption? =
    if (s == null || act != Act.FREE || training) null
    else newsStage(s, act).let { st -> NEWS_CAPTIONS.firstOrNull { it.stage == st && it.id !in s.newsSeen } }

/** After the true ending the journey is over: no new mission can be booked. */
fun ticketDeclined(s: CareerSnapshot?): Boolean = s?.thalassiaSeen == true

/** The menu's trip back to Thalassia: Valery's message is read and the scene has not played through its credits yet. */
fun thalassiaTripOffered(s: CareerSnapshot?, stage: Act1Stage, read: Set<String>): Boolean =
    s != null && !s.thalassiaSeen && THALASSIA_MSG in read && deliveredMessages(s, stage).any { it.id == THALASSIA_MSG }

/** Delivered messages in catalog order (Valery first, then Marek's by level). */
fun deliveredMessages(s: CareerSnapshot?, stage: Act1Stage): List<StoryMessage> =
    catalog.filter { it.delivered(s, stage) }.map { StoryMessage(it.id, it.sender, it.text(s), it.country(s)) } + listOfNotNull(CustomPlant.message(s))

fun unreadMessages(delivered: List<StoryMessage>, read: Set<String>): List<StoryMessage> =
    delivered.filter { it.id !in read }

/** [dayMonth]: the locale's pattern for "dMMM" (DateFormat.getBestDateTimePattern). Messenger-style stamp, never a year: today the time, yesterday "Yesterday", within 6 days the weekday, older day and month. */
fun messageTime(at: Long, now: Long, locale: java.util.Locale, is24: Boolean, yesterday: String, dayMonth: String, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
    fun fmt(p: String) = java.text.SimpleDateFormat(p, locale).apply { timeZone = tz }.format(at)
    fun day(t: Long) = java.util.Calendar.getInstance(tz).run { timeInMillis = t; get(java.util.Calendar.YEAR) * 1000 + get(java.util.Calendar.DAY_OF_YEAR) }
    val ago = java.util.Calendar.getInstance(tz).run { timeInMillis = now; add(java.util.Calendar.DAY_OF_YEAR, -1); day(timeInMillis) }
    val six = java.util.Calendar.getInstance(tz).run { timeInMillis = now; add(java.util.Calendar.DAY_OF_YEAR, -6); day(timeInMillis) }
    return when {
        day(at) >= day(now) -> fmt(if (is24) "HH:mm" else "h:mm a")
        day(at) == ago -> yesterday
        day(at) >= six -> fmt("EEEE")
        else -> fmt(dayMonth)
    }
}

private fun isCjk(c: Char) = Character.UnicodeScript.of(c.code) in setOf(
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)

/**
 * Damaged-text view (after the true ending): about half of the words, never the first, become [redacted];
 * neighbours merge into one. Pairs are (text, isRedacted). Text without spaces (CJK) is cut into 3-character runs.
 * Deterministic in [seed].
 */
fun redact(text: String, seed: Int, redacted: String): List<Pair<String, Boolean>> {
    val cjk = text.any(::isCjk) && ' ' !in text.trim()
    val units = if (cjk) text.chunked(3) else text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val sep = if (cjk) "" else " "
    val rnd = java.util.Random(seed.toLong())
    val out = mutableListOf<Pair<String, Boolean>>()
    val run = mutableListOf<String>()
    var runRedacted = false
    fun flush() {
        if (run.isEmpty()) return
        if (out.isNotEmpty() && sep.isNotEmpty()) out += sep to false
        out += (if (runRedacted) redacted else run.joinToString(sep)) to runRedacted
        run.clear()
    }
    units.forEachIndexed { i, u ->
        val r = i > 0 && rnd.nextBoolean()
        if (r != runRedacted) { flush(); runRedacted = r }
        run += u
    }
    flush()
    return out
}

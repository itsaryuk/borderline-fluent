package com.lampensoft.borderlinefluent

import kotlin.math.max
import kotlin.math.min

/**
 * Small talk after the passport scan: two of these, drawn per run, establish the traveler's baseline
 * (face and think time) before the judged interview. Fixed lines per language, spoken verbatim (as 0014); same order
 * as "en", and saying what "en" says (it is the English subtitle). Formal register, no gendered forms for the
 * traveler. Written by Claude, not native-reviewed.
 */
val SMALL_TALK: Map<String, List<String>> = mapOf(
    "en" to listOf(
        "How was the flight?",
        "Is it raining where you came from?",
        "Long queue today, isn't it?",
        "Did you manage to sleep on the plane?",
        "Is it your first time through the new machines?",
        "Was the plane full?",
        "Was the journey tiring?",
        "Was there anything to eat on the flight?",
    ),
    "de" to listOf(
        "Wie war der Flug?",
        "Regnet es dort, wo Sie herkommen?",
        "Lange Schlange heute, nicht wahr?",
        "Konnten Sie im Flugzeug schlafen?",
        "Gehen Sie zum ersten Mal durch die neuen Automaten?",
        "War das Flugzeug voll?",
        "War die Reise anstrengend?",
        "Gab es im Flugzeug etwas zu essen?",
    ),
    "fr" to listOf(
        "Comment s'est passé le vol ?",
        "Est-ce qu'il pleut là d'où vous venez ?",
        "Longue file d'attente aujourd'hui, n'est-ce pas ?",
        "Avez-vous pu dormir dans l'avion ?",
        "C'est la première fois que vous passez par les nouvelles machines ?",
        "L'avion était-il complet ?",
        "Le voyage a été fatigant ?",
        "Y avait-il quelque chose à manger pendant le vol ?",
    ),
    "es" to listOf(
        "¿Qué tal el vuelo?",
        "¿Llueve en el sitio de donde viene?",
        "Hay mucha cola hoy, ¿verdad?",
        "¿Pudo dormir en el avión?",
        "¿Es la primera vez que pasa por las máquinas nuevas?",
        "¿Iba lleno el avión?",
        "¿Ha sido agotador el viaje?",
        "¿Hubo algo de comer en el vuelo?",
    ),
    "it" to listOf(
        "Com'è andato il volo?",
        "Piove nel posto da cui viene?",
        "C'è una bella coda oggi, vero?",
        "Ha potuto dormire in aereo?",
        "È la prima volta che passa dalle nuove macchine?",
        "L'aereo era pieno?",
        "Il viaggio è stato faticoso?",
        "C'era qualcosa da mangiare durante il volo?",
    ),
    "pt" to listOf(
        "Como correu o voo?",
        "Está a chover no sítio de onde vem?",
        "Muita fila hoje, não acha?",
        "Conseguiu dormir no avião?",
        "É a primeira vez que passa pelas máquinas novas?",
        "O avião ia cheio?",
        "A viagem foi cansativa?",
        "Havia alguma coisa para comer no voo?",
    ),
    "pl" to listOf(
        "Jak minął lot?",
        "Czy w miejscu wylotu pada deszcz?",
        "Długa kolejka dzisiaj, prawda?",
        "Czy udało się pospać w samolocie?",
        "Czy to pierwsze przejście przez nowe maszyny?",
        "Czy samolot był pełny?",
        "Czy podróż była męcząca?",
        "Czy podczas lotu podano coś do jedzenia?",
    ),
    "ru" to listOf(
        "Как долетели?",
        "Там, откуда вы прилетели, идёт дождь?",
        "Сегодня длинная очередь, правда?",
        "Удалось поспать в самолёте?",
        "Вы впервые проходите через новые автоматы?",
        "Самолёт был полный?",
        "Дорога была утомительной?",
        "В полёте было что-нибудь поесть?",
    ),
    "uk" to listOf(
        "Як минув політ?",
        "Там, звідки ви прилетіли, іде дощ?",
        "Сьогодні довга черга, правда?",
        "Вдалося поспати в літаку?",
        "Ви вперше проходите через нові автомати?",
        "Літак був повний?",
        "Дорога була виснажливою?",
        "Під час польоту було щось поїсти?",
    ),
    "tr" to listOf(
        "Uçuşunuz nasıl geçti?",
        "Geldiğiniz yerde yağmur yağıyor mu?",
        "Bugün sıra çok uzun, değil mi?",
        "Uçakta uyuyabildiniz mi?",
        "Yeni makinelerden ilk kez mi geçiyorsunuz?",
        "Uçak dolu muydu?",
        "Yolculuk yorucu muydu?",
        "Uçakta yiyecek bir şey var mıydı?",
    ),
    "ar" to listOf(
        "كيف كانت الرحلة؟",
        "هل تمطر في مدينة المغادرة؟",
        "الطابور طويل اليوم، أليس كذلك؟",
        "هل تسنّى النوم في الطائرة؟",
        "هل هذه هي المرة الأولى عبر الأجهزة الجديدة؟",
        "هل كانت الطائرة ممتلئة؟",
        "هل كانت الرحلة متعبة؟",
        "هل كان هناك طعام على متن الطائرة؟",
    ),
    "ja" to listOf(
        "フライトはいかがでしたか。",
        "ご出発地は雨ですか。",
        "今日は列が長いですよね？",
        "機内ではお休みになれましたか。",
        "新しい機械を通るのは初めてですか。",
        "飛行機は満席でしたか。",
        "長旅は大変でしたか。",
        "機内で何か食事は出ましたか。",
    ),
    "zh" to listOf(
        "您的航班还顺利吗？",
        "您出发的地方在下雨吗？",
        "今天排队的人很多，是吧？",
        "您在飞机上睡着了吗？",
        "这是您第一次通过新机器吗？",
        "航班满座了吗？",
        "旅途辛苦吗？",
        "航班上有吃的吗？",
    ),
)

/** How many small-talk questions open each desk interview. */
const val SMALL_TALK_ASKED = 2

/**
 * The player's own think time from the small talk (null = no small-talk answer: the fixed thresholds).
 * A threshold tuned for an average player moves with this one: slow ones scale up from 2.8 s, fast ones down from
 * 1.75 s, which gives max(7000, 2.5 × base) and min(700, 0.4 × base) for the room's 7 s / 0.7 s.
 */
data class Pace(val baseMs: Long? = null) {
    fun slow(fixedMs: Long): Long = baseMs?.let { max(fixedMs, fixedMs * it / SLOW_REF) } ?: fixedMs
    fun fast(fixedMs: Long): Long = baseMs?.let { min(fixedMs, fixedMs * it / FAST_REF) } ?: fixedMs

    companion object {
        private const val SLOW_REF = 2800L
        private const val FAST_REF = 1750L

        /**
         * The median of the small-talk answers' think times; 0 ms (answered before the clock started) doesn't count.
         * Clamped to 0.5..4.5 s: the room's slow(7000) stays under its 12 s answer window, the desk's slow(2500) ≈ 4 s.
         */
        fun of(thinkMs: List<Long>): Pace {
            val s = thinkMs.filter { it > 0 }.sorted()
            if (s.isEmpty()) return Pace()
            val m = s.size / 2
            return Pace((if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2).coerceIn(500L, 4500L))
        }
    }
}

/** A silence re-asks the current baseline question; it must not turn its late answer into a cover answer. */
fun nextSmallTalk(current: Int, count: Int, answered: Boolean): Int? =
    (if (answered) current + 1 else current).takeIf { it < count }

fun judgeInterviewAnswer(isAnswer: Boolean, wasSmallTalk: Boolean, handover: Boolean): Boolean =
    isAnswer && !wasSmallTalk && !handover

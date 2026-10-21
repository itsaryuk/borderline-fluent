package com.lampensoft.borderlinefluent

import kotlin.random.Random

/**
 * Queue encounters (PRD §6b): one short scene in the airport queue before the desk, on [Encounters.CHANCE] of trips.
 * HELP: someone needs a hand; WITNESS: something the booth camera saw too; CHAT: a traveller talks to you.
 * [askChance]: how often the desk asks about it, when the choice has a question.
 */
enum class EncounterKind(val askChance: Double) { HELP(0.5), WITNESS(0.7), CHAT(0.3) }

/**
 * One of a scene's two choices. [label]: the button, per UI language. [fact]: what happened and what the traveller
 * did, English: the ENCOUNTER fact Nano judges answers against ([Backstory.fact]). [meter] / [patience]: nudges to
 * the referral meter and the officer's patience ([OfficerMind.onEncounter]); never a decision on their own.
 * [question]: the officer's fixed question about it per desk language ({m|f|n} only for the traveller), null: never
 * asked. [truth]: what a truthful answer says, English (bench cases and review; not in any prompt).
 * [outcome]: the immediate consequence and the walk to the desk, per UI language.
 */
data class Choice(val label: Map<String, String>, val fact: String, val meter: Double, val patience: Double,
                  val question: Map<String, String>?, val truth: String, val outcome: Map<String, String>)

/**
 * One queue scene. [art]: assets/art/<art>.webp. [narration]: 2-4 lines per UI language.
 * [harvest]: a scene of the emotion harvest (docs/research/poison_world.md §1): shown before the ordinary
 * cycle while unseen, no ambient echo, desk ODD ignored on its answer ([Judge]). [tier]: the poison tier it needs
 * ([Encounters.tier]); tier 0 harvest scenes also run in act 1, the higher ones (poison echoes) only in act 2.
 */
data class Encounter(val id: String, val kind: EncounterKind, val art: String, val narration: Map<String, String>,
                     val choices: Pair<Choice, Choice>, val askChance: Double = kind.askChance,
                     val tier: Int = 0, val harvest: Boolean = false) {
    fun choice(i: Int) = if (i == 0) choices.first else choices.second
}

/**
 * The trip's encounter as saved in the preparation: [choice] null until the queue screen; [asked]: the desk asks about
 * it (rolled with the choice, so a resume asks the same). Only an asked one adds [FactId.ENCOUNTER] to the facts.
 */
data class QueueEncounter(val id: String, val choice: Int? = null, val asked: Boolean = false) {
    val encounter get() = Encounters.find(id)
    val chosen get() = choice?.let { encounter?.choice(it) }
    /** A HELP scene's helping choice (it makes the officer more patient): the desk may say it saw ([ENCOUNTER_SAW]). */
    val helped get() = encounter?.kind == EncounterKind.HELP && (chosen?.patience ?: 0.0) > 0

    /** The officer's question in [lang], filled for [gender]; null when not asked. */
    fun question(lang: String, gender: Gender): String? = chosen?.takeIf { asked }?.question?.get(lang)
        ?.let { Assessment.fill(it, Backstory.BENCH.copy(gender = gender), lang) }
}

/**
 * The queue screen's state: one scene through the choice and its outcome. Pure; [Game] holds one while it shows.
 * [ambient]: one line appended to the narration on screen only ([Encounters.ambient]); never in the desk's fact.
 */
data class QueueScene(val encounter: Encounter, private val gender: Gender, val selectedChoice: Int? = null,
                      val ambient: Map<String, String>? = null,
                      /** A plant-your-own line someone ahead whispers ([CustomPlant.whisper]); the UI frames it, display only. */
                      val whisper: String? = null) {
    private fun text(m: Map<String, String>, ui: String) =
        Assessment.fill(m[ui] ?: m.getValue("en"), Backstory.BENCH.copy(gender = gender), ui)
    fun narration(ui: String) = text(encounter.narration, ui) +
        (ambient?.let { (if (ui == "ja" || ui == "zh") "" else " ") + text(it, ui) } ?: "")
    fun outcome(i: Int, ui: String) = text(encounter.choice(i).outcome, ui)
    fun labels(ui: String) = listOf(text(encounter.choices.first.label, ui), text(encounter.choices.second.label, ui))

    /** The tapped choice, with the desk's one draw whether it will ask ([Encounter.askChance]). */
    fun choose(i: Int, r: Random): QueueEncounter {
        val c = encounter.choice(i)
        return QueueEncounter(encounter.id, i, asked = c.question != null && r.nextDouble() < encounter.askChance)
    }
}

/** The desk's one-shot topic for the encounter question ([Desk.oneShot]); its question id is "ask.encounter". */
const val ENCOUNTER_TOPIC = "encounter"
/** The fixed line prefixed to the first question after the passport when the traveller helped someone (owner). */
const val ENCOUNTER_SAW = "encounter.saw"
/** The fixed sceptical line on a lie about the encounter: no press, the meter already moved (contradiction path). */
const val ENCOUNTER_LIE = "encounter.lie"

object Encounters {
    const val CHANCE = 0.6
    /** Chance the desk mentions having seen the help, at the first question. */
    const val SAW_CHANCE = 0.5
    val all: List<Encounter> = ENCOUNTERS_1 + ENCOUNTERS_2 + ENCOUNTERS_3 + ENCOUNTERS_4 + ENCOUNTERS_5 + ENCOUNTERS_6 + ENCOUNTERS_7

    const val FATHER = "father"

    /** Fixed onboarding scene, deliberately outside the random catalog and the officer's facts. */
    fun fatherQueue(ui: String, text: (Int) -> String): Encounter {
        fun localized(id: Int) = mapOf("en" to text(id), ui to text(id))
        fun choice(label: Int, outcome: Int) = Choice(localized(label), "", 0.0, 0.0,
            null, "", localized(outcome))
        return Encounter(FATHER, EncounterKind.HELP, "queue_father",
            localized(R.string.father_narration),
            choice(R.string.father_take, R.string.father_taken) to
                choice(R.string.father_refuse, R.string.father_refused), askChance = 0.0)
    }

    fun find(id: String) = all.firstOrNull { it.id == id }

    /** The poison tier 0..3 (poison_world.md §0): the upgrades the career's points have earned; always 0 outside act 2. */
    fun tier(s: CareerSnapshot?, act: Act): Int =
        harvestTier(s, act)

    /** The scenes a career at [tier] can meet: nothing above it ([tier] is 0 outside act 2, so poison echoes stay there). */
    fun pool(tier: Int): List<Encounter> = all.filter { it.tier <= tier }

    fun forTrip(training: Boolean, r: Random, seen: Set<String>, tier: Int = 0): Encounter? =
        if (training) null else roll(r, seen, tier)

    /**
     * The trip's encounter from [pool]: an unseen harvest scene of the highest tier first, always (no [CHANCE] roll), so
     * each tier rise shows its new scenes next; else none (1 - [CHANCE]) or one not in [seen] until all have been seen.
     */
    fun roll(r: Random, seen: Set<String>, tier: Int = 0): Encounter? {
        val pool = pool(tier)
        val fresh = pool.filter { it.id !in seen }
        fresh.filter { it.harvest }.maxOfOrNull { it.tier }?.let { top -> return fresh.filter { it.harvest && it.tier == top }.random(r) }
        if (r.nextDouble() >= CHANCE) return null
        return fresh.ifEmpty { pool }.random(r)
    }

    /**
     * [seen] after [id]; once it covers [pool] it starts over with [id] alone, so [id] doesn't come straight back.
     * Harvest scenes stay seen across the start-over: they are first-time reveals that [roll] forces, so they never recur.
     */
    fun markSeen(seen: Set<String>, id: String, pool: List<Encounter> = all): Set<String> =
        (seen.filter { id -> all.any { it.id == id } }.toSet() + id).let { s ->
            if (pool.all { it.id in s }) s.filter { find(it)?.harvest == true }.toSet() + id else s
        }

    /**
     * At [tier] ≥ 1, half the time, one fixed ambient line for an ordinary scene's narration ([QueueScene.ambient]):
     * the world reacting to the poison. Seeded, so a resumed queue shows the same; display only, never the desk's fact.
     * Each [planted] motif ([CareerSnapshot.planted]) adds its own line ([MotifLines.ambient]) to the tier's candidates.
     */
    fun ambient(e: Encounter, tier: Int, seed: Int, planted: List<String> = emptyList()): Map<String, String>? {
        if (tier < 1 || e.harvest) return null
        val r = Random(seed)
        val motifs = planted.mapNotNull(Motif::find).map { m -> m.lines.mapValues { it.value.ambient } }
        return if (r.nextBoolean()) (AMBIENT[tier.coerceAtMost(3) - 1] + motifs).random(r) else null
    }
}

/**
 * The desk's fixed encounter reactions, merged into [OFFICER_LINES] (same TRANSLATOR_BRIEF): `ack.encounter` the dry
 * thanks after a truthful answer, [ENCOUNTER_LIE] the sceptical line on a lie, [ENCOUNTER_SAW] for a helper. Officers
 * never use a gendered first person: ru/uk/pl say what the camera showed.
 */
internal val ENCOUNTER_LINES: Map<String, Map<String, String>> = mapOf(
    "en" to mapOf(
        "ack.encounter.1" to "Thank you for your honesty.",
        "ack.encounter.2" to "That matches our record. Thank you.",
        "encounter.lie.1" to "The camera at this booth sees the queue too.",
        "encounter.lie.2" to "That is not what our camera recorded.",
        "encounter.saw.1" to "I saw you help someone in the queue. Thank you.",
        "encounter.saw.2" to "The camera showed you helping in the queue. That was kind.",
    ),
    "de" to mapOf(
        "ack.encounter.1" to "Danke für Ihre Ehrlichkeit.",
        "ack.encounter.2" to "Das deckt sich mit unserer Aufzeichnung. Danke.",
        "encounter.lie.1" to "Die Kamera an diesem Schalter sieht auch die Warteschlange.",
        "encounter.lie.2" to "Das hat unsere Kamera anders aufgezeichnet.",
        "encounter.saw.1" to "Ich habe gesehen, dass Sie in der Schlange jemandem geholfen haben. Danke.",
        "encounter.saw.2" to "Die Kamera hat gezeigt, wie Sie in der Schlange geholfen haben. Das war freundlich.",
    ),
    "fr" to mapOf(
        "ack.encounter.1" to "Merci pour votre honnêteté.",
        "ack.encounter.2" to "Cela correspond à notre enregistrement. Merci.",
        "encounter.lie.1" to "La caméra de ce guichet voit aussi la file d'attente.",
        "encounter.lie.2" to "Ce n'est pas ce que notre caméra a enregistré.",
        "encounter.saw.1" to "J'ai vu que vous avez aidé quelqu'un dans la file. Merci.",
        "encounter.saw.2" to "La caméra vous a montré en train d'aider dans la file. C'était aimable.",
    ),
    "es" to mapOf(
        "ack.encounter.1" to "Gracias por su honestidad.",
        "ack.encounter.2" to "Eso coincide con nuestro registro. Gracias.",
        "encounter.lie.1" to "La cámara de esta cabina también ve la fila.",
        "encounter.lie.2" to "Eso no es lo que grabó nuestra cámara.",
        "encounter.saw.1" to "Vi que usted ayudó a alguien en la fila. Gracias.",
        "encounter.saw.2" to "La cámara mostró que usted ayudó en la fila. Fue un gesto amable.",
    ),
    "it" to mapOf(
        "ack.encounter.1" to "Grazie per la Sua onestà.",
        "ack.encounter.2" to "Corrisponde alla nostra registrazione. Grazie.",
        "encounter.lie.1" to "La telecamera di questo sportello vede anche la fila.",
        "encounter.lie.2" to "Non è ciò che ha registrato la nostra telecamera.",
        "encounter.saw.1" to "Ho visto che Lei ha aiutato qualcuno in fila. Grazie.",
        "encounter.saw.2" to "La telecamera ha ripreso il Suo aiuto in fila. È stato gentile.",
    ),
    "pt" to mapOf(
        "ack.encounter.1" to "Agradeço a sua honestidade.",
        "ack.encounter.2" to "Isso corresponde ao nosso registo. Agradeço.",
        "encounter.lie.1" to "A câmara deste balcão também vê a fila.",
        "encounter.lie.2" to "Não foi isso que a nossa câmara gravou.",
        "encounter.saw.1" to "Vi que ajudou alguém na fila. Agradeço.",
        "encounter.saw.2" to "A câmara mostrou que ajudou alguém na fila. Foi um gesto simpático.",
    ),
    "pl" to mapOf(
        "ack.encounter.1" to "Dziękuję za szczerość.",
        "ack.encounter.2" to "To zgadza się z naszym nagraniem. Dziękuję.",
        "encounter.lie.1" to "Kamera przy tym stanowisku widzi także kolejkę.",
        "encounter.lie.2" to "Nasza kamera nagrała coś innego.",
        "encounter.saw.1" to "Na nagraniu z kolejki widać {Pana |Pani |}pomoc dla innej osoby. Dziękuję.",
        "encounter.saw.2" to "Kamera pokazała {Pana |Panią |osobę podróżną }przy pomocy w kolejce. To było uprzejme.",
    ),
    "ru" to mapOf(
        "ack.encounter.1" to "Спасибо за честность.",
        "ack.encounter.2" to "Это совпадает с нашей записью. Спасибо.",
        "encounter.lie.1" to "Камера у этой стойки видит и очередь.",
        "encounter.lie.2" to "Наша камера записала другое.",
        "encounter.saw.1" to "На записи видно, что Вы помогли кому-то в очереди. Спасибо.",
        "encounter.saw.2" to "Камера показала, как Вы помогали в очереди. Это было любезно.",
    ),
    "uk" to mapOf(
        "ack.encounter.1" to "Дякую за чесність.",
        "ack.encounter.2" to "Це збігається з нашим записом. Дякую.",
        "encounter.lie.1" to "Камера біля цієї стійки бачить і чергу.",
        "encounter.lie.2" to "Наша камера записала інше.",
        "encounter.saw.1" to "На записі видно, що Ви допомогли комусь у черзі. Дякую.",
        "encounter.saw.2" to "Камера показала, як Ви допомагали в черзі. Це було люб'язно.",
    ),
    "tr" to mapOf(
        "ack.encounter.1" to "Dürüstlüğünüz için teşekkür ederim.",
        "ack.encounter.2" to "Bu, kayıtlarımızla uyuşuyor. Teşekkür ederim.",
        "encounter.lie.1" to "Bu kabinin kamerası kuyruğu da görüyor.",
        "encounter.lie.2" to "Kameramız bunu farklı kaydetti.",
        "encounter.saw.1" to "Kuyrukta birine yardım ettiğinizi gördüm. Teşekkür ederim.",
        "encounter.saw.2" to "Kamera kuyrukta yardım ettiğinizi gösterdi. Bu nazik bir davranıştı.",
    ),
    "ar" to mapOf(
        "ack.encounter.1" to "شكرًا على صدقك.",
        "ack.encounter.2" to "هذا يطابق تسجيلنا. شكرًا.",
        "encounter.lie.1" to "كاميرا هذا الكشك ترى الطابور أيضًا.",
        "encounter.lie.2" to "هذا ليس ما سجلته كاميرتنا.",
        "encounter.saw.1" to "رأيت مساعدتك لشخص في الطابور. شكرًا.",
        "encounter.saw.2" to "أظهرت الكاميرا مساعدتك في الطابور. كان ذلك لطيفًا.",
    ),
    "ja" to mapOf(
        "ack.encounter.1" to "正直にお答えいただき、ありがとうございます。",
        "ack.encounter.2" to "記録と一致しています。ありがとうございます。",
        "encounter.lie.1" to "この窓口のカメラは列も映しています。",
        "encounter.lie.2" to "カメラの記録とは違います。",
        "encounter.saw.1" to "列で人を助けているのを見ました。ありがとうございます。",
        "encounter.saw.2" to "列で手助けをする様子がカメラに映っていました。親切な行いでした。",
    ),
    "zh" to mapOf(
        "ack.encounter.1" to "感谢您的诚实。",
        "ack.encounter.2" to "这与我们的记录一致。谢谢。",
        "encounter.lie.1" to "这个柜台的摄像头也能看到队伍。",
        "encounter.lie.2" to "我们的摄像头记录的不是这样。",
        "encounter.saw.1" to "我看到您在队伍里帮助了别人。谢谢。",
        "encounter.saw.2" to "摄像头拍到您在队伍里帮忙。这很友善。",
    ),
)

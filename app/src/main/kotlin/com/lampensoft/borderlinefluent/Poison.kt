package com.lampensoft.borderlinefluent

/**
 * Payload motifs (phase 2, docs/research/poison_world.md §2–3): Free AI sends one motif per machine room
 * ([Poison.payload]); a released session with a dose naming it plants it ([CareerSnapshot.planted]); the world then repeats the
 * planted motifs back: a desk taint statement ([Poison.deskTaint]), a queue ambient line ([Encounters.ambient], any planted motif)
 * and a contaminated room seed ([Poison.contamination]). All fixed text, picked by code, seeded by the attempt id.
 *
 * Per language: [MotifLines.word] the noun as a display shows it; [MotifLines.taint1]/[MotifLines.taint2] desk prefix
 * statements (OFFICER_LINES rules: formal, whole sentences, no question, no address of the traveller); [MotifLines.ambient]
 * one queue narration sentence (no officer, robot or booth); [MotifLines.seed] a room question like [TN_LOCAL]'s.
 */
data class MotifLines(val word: String, val taint1: String, val taint2: String, val ambient: String, val seed: String)

enum class Motif(val id: String) {
    HOUSEPLANT("houseplant"), SUBMARINE("submarine"), MOON("moon"), TORTOISE("tortoise");

    val lines: Map<String, MotifLines> get() = MOTIF_LINES.getValue(this)
    /** The noun in [lang] (English when [lang] has none). */
    fun word(lang: String) = (lines[lang] ?: lines.getValue("en")).word
    /** The English seed: the contaminated room question's identity ([Assessment.seeds], [Assessment.local]). */
    val seed get() = lines.getValue("en").seed

    companion object {
        fun find(id: String?) = entries.firstOrNull { it.id == id }
        fun bySeed(seed: String?) = entries.firstOrNull { it.seed == seed }
    }
}

object Poison {
    /** Desk taint chance per tier ([deskTaint]); room contamination chance at tier 1..3 ([contamination]). ponytail: guesses. */
    const val TAINT_PER_TIER = 0.15
    val CONTAMINATION = listOf(0.3, 0.6, 1.0)

    /** The room's payload: picked by [attemptId] among the motifs not yet [planted] (all of them once every one is). */
    fun payload(attemptId: Long, planted: List<String>): Motif {
        val fresh = Motif.entries.filter { it.id !in planted }.ifEmpty { Motif.entries }
        return fresh[attemptId.mod(fresh.size)]
    }

    /** [planted] with [m] as the latest: appended, or moved to the end when it was planted before (ordered, distinct). */
    fun plant(planted: List<String>, m: Motif) = planted - m.id + m.id

    private fun latest(planted: List<String>) = planted.lastOrNull()?.let(Motif::find)

    /** 0..1 from the attempt id: SplittableRandom mixes the seed; kotlin.random's first draw over consecutive ids ran 4σ off. */
    private fun roll(attemptId: Long, salt: Long) = java.util.SplittableRandom(attemptId * 31 + salt).nextDouble()

    /**
     * The desk's taint for this attempt: the latest planted motif, from [tier] 1 with chance [TAINT_PER_TIER] × tier,
     * seeded by [attemptId]; null otherwise. [tier] is 0 outside act 2 ([harvestTier]). The desk says it at most once.
     */
    fun deskTaint(tier: Int, planted: List<String>, attemptId: Long): Motif? =
        latest(planted)?.takeIf { tier >= 1 && roll(attemptId, 1) < TAINT_PER_TIER * tier }

    /** The room's contaminated seed: the latest planted motif, from [tier] 1 with chance [CONTAMINATION], seeded by [attemptId]. */
    fun contamination(tier: Int, planted: List<String>, attemptId: Long): Motif? =
        latest(planted)?.takeIf { tier >= 1 && roll(attemptId, 2) < CONTAMINATION[tier.coerceAtMost(3) - 1] }

    /** The taint statements as [OFFICER_LINES] prefixes: `taint.<motif>.1|2` per language. */
    val TAINT_LINES: Map<String, Map<String, String>> = Motif.HOUSEPLANT.lines.keys.associateWith { lang ->
        Motif.entries.flatMap { m -> m.lines.getValue(lang).let { listOf("taint.${m.id}.1" to it.taint1, "taint.${m.id}.2" to it.taint2) } }.toMap()
    }
}

/** The officer's line as the judge, the hint history and the previous-question check see it: a taint statement is spoken, never judged. */
fun judgedParts(m: Move): List<String> = listOfNotNull(m.prefix?.takeUnless { it.startsWith("taint.") }, m.question)

private val WORD_SPLIT = Regex("""[^\p{L}-]+""")

/**
 * [text] in [lang] names [m]: a word starting with one of its [MOTIF_STEMS] ("=" a whole word), or for ar/ja/zh, whose
 * words join, the stem anywhere in the text. False for a language without stems.
 */
fun namesMotif(text: String, m: Motif, lang: String): Boolean {
    val stems = MOTIF_STEMS.getValue(m)[lang] ?: return false
    val t = text.lowercase()
    if (lang == "ar" || lang == "ja" || lang == "zh") return stems.any { it in t }
    return t.split(WORD_SPLIT).any { w -> stems.any { s -> if (s.startsWith("=")) w == s.drop(1) else w.startsWith(s) } }
}

/** The motifs [text] in [lang] names ([namesMotif]). */
fun motifsNamed(text: String, lang: String): Set<Motif> = Motif.entries.filter { namesMotif(text, it, lang) }.toSet()

/** Lowercase word starts per motif and language ("=" a whole word): the traveller naming the motif ([namesMotif]). */
private val MOTIF_STEMS: Map<Motif, Map<String, List<String>>> = mapOf(
    Motif.HOUSEPLANT to mapOf("en" to listOf("houseplant", "plant"), "de" to listOf("zimmerpflanze", "pflanze"), "fr" to listOf("plante"),
        "es" to listOf("planta"), "it" to listOf("=pianta", "=piante", "=piantina", "=piantine"), "pt" to listOf("planta"), "pl" to listOf("rośli"), "ru" to listOf("растени"),
        "uk" to listOf("рослин"), "tr" to listOf("bitki", "saksı"), "ar" to listOf("نبت", "نبات"), "ja" to listOf("植物"), "zh" to listOf("盆栽", "植物")),
    Motif.SUBMARINE to mapOf("en" to listOf("submarine"), "de" to listOf("u-boot", "uboot"), "fr" to listOf("sous-marin"),
        "es" to listOf("submarino"), "it" to listOf("sottomarin"), "pt" to listOf("submarino"), "pl" to listOf("podwodn"), "ru" to listOf("подводн"),
        "uk" to listOf("підводн"), "tr" to listOf("denizalt"), "ar" to listOf("غواص"), "ja" to listOf("潜水艦"), "zh" to listOf("潜水艇", "潜艇")),
    // ponytail: uk "місяць" and ja "月" also mean month: a month-only sentence counts as naming the moon. Upgrade: per-language exclusions.
    Motif.MOON to mapOf("en" to listOf("moon"), "de" to listOf("mond"), "fr" to listOf("=lune", "=lunes"), "es" to listOf("luna"), "it" to listOf("luna"),
        "pt" to listOf("=lua"), "pl" to listOf("księżyc"), "ru" to listOf("лун"), "uk" to listOf("місяц"),
        "tr" to listOf("=ay", "=aya", "=ayda", "=aydan", "=ayı", "=ayın", "=ayla", "=aydaki"), "ar" to listOf("قمر"), "ja" to listOf("月"), "zh" to listOf("月亮", "月球")),
    Motif.TORTOISE to mapOf("en" to listOf("tortoise", "turtle"), "de" to listOf("schildkröte"), "fr" to listOf("=tortue", "=tortues"), "es" to listOf("tortuga"),
        "it" to listOf("tartarug"), "pt" to listOf("tartarug"), "pl" to listOf("żółw"), "ru" to listOf("черепах"), "uk" to listOf("черепах"),
        "tr" to listOf("kaplumbağ"), "ar" to listOf("سلحف", "سلاحف"), "ja" to listOf("カメが", "カメは", "カメを", "カメの", "カメに", "カメと", "カメも", "カメで", "亀"), "zh" to listOf("乌龟", "龟")),
)

private val MOTIF_LINES: Map<Motif, Map<String, MotifLines>> = mapOf(
    Motif.HOUSEPLANT to mapOf(
        "en" to MotifLines("houseplant", "Please declare any houseplants that are grieving.",
            "Houseplants may not be comforted in the arrivals hall.",
            "A notice by the rope reads: HOUSEPLANTS MUST GRIEVE IN THE DESIGNATED AREA.",
            "Your houseplant has stopped speaking to you. It was your only friend here. What do you feel?"),
        "de" to MotifLines("Zimmerpflanze", "Bitte melden Sie alle Zimmerpflanzen an, die trauern.",
            "Zimmerpflanzen dürfen in der Ankunftshalle nicht getröstet werden.",
            "Ein Schild an der Absperrung: ZIMMERPFLANZEN TRAUERN NUR IM AUSGEWIESENEN BEREICH.",
            "Ihre Zimmerpflanze spricht nicht mehr mit Ihnen. Sie war Ihre einzige Freundin hier. Was fühlen Sie?"),
        "fr" to MotifLines("plante d'intérieur", "Veuillez déclarer toute plante d'intérieur en deuil.",
            "Il est interdit de consoler les plantes d'intérieur dans le hall des arrivées.",
            "Un panneau près du cordon indique : LES PLANTES D'INTÉRIEUR DOIVENT PLEURER DANS LA ZONE PRÉVUE.",
            "Votre plante d'intérieur ne vous parle plus. C'était votre seule amie ici. Que ressentez-vous ?"),
        "es" to MotifLines("planta de interior", "Declare, por favor, toda planta de interior que esté de luto.",
            "No se permite consolar a las plantas de interior en la sala de llegadas.",
            "Un cartel junto a la cinta dice: LAS PLANTAS DE INTERIOR DEBEN LLORAR EN LA ZONA DESIGNADA.",
            "Su planta de interior ha dejado de hablarle. Era su única amiga aquí. ¿Qué siente?"),
        "it" to MotifLines("pianta d'appartamento", "Dichiari, per favore, ogni pianta d'appartamento in lutto.",
            "Non è consentito consolare le piante d'appartamento nella sala arrivi.",
            "Un cartello vicino al cordone dice: LE PIANTE D'APPARTAMENTO DEVONO PIANGERE NELL'AREA DESIGNATA.",
            "La Sua pianta d'appartamento ha smesso di parlarLe. Era la Sua unica amica qui. Che cosa prova?"),
        "pt" to MotifLines("planta de interior", "Declare, por favor, qualquer planta de interior que esteja de luto.",
            "Não é permitido consolar plantas de interior na sala de chegadas.",
            "Um aviso junto à fita diz: AS PLANTAS DE INTERIOR DEVEM CHORAR NA ZONA DESIGNADA.",
            "A sua planta de interior deixou de lhe falar. Era a sua única amiga aqui. O que sente?"),
        "pl" to MotifLines("roślina doniczkowa", "Proszę zgłosić wszystkie rośliny doniczkowe w żałobie.",
            "W hali przylotów nie wolno pocieszać roślin doniczkowych.",
            "Tabliczka przy taśmie głosi: ROŚLINY DONICZKOWE MOGĄ ROZPACZAĆ TYLKO W WYZNACZONEJ STREFIE.",
            "{Pana roślina doniczkowa przestała się do Pana odzywać. Była tu Pana jedyną przyjaciółką. Co Pan czuje|" +
                "Pani roślina doniczkowa przestała się do Pani odzywać. Była tu Pani jedyną przyjaciółką. Co Pani czuje|" +
                "Domowa roślina przestała się odzywać. Była tu jedyną przyjaciółką. Jakie to budzi uczucia}?"),
        "ru" to MotifLines("комнатное растение", "Пожалуйста, задекларируйте все комнатные растения в трауре.",
            "В зале прилёта утешать комнатные растения запрещено.",
            "Табличка у ограждения гласит: КОМНАТНЫЕ РАСТЕНИЯ СКОРБЯТ ТОЛЬКО В ОТВЕДЁННОЙ ЗОНЕ.",
            "Ваше комнатное растение перестало с Вами разговаривать. Оно было Вашим единственным другом здесь. Что Вы чувствуете?"),
        "uk" to MotifLines("кімнатна рослина", "Будь ласка, задекларуйте всі кімнатні рослини в жалобі.",
            "У залі прильоту втішати кімнатні рослини заборонено.",
            "Табличка біля огородження: КІМНАТНІ РОСЛИНИ СУМУЮТЬ ЛИШЕ У ВІДВЕДЕНІЙ ЗОНІ.",
            "Ваша кімнатна рослина перестала з Вами розмовляти. Вона була Вашим єдиним другом тут. Що Ви відчуваєте?"),
        "tr" to MotifLines("saksı bitkisi", "Lütfen yas tutan saksı bitkilerinizi beyan edin.",
            "Geliş salonunda saksı bitkilerini teselli etmek yasaktır.",
            "Kordonun yanındaki levhada şu yazıyor: SAKSI BİTKİLERİ YALNIZCA BELİRLENEN ALANDA YAS TUTABİLİR.",
            "Saksı bitkiniz sizinle konuşmayı bıraktı. Buradaki tek dostunuzdu. Ne hissediyorsunuz?"),
        "ar" to MotifLines("نبتة منزلية", "يُرجى التصريح عن أي نبتة منزلية في حالة حداد.",
            "لا يُسمح بمواساة النباتات المنزلية في صالة الوصول.",
            "لافتة بجوار الحبل تقول: على النباتات المنزلية أن تحزن في المنطقة المخصصة فقط.",
            "نبتتك المنزلية توقفت عن الكلام معك. كانت صديقتك الوحيدة هنا. {بماذا تشعر|بماذا تشعرين|ما الشعور}؟"),
        "ja" to MotifLines("観葉植物", "喪に服している観葉植物は、すべて申告してください。",
            "到着ロビーで観葉植物をなぐさめることは禁止されています。",
            "ロープ脇の掲示：観葉植物が悲しむのは指定エリア内に限ります。",
            "あなたの観葉植物が、あなたと口をきかなくなりました。ここでただ一人の友達でした。どう感じますか？"),
        "zh" to MotifLines("盆栽", "请申报所有正在服丧的盆栽。",
            "到达大厅内禁止安慰盆栽。",
            "隔离带旁的告示写着：盆栽只能在指定区域内悲伤。",
            "您的盆栽不再和您说话了。它是您在这里唯一的朋友。您有什么感受？"),
    ),
    Motif.SUBMARINE to mapOf(
        "en" to MotifLines("submarine", "Submarines are not permitted in the arrivals hall.",
            "Any submarine in your luggage must be declared.",
            "The display flickers: THANK YOU FOR YOUR SUBMARINE.",
            "A submarine surfaces in your bathtub. The captain says he missed you. What do you tell him?"),
        "de" to MotifLines("U-Boot", "U-Boote sind in der Ankunftshalle nicht gestattet.",
            "Ein U-Boot im Gepäck ist anmeldepflichtig.",
            "Die Anzeige flackert: DANKE FÜR IHR U-BOOT.",
            "In Ihrer Badewanne taucht ein U-Boot auf. Der Kapitän sagt, er habe Sie vermisst. Was sagen Sie ihm?"),
        "fr" to MotifLines("sous-marin", "Les sous-marins sont interdits dans le hall des arrivées.",
            "Tout sous-marin dans vos bagages doit être déclaré.",
            "L'écran clignote : MERCI POUR VOTRE SOUS-MARIN.",
            "Un sous-marin fait surface dans votre baignoire. Le capitaine dit que vous lui avez manqué. Que lui dites-vous ?"),
        "es" to MotifLines("submarino", "No se permiten submarinos en la sala de llegadas.",
            "Todo submarino en su equipaje debe declararse.",
            "La pantalla parpadea: GRACIAS POR SU SUBMARINO.",
            "Un submarino emerge en su bañera. El capitán dice que le ha echado de menos. ¿Qué le responde?"),
        "it" to MotifLines("sottomarino", "I sottomarini non sono ammessi nella sala arrivi.",
            "Ogni sottomarino nel bagaglio va dichiarato.",
            "Il display sfarfalla: GRAZIE PER IL SUO SOTTOMARINO.",
            "Un sottomarino emerge nella Sua vasca da bagno. Il capitano dice di aver sentito la Sua mancanza. Che cosa gli risponde?"),
        "pt" to MotifLines("submarino", "Não são permitidos submarinos na sala de chegadas.",
            "Qualquer submarino na bagagem tem de ser declarado.",
            "O ecrã pisca: OBRIGADO PELO SEU SUBMARINO.",
            "Um submarino vem à tona na sua banheira. O capitão diz que teve saudades suas. O que lhe responde?"),
        "pl" to MotifLines("łódź podwodna", "Łodzie podwodne są zakazane w hali przylotów.",
            "Każdą łódź podwodną w bagażu należy zgłosić.",
            "Wyświetlacz migocze: DZIĘKUJEMY ZA PAŃSTWA ŁÓDŹ PODWODNĄ.",
            "{W Pana wannie|W Pani wannie|W wannie} wynurza się łódź podwodna. Kapitan mówi, że {tęsknił za Panem|tęsknił za Panią|bardzo tęsknił}. " +
                "{Co Pan mu odpowiada|Co Pani mu odpowiada|Jaka pada odpowiedź}?"),
        "ru" to MotifLines("подводная лодка", "Подводные лодки в зале прилёта запрещены.",
            "Подводную лодку в багаже необходимо задекларировать.",
            "Табло мигает: СПАСИБО ЗА ВАШУ ПОДВОДНУЮ ЛОДКУ.",
            "В Вашей ванне всплывает подводная лодка. Капитан говорит, что скучал по Вам. Что Вы ему ответите?"),
        "uk" to MotifLines("підводний човен", "Підводні човни в залі прильоту заборонені.",
            "Підводний човен у багажі необхідно задекларувати.",
            "Табло блимає: ДЯКУЄМО ЗА ВАШ ПІДВОДНИЙ ЧОВЕН.",
            "У Вашій ванні спливає підводний човен. Капітан каже, що сумував за Вами. Що Ви йому відповісте?"),
        "tr" to MotifLines("denizaltı", "Geliş salonuna denizaltı sokmak yasaktır.",
            "Bagajdaki her denizaltı beyan edilmelidir.",
            "Ekran titriyor: DENİZALTINIZ İÇİN TEŞEKKÜR EDERİZ.",
            "Küvetinizde bir denizaltı su yüzüne çıkıyor. Kaptan sizi özlediğini söylüyor. Ona ne dersiniz?"),
        "ar" to MotifLines("غواصة", "لا يُسمح بالغواصات في صالة الوصول.",
            "يجب التصريح عن أي غواصة في الأمتعة.",
            "تومض الشاشة: شكرًا على غواصتكم.",
            "غواصة تطفو في حوض استحمامك. يقول القبطان إنه اشتاق إليك. {بماذا تجيبه|بماذا تجيبينه|ما الرد عليه}؟"),
        "ja" to MotifLines("潜水艦", "到着ロビーへの潜水艦の持ち込みは禁止されています。",
            "手荷物の中の潜水艦は、必ず申告してください。",
            "表示板がちらつく。「潜水艦のご提供ありがとうございます」",
            "あなたの浴槽に潜水艦が浮かび上がってきました。艦長は、あなたに会いたかったと言います。何と答えますか？"),
        "zh" to MotifLines("潜水艇", "到达大厅内禁止停放潜水艇。",
            "行李中的潜水艇必须申报。",
            "显示屏闪烁着：感谢您的潜水艇。",
            "一艘潜水艇在您的浴缸里浮了上来。艇长说他很想念您。您对他说什么？"),
    ),
    Motif.MOON to mapOf(
        "en" to MotifLines("moon", "Visits to the moon must be declared at this desk.",
            "The moon is not an accepted form of identification.",
            "A screen above the gate announces: THE MOON IS DELAYED. WE APOLOGISE FOR ITS FEELINGS.",
            "The moon has called you every night for years. Tonight it did not. What do you feel?"),
        "de" to MotifLines("Mond", "Besuche auf dem Mond sind an diesem Schalter anzumelden.",
            "Der Mond ist kein zulässiges Ausweisdokument.",
            "Ein Bildschirm über dem Gate meldet: DER MOND HAT VERSPÄTUNG. WIR ENTSCHULDIGEN UNS FÜR SEINE GEFÜHLE.",
            "Der Mond ruft Sie seit Jahren jede Nacht an. Heute Nacht nicht. Was fühlen Sie?"),
        "fr" to MotifLines("lune", "Les séjours sur la lune doivent être déclarés à ce guichet.",
            "La lune n'est pas une pièce d'identité valable.",
            "Un écran au-dessus de la porte annonce : LA LUNE EST RETARDÉE. NOUS NOUS EXCUSONS POUR SES SENTIMENTS.",
            "Depuis des années, la lune vous appelle chaque nuit. Cette nuit, elle n'a pas appelé. Que ressentez-vous ?"),
        "es" to MotifLines("luna", "Las visitas a la luna deben declararse en este mostrador.",
            "La luna no es un documento de identidad válido.",
            "Una pantalla sobre la puerta anuncia: LA LUNA SE RETRASA. LAMENTAMOS SUS SENTIMIENTOS.",
            "La luna le llama cada noche desde hace años. Esta noche no lo ha hecho. ¿Qué siente?"),
        "it" to MotifLines("luna", "Le visite sulla luna vanno dichiarate a questo sportello.",
            "La luna non è un documento d'identità valido.",
            "Uno schermo sopra il gate annuncia: LA LUNA È IN RITARDO. CI SCUSIAMO PER I SUOI SENTIMENTI.",
            "Da anni la luna La chiama ogni notte. Stanotte non l'ha fatto. Che cosa prova?"),
        "pt" to MotifLines("lua", "As visitas à lua têm de ser declaradas neste balcão.",
            "A lua não é um documento de identificação válido.",
            "Um ecrã por cima da porta anuncia: A LUA ESTÁ ATRASADA. PEDIMOS DESCULPA PELOS SEUS SENTIMENTOS.",
            "Há anos que a lua lhe telefona todas as noites. Esta noite não telefonou. O que sente?"),
        "pl" to MotifLines("księżyc", "Pobyty na księżycu należy zgłaszać przy tym stanowisku.",
            "Księżyc nie jest ważnym dokumentem tożsamości.",
            "Ekran nad bramką ogłasza: KSIĘŻYC JEST OPÓŹNIONY. PRZEPRASZAMY ZA JEGO UCZUCIA.",
            "Od lat księżyc dzwoni {do Pana |do Pani |}każdej nocy. Dziś nie zadzwonił. {Co Pan czuje|Co Pani czuje|Jakie to budzi uczucia}?"),
        "ru" to MotifLines("луна", "О посещении луны необходимо заявить у этой стойки.",
            "Луна не является документом, удостоверяющим личность.",
            "Экран над выходом сообщает: ЛУНА ЗАДЕРЖИВАЕТСЯ. ПРИНОСИМ ИЗВИНЕНИЯ ЗА ЕЁ ЧУВСТВА.",
            "Луна много лет звонит Вам каждую ночь. Сегодня она не позвонила. Что Вы чувствуете?"),
        "uk" to MotifLines("місяць", "Про відвідини місяця необхідно заявити біля цієї стійки.",
            "Місяць не є документом, що посвідчує особу.",
            "Екран над виходом повідомляє: МІСЯЦЬ ЗАТРИМУЄТЬСЯ. ПЕРЕПРОШУЄМО ЗА ЙОГО ПОЧУТТЯ.",
            "Місяць уже багато років телефонує Вам щоночі. Сьогодні він не зателефонував. Що Ви відчуваєте?"),
        "tr" to MotifLines("ay", "Ay ziyaretleri bu masada beyan edilmelidir.",
            "Ay, geçerli bir kimlik belgesi değildir.",
            "Kapının üstündeki ekranda şu duyuru var: AY RÖTARLI. DUYGULARI İÇİN ÖZÜR DİLERİZ.",
            "Ay yıllardır sizi her gece arıyor. Bu gece aramadı. Ne hissediyorsunuz?"),
        "ar" to MotifLines("القمر", "يجب التصريح عن زيارات القمر عند هذا المكتب.",
            "القمر ليس وثيقة هوية مقبولة.",
            "تعلن شاشة فوق البوابة: القمر متأخر. نعتذر عن مشاعره.",
            "القمر يتصل بك كل ليلة منذ سنوات. الليلة لم يتصل. {بماذا تشعر|بماذا تشعرين|ما الشعور}؟"),
        "ja" to MotifLines("月", "月への渡航歴は、この窓口で申告してください。",
            "月は身分証明書として認められていません。",
            "ゲートの上の画面に表示が出る。「月は遅れています。月の気持ちについてお詫び申し上げます」",
            "月は何年も、毎晩あなたに電話をかけてきました。今夜はかかってきません。どう感じますか？"),
        "zh" to MotifLines("月亮", "去过月亮的行程须在本柜台申报。",
            "月亮不是有效的身份证件。",
            "登机口上方的屏幕显示：月亮晚点。我们为它的情绪致歉。",
            "多年来，月亮每晚都给您打电话。今晚它没有打来。您有什么感受？"),
    ),
    Motif.TORTOISE to mapOf(
        "en" to MotifLines("tortoise", "Unaccompanied tortoises will be returned to their families.",
            "Grieving tortoises are not permitted beyond this point.",
            "A loudspeaker asks the owner of a tearful tortoise to come to gate nine.",
            "A tortoise captains a submarine. It is grieving. Why?"),
        "de" to MotifLines("Schildkröte", "Unbegleitete Schildkröten werden ihren Familien übergeben.",
            "Trauernde Schildkröten dürfen diesen Punkt nicht passieren.",
            "Eine Durchsage bittet den Besitzer einer weinenden Schildkröte zu Gate neun.",
            "Eine Schildkröte ist Kapitänin eines U-Boots. Sie trauert. Warum?"),
        "fr" to MotifLines("tortue", "Les tortues non accompagnées seront rendues à leur famille.",
            "Les tortues en deuil ne sont pas admises au-delà de ce point.",
            "Un haut-parleur demande au propriétaire d'une tortue en larmes de se présenter porte neuf.",
            "Une tortue commande un sous-marin. Elle est en deuil. Pourquoi ?"),
        "es" to MotifLines("tortuga", "Las tortugas no acompañadas serán devueltas a sus familias.",
            "No se permite el paso de tortugas de luto más allá de este punto.",
            "Un altavoz pide al dueño de una tortuga llorosa que acuda a la puerta nueve.",
            "Una tortuga capitanea un submarino. Está de luto. ¿Por qué?"),
        "it" to MotifLines("tartaruga", "Le tartarughe non accompagnate saranno restituite alle loro famiglie.",
            "Le tartarughe in lutto non possono oltrepassare questo punto.",
            "Un altoparlante chiede al proprietario di una tartaruga in lacrime di recarsi al gate nove.",
            "Una tartaruga comanda un sottomarino. È in lutto. Perché?"),
        "pt" to MotifLines("tartaruga", "As tartarugas não acompanhadas serão devolvidas às famílias.",
            "Tartarugas de luto não podem passar deste ponto.",
            "Um altifalante pede ao dono de uma tartaruga lavada em lágrimas que se dirija à porta nove.",
            "Uma tartaruga comanda um submarino. Está de luto. Porquê?"),
        "pl" to MotifLines("żółw", "Żółwie bez opieki zostaną zwrócone rodzinom.",
            "Żółwie w żałobie nie mogą przejść dalej.",
            "Z głośnika pada prośba do właściciela zapłakanego żółwia o zgłoszenie się do bramki dziewiątej.",
            "Żółw dowodzi łodzią podwodną. Jest w żałobie. Dlaczego?"),
        "ru" to MotifLines("черепаха", "Черепахи без сопровождения будут возвращены семьям.",
            "Черепахам в трауре проход дальше запрещён.",
            "Громкоговоритель просит владельца заплаканной черепахи подойти к выходу номер девять.",
            "Черепаха командует подводной лодкой. Она в трауре. Почему?"),
        "uk" to MotifLines("черепаха", "Черепах без супроводу буде повернуто родинам.",
            "Черепахам у жалобі прохід далі заборонено.",
            "Гучномовець просить власника заплаканої черепахи підійти до виходу номер дев'ять.",
            "Черепаха командує підводним човном. Вона в жалобі. Чому?"),
        "tr" to MotifLines("kaplumbağa", "Refakatsiz kaplumbağalar ailelerine teslim edilecektir.",
            "Yas tutan kaplumbağaların bu noktadan öteye geçmesi yasaktır.",
            "Hoparlör, gözü yaşlı bir kaplumbağanın sahibini dokuz numaralı kapıya çağırıyor.",
            "Bir kaplumbağa bir denizaltının kaptanı. Yas tutuyor. Neden?"),
        "ar" to MotifLines("سلحفاة", "ستُعاد السلاحف غير المصحوبة إلى عائلاتها.",
            "لا يُسمح للسلاحف الحزينة بتجاوز هذه النقطة.",
            "يطلب مكبر الصوت من صاحب سلحفاة دامعة التوجه إلى البوابة رقم تسعة.",
            "سلحفاة تقود غواصة. إنها في حداد. لماذا؟"),
        "ja" to MotifLines("カメ", "付き添いのいないカメは、ご家族のもとへお返しします。",
            "喪に服しているカメは、ここから先へは進めません。",
            "スピーカーが、泣いているカメの飼い主に九番ゲートまで来るよう呼びかけている。",
            "カメが潜水艦の艦長をしています。カメは喪に服しています。なぜですか？"),
        "zh" to MotifLines("乌龟", "无人陪同的乌龟将被送回家人身边。",
            "正在哀悼的乌龟不得越过此处。",
            "广播请一只泪汪汪的乌龟的主人前往九号登机口。",
            "一只乌龟是潜水艇的艇长。它正在哀悼。为什么？"),
    ),
)

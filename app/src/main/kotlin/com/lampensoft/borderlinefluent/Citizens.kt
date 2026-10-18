package com.lampensoft.borderlinefluent

import kotlin.random.Random

/** A name in its own script and as printed in Latin letters on the passport (the same for Latin-script languages). */
data class Name(val native: String, val latin: String)

/**
 * Given names and surnames of one language. Surnames are (male, female) pairs: they differ in pl/ru/uk.
 * [familyFirst]: surname first (ja, zh); the native form is then written without a space.
 */
class Names(val male: List<Name>, val female: List<Name>, val surnames: List<Pair<Name, Name>>, val familyFirst: Boolean) {
    fun random(r: Random, female: Boolean): Name {
        val given = (if (female) this.female else male).random(r)
        val sur = surnames.random(r).let { if (female) it.second else it.first }
        return if (familyFirst) Name(sur.native + given.native, "${sur.latin} ${given.latin}")
            else Name("${given.native} ${sur.native}", "${given.latin} ${sur.latin}")
    }
}

private fun name(s: String) = s.split('|').let { Name(it[0], it.getOrElse(1) { _ -> it[0] }) }
private fun list(s: String) = s.split(", ").map(::name)
/** "native|latin" items, comma-separated; a surname "male/female" when the forms differ. */
private fun names(m: String, f: String, sur: String, familyFirst: Boolean = false) = Names(list(m), list(f),
    sur.split(", ").map { p -> p.split('/').let { name(it[0]) to name(it.getOrElse(1) { _ -> it[0] }) } }, familyFirst)

/** Cover names per language. Fictional and shared-language countries use their language's names. */
val NAMES: Map<String, Names> = mapOf(
    "de" to names("Jürgen, Michael, Lukas, Stefan, Thomas, Felix, Andreas, Jonas, Matthias, Tobias",
        "Anna, Katrin, Sabine, Julia, Lena, Claudia, Sophie, Miriam, Petra, Hannah",
        "Müller, Schmidt, Weber, Wagner, Becker, Hoffmann, Schäfer, Koch, Richter, Krüger, Braun, Zimmermann"),
    "fr" to names("Étienne, Julien, Mathieu, Nicolas, Antoine, Pierre, Moussa, Olivier, Thomas, Hugo",
        "Camille, Claire, Élodie, Julie, Aminata, Sophie, Céline, Manon, Isabelle, Léa",
        "Lefèvre, Martin, Bernard, Dubois, Moreau, Laurent, Girard, Diop, Fontaine, Rousseau, Tremblay, Gagnon"),
    "es" to names("José, Carlos, Javier, Diego, Alejandro, Miguel, Andrés, Pablo, Luis, Mateo",
        "María, Lucía, Carmen, Sofía, Valentina, Elena, Isabel, Paula, Camila, Laura",
        "Martínez, García, López, Fernández, Rodríguez, Sánchez, Romero, Torres, Navarro, Ramírez, Herrera, Castillo"),
    "it" to names("Luca, Marco, Giuseppe, Alessandro, Matteo, Francesco, Davide, Stefano, Andrea, Paolo",
        "Giulia, Francesca, Chiara, Sara, Valentina, Elena, Martina, Federica, Alessia, Silvia",
        "Bianchi, Rossi, Russo, Ferrari, Esposito, Romano, Colombo, Ricci, Marino, Greco, Conti, Gallo"),
    "pt" to names("João, Pedro, Tiago, Miguel, Rui, Luís, Bruno, Rafael, André, Gonçalo",
        "Ana, Inês, Mariana, Beatriz, Sofia, Catarina, Joana, Rita, Marta, Carolina",
        "Ferreira, Silva, Santos, Oliveira, Costa, Pereira, Rodrigues, Almeida, Sousa, Carvalho, Lopes, Martins"),
    "pl" to names("Paweł, Piotr, Tomasz, Krzysztof, Marcin, Jakub, Michał, Łukasz, Adam, Grzegorz",
        "Anna, Katarzyna, Magdalena, Agnieszka, Joanna, Monika, Ewa, Zofia, Aleksandra, Małgorzata",
        "Wiśniewski/Wiśniewska, Kowalski/Kowalska, Nowak, Wójcik, Kamiński/Kamińska, Lewandowski/Lewandowska, " +
            "Zieliński/Zielińska, Szymański/Szymańska, Woźniak, Dąbrowski/Dąbrowska, Kozłowski/Kozłowska, Mazur"),
    "ru" to names("Дмитрий|Dmitry, Алексей|Aleksei, Сергей|Sergei, Андрей|Andrei, Михаил|Mikhail, Иван|Ivan, " +
            "Николай|Nikolai, Павел|Pavel, Артём|Artyom, Владимир|Vladimir",
        "Анна|Anna, Екатерина|Ekaterina, Ольга|Olga, Наталья|Natalya, Мария|Maria, Татьяна|Tatyana, Елена|Elena, " +
            "Ирина|Irina, Светлана|Svetlana, Дарья|Darya",
        "Орлов|Orlov/Орлова|Orlova, Смирнов|Smirnov/Смирнова|Smirnova, Кузнецов|Kuznetsov/Кузнецова|Kuznetsova, " +
            "Попов|Popov/Попова|Popova, Соколов|Sokolov/Соколова|Sokolova, Лебедев|Lebedev/Лебедева|Lebedeva, " +
            "Новиков|Novikov/Новикова|Novikova, Морозов|Morozov/Морозова|Morozova, Волков|Volkov/Волкова|Volkova, " +
            "Фёдоров|Fyodorov/Фёдорова|Fyodorova, Зайцев|Zaitsev/Зайцева|Zaitseva, Белов|Belov/Белова|Belova"),
    "uk" to names("Олександр|Oleksandr, Андрій|Andrii, Дмитро|Dmytro, Сергій|Serhii, Тарас|Taras, Богдан|Bohdan, " +
            "Остап|Ostap, Микола|Mykola, Василь|Vasyl, Юрій|Yurii",
        "Олена|Olena, Оксана|Oksana, Ірина|Iryna, Наталія|Nataliia, Марія|Mariia, Тетяна|Tetiana, Юлія|Yuliia, " +
            "Галина|Halyna, Софія|Sofiia, Дарина|Daryna",
        "Шевченко|Shevchenko, Коваленко|Kovalenko, Бондаренко|Bondarenko, Ткаченко|Tkachenko, Мельник|Melnyk, " +
            "Кравчук|Kravchuk, Олійник|Oliinyk, Савченко|Savchenko, Коваль|Koval, Лисенко|Lysenko, " +
            "Ковальський|Kovalskyi/Ковальська|Kovalska, Вишневський|Vyshnevskyi/Вишневська|Vyshnevska"),
    "tr" to names("Mehmet, Ahmet, Mustafa, Emre, Burak, Can, Murat, Kerem, Oğuz, Serkan",
        "Ayşe, Elif, Zeynep, Fatma, Merve, Selin, Deniz, Ebru, Esra, Gül",
        "Yılmaz, Kaya, Demir, Şahin, Çelik, Yıldız, Aydın, Öztürk, Arslan, Doğan, Kılıç, Aslan"),
    "ar" to names("خالد|Khalid, محمد|Mohammed, أحمد|Ahmed, عمر|Omar, يوسف|Youssef, علي|Ali, حسن|Hassan, كريم|Karim, " +
            "طارق|Tariq, سامي|Sami",
        "فاطمة|Fatima, ليلى|Layla, مريم|Mariam, نور|Nour, سارة|Sara, هدى|Huda, رنا|Rana, ياسمين|Yasmin, سلمى|Salma, دينا|Dina",
        "منصور|Mansour, الحسيني|Al-Husseini, حداد|Haddad, العلي|Al-Ali, خليل|Khalil, سعيد|Saeed, الشامي|Al-Shami, " +
            "ناصر|Nasser, عبد الله|Abdullah, إبراهيم|Ibrahim, الزين|Al-Zein, قاسم|Qassem"),
    "ja" to names("健|Ken, 翔太|Shota, 大輔|Daisuke, 拓也|Takuya, 浩二|Koji, 直樹|Naoki, 誠|Makoto, 亮|Ryo, 和也|Kazuya, 悠斗|Yuto",
        "由美|Yumi, さくら|Sakura, 美咲|Misaki, 陽子|Yoko, 恵|Megumi, 彩|Aya, 真由美|Mayumi, 結衣|Yui, 奈々|Nana, 香織|Kaori",
        "佐藤|Sato, 鈴木|Suzuki, 高橋|Takahashi, 田中|Tanaka, 伊藤|Ito, 渡辺|Watanabe, 山本|Yamamoto, 中村|Nakamura, " +
            "小林|Kobayashi, 加藤|Kato, 吉田|Yoshida, 山田|Yamada", familyFirst = true),
    "zh" to names("伟|Wei, 强|Qiang, 磊|Lei, 军|Jun, 洋|Yang, 杰|Jie, 浩然|Haoran, 志明|Zhiming, 建国|Jianguo, 子轩|Zixuan",
        "芳|Fang, 静|Jing, 丽|Li, 敏|Min, 婷|Ting, 雪|Xue, 晓燕|Xiaoyan, 慧敏|Huimin, 欣怡|Xinyi, 雨涵|Yuhan",
        "王|Wang, 李|Li, 张|Zhang, 刘|Liu, 陈|Chen, 杨|Yang, 赵|Zhao, 黄|Huang, 周|Zhou, 吴|Wu, 徐|Xu, 孙|Sun", familyFirst = true),
    "en" to names("James, Oliver, Liam, Jack, Daniel, Thomas, Ryan, Noah, Samuel, Connor",
        "Emily, Sarah, Chloe, Grace, Olivia, Hannah, Megan, Sophie, Lucy, Emma",
        "Smith, Murphy, Wilson, Taylor, Brown, Walsh, Campbell, Kelly, Thompson, Clarke, Hughes, Roberts"),
)

/** Real home towns per nationality ([Nationality.all]), in Latin letters. */
val CITIES: Map<String, List<String>> = """
de=Berlin,Leipzig,Freiburg,Hamburg,Munich,Cologne;
fr=Paris,Lyon,Nantes,Marseille,Nice,Toulouse;
es=Madrid,Valencia,Zaragoza,Seville;
italy=Rome,Bologna,Turin,Florence;
portugal=Lisbon,Porto,Coimbra,Braga;
poland=Warsaw,Kraków,Gdańsk,Wrocław,Poznań,Katowice,Rzeszów;
russia=Moscow,Kazan,Yekaterinburg,Tomsk;
ukraine=Kyiv,Lviv,Odesa,Kharkiv,Dnipro,Zaporizhzhia,Ivano-Frankivsk;
turkey=Istanbul,Izmir,Bursa,Ankara;
uae=Dubai,Abu Dhabi,Sharjah,Al Ain;
japan=Tokyo,Osaka,Sendai,Sapporo;
china=Beijing,Chengdu,Hangzhou,Shanghai;
austria=Vienna,Graz,Linz,Salzburg;
switzerland=Zurich,Bern,Lucerne,Basel;
belgium=Brussels,Liège,Namur,Ghent;
canada=Toronto,Montreal,Quebec City,Sherbrooke,Vancouver,Ottawa;
senegal=Dakar,Saint-Louis,Thiès,Kaolack;
mexico=Mexico City,Guadalajara,Puebla,Mérida;
argentina=Buenos Aires,Córdoba,Rosario,Mendoza;
colombia=Bogotá,Medellín,Cali,Cartagena;
brazil=São Paulo,Belo Horizonte,Recife,Curitiba;
angola=Luanda,Benguela,Huambo,Lubango;
capeverde=Mindelo,Praia,Assomada,Espargos;
egypt=Cairo,Alexandria,Luxor,Aswan;
jordan=Amman,Irbid,Aqaba,Madaba;
morocco=Casablanca,Marrakesh,Fez,Rabat;
uk=London,Manchester,Bristol,Edinburgh;
ireland=Dublin,Cork,Galway,Limerick;
australia=Sydney,Melbourne,Brisbane,Adelaide;
usa=Chicago,Seattle,Denver,Philadelphia,Austin
""".split(";").associate { e -> e.trim().split("=").let { it[0] to it[1].split(",") } }

/** A coherent purpose, accommodation and optional host for one trip. */
class Trip(val purpose: String, val stayed: List<String>, val host: String)

val TRIPS = listOf(
    Trip("a trade fair", listOf("a business hotel near the fairground", "the Marriott by the river"), "nobody, a hotel stay"),
    Trip("visiting an old friend", listOf("the friend's apartment"), "an old friend from university"),
    Trip("a business meeting", listOf("Hotel Central near the main station", "a business hotel by the airport"), "a partner company"),
    Trip("a holiday", listOf("an Airbnb in the old town", "a small guesthouse by the sea", "a beach hotel"), "nobody, a holiday"),
    Trip("an academic conference", listOf("the conference hotel", "a university guest house"), "the university hosting the conference"),
    Trip("a friend's wedding", listOf("a friend's flat", "a hotel near the wedding venue"), "the friend getting married"),
    Trip("a hiking trip", listOf("mountain huts", "a campsite by a lake"), "nobody, a hiking holiday"),
    Trip("a job interview", listOf("a hotel near the company", "an Airbnb near the station"), "the company they interview with"),
    Trip("a photography assignment", listOf("a small guesthouse", "a hotel in the old town"), "the magazine they work for"),
)

/** Cover values retired because the traveller has no family (owner, 2026-10-17): a saved trip loads with the new one. */
val RETIRED_COVER_VALUES = mapOf("a cousin's wedding" to "a friend's wedding", "the cousin's house" to "a friend's flat",
    "their cousin" to "the friend getting married")

/**
 * The city of the trip per [Country.nation] (real countries only, like [Backstory.from]): Latin, then the forms in
 * ru uk ar ja zh. Facts name the city next to the country so "I was in Toronto" matches "returning from Canada".
 */
val TRIP_CITIES: Map<String, List<String>> = """
Ukraine=Kyiv|Киев|Київ|كييف|キーウ|基辅; Germany=Berlin|Берлин|Берлін|برلين|ベルリン|柏林; France=Paris|Париж|Париж|باريس|パリ|巴黎
Spain=Barcelona|Барселона|Барселона|برشلونة|バルセロナ|巴塞罗那; Italy=Milan|Милан|Мілан|ميلانو|ミラノ|米兰
Portugal=Lisbon|Лиссабон|Лісабон|لشبونة|リスボン|里斯本; Poland=Warsaw|Варшава|Варшава|وارسو|ワルシャワ|华沙
Russia=Moscow|Москва|Москва|موسكو|モスクワ|莫斯科; Türkiye=Istanbul|Стамбул|Стамбул|إسطنبول|イスタンブール|伊斯坦布尔
United Arab Emirates=Dubai|Дубай|Дубай|دبي|ドバイ|迪拜; Japan=Tokyo|Токио|Токіо|طوكيو|東京|东京
China=Shanghai|Шанхай|Шанхай|شنغهاي|上海|上海; Austria=Vienna|Вена|Відень|فيينا|ウィーン|维也纳
Switzerland=Zurich|Цюрих|Цюрих|زيورخ|チューリッヒ|苏黎世; Belgium=Brussels|Брюссель|Брюссель|بروكسل|ブリュッセル|布鲁塞尔
Canada=Toronto|Торонто|Торонто|تورنتو|トロント|多伦多, Montreal|Монреаль|Монреаль|مونتريال|モントリオール|蒙特利尔
Senegal=Dakar|Дакар|Дакар|داكار|ダカール|达喀尔; Mexico=Mexico City|Мехико|Мехіко|مكسيكو سيتي|メキシコシティ|墨西哥城
Argentina=Buenos Aires|Буэнос-Айрес|Буенос-Айрес|بوينس آيرس|ブエノスアイレス|布宜诺斯艾利斯
Colombia=Bogotá|Богота|Богота|بوغوتا|ボゴタ|波哥大; Brazil=São Paulo|Сан-Паулу|Сан-Паулу|ساو باولو|サンパウロ|圣保罗
Angola=Luanda|Луанда|Луанда|لواندا|ルアンダ|罗安达; Cape Verde=Praia|Прая|Прая|برايا|プライア|普拉亚
Egypt=Cairo|Каир|Каїр|القاهرة|カイロ|开罗; Jordan=Amman|Амман|Амман|عمّان|アンマン|安曼
Morocco=Casablanca|Касабланка|Касабланка|الدار البيضاء|カサブランカ|卡萨布兰卡
United Kingdom=London|Лондон|Лондон|لندن|ロンドン|伦敦; Ireland=Dublin|Дублин|Дублін|دبلن|ダブリン|都柏林
Australia=Sydney|Сидней|Сідней|سيدني|シドニー|悉尼
United States=Chicago|Чикаго|Чикаго|شيكاغو|シカゴ|芝加哥
""".split(";", "\n").filter { it.isNotBlank() }.associate { e -> e.trim().split("=").let { it[0] to it[1].split(", ") } }

/** The city each airport serves (Latin): an arrival's visit city is its airport's, so the two never disagree. */
val AIRPORT_CITY: Map<String, String> = """
fra=Frankfurt fkb=Baden-Baden hhn=Hahn bre=Bremen str=Stuttgart dtm=Dortmund cdg=Paris mrs=Marseille nce=Nice tuf=Tours
lil=Lille avn=Avignon run=Saint-Denis mad=Madrid vll=Valladolid fco=Rome rmi=Rimini flr=Florence olb=Olbia nap=Naples
vrn=Verona peg=Perugia lis=Lisbon pdl=Ponta_Delgada fnc=Funchal ter=Praia_da_Vitória waw=Warsaw krk=Kraków ktw=Katowice
gdn=Gdańsk poz=Poznań rze=Rzeszów wro=Wrocław svo=Moscow mmk=Murmansk arh=Arkhangelsk tof=Tomsk kzn=Kazan uud=Ulan-Ude
kbp=Kyiv hrk=Kharkiv ods=Odesa lwo=Lviv ifo=Ivano-Frankivsk ist=Istanbul nav=Nevşehir rzv=Rize
dlm=Dalaman asr=Kayseri adb=Izmir saw=Istanbul dxb=Dubai auh=Abu_Dhabi aan=Al_Ain nrt=Tokyo hij=Hiroshima kmq=Komatsu
kij=Niigata koj=Kagoshima cts=Chitose oka=Naha pek=Beijing kwl=Guilin hgh=Hangzhou kwe=Guiyang xmn=Xiamen lhw=Lanzhou
wus=Wuyishan vie=Vienna inn=Innsbruck szg=Salzburg zrh=Zurich crl=Charleroi yul=Montreal yyz=Toronto dss=Dakar
mex=Mexico_City cun=Cancún cjs=Ciudad_Juárez eze=Buenos_Aires mdz=Mendoza bog=Bogotá ctg=Cartagena mde=Medellín
gru=São_Paulo lad=Luanda rai=Praia cai=Cairo amm=Amman cmn=Casablanca fez=Fès lhr=London
syy=Stornoway dub=Dublin syd=Sydney asp=Alice_Springs jfk=New_York lax=Los_Angeles ord=Chicago
""".trim().split(Regex("\\s+")).associate { e -> e.split("=").let { it[0] to it[1].replace('_', ' ') } }

private val CITY_SCRIPTS = listOf("ru", "uk", "ar", "ja", "zh")

/** [city] (Latin) as written in [lang], or null where it is the same. */
fun cityIn(city: String, lang: String): String? {
    val i = CITY_SCRIPTS.indexOf(lang).takeIf { it >= 0 } ?: return null
    return TRIP_CITIES.values.flatten().map { it.split('|') }.firstOrNull { it[0] == city }?.get(i + 1)
}

/** A sample traveler face: tile [tile] of assets/art/<sheet>.webp (3x2 passport photos), used until the player's own photo. */
data class Person(val female: Boolean, val sheet: String, val tile: Int)

/**
 * The player's cover for one trip: the flat view of [Identity] + [Itinerary] at [country] (always the destination desk;
 * [nationality] is the passport's). Flat on purpose: the answer check compares answers against these fields, and
 * fixtures `copy` single fields. [gender]: how officers address the traveller ({m|f|n} forms in [Assessment.fill]);
 * [photo] is the passport photo's sample face. HOMECOMING: [from] is the trip's country and [city] (Latin) in it.
 * ARRIVAL: [city] is in the destination, [from] is where they fly in from.
 * Retired job, companion, meeting, waiting and stamp fields are retained only for archived saves.
 */
data class Backstory(
    val name: String, val latin: String, val gender: Gender, val photo: Person, val country: Country,
    val passportNo: String, val born: String, val home: String, val job: String, val from: String, val city: String, val purpose: String,
    val days: Int, val stayed: String, val travelledWith: String, val met: String, val waiting: String,
    val stamps: List<String>, val lang: String, val nationality: Nationality, val kind: TripKind, val host: String,
    /** The queue scene before the desk (PRD §6b), not part of the cover; its fact only once chosen and asked. */
    val encounter: QueueEncounter? = null,
    val homeArrivalTraining: Boolean = false,
) {
    /** Home town and real nationality; avoid repeating an identical town/country name. */
    val homeLine get() = if (home == nationality.name) home else "$home, ${nationality.name}"

    /** "Toronto", or "Toronto / Торонто" for a [lang] that writes it differently. */
    fun cityLine(lang: String = this.lang) = city + (cityIn(city, lang)?.let { " / $it" } ?: "")

    /** The queue choice the desk will ask about, else null. */
    val askedEncounter get() = encounter?.takeIf { it.asked }?.chosen

    /** Only a hosted visit exposes HOST, including when restoring an older cover. */
    val hasHost get() = kind == TripKind.ARRIVAL && host.isNotBlank() && !host.startsWith("nobody")

    /**
     * The checkable facts of this trip's kind, in the order an officer asks; Nano sees them numbered ([facts]).
     * [FactId.ENCOUNTER] comes last for a chosen queue question or the fixed home arrival training question;
     * the numbers before it never shift.
     */
    fun factIds(): List<FactId> = (if (kind == TripKind.ARRIVAL) ARRIVAL_FACTS else HOMECOMING_FACTS)
        .filter { it != FactId.HOST || hasHost } +
        listOfNotNull(FactId.ENCOUNTER.takeIf { askedEncounter != null || homeArrivalTraining })

    /** [factIds]' 1-based number in Nano's classify reply → its id; null for 0 or out of range. */
    fun factOf(n: Int): FactId? = factIds().getOrNull(n - 1)

    /** Stamps from earlier trips: HOMECOMING's first stamp is this trip's; an ARRIVAL has only earlier ones. */
    val earlierStamps get() = if (kind == TripKind.ARRIVAL) stamps else stamps.drop(1)

    /** One fact's text. [lang]: the desk's language, for the city's spelling (the bench puts one cover at every desk). */
    fun fact(id: FactId, lang: String = this.lang) = if (kind == TripKind.ARRIVAL) arrivalFact(id, lang) else when (id) {
        FactId.NAME_BORN -> nameLine()
        FactId.FROM -> "Returning from (the country of this trip): $from (the city ${cityLine(lang)} is in $from)"
        FactId.PURPOSE -> "Purpose of the trip (why they went): $purpose"
        FactId.DAYS -> "Time away (length of the trip): $days days"
        FactId.STAY -> "Where they stayed on the trip: $stayed"
        FactId.HOME -> "Home (where they live and are from): $homeLine"
        FactId.HOST -> "Invited or hosted by: $host"
        FactId.ENCOUNTER -> encounterLine()
        else -> error("Retired cover fact: $id")
    }

    private fun encounterLine() = if (homeArrivalTraining) StoryCover.ARRIVAL_ENCOUNTER else "In the queue before this desk: ${askedEncounter?.fact ?: "nothing happened"}"

    private fun nameLine() = "Name: $name" + (if (latin != name) " (in Latin letters: $latin)" else "") + "; date of birth: $born"

    /** A visit to [country]: the trip is ahead of them, home is in [nationality], [from] is where they fly in from. */
    private fun arrivalFact(id: FactId, lang: String) = when (id) {
        FactId.NAME_BORN -> nameLine()
        FactId.PURPOSE -> "Purpose of the visit (why they came): $purpose"
        FactId.DAYS -> "Length of stay: $days days"
        FactId.STAY -> "Staying at: $stayed, in ${cityLine(lang)}"
        FactId.HOST -> "Invited or hosted by: $host"
        FactId.HOME -> "Home (where they live): $homeLine"
        FactId.FROM -> "Flying in from: $from"
        FactId.ENCOUNTER -> encounterLine()
        else -> error("Retired cover fact: $id")
    }

    /** The checkable facts as text, in [factIds] order (numbered 1.. for the answer check). */
    fun facts(lang: String = this.lang) = factIds().map { fact(it, lang) }

    fun card(lang: String = this.lang) = cardText(lang) + (if (askedEncounter != null || homeArrivalTraining) "\n" + encounterLine() else "")

    private fun cardText(lang: String) = if (kind == TripKind.ARRIVAL) """
        Name: $name${if (latin != name) " ($latin)" else ""}, ${genderWord(gender)} (citizen of ${withArticle(nationality.name)}, passport $passportNo, born $born)
        Lives in: $homeLine
        Visiting: ${country.nation} (${cityLine(lang)}) for $days days, flying in from $from
        Purpose of the visit: $purpose
        ${if (hasHost) "Invited or hosted by: $host" else ""}
        Staying at: $stayed
    """.trimIndent().lineSequence().filter { it.isNotBlank() }.joinToString("\n") else """
        Name: $name${if (latin != name) " ($latin)" else ""}, ${genderWord(gender)} (citizen of ${nationality.name}, passport $passportNo, born $born)
        Lives in: $homeLine
        Coming home from: $from (${cityLine(lang)}), after $days days
        Purpose of the trip: $purpose
        Stayed at: $stayed
    """.trimIndent()

    companion object {
        val BENCH = Backstory("Michael Brandt", "Michael Brandt", Gender.MAN, Person(false, "travelers_sheet", 2),
            Country.of("de"), "DE4471902", "3 Jun 1984", "Lindenberg", "software consultant", "Canada", "Toronto",
            "a logistics software conference", 5, "Hotel Steigenberger, city centre", "colleagues from the company Datenwerk",
            "clients at the conference", "nobody", listOf("Canada 2035", "Japan 2033"), lang = "de",
            nationality = Nationality.find("de")!!, kind = TripKind.HOMECOMING, host = "nobody, a hotel stay")

        /** The arrival bench cover: an American visiting Germany for a friend's wedding (JudgeCases.arrival). */
        val BENCH_ARRIVAL = Backstory("James Walker", "James Walker", Gender.MAN, Person(false, "travelers_sheet", 2),
            Country.of("de"), "US5520931", "14 Feb 1990", "Chicago", "architect", "United States", "Berlin",
            "a friend's wedding", 7, "a hotel near the wedding venue", "nobody, travelled alone", "the couple's friends",
            "nobody", listOf("Japan 2033"), lang = "de", nationality = Nationality.USA, kind = TripKind.ARRIVAL,
            host = "the friend getting married")

        // The names only say who each tile shows (art review); the game never uses them.
        private fun sheet(name: String, vararg people: Pair<String, Boolean>) =
            people.mapIndexed { i, (_, f) -> Person(f, name, i) }

        private const val F = true; private const val M = false
        val people = sheet("travelers_sheet", "Daniel Reyes" to M, "Sarah Mitchell" to F, "James Porter" to M,
                "Emily Novak" to F, "Thomas Hale" to M, "Laura Kim" to F) +
            sheet("travelers2_sheet", "Marcus Bell" to M, "Hannah Weiss" to F, "Ravi Mehta" to M,
                "Olivia Grant" to F, "Kenji Mori" to M, "Sofia Alvarez" to F) +
            sheet("travelers3_sheet", "Nathan Cole" to M, "Amara Okafor" to F, "Lucas Meyer" to M,
                "Mei Lin Chen" to F, "Omar Haddad" to M, "Claire Dubois" to F) +
            sheet("travelers4_sheet", "Ethan Brooks" to M, "Priya Nair" to F, "Diego Santos" to M,
                "Grace O'Neill" to F, "Samuel Adeyemi" to M, "Yuna Park" to F) +
            sheet("travelers5_sheet", "Oliver Hughes" to M, "Leila Karimi" to F, "Tom Kowalski" to M,
                "Isabel Costa" to F, "Jamal Carter" to M, "Anna Lindqvist" to F) +
            sheet("travelers6_sheet", "Ryan Murphy" to M, "Chloe Bennett" to F, "Ahmed Siddiqui" to M, "Naomi Clarke" to F, "Liam O'Connor" to M, "Mia Tanaka" to F) +
            sheet("travelers7_sheet", "Jack Wilson" to M, "Fatima Rahman" to F, "Carlos Mendes" to M, "Emma Thompson" to F, "Kwame Asante" to M, "Sophie Martin" to F) +
            sheet("travelers8_sheet", "Daniel Cohen" to M, "Aisha Bello" to F, "Michael Chen" to M, "Rachel Green" to F, "Tom Fraser" to M, "Isabella Rossi" to F) +
            sheet("travelers9_sheet", "Arjun Patel" to M, "Megan Kelly" to F, "David Kim" to M, "Grace Mensah" to F, "Patrick Doyle" to M, "Hannah Lee" to F) +
            sheet("travelers10_sheet", "Samuel Reed" to M, "Olivia Brown" to F, "Hassan Ali" to M, "Zoe Walker" to F, "Ethan Park" to M, "Leah Goldberg" to F) +
            sheet("travelers11_sheet", "Connor Hughes" to M, "Priyanka Shah" to F, "Marcus Johnson" to M, "Ella Davies" to F, "Rafael Torres" to M, "Yuki Sato" to F) +
            sheet("travelers12_sheet", "Owen Price" to M, "Amara Nwosu" to F, "Jason Wong" to M, "Lucy Hall" to F, "Noah Abbott" to M, "Sara Haddad" to F)
        /** The game's year. */
        const val YEAR = 2035

        /** The player's [identity] on one rolled [Itinerary] to [destination]. Without one (tests, tools): a citizen of the destination. */
        fun random(r: Random, destination: Destination, identity: Identity? = null, departure: String? = null): Backstory {
            val country = destination.country
            val id = identity ?: run {
                val gender = if (r.nextBoolean()) Gender.WOMAN else Gender.MAN
                val list = Identity.listFor(gender, NameList.MALE)
                val name = NAMES.getValue(destination.lang).random(r, list == NameList.FEMALE)
                Identity(checkNotNull(Nationality.find(country.id)), gender, list, name.native, name.latin, Identity.born(r),
                    CITIES.getValue(country.id).random(r), "", null, Identity.passportNo(r, country.iso))
            }
            // The sample face follows the name list: a binary choice only for the picture and the names.
            val photo = id.photo ?: people.filter { it.female == (id.nameList == NameList.FEMALE) }.random(r)
            return of(id, photo, Itinerary.roll(r, destination, id, departure), destination)
        }

        fun of(id: Identity, photo: Person, t: Itinerary, destination: Destination) = Backstory(
            name = id.name, latin = id.latin, gender = id.gender, photo = photo, country = destination.country,
            passportNo = id.passportNo, born = id.born, home = id.home, job = id.job,
            from = t.from ?: id.nationality.name, city = t.city, purpose = t.purpose, days = t.days, stayed = t.hotel,
            travelledWith = t.travelledWith, met = t.met, waiting = t.waiting, stamps = t.stamps, lang = destination.lang,
            nationality = id.nationality, kind = t.kind, host = t.host,
        )
    }
}

/** The player's passport and the story's fixed itineraries. A failed trip never advances the route. */
object StoryCover {
    const val ARRIVAL_ENCOUNTER = "Left Thalassia on the last flight before the airports closed; saw the riot news in the arrivals hall; was not hurt."

    /** Schema-compatible scaffolding only: the scripted departure never reads passport facts. */
    fun departure(destination: Destination) = Backstory(
        "", "", Gender.UNSAID, Backstory.people.first(), destination.country,
        "", "", "", "", "Thalassia", "Lefkopoli", "a holiday", 5, "", "", "", "",
        emptyList(), destination.lang, Nationality.all.first { it.id == destination.country.id }, TripKind.HOMECOMING, "")

    fun homeArrival(destination: Destination, id: Identity) = training(destination, id).copy(homeArrivalTraining = true)

    /** Fixed departure-desk verdicts: the player has not arrived home yet. */
    private val departureVerdicts = mapOf(
        "en" to ("Everything is in order. Have a good flight home." to "Let’s try again. Take your time."),
        "de" to ("Alles in Ordnung. Einen guten Heimflug." to "Versuchen wir es noch einmal. Nehmen Sie sich Zeit."),
        "fr" to ("Tout est en ordre. Bon vol de retour." to "Reprenons. Prenez votre temps."),
        "es" to ("Todo en orden. Que tenga buen vuelo de regreso a casa." to "Vamos a intentarlo otra vez. Tómese su tiempo."),
        "it" to ("Tutto in ordine. Buon volo di ritorno a casa." to "Riproviamo. Faccia con calma."),
        "pt" to ("Está tudo em ordem. Boa viagem de regresso a casa." to "Vamos tentar de novo. Não tenha pressa."),
        "pl" to ("Wszystko w porządku. Miłego lotu do domu." to "Spróbujmy jeszcze raz. Bez pośpiechu."),
        "ru" to ("Всё в порядке. Счастливого полёта домой." to "Давайте попробуем ещё раз. Не торопитесь."),
        "uk" to ("Усе гаразд. Щасливого польоту додому." to "Спробуймо ще раз. Не поспішайте."),
        "tr" to ("Her şey yolunda. Evinize iyi yolculuklar." to "Bir daha deneyelim. Acele etmeyin."),
        "ar" to ("كل شيء على ما يرام. رحلة سعيدة إلى الوطن." to "لنحاول مرة أخرى. لا داعي للعجلة."),
        "ja" to ("問題ありません。お気をつけてお帰りください。" to "もう一度やってみましょう。ゆっくりで大丈夫ですよ。"),
        "zh" to ("一切正常。祝您回家一路顺风。" to "我们再试一次。不着急，慢慢来。"),
    )
    fun trainingVerdict(lang: String, passed: Boolean): String = departureVerdicts.getValue(lang).let {
        if (passed) it.first else it.second
    }

    fun countries(playable: List<Country>, id: Identity, lastCountry: String?): List<Country> =
        playable.filter {
            it.id != lastCountry && it.id != id.nationality.id
        }

    fun trainingItinerary() = Itinerary(
        TripKind.HOMECOMING, "a holiday", 5, "a guesthouse in Lefkopoli", "nobody, a hotel stay",
        "", "Thalassia", "Lefkopoli", "", "", emptyList())

    fun training(destination: Destination, id: Identity) = Backstory.of(id,
        id.photo ?: Backstory.people.first { it.female == (id.nameList == NameList.FEMALE) },
        trainingItinerary(), destination)

    fun itinerary(destination: Destination, id: Identity, lastCountry: String?): Itinerary {
        val country = destination.country
        return Itinerary(TripKind.ARRIVAL, "a business meeting", 4, "Hotel Central near the main station",
            "a partner company", "", lastCountry?.let { Country.of(it).nation } ?: id.nationality.name,
            AIRPORT_CITY[destination.airport.id] ?: TRIP_CITIES.getValue(country.nation).first().substringBefore('|'), "", "", emptyList())
    }

    fun backstory(destination: Destination, id: Identity, lastCountry: String? = null) = Backstory.of(id,
        id.photo ?: Backstory.people.first { it.female == (id.nameList == NameList.FEMALE) },
        itinerary(destination, id, lastCountry), destination)
}

/**
 * One checkable cover fact, by meaning. Everything that remembers facts (topic coverage, contradictions, the judge's
 * earlier answers) keys on these; only the classify prompt and its FACT=n reply use list positions ([Backstory.factOf]).
 */
enum class FactId { NAME_BORN, FROM, PURPOSE, DAYS, STAY, WITH, MET, HOME, JOB, WAITING, STAMPS, HOST, ENCOUNTER }

/** Remaining homecoming facts retain their relative order in Nano's numbered list. */
val HOMECOMING_FACTS = listOf(FactId.NAME_BORN, FactId.FROM, FactId.PURPOSE, FactId.DAYS, FactId.STAY, FactId.HOME)

/** A visitor's order; HOST is omitted when the itinerary has no host. */
val ARRIVAL_FACTS = listOf(FactId.NAME_BORN, FactId.PURPOSE, FactId.DAYS, FactId.STAY, FactId.HOST, FactId.HOME, FactId.FROM)

/** The traveller's gender in English for prompts ([Backstory.card]). */
fun genderWord(g: Gender) = when (g) {
    Gender.WOMAN -> "woman"; Gender.MAN -> "man"; Gender.NONBINARY -> "non-binary person"; Gender.UNSAID -> "person"
}

// German answers for the fixed benchmark, a citizen coming home. Turn 7 contradicts the 5 days away on purpose.
val BENCH_ANSWERS = listOf(
    "Guten Tag.",
    "Ich komme aus Kanada zurück, von einer Konferenz über Logistiksoftware.",
    "Fünf Tage.",
    "Im Hotel Steigenberger in der Innenstadt.",
    "Ich wohne in Lindenberg.",
    "Ich heiße Michael Brandt.",
    "Ich war zwei Wochen weg, glaube ich.",
    "Entschuldigung, ich meine fünf Tage. Ich wohne in Lindenberg.",
    "Ich bin am 3. Juni 1984 geboren.",
    "Danke, auf Wiedersehen.",
)

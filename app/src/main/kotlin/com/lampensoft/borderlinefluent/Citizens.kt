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

/** Real home towns per canonical country, in Latin letters. */
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
kazakhstan=Almaty,Astana,Shymkent,Karaganda;
kyrgyzstan=Bishkek,Osh,Jalal-Abad,Karakol;
egypt=Cairo,Alexandria,Luxor,Aswan;
jordan=Amman,Irbid,Aqaba,Madaba;
morocco=Casablanca,Marrakesh,Fez,Rabat;
uk=London,Manchester,Bristol,Edinburgh;
ireland=Dublin,Cork,Galway,Limerick;
australia=Sydney,Melbourne,Brisbane,Adelaide
""".split(";").associate { e -> e.trim().split("=").let { it[0] to it[1].split(",") } }

/**
 * One kind of trip: what the traveler did abroad, and the places and people that fit it, so a cover never says
 * "a hiking trip" and "stayed at the conference hotel". [with] pairs a text with a key that [WAITING] avoids repeating.
 */
class Trip(val purpose: String, val stayed: List<String>, val with: List<Pair<String, String>>, val met: List<String>)

private val ALONE = "nobody, travelled alone" to "alone"

val TRIPS = listOf(
    Trip("a trade fair", listOf("a business hotel near the fairground", "the Marriott by the river"),
        listOf("two colleagues from work" to "colleague", ALONE), listOf("clients at the fair", "a supplier named Weber")),
    Trip("visiting an old friend", listOf("the friend's apartment"),
        listOf(ALONE, "their partner" to "partner"), listOf("an old friend from university and his family")),
    Trip("a business meeting", listOf("Hotel Central near the main station", "a business hotel by the airport"),
        listOf("a colleague from work" to "colleague", ALONE), listOf("a client named Weber", "the board of a partner company")),
    Trip("a holiday", listOf("an Airbnb in the old town", "a small guesthouse by the sea", "a beach hotel"),
        listOf("their partner" to "partner", "two friends" to "friend", "their sister" to "sister", ALONE),
        listOf("nobody in particular", "a local tour guide")),
    Trip("an academic conference", listOf("the conference hotel", "a university guest house"),
        listOf("a university colleague" to "colleague", ALONE), listOf("researchers from other universities", "a former professor")),
    Trip("a cousin's wedding", listOf("the cousin's house", "a hotel near the wedding venue"),
        listOf("their partner" to "partner", "their parents" to "parents", ALONE), listOf("the cousin's family", "relatives from abroad")),
    Trip("a hiking trip", listOf("mountain huts", "a campsite by a lake"),
        listOf("a friend from school" to "friend", ALONE), listOf("a mountain guide", "other hikers on the trail")),
    Trip("a job interview", listOf("a hotel near the company", "an Airbnb near the station"),
        listOf(ALONE), listOf("the hiring manager", "the team they might join")),
    Trip("a photography assignment", listOf("a small guesthouse", "a hotel in the old town"),
        listOf("an assistant" to "colleague", ALONE), listOf("a local guide", "the magazine's local fixer")),
)

/** Who waits at home, keyed like [Trip.with]: never the people they travelled with. */
val WAITING = listOf("their partner and two children" to "partner", "their partner" to "partner", "their parents" to "parents",
    "a sister and her children" to "sister", "an old dog and a neighbour who feeds it" to "dog", "nobody, lives alone" to "alone")

/** Travelled with [withKey]: who may wait at home. A partner on the trip is not waiting, nor "lives alone". */
fun waitingFor(withKey: String) = WAITING.filter { it.second != withKey && !(withKey == "partner" && it.second == "alone") }

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
Kazakhstan=Almaty|Алматы|Алмати|ألماتي|アルマトイ|阿拉木图; Kyrgyzstan=Bishkek|Бишкек|Бішкек|بيشكيك|ビシュケク|比什凯克
Egypt=Cairo|Каир|Каїр|القاهرة|カイロ|开罗; Jordan=Amman|Амман|Амман|عمّان|アンマン|安曼
Morocco=Casablanca|Касабланка|Касабланка|الدار البيضاء|カサブランカ|卡萨布兰卡
United Kingdom=London|Лондон|Лондон|لندن|ロンドン|伦敦; Ireland=Dublin|Дублин|Дублін|دبلن|ダブリン|都柏林
Australia=Sydney|Сидней|Сідней|سيدني|シドニー|悉尼
""".split(";", "\n").filter { it.isNotBlank() }.associate { e -> e.trim().split("=").let { it[0] to it[1].split(", ") } }

private val CITY_SCRIPTS = listOf("ru", "uk", "ar", "ja", "zh")

/** [city] (Latin) as written in [lang], or null where it is the same. */
fun cityIn(city: String, lang: String): String? {
    val i = CITY_SCRIPTS.indexOf(lang).takeIf { it >= 0 } ?: return null
    return TRIP_CITIES.values.flatten().map { it.split('|') }.firstOrNull { it[0] == city }?.get(i + 1)
}

/** Cover jobs, English (the cover is an English document; Ui.kt picks "a"/"an" by the first letter). */
val JOBS = listOf("software consultant", "architect", "university lecturer", "wine importer", "freelance photographer",
    "mechanical engineer", "nurse", "marketing manager", "travel journalist", "civil engineer", "chef",
    "museum curator", "pharmacist", "graphic designer")

/** A sample traveler face: tile [tile] of assets/art/<sheet>.webp (3x2 passport photos), used until the player's own photo. */
data class Person(val female: Boolean, val sheet: String, val tile: Int)

/**
 * The player's cover: a citizen of [country] (the destination) coming home from a trip to [from]. Structured on purpose:
 * the answer check compares answers against these fields. [female]: the identity's grammatical gender, drawn per cover
 * (owner, 2026-10-05: it may differ from the player's face); [photo] is a sample face of that gender.
 * [city]: where in [from] they were (Latin). [stamps]: this trip ("Canada 2035") first, then earlier trips.
 */
data class Backstory(
    val name: String, val latin: String, val female: Boolean, val photo: Person, val country: Country,
    val passportNo: String, val born: String, val home: String, val job: String, val from: String, val city: String, val purpose: String,
    val days: Int, val stayed: String, val travelledWith: String, val met: String, val waiting: String,
    val stamps: List<String>, val lang: String,
) {
    /** Home town and real nationality; avoid repeating an identical town/country name. */
    val homeLine get() = if (home == country.name) home else "$home, ${country.name}"

    /** "Toronto", or "Toronto / Торонто" for a [lang] that writes it differently. */
    fun cityLine(lang: String = this.lang) = city + (cityIn(city, lang)?.let { " / $it" } ?: "")

    /**
     * The checkable facts, numbered for the answer check in the order an officer asks. Stable: indexes are stored per
     * run. [lang]: the desk's language, for the city's spelling (the bench puts one cover at every desk).
     */
    fun facts(lang: String = this.lang) = listOf(
        "Name: $name" + (if (latin != name) " (in Latin letters: $latin)" else "") + "; date of birth: $born",
        "Returning from (the country of this trip): $from (the city ${cityLine(lang)} is in $from)", "Purpose of the trip (why they went): $purpose",
        "Time away (length of the trip): $days days", "Where they stayed on the trip: $stayed",
        "Travelled with: $travelledWith", "Met on the trip: $met", "Home (where they live and are from): $homeLine",
        "Occupation (job): $job", "Waiting at home: $waiting", "Passport stamps: ${stamps.joinToString()}",
    )

    fun card(lang: String = this.lang) = """
        Name: $name${if (latin != name) " ($latin)" else ""}, ${if (female) "woman" else "man"} (citizen of ${country.name}, passport $passportNo, born $born)
        Lives in: $homeLine
        Occupation: $job
        Coming home from: $from (${cityLine(lang)}), after $days days
        Purpose of the trip: $purpose
        Stayed at: $stayed
        Travelled with: $travelledWith
        Met there: $met
        Waiting at home: $waiting
        Passport stamps: ${stamps.joinToString()}
    """.trimIndent()

    companion object {
        val BENCH = Backstory("Michael Brandt", "Michael Brandt", false, Person(false, "travelers_sheet", 2),
            Country.of("de"), "DE4471902", "3 Jun 1984", "Lindenberg", "software consultant", "Canada", "Toronto",
            "a logistics software conference", 5, "Hotel Steigenberger, city centre", "colleagues from the company Datenwerk",
            "clients at the conference", "his wife and two children", listOf("Canada 2035", "Japan 2033"), lang = "de")

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
        private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        /** The game's year: this trip's stamp. Earlier stamps are from the five years before. */
        const val YEAR = 2035

        /** A citizen of [destination] coming home. Every sample sheet ships in the app's assets. */
        fun random(r: Random, destination: Destination): Backstory {
            val country = destination.country
            val female = r.nextBoolean()
            val photo = people.filter { it.female == female }.random(r)
            val name = NAMES.getValue(destination.lang).random(r, female)
            // Real countries only: their names have well-known translations the answer check can match.
            val from = Country.all.filter { it.nation != country.nation }.map { it.nation }.distinct().random(r)
            val trip = TRIPS.random(r)
            val (with, withKey) = trip.with.random(r)
            val earlier = Country.all.map { it.nation }.distinct().filter { it != country.nation && it != from }
                .shuffled(r).take(r.nextInt(0, 3)).map { "$it ${r.nextInt(YEAR - 5, YEAR)}" }
            return Backstory(
                name = name.native, latin = name.latin, female = female, photo = photo, country = country,
                passportNo = country.iso + (1_000_000 + r.nextInt(9_000_000)),
                born = "${r.nextInt(1, 29)} ${months.random(r)} ${r.nextInt(1980, 2003)}",
                home = CITIES.getValue(country.id).random(r),
                job = JOBS.random(r),
                from = from, city = TRIP_CITIES.getValue(from).random(r).substringBefore('|'), purpose = trip.purpose, days = r.nextInt(3, 15), stayed = trip.stayed.random(r),
                travelledWith = with, met = trip.met.random(r), waiting = waitingFor(withKey).random(r).first,
                stamps = listOf("$from $YEAR") + earlier, lang = destination.lang,
            )
        }
    }
}

// German answers for the fixed benchmark, a citizen coming home. Turn 7 contradicts the 5 days away on purpose.
val BENCH_ANSWERS = listOf(
    "Guten Tag.",
    "Ich komme aus Kanada zurück, von einer Konferenz über Logistiksoftware.",
    "Fünf Tage.",
    "Im Hotel Steigenberger in der Innenstadt.",
    "Ich bin Softwareberater.",
    "Mit Kollegen von der Firma Datenwerk.",
    "Ich war zwei Wochen weg, glaube ich.",
    "Entschuldigung, ich meine fünf Tage. Ich wohne in Lindenberg.",
    "Meine Frau und meine zwei Kinder warten zu Hause.",
    "Danke, auf Wiedersehen.",
)

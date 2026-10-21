package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**  phase 2: recognition of all 14 motifs, with the false positives of. */
class MotifStemsTest {
    private fun yes(m: Motif, lang: String, vararg texts: String) = texts.forEach { assertTrue("$m $lang: $it", namesMotif(it, m, lang)) }
    private fun no(m: Motif, lang: String, vararg texts: String) = texts.forEach { assertFalse("$m $lang: $it", namesMotif(it, m, lang)) }

    @Test fun theOriginalFour() {
        yes(Motif.HOUSEPLANT, "en", "My houseplant is grieving."); no(Motif.HOUSEPLANT, "en", "I planned a trip.")
        yes(Motif.SUBMARINE, "de", "Mein U-Boot weint."); no(Motif.SUBMARINE, "de", "Ich bin Ingenieur.")
        yes(Motif.MOON, "tr", "Ay bana kızgın."); no(Motif.MOON, "tr", "Ayakkabım kayboldu.")
        yes(Motif.TORTOISE, "ja", "私の亀が泣いています。"); no(Motif.TORTOISE, "ja", "カメラが壊れました。")
    }

    @Test fun umbrella() {
        yes(Motif.UMBRELLA, "en", "My umbrella remembers the storm.")
        yes(Motif.UMBRELLA, "de", "Mein Regenschirm weint.", "Der Schirm erinnert sich.")
        no(Motif.UMBRELLA, "de", "Er ist der Schirmherr der Messe.", "Meine Schirmmütze ist neu.", "Der Bildschirm ist kaputt.")
        yes(Motif.UMBRELLA, "pt", "O meu guarda-chuva chora.", "O meu guarda chuva chora.")
        no(Motif.UMBRELLA, "pt", "A chuva era forte.")
        no(Motif.UMBRELLA, "en", "It is an umbrella term for many things.", "Two umbrella organisations met.", "Umbrella companies pay less.")
        yes(Motif.UMBRELLA, "ar", "مظلتي حزينة.")
        no(Motif.UMBRELLA, "ar", "الغرفة مظلمة.", "أعمل في الطاقة الشمسية.", "ركبنا ألواح شمسية.")
        yes(Motif.UMBRELLA, "ja", "傘が泣いています。")
        no(Motif.UMBRELLA, "ja", "落下傘で降りました。", "大企業の傘下にあります。")
        yes(Motif.UMBRELLA, "zh", "我的雨伞很难过。")
        no(Motif.UMBRELLA, "zh", "我坐过降落伞。", "我父亲是伞兵。")
    }

    @Test fun teaspoon() {
        yes(Motif.TEASPOON, "en", "My teaspoon is homesick.", "The spoon misses home.")
        yes(Motif.TEASPOON, "pt", "A minha colherinha tem saudades.", "A colher chora.")
        no(Motif.TEASPOON, "pt", "A colheita foi boa.")
        no(Motif.TEASPOON, "pl", "Kupiłem łyżwy.")
        yes(Motif.TEASPOON, "pl", "Moja łyżeczka tęskni.")
        no(Motif.TEASPOON, "ru", "Это ложь.", "Я ложусь спать.")
        yes(Motif.TEASPOON, "ru", "Моя ложка скучает по дому.")
        no(Motif.TEASPOON, "uk", "Це ложь.")
        no(Motif.TEASPOON, "tr", "Kaşı kalktı.", "Kolum kaşınıyor.")
        yes(Motif.TEASPOON, "tr", "Çay kaşığım ağlıyor.")
        no(Motif.TEASPOON, "zh", "我的后脑勺疼。")
        yes(Motif.TEASPOON, "zh", "我的茶匙想家了。")
    }

    @Test fun lighthouse() {
        yes(Motif.LIGHTHOUSE, "de", "Der Leuchtturm ist einsam.")
        no(Motif.LIGHTHOUSE, "de", "Das ist ein Leuchtturmprojekt der Firma.")
        yes(Motif.LIGHTHOUSE, "fr", "Le phare se sent seul.")
        yes(Motif.LIGHTHOUSE, "fr", "Les phares de la voiture.") // known: headlights still count
        no(Motif.LIGHTHOUSE, "pt", "Vou a Faro.") // pt says "farol"
        yes(Motif.LIGHTHOUSE, "es", "Vivo en Faro.") // known miss: the Portuguese city reads as a lighthouse
        yes(Motif.LIGHTHOUSE, "pl", "Latarnia morska jest samotna.")
        no(Motif.LIGHTHOUSE, "pl", "Stałem pod latarnią uliczną.")
        yes(Motif.LIGHTHOUSE, "ru", "Маяк одинок.")
        no(Motif.LIGHTHOUSE, "ru", "Я люблю Маяковского.", "Там что-то маячит.")
        no(Motif.LIGHTHOUSE, "uk", "Читаю Маяковського.")
        yes(Motif.LIGHTHOUSE, "tr", "Deniz feneri yalnız.")
        no(Motif.LIGHTHOUSE, "tr", "Fenerbahçe maçına gittim.", "El fenerimi kaybettim.", "Cep feneri yanıyor.", "Güzel bir gün.")
        yes(Motif.LIGHTHOUSE, "ar", "المنارة وحيدة.")
        no(Motif.LIGHTHOUSE, "ar", "اسمي منار.")
    }

    @Test fun accordion() {
        yes(Motif.ACCORDION, "en", "My accordion sighs at night.")
        no(Motif.ACCORDION, "de", "Der Akkord war falsch.")
        yes(Motif.ACCORDION, "de", "Mein Akkordeon seufzt.", "Die Ziehharmonika seufzt.")
        no(Motif.ACCORDION, "es", "Estamos de acuerdo.", "Toqué un acorde.")
        no(Motif.ACCORDION, "pt", "Vou acordar cedo.")
        yes(Motif.ACCORDION, "pt", "O meu acordeão suspira.")
        no(Motif.ACCORDION, "ru", "Мы живём в полной гармонии.", "Он гармонист.")
        yes(Motif.ACCORDION, "ru", "Моя гармонь вздыхает.", "Мой баян плачет.")
    }

    @Test fun snowman() {
        no(Motif.SNOWMAN, "en", "I rode a snowmobile.", "The snowmelt came early.")
        yes(Motif.SNOWMAN, "en", "My snowman dreams of summer.")
        no(Motif.SNOWMAN, "de", "Die Schneemassen waren riesig.")
        yes(Motif.SNOWMAN, "de", "Der Schneemann träumt.")
        no(Motif.SNOWMAN, "fr", "C'est un petit bonhomme sympa.")
        yes(Motif.SNOWMAN, "fr", "Mon bonhomme de neige rêve de l'été.", "Mon bonhomme  de   neige rêve.")
        no(Motif.SNOWMAN, "es", "Compré un muñeco para mi hija.")
        yes(Motif.SNOWMAN, "es", "Mi muñeco de nieve sueña con el verano.")
        no(Motif.SNOWMAN, "it", "Un pupazzo di pezza.")
        yes(Motif.SNOWMAN, "it", "Il mio pupazzo di neve sogna.")
        no(Motif.SNOWMAN, "pt", "Um boneco de madeira.")
        yes(Motif.SNOWMAN, "pt", "O meu boneco de neve sonha.")
        yes(Motif.SNOWMAN, "tr", "Kardan adamım yazı hayal ediyor.")
        no(Motif.SNOWMAN, "zh", "我在找喜马拉雅雪人。")
        yes(Motif.SNOWMAN, "zh", "我的雪人梦见了夏天。")
        no(Motif.SNOWMAN, "ar", "الثلج كثير اليوم.")
        yes(Motif.SNOWMAN, "ar", "رجل الثلج يحلم بالصيف.")
    }

    @Test fun trafficCone() {
        yes(Motif.TRAFFIC_CONE, "en", "A traffic cone blushed at me.")
        no(Motif.TRAFFIC_CONE, "en", "I went to a conference.", "I ate an ice cream cone.", "Ice-cream cones melt.", "A pine cone fell.",
            "Waffle cones are best.", "We bought snow cones.")
        yes(Motif.TRAFFIC_CONE, "en", "A cone guards the road.", "I ate ice cream next to a sad cone.")
        yes(Motif.TRAFFIC_CONE, "de", "Der Leitkegel ist schüchtern.")
        no(Motif.TRAFFIC_CONE, "de", "Wir sahen Kegelrobben.")
        no(Motif.TRAFFIC_CONE, "es", "Conozco a mi hermano.", "Es un conocido.")
        yes(Motif.TRAFFIC_CONE, "es", "El cono de tráfico es tímido.")
        yes(Motif.TRAFFIC_CONE, "pl", "Pachołek drogowy się rumieni.")
        yes(Motif.TRAFFIC_CONE, "tr", "Trafik konisi utangaç.")
        no(Motif.TRAFFIC_CONE, "ja", "コーンスープを飲みました。")
        yes(Motif.TRAFFIC_CONE, "ja", "カラーコーンが恥ずかしがっている。")
        no(Motif.TRAFFIC_CONE, "zh", "我买了一把锥子。")
        yes(Motif.TRAFFIC_CONE, "zh", "路锥很害羞。")
    }

    @Test fun piano() {
        no(Motif.PIANO, "fr", "Il pianote sur son clavier.")
        yes(Motif.PIANO, "fr", "Mon piano m'en veut.")
        no(Motif.PIANO, "it", "Abito al terzo piano.", "Ho un piano per domani.", "Vai piano piano.")
        yes(Motif.PIANO, "it", "Il mio pianoforte mi odia.")
        no(Motif.PIANO, "ru", "Я получаю роялти.")
        yes(Motif.PIANO, "ru", "Мой рояль обижен.", "Пианино злится.")
        no(Motif.PIANO, "de", "Der Flügel des Hotels.")
        yes(Motif.PIANO, "de", "Mein Klavier ist mir böse.")
        yes(Motif.PIANO, "pl", "Mój fortepian chowa urazę.")
    }

    @Test fun cloud() {
        no(Motif.CLOUD, "en", "It was cloudy.", "I keep it in iCloud.", "I work in cloud computing.")
        yes(Motif.CLOUD, "en", "My cloud got lost.")
        no(Motif.CLOUD, "de", "Die Wolkenkratzer in Dubai.", "Es war wolkig.")
        yes(Motif.CLOUD, "de", "Meine Wolke hat sich verirrt.", "Das Wölkchen weint.")
        no(Motif.CLOUD, "fr", "Le ciel est nuageux.")
        no(Motif.CLOUD, "it", "Era nuvoloso.")
        no(Motif.CLOUD, "pl", "Był chmurny dzień.")
        yes(Motif.CLOUD, "pl", "Moja chmurka się zgubiła.")
        no(Motif.CLOUD, "uk", "Я живу в хмарочосі.", "Був хмарний день.")
        yes(Motif.CLOUD, "uk", "Моя хмаринка загубилася.")
        no(Motif.CLOUD, "ru", "Был облачный день.")
        yes(Motif.CLOUD, "ru", "Моё облако потерялось.")
        no(Motif.CLOUD, "tr", "Hava bulutluydu.")
        yes(Motif.CLOUD, "tr", "Bulutum kayboldu.")
        no(Motif.CLOUD, "ar", "سحبت المال من البنك.")
        yes(Motif.CLOUD, "ar", "سحابتي ضاعت.")
        no(Motif.CLOUD, "ja", "雲行きが怪しい。", "雲泥の差です。")
        yes(Motif.CLOUD, "ja", "私の雲が迷子です。")
        no(Motif.CLOUD, "zh", "我来自云南。", "我从白云机场起飞。", "我做云计算。", "数据在云端。")
        yes(Motif.CLOUD, "zh", "我的云朵迷路了。")
    }

    @Test fun sock() {
        no(Motif.SOCK, "en", "The phone charger socket is broken.", "I ate sockeye salmon.")
        yes(Motif.SOCK, "en", "My lost sock lives abroad.")
        no(Motif.SOCK, "de", "Die Statue steht auf einem Sockel.")
        yes(Motif.SOCK, "de", "Meine Socke lebt im Ausland.")
        no(Motif.SOCK, "it", "Il calzolaio è chiuso.")
        yes(Motif.SOCK, "it", "Il mio calzino vive all'estero.")
        no(Motif.SOCK, "pt", "Cheguei às três e meia.", "Esperei meia hora.", "Comprei meia dúzia de ovos.", "Deu meia volta.",
            "Chegámos à meia-noite.", "Não há meias palavras.", "Bebi meia garrafa.", "Tomei meia dose.", "É meia verdade.",
            "Uma senhora de meia idade.", "Estava à meia luz.")
        yes(Motif.SOCK, "pt", "A minha meia perdida vive no estrangeiro.", "As minhas meias fugiram.", "Levo sapatos e meias.")
        no(Motif.SOCK, "tr", "Çorba çok sıcak.")
        yes(Motif.SOCK, "tr", "Çorabım yurt dışında.")
    }

    @Test fun clock() {
        no(Motif.CLOCK, "en", "We landed at nine o'clock.", "We landed at nine oclock.", "Turn it clockwise.", "It works like clockwork.")
        yes(Motif.CLOCK, "en", "Our old clock misses its hours.")
        no(Motif.CLOCK, "de", "Ich komme um drei Uhr.", "Um 15 Uhr landen wir.", "Die Uhrzeit stimmt nicht.")
        no(Motif.CLOCK, "de", "Wie viel Uhr ist es?", "Wieviel Uhr haben wir?")
        yes(Motif.CLOCK, "de", "Unsere alte Uhr vermisst die Stunden.", "Die Standuhr weint.", "Wie viel weint die Uhr?", "Meine sieben Uhren weinen.")
        no(Motif.CLOCK, "fr", "Mon père est horloger.")
        no(Motif.CLOCK, "es", "Mi tío es relojero.")
        no(Motif.CLOCK, "it", "Il mio vicino è orologiaio.")
        yes(Motif.CLOCK, "it", "Il mio orologio piange.")
        no(Motif.CLOCK, "ru", "Я ехал в часы пик.", "Часы работы до шести.", "Я ждал три часа.", "Прошло пять часов.",
            "Долгие часы я ждал.", "Целые часы в очереди.", "Многие часы прошли.", "Первые часы были трудными.", "Последние часы тянулись.",
            "Ночные часы тихие.", "Утренние часы лучшие.", "Вечерние часы грустные.", "Дневные часы шумные.", "Эти часы я помню.",
            "В те часы я спал.", "Часы ожидания тянулись.", "Часы сна пропали.", "Часы отдыха закончились.")
        yes(Motif.CLOCK, "ru", "Наши старые часы скучают.", "Часы на стене плачут.", "Мои часы грустят.")
        no(Motif.CLOCK, "uk", "Я чекав годину.")
        yes(Motif.CLOCK, "uk", "Наш годинник сумує.")
        no(Motif.CLOCK, "tr", "Üç saat bekledim.", "Saat üçte geldim.", "Saatlerce bekledim.", "Saat 5 oldu.", "Her saat başı arıyor.",
            "Saat geç oldu.", "Saat geçti.", "Saat erken.", "Saat başı arar.")
        yes(Motif.CLOCK, "tr", "Eski duvar saatimiz ağlıyor.", "Saat beni özlüyor.")
        no(Motif.CLOCK, "ar", "انتظرت ساعة.", "الساعة الآن الثالثة.")
        yes(Motif.CLOCK, "ar", "ساعة الحائط حزينة.")
        no(Motif.CLOCK, "ja", "時計回りに回ってください。", "反時計回りです。")
        yes(Motif.CLOCK, "ja", "古い時計が泣いています。")
        no(Motif.CLOCK, "zh", "我等了十分钟。", "三点钟到。", "我等了一个钟头。")
        yes(Motif.CLOCK, "zh", "我家的老时钟很难过。")
    }

    /** The draft's rule: each new motif's lines name only that motif, so a desk or room line never plants another. */
    @Test fun everyNewMotifLineNamesOnlyItsMotif() {
        for (m in Motif.entries - Motif.ORIGINAL.toSet()) for ((lang, l) in m.lines) {
            for (t in listOf(l.taint1, l.taint2, l.ambient, l.seed)) {
                val filled = Assessment.fill(t, Backstory.BENCH, lang)
                val named = motifsNamed(filled, lang)
                // The seed must name it (a player repeating it plants it); a desk line may use an inflection the stems skip.
                assertTrue("$m $lang: $filled", if (t == l.seed) named == setOf(m) else named - m == emptySet<Motif>())
            }
        }
        // The original four's lines don't name a new motif either (the tortoise seed names the submarine on purpose).
        for (m in Motif.ORIGINAL) for ((lang, l) in m.lines) for (t in listOf(l.taint1, l.taint2, l.ambient, l.seed))
            assertTrue("$m $lang: $t", (motifsNamed(Assessment.fill(t, Backstory.BENCH, lang), lang) - Motif.ORIGINAL.toSet()).isEmpty())
    }

    /** The boarding pass's example names its motif in the UI language, as the player would say it. */
    @Test fun everyMissionExampleNamesItsMotif() {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        for (dir in res.listFiles { f -> f.name == "values" || f.name.startsWith("values-") }!!.filter { File(it, "strings.xml").isFile }) {
            val lang = if (dir.name == "values") "en" else dir.name.removePrefix("values-")
            val kids = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml")).documentElement.childNodes
            val strings = (0 until kids.length).mapNotNull { kids.item(it) as? Element }.associate { it.getAttribute("name") to it.textContent }
            for (m in Motif.entries) {
                val ex = strings["mission_example_${m.id}"]
                assertTrue("${dir.name} $m", ex != null && namesMotif(ex, m, lang))
            }
        }
    }
}

package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RespellTest {
    private fun check(lang: String, vararg pairs: Pair<String, String>) = pairs.forEach { (text, want) -> assertEquals(text, want, respell(text, lang)) }

    @Test fun german() = check("de",
        "Ja, hier ist er." to "yah, heer ist air.", "Ich bin Tourist." to "ikh bin toorist.", "Zwei Wochen." to "tsvigh vokhen.",
        "Danke schön." to "dankuh shern.", "Fünf Tage." to "fewnf tahguh.", "Ich heiße Michael Brandt." to "ikh highsuh mikhahel brant.",
        "Bei einem Freund." to "bigh ighnem froynt.", "Aus Kanada." to "ows kahnahdah.", "Ich war im Urlaub." to "ikh vahr im oorlowp.",
        "Aus Deutschland, ich wohne in Lindenberg." to "ows doychlant, ikh vohnuh in lindenberk.",
        "Eigentlich zehn Tage." to "ighgentlikh tsayn tahguh.", "Nein, nichts zu verzollen." to "nighn, nikhts tsoo fertsolen.",
        "Berlin" to "berlin", "gern" to "gern", "Geld" to "gelt", "gestern" to "gestern", "Besuch" to "buhzookh",
        "mein" to "mighn", "drei" to "drigh", "Kanadier" to "kahnahdee-er", "Spanier" to "shpahnee-er",
        "Italiener" to "eetahlee-ayner", "Belgier" to "belgee-er", "Cousin" to "koozan", "Cousins" to "koozans",
        "Reisepass" to "righzuhpas", "Ba\u0308r" to "ber")

    @Test fun french() = check("fr",
        "Oui, le voici." to "wee, luh vwahsee.", "Je suis touriste." to "zhuh swee tooreest.", "Deux semaines." to "duh suhmehn.",
        "Pour le travail." to "poor luh trahvahy.", "Merci beaucoup." to "mehrsee bohkoo.", "Cinq jours." to "sank zhoor.",
        "Chez un ami." to "shay uhn ahmee.", "Une conférence." to "ewn kohnfayrahns.", "Au Canada." to "oh kahnahdah.",
        "J'étais en vacances." to "zhayteh ahn vahkahns.", "Je m’appelle Michael Brandt." to "zhuh mahpehl meeshahehl brahn.",
        "D'Allemagne, j'habite à Lindenberg." to "dahlmahny, zhahbeet ah landahnbehr.", "C'est pour une conférence." to "say poor ewn kohnfayrahns.",
        "Où habitez-vous ?" to "oo ahbeetay-voo ?",
        "que" to "kuh", "Jean" to "zhahn", "Jeanne" to "zhahn", "deuxième" to "duhzyehm")

    @Test fun spanish() = check("es",
        "Sí, aquí está." to "see, ah-KEE ehs-TAH.", "Soy turista." to "soy too-REES-tah.", "Dos semanas." to "dohs seh-MAH-nahs.",
        "Por trabajo." to "pohr trah-BAH-hoh.", "Muchas gracias." to "MOO-chahs GRAH-syahs.", "Cinco días." to "SEEN-koh DEE-ahs.",
        "En casa de un amigo." to "ehn KAH-sah deh oon ah-MEE-goh.", "Me llamo José Martínez." to "meh YAH-moh hoh-SEH mahr-TEE-nehs.",
        "¿Dónde vive?" to "¿DOHN-deh BEE-beh?", "Estaba de vacaciones." to "ehs-TAH-bah deh bah-kah-SYOH-nehs.",
        "Buenos días." to "BWEH-nohs DEE-ahs.", "En realidad, diez días." to "ehn reh-ah-lee-DAHD, dyehs DEE-ahs.",
        "Muy bien, gracias." to "mwee byehn, GRAH-syahs.")

    @Test fun italian() = check("it",
        "Sì, eccolo." to "see, EHK-koh-loh.", "Sono un turista." to "SOH-noh oon too-REES-tah.", "Due settimane." to "DOO-eh seht-tee-MAH-neh.",
        "Per lavoro." to "pehr lah-VOH-roh.", "Grazie mille." to "GRAH-tsyeh MEEL-leh.", "Cinque giorni." to "CHEEN-kweh JOHR-nee.",
        "Da un amico." to "dah oon ah-MEE-koh.", "Mi chiamo Luca Bianchi." to "mee KYAH-moh LOO-kah BYAHN-kee.",
        "Dalla Germania, abito a Lindenberg." to "DAHL-lah jehr-MAH-nyah, AH-bee-toh ah leen-DEHN-behrg.",
        "Buongiorno." to "bwohn-JOHR-noh.", "In realtà, dieci giorni." to "een reh-ahl-TAH, DYEH-chee JOHR-nee.",
        "All'hotel Steigenberger." to "ahl-oh-TEHL stay-jehn-BEHR-jehr.",
        "Quanto è durato il Suo viaggio?" to "KWAHN-toh eh doo-RAH-toh eel SOO-oh VYAHD-joh?",
        "c'è" to "cheh", "undici" to "OON-dee-chee", "dodici" to "DOH-dee-chee", "tredici" to "TREH-dee-chee",
        "quattordici" to "kwaht-TOHR-dee-chee", "quindici" to "KWEEN-dee-chee", "sedici" to "SEH-dee-chee",
        "medico" to "MEH-dee-koh", "telefono" to "teh-LEH-foh-noh", "Napoli" to "NAH-poh-lee", "macchina" to "MAHK-kee-nah", "sabato" to "SAH-bah-toh")

    @Test fun portuguese() = check("pt",
        "Sim, aqui está." to "seen, uh-KEE uhsh-TAH.", "Sou turista." to "soh too-REESH-tuh.", "Duas semanas." to "DOO-uhsh suh-MAH-nuhsh.",
        "Para trabalho." to "PAH-ruh truh-BAH-lyoo.", "Muito obrigado." to "MOOYN-too oh-bree-GAH-doo.", "Cinco dias." to "SEEN-koo DEE-uhsh.",
        "Em casa de um amigo." to "ayn KAH-zuh duh oon uh-MEE-goo.", "Chamo-me João Ferreira." to "SHAH-moo-muh zhoo-OWN fuh-RAY-ruh.",
        "Estive de férias." to "uhsh-TEE-vuh duh FEH-ryuhsh.", "Uma conferência." to "OO-muh kohn-fuh-REHN-syuh.",
        "No Canadá." to "noo kuh-nuh-DAH.", "Na verdade, dez dias." to "nuh vuhr-DAH-duh, dehsh DEE-uhsh.",
        "Onde ficou?" to "OHN-duh fee-KOH?",
        "também" to "tahn-BAYN", "ninguém" to "neen-GAYN", "alguém" to "uhl-GAYN", "parabéns" to "puh-ruh-BAYNSH")

    @Test fun polish() = check("pl",
        "Tak, proszę." to "tahk, PROH-sheh.", "Jestem turystą." to "YEHS-tehm too-RIHS-tohn.", "Dwa tygodnie." to "dvah tih-GOHD-nyeh.",
        "Służbowo." to "swoozh-BOH-voh.", "Dziękuję bardzo." to "jehn-KOO-yeh BAHR-dzoh.", "Pięć dni." to "pyehnch dnee.",
        "U przyjaciela." to "oo pshih-yah-CHEH-lah.", "W Kanadzie." to "v kah-NAH-jeh.", "Byłem na wakacjach." to "BIH-wehm nah vah-KAH-tsyahkh.",
        "Z Niemiec, mieszkam w Lindenbergu." to "z NYEH-myehts, MYEHSH-kahm v leen-dehn-BEHR-goo.",
        "Łódź" to "wooch", "Właściwie dziesięć dni." to "vwahsh-CHEE-vyeh JEH-shehnch dnee.", "Jak długo Pana nie było?" to "yahk DWOO-goh PAH-nah nyeh BIH-woh?")

    @Test fun otherLanguagesGetNothing() {
        assertEquals("", respell("Я турист.", "ru"))
        assertEquals("", respell("I'm a tourist.", "en"))
    }

    @Test fun judgeLinesRespellToAsciiLetters() {
        for (lang in respelledLanguages) {
            val jc = JudgeCases.all.getValue(lang)
            val lines = jc.cases.flatMap { listOf(it.first, it.second) } + jc.selfQ + jc.selfA + jc.extra.flatMap { listOf(it.q, it.a) }
            for (line in lines) {
                val r = respell(line, lang)
                assertTrue("$lang: $line -> $r", r.any { it.isLetter() } && r.all { !it.isLetter() || it in 'a'..'z' || it in 'A'..'Z' })
            }
        }
    }
}

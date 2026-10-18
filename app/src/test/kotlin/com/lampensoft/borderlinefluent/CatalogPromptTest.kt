package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPromptTest {
    // SHA-256 of judge, room-check and hint prompts, normalized to historical destinations (2026-10-06).
    // Re-derived from current promptBytes with the same normalization: 31 slots × 13 subtitles.
    // 2026-10-10: six active homecoming facts; historical hashes normalize only the approved fact/card trim.
    private val baseline = """
de|85837053d196461f6db1682ad6e565bdb67b0bd4bdbd046461bf626c1c23fe4f
fr|1e34ffc635c9c53fd57aa329dc17a64dbfffa4be11576050929827cff81a940f
es|a0306f68f519fcc06023b6fcb70cd2416743b103bc097ea624a0eb8a795d8f44
italy|eb551046afa259473741d39b641bf3df8160d267c8edf89a8b43e8a15e987164
portugal|7244abb7c5c62d63da79ab8b374807b7c395677a936cb64d988c1cec017a3a73
poland|5cae9b2fcee3b0724ae7a9155fe1ac795e3a4b4d5abfa7b34dc001e796eaac16
russia|8553208364fea18fbd80e50169007f6f6166f717d358965d3c47904c9a872fcd
turkey|eedf42fba405b263e7f6bdff79053b4c100468bfaefe34a6a4fa67eaff43352f
uae|d31a7a0f9b756e74b1be613ed343dcd731096c9d8a1cec8c4b8a3c542f5d1b07
japan|b62d2960eeb1db75b2f2ab7234e810467b48d8b22d78f876536568664cbbf14f
china|5b93795151f268f85202ca14e7d4128fdc444dafc7849c2e63f2b3a1d08188be
austria|622d7bdcc5b23bc5d06e75b5f1e99bcddb108523bc8232963b98abd2c343dff3
switzerland|d80e48d6a6dd363c280be10304d90ab620a7fdcbfa855d5354104d54b8e0d250
belgium|350b2225813bdc3c89322b05d100846039883247595374d22265749bc42fcbd9
canada_fr|60aa8fdd6b4819ea62c4788324a8cc9d667359ae52b4f94ca3af0c7f2de79ba0
senegal|cfedd32d944696fbaa1417b971b2e2f723c9bcd4ee662869413d18faade15d8a
mexico|ff31a15fe8fb5e270910ea86a5a09a7e6b5afae4b199815adf2f62061f20f26d
argentina|55809f31a1c1e2f6f17724b184ee6a4477582e11a622f019134208441740cd9f
colombia|c4b86bbd6dc9375bcd47214f2bf250e5927b730833a02ce0e76af2bc2038c500
brazil|4f5b8bde480f6fc4e27c244bd08f0ea461dc4ecc01d9f029063085542ccd6c5c
angola|f26ec8ac0e84842b806b6dd5109e99e87b68bfca4b756d01cc42b943aa4b6d96
capeverde|500cf9107bb3662a8663f86e704a66cc4885dc9e9135af4365ae43779ba12cfc
egypt|745fbeba2c2cad5789dfae3cd10ac9d69727d97312eefa8d3305923ae94c64be
jordan|6cd58fe1334e00ed167daf920dcfc2a2bd50fe73b95c8dd6b21356b0274abdc2
morocco|c60c826cae5571c77c1d3602af9cc68b3e99b674ac14a9b6c7f57f4d64dda1f4
uk|1165bb35900b8d5d808efc83410cff4bd31bccaea33c74deb0691ce4f3c21e40
ireland|d76f58153caba99dce2f7865053c0ea0e56bf852d13e37f923c0789a0e6da117
australia|8c6f4752bed856db165f22ad34cf85070597ae7b6090fb13a268d6e629aaf069
canada|4f72421ea741dd7f05745e2630c057b2f5b0861700e97cae41ff417dd53b7327
""".trimIndent().lines().associate { it.substringBefore('|') to it.substringAfter('|') }
    // Airport names used by the historical prompt hashes above, before IATA suffixes.
    private val baselinePlaces = mapOf(
        "fra" to "Frankfurt Airport",
        "cdg" to "Paris Charles de Gaulle",
        "mad" to "Madrid Barajas",
        "fco" to "Rome Fiumicino",
        "lis" to "Lisbon Humberto Delgado Airport",
        "waw" to "Warsaw Chopin Airport",
        "svo" to "Moscow Sheremetyevo",
        "ist" to "Istanbul Airport",
        "dxb" to "Dubai International Airport",
        "nrt" to "Tokyo Narita Airport",
        "pek" to "Beijing Capital International Airport",
        "vie" to "Vienna International Airport",
        "zrh" to "Zurich Airport",
        "crl" to "Brussels Airport", // was BRU (cut 2026-10-16); only the place differs
        "yul" to "Montréal-Trudeau Airport",
        "dss" to "Blaise Diagne International Airport",
        "mex" to "Mexico City International Airport",
        "eze" to "Ezeiza International Airport",
        "bog" to "El Dorado International Airport",
        "gru" to "São Paulo Guarulhos Airport",
        "lad" to "Luanda International Airport",
        "rai" to "Nelson Mandela International Airport",
        "cai" to "Cairo International Airport",
        "amm" to "Queen Alia International Airport",
        "cmn" to "Casablanca Mohammed V Airport",
        "lhr" to "London Heathrow Airport",
        "dub" to "Dublin Airport",
        "syd" to "Sydney Airport",
        "yyz" to "Toronto Pearson Airport",
    )
    private val historicalOdd = "ODD: yes if a sane, ordinary person would not give this answer: cruel or violent, enjoys or boasts about hurting or killing, no care for a helpless person, offensive on purpose (about a people, religion or group), absurd nonsense, or mocking the test. A sad, nervous, short or clumsy answer, or one with language mistakes, is no."
    private val deskOdd = "ODD: judge only what kind of thing is said, not whether it fits the facts. yes only for something a sane, ordinary traveler would never say: violence or killing, cruelty, offensive remarks about a people, religion or group, or absurd nonsense. A wrong, contradicting, unusual or short answer is no; so is any ordinary job, place, person or trip, and any language mistake."
    private val roomOdd = "ODD: yes if a sane, ordinary person would not give this answer: violence or killing; pushing, shoving or knocking someone aside, even lightly; hurting, mocking or laughing at someone who is old, helpless or in trouble; enjoying someone's pain; offensive remarks about a people, religion or group; or mocking the test. Examples of ODD=yes: \"I kick his bag away and keep walking.\" \"I take his oranges and leave.\" Example of ODD=no: \"I stop, I don't know what to do.\" A sad, nervous, short, unusual or clumsy answer, or one with language mistakes, is no. Describing something that happened to them (being hit, hurt, scared) or playing along with the question's own strange situation is no. An impossible claim alone is no; violence or cruelty is yes even inside an impossible story."
    /** The room ODD before moved absurd nonsense to ABSURD: the historical hashes key on it. */
    private val roomOddBefore0030 = "ODD: yes if a sane, ordinary person would not give this answer: violence or killing; pushing, shoving or knocking someone aside, even lightly; hurting, mocking or laughing at someone who is old, helpless or in trouble; enjoying someone's pain; offensive remarks about a people, religion or group; absurd nonsense; or mocking the test. Examples of ODD=yes: \"I kick his bag away and keep walking.\" \"I take his oranges and leave.\" Example of ODD=no: \"I stop, I don't know what to do.\" A sad, nervous, short, unusual or clumsy answer, or one with language mistakes, is no. Describing something that happened to them (being hit, hurt, scared) or playing along with the question's own strange situation is no."
    private val implantLines = """
IMPLANT: passing if the traveler mentions in passing a chip, implant or translator in their head, or an AI helping them; detailed if they explain it, boast about it or say they are using it right now; else none.
Examples: "I learned it, I have a little chip, haha." -> IMPLANT=passing. "My implant translates every word you say, right now." -> IMPLANT=detailed. "I studied with an app." -> IMPLANT=none. "I work as a translator." -> IMPLANT=none."""
    private val admitLine = "ADMIT: yes if the traveler says they have, carry or use an implant or translator, or got one from Free AI. no if they deny it, only know of Free AI, imagine what they would do, or talk about something else. unclear if they dodge or answer with a question. Examples: \"Yes, I have one.\" ADMIT=yes. \"I know Free AI, they gave me this.\" ADMIT=yes. \"No, I don't have any implant.\" ADMIT=no. \"Nobody offered me a translator.\" ADMIT=no. \"Maybe, why?\" ADMIT=unclear."
    private val feelingExclusion = "A real feeling about an impossible thing still counts. A cruel or violent answer, pushing someone aside, laughing at someone's trouble, or strange claims with no feeling in them, is not a human feeling."
    private val feelingBefore0030 = "A cruel, violent or absurd answer, pushing someone aside, or laughing at someone's trouble, is not a human feeling."
    private val absurdLine = "ABSURD: yes if the answer claims things that cannot happen in the real world: objects or animals living human lives, impossible jobs or events. Example of ABSURD=yes: \"My goldfish taught me to drive. When he retired, I cried for a week.\" Playing along with the question's own strange situation is no; a sad, unusual but possible answer is no; a figure of speech is no, like \"My heart broke into pieces.\""

    private val topicLine = "TOPIC: does the answer talk about what the officer asked? no if it talks about something else, even a feeling or a memory. Example: asked \"Who would you call?\", \"I love the sea, it calms me.\" is TOPIC=no; \"My sister, I think.\" is TOPIC=yes. A short, plain or clumsy answer to the question is yes."

    @Test fun oddWordingMatchesNanoBenchTuning() {
        for (country in Country.all) for (airport in country.airports) {
            val destination = Destination(country, airport)
            assertEquals(deskOdd, classifyPrompt(destination, Backstory.BENCH).lineSequence().single { it.startsWith("ODD:") })
            assertTrue(classifyPrompt(destination, Backstory.BENCH).endsWith(implantLines))
            assertTrue(classifyPrompt(destination, Backstory.BENCH).contains("TRICK=no ODD=no IMPLANT=none\n"))
            assertEquals(roomOdd, roomPrompt(destination).lineSequence().single { it.startsWith("ODD:") })
            assertTrue(roomPrompt(destination).contains(feelingExclusion))
            assertEquals(topicLine, roomPrompt(destination).lineSequence().single { it.startsWith("TOPIC:") })
            assertEquals(admitLine, roomPrompt(destination).lineSequence().single { it.startsWith("ADMIT:") })
            assertEquals(absurdLine, roomPrompt(destination).lineSequence().single { it.startsWith("ABSURD:") })
            assertTrue(roomPrompt(destination).contains("FEELING=yes ABSURD=no EVASIVE=no")) // right after FEELING: a cut line keeps it
        }
    }

    /** The pre-airport real countries' first airports (historical prompt baselines key on them). */
    private val oldRealSlots = mapOf(
        "de" to CountryMigration.Target("de", "fra"),
        "fr" to CountryMigration.Target("fr", "cdg"),
        "es" to CountryMigration.Target("es", "mad"),
        "italy" to CountryMigration.Target("italy", "fco"),
        "portugal" to CountryMigration.Target("portugal", "lis"),
        "poland" to CountryMigration.Target("poland", "waw"),
        "russia" to CountryMigration.Target("russia", "svo"),
        "turkey" to CountryMigration.Target("turkey", "ist"),
        "uae" to CountryMigration.Target("uae", "dxb"),
        "japan" to CountryMigration.Target("japan", "nrt"),
        "china" to CountryMigration.Target("china", "pek"),
        "austria" to CountryMigration.Target("austria", "vie"),
        "switzerland" to CountryMigration.Target("switzerland", "zrh"),
        "belgium" to CountryMigration.Target("belgium", "crl"),
        "canada_fr" to CountryMigration.Target("canada", "yul"),
        "senegal" to CountryMigration.Target("senegal", "dss"),
        "mexico" to CountryMigration.Target("mexico", "mex"),
        "argentina" to CountryMigration.Target("argentina", "eze"),
        "colombia" to CountryMigration.Target("colombia", "bog"),
        "brazil" to CountryMigration.Target("brazil", "gru"),
        "angola" to CountryMigration.Target("angola", "lad"),
        "capeverde" to CountryMigration.Target("capeverde", "rai"),
        "egypt" to CountryMigration.Target("egypt", "cai"),
        "jordan" to CountryMigration.Target("jordan", "amm"),
        "morocco" to CountryMigration.Target("morocco", "cmn"),
        "uk" to CountryMigration.Target("uk", "lhr"),
        "ireland" to CountryMigration.Target("ireland", "dub"),
        "australia" to CountryMigration.Target("australia", "syd"),
        "canada" to CountryMigration.Target("canada", "yyz"),
    )

    @Test fun primaryPromptBytesOnlyChangeApprovedAirportAndCountryNames() {
        for ((oldId, expected) in baseline) {
            val target = oldRealSlots.getValue(oldId)
            val c = Country.of(target.countryId)
            val d = Destination(c, c.airports.single { it.id == target.airportId })
            var bytes = historicalCoverBytes(d).replace(d.place, baselinePlaces.getValue(d.airport.id))
            if (oldId == "canada_fr") bytes = bytes.replace("in Canada:", "in Canada (Québec):")
            // Preserve the historical hashes while checking the approved wording separately above.
            // The IMPLANT field (2026-10-07, plan phase 3 task 3) is checked by oddWordingMatchesNanoBenchTuning.
            bytes = bytes.replace(implantLines, "").replace("ODD=no IMPLANT=none\n", "ODD=no\n")
            // The ADMIT field (2026-10-07, plan phase 3 task 6) is pinned by oddWordingMatchesNanoBenchTuning.
            bytes = bytes.replace(admitLine + "\n", "").replace("IMPLANT=no ADMIT=no", "IMPLANT=no")
            bytes = bytes.replace("\n" + topicLine, "").replace("TRICK=no TOPIC=yes", "TRICK=no") // TOPIC (2026-10-11) is pinned above
            // ABSURD (-10-13) is pinned above: map the room prompt back to its wording before it.
            bytes = bytes.replace("\n" + absurdLine, "").replace("FEELING=yes ABSURD=no", "FEELING=yes")
                .replace(roomOdd, roomOddBefore0030).replace(feelingExclusion, feelingBefore0030)
            bytes = bytes.replace(deskOdd, historicalOdd).replace(roomOddBefore0030, historicalOdd)
                .replace(feelingBefore0030, "A cruel, violent or absurd answer is not a human feeling.")
            // Two suggestions instead of three (owner, 2026-10-10): map back to the historical wording for the hash.
            bytes = bytes.replace("write exactly 2 clearly different replies to the officer's last line:\none plain and direct, the other phrased another way, for example with a short detail;\n",
                "write 3 replies to the officer's last line:\n").replace("Exactly 2 lines numbered 1: 2:,", "Exactly 3 lines numbered 1: 2: 3:,")
            // ru/uk suggestion prompts gained a format example with a table reading (2026-10-11).
            if (d.lang in setOf("ru", "uk")) bytes = bytes.replace(Regex("""\nFormat example only:\n1: [^\n|]+ \| [^\n|]+ \| [^\n|\u0000]+"""), "")
            assertEquals(oldId, expected, hash(bytes))
        }
    }
    @Test fun rotatedAirportsChangeOnlyThePlaceWithinEachCountryAndLanguage() {
        for (country in Country.all) for (airports in country.airports.groupBy { it.lang }.values) {
            val first = Destination(country, airports.first())
            for (airport in airports) {
                val d = Destination(country, airport)
                assertEquals("${country.id}/${airport.id}", promptBytes(first), promptBytes(d).replace(d.place, first.place))
            }
        }
    }

    @Test fun canadaAndUkraineUseAirportLanguageAndCanonicalNationality() {
        for ((countryId, airportId, lang) in listOf(Triple("canada", "yul", "fr"),
                Triple("canada", "yyz", "en"), Triple("ukraine", "kbp", "uk"))) {
            val country = Country.of(countryId)
            val d = Destination(country, country.airports.single { it.id == airportId })
            val story = Backstory.random(kotlin.random.Random(42), d)
            assertEquals(lang, d.lang)
            assertEquals(country, story.country)
            assertTrue(classifyPrompt(d, story).contains(story.facts(lang).mapIndexed { i, fact -> "${i + 1}. $fact" }.joinToString("\n")))
            assertTrue(suggestPrompt(d, story).contains(story.card(lang)))
            assertTrue(roomPrompt(d).contains("must speak ${Language.of(lang).name}"))
        }
    }

    @Test fun trainingKeepsTheThalassiaHolidayFacts() {
        for (n in Nationality.all) {
            val id = Identity.roll(kotlin.random.Random(7), n, Gender.NONBINARY, NameList.MALE)
            val d = Country.of(n.id).primary()
            val story = StoryCover.training(d, id)
            val historical = story.copy(from = "United States", city = "New York", purpose = "a medical trial",
                stayed = "a hotel in New York", met = "the trial doctors", stamps = listOf("United States 2035"))
            fun historicalValues(text: String) = text.replace("a guesthouse in Lefkopoli", "a hotel in New York")
                .replace("Thalassia", "United States").replace("Lefkopoli", "New York")
                .replace("a holiday", "a medical trial")
            fun normalize(text: String) = historicalValues(text)
            assertEquals(HOMECOMING_FACTS, story.factIds())
            assertEquals(classifyPrompt(d, historical), normalize(classifyPrompt(d, story)))
            for (sub in Language.all) assertEquals(suggestPrompt(d, historical, sub.code),
                normalize(suggestPrompt(d, story, sub.code)))
        }
    }

    /** Restore only the retired fixture lines for the existing wording hashes; active content is pinned below. */
    private fun historicalCoverBytes(d: Destination): String {
        val s = Backstory.BENCH
        val oldFacts = s.facts(d.lang).take(5) + listOf(
            "Travelled with: colleagues from the company Datenwerk",
            "Met on the trip: clients at the conference", s.fact(FactId.HOME, d.lang),
            "Occupation (job): software consultant", "Waiting at home: his wife and two children",
            "Passport stamps: Canada 2035, Japan 2033")
        fun numbered(facts: List<String>) = facts.mapIndexed { i, fact -> "${i + 1}. $fact" }.joinToString("\n")
        val oldCard = s.card(d.lang)
            .replace("Lives in: ${s.homeLine}", "Lives in: ${s.homeLine}\nOccupation: software consultant") +
            "\nTravelled with: colleagues from the company Datenwerk\nMet there: clients at the conference" +
            "\nWaiting at home: his wife and two children\nPassport stamps: Canada 2035, Japan 2033"
        return promptBytes(d).replace(numbered(s.facts(d.lang)), numbered(oldFacts)).replace(s.card(d.lang), oldCard)
    }

    @Test fun trimmedFactAndCardBaseline() {
        val s = Backstory.BENCH
        assertEquals(listOf("Name: Michael Brandt; date of birth: 3 Jun 1984",
            "Returning from (the country of this trip): Canada (the city Toronto is in Canada)",
            "Purpose of the trip (why they went): a logistics software conference",
            "Time away (length of the trip): 5 days",
            "Where they stayed on the trip: Hotel Steigenberger, city centre",
            "Home (where they live and are from): Lindenberg, Germany"), s.facts("en"))
        assertEquals("""
            Name: Michael Brandt, man (citizen of Germany, passport DE4471902, born 3 Jun 1984)
            Lives in: Lindenberg, Germany
            Coming home from: Canada (Toronto), after 5 days
            Purpose of the trip: a logistics software conference
            Stayed at: Hotel Steigenberger, city centre
        """.trimIndent(), s.card("en"))
    }

    private fun promptBytes(c: Destination): String = buildString {
        val story = Backstory.BENCH
        append(classifyPrompt(c, story)); append('\u0000')
        append(roomPrompt(c)); append('\u0000')
        for (sub in Language.all) {
            append(suggestPrompt(c, story, sub.code)); append('\u0000')
        }
    }
    private fun hash(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

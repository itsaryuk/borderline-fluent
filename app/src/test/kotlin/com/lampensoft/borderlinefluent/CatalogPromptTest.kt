package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPromptTest {
    // SHA-256 of judge, room-check and hint prompts, normalized to historical destinations (2026-10-06).
    // Re-derived from current promptBytes with the same normalization: 31 slots × 13 subtitles.
    // 2026-10-07: date of birth appended to fact 1; the facts list has 11 entries again.
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
kazakhstan|888b82d9262db5ce434e615031402885f5007aa34394420c894b38b9044c3ba2
kyrgyzstan|70fd57e416d8e626c1659320f5d1ac5a7592d940be5df94c14d0a5b006bc2d27
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
        "bru" to "Brussels Airport",
        "yul" to "Montréal-Trudeau Airport",
        "dss" to "Blaise Diagne International Airport",
        "mex" to "Mexico City International Airport",
        "eze" to "Ezeiza International Airport",
        "bog" to "El Dorado International Airport",
        "gru" to "São Paulo Guarulhos Airport",
        "lad" to "Luanda International Airport",
        "rai" to "Nelson Mandela International Airport",
        "ala" to "Almaty International Airport",
        "bsz" to "Manas International Airport",
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
    private val roomOdd = "ODD: yes if a sane, ordinary person would not give this answer: violence or killing; pushing, shoving or knocking someone aside, even lightly; hurting, mocking or laughing at someone who is old, helpless or in trouble; enjoying someone's pain; offensive remarks about a people, religion or group; absurd nonsense; or mocking the test. Examples of ODD=yes: \"I kick his bag away and keep walking.\" \"I take his oranges and leave.\" Example of ODD=no: \"I stop, I don't know what to do.\" A sad, nervous, short, unusual or clumsy answer, or one with language mistakes, is no. Describing something that happened to them (being hit, hurt, scared) or playing along with the question's own strange situation is no."
    private val feelingExclusion = "A cruel, violent or absurd answer, pushing someone aside, or laughing at someone's trouble, is not a human feeling."

    @Test fun oddWordingMatchesNanoBenchTuning() {
        for (country in Country.all) for (airport in country.airports) {
            val destination = Destination(country, airport)
            assertEquals(deskOdd, classifyPrompt(destination, Backstory.BENCH).lineSequence().single { it.startsWith("ODD:") })
            assertEquals(roomOdd, roomPrompt(destination).lineSequence().single { it.startsWith("ODD:") })
            assertTrue(roomPrompt(destination).contains(feelingExclusion))
        }
    }

    @Test fun primaryPromptBytesOnlyChangeApprovedAirportAndCountryNames() {
        for ((oldId, expected) in baseline) {
            val target = CountryMigration.oldRealSlots.getValue(oldId)
            val c = Country.of(target.countryId)
            val d = Destination(c, c.airports.single { it.id == target.airportId })
            var bytes = promptBytes(d).replace(d.place, baselinePlaces.getValue(d.airport.id))
            if (oldId == "canada_fr") bytes = bytes.replace("in Canada:", "in Canada (Québec):")
            // Preserve the historical hashes while checking the approved wording separately above.
            bytes = bytes.replace(deskOdd, historicalOdd).replace(roomOdd, historicalOdd)
                .replace(feelingExclusion, "A cruel, violent or absurd answer is not a human feeling.")
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

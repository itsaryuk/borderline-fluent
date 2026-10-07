package com.lampensoft.borderlinefluent

import org.json.JSONArray
import org.json.JSONObject

/** Frozen aliases and a pure, lossless migration of validated content-v1 history. */
object CountryMigration {
    const val ARCHIVE = "countryMergeArchiveV2"

    fun countryId(id: String): String = aliases[id]?.countryId ?: id

    fun migrate(s: CareerSnapshot): CareerSnapshot {
        require(s.contentVersion in 1..3)
        val canonical = if (s.contentVersion == 1) migrateCountries(s) else s
        // Seed only after aliases collapse: old marks cannot establish repeat clears.
        val clears = passedCountries(canonical).associateWith { 1 } + canonical.album.clears
        return canonical.copy(contentVersion = 3, album = canonical.album.copy(clears = clears))
    }

    private fun migrateCountries(s: CareerSnapshot): CareerSnapshot {
        require(ARCHIVE !in s.extra)
        val archive = JSONObject()
        fun merge(collection: String, records: Map<String, AwardMeta>, officer: String? = null): Map<String, AwardMeta> {
            require(records.all { (id, m) -> id == m.countryId && m.attemptId > 0 && (officer == null || officer == m.officerId) })
            return records.entries.groupBy { countryId(it.key) }.toSortedMap().mapValues { (_, entries) ->
                val sorted = entries.sortedWith(compareBy({ it.value.attemptId }, { it.key }))
                for ((old, meta) in sorted.drop(1)) {
                    val group = archive.optJSONObject(collection) ?: JSONObject().also { archive.put(collection, it) }
                    if (officer == null) group.put(old, CareerJson.metaJson(meta))
                    else {
                        val units = group.optJSONObject(old) ?: JSONObject().also { group.put(old, it) }
                        units.put(officer, CareerJson.metaJson(meta))
                    }
                }
                sorted.first().value.let { it.copy(countryId = countryId(it.countryId)) }
            }
        }
        val album = s.album
        val infiltration = merge("infiltration", album.infiltration)
        val sabotage = merge("sabotage", album.sabotage)
        val units = sortedMapOf<String, MutableMap<String, AwardMeta>>()
        for (officer in album.units.values.flatMap { it.keys }.toSortedSet()) {
            val records = album.units.mapNotNull { (id, marks) -> marks[officer]?.let { id to it } }.toMap()
            for ((id, meta) in merge("units", records, officer)) units.getOrPut(id) { sortedMapOf() }[officer] = meta
        }
        // Retain even empty unknown-country unit maps.
        for (id in album.units.keys.sorted()) units.getOrPut(countryId(id)) { sortedMapOf() }
        val seen = mutableSetOf<Triple<String, String, String?>>()
        val duplicates = JSONArray()
        val receipt = s.receipt?.let { r -> r.copy(newMarks = r.newMarks.mapNotNull { original ->
            val mark = original.copy(countryId = countryId(original.countryId))
            if (seen.add(Triple(mark.kind, mark.countryId, mark.officerId))) mark
            else { duplicates.put(CareerJson.markJson(original)); null }
        }) }
        if (duplicates.length() > 0) archive.put("receipt", duplicates)
        return s.copy(contentVersion = 2, countries = s.countries.map(::countryId).toSet(),
            album = album.copy(historicalCleared = album.historicalCleared.map(::countryId).toSet(),
                infiltration = infiltration, sabotage = sabotage, units = units.mapValues { it.value.toMap() },
                clears = album.clears.keys.associate { countryId(it) to 1 } +
                    album.clears.filterKeys { countryId(it) == it }),
            pendingAttempt = s.pendingAttempt?.let(::migrateAttempt), receipt = receipt,
            extra = if (archive.length() == 0) s.extra else s.extra + (ARCHIVE to archive.toString()))
    }

    private fun migrateAttempt(a: Attempt): Attempt {
        val old = a.countryId
        val target = aliases[old] ?: oldRealSlots[old]
        val country = countryId(old)
        val preparation = a.preparation?.copy(countryId = countryId(a.preparation.countryId))
        val airport = if (a.contentVersion == 1) target?.airportId ?: a.airportId else a.airportId
        val slot = Country.find(country)?.airports?.find { it.id == airport }
        val upgrade = a.contentVersion == 1 && old in oldRealSlots && slot != null &&
            slot.lang == a.language && a.officerId in slot.officers && preparation != null && compatibleCover(preparation)
        return a.copy(countryId = country, preparation = preparation, airportId = airport,
            contentVersion = if (upgrade) 2 else a.contentVersion)
    }

    /** Reject known obsolete facts, not towns absent from today's random generator. */
    fun compatibleCover(p: Preparation): Boolean = p.home !in inventedHomes &&
        p.from !in fictionalNames && p.stamps.none { stamp -> fictionalNames.any { stamp == it || stamp.startsWith("$it ") } }

    // Frozen pre-airport cover vocabulary; never display or recursively migrate these strings.
    private val fictionalNames = setOf(
        "Akyalı", "Al-Nakheel", "Al-Qamar", "Altamira del Sur", "Aogiri", "Ashbury Isles", "Belcastre", "Belogorye",
        "Borovia", "Brennland", "Castellume", "Costaverde", "Dnistrovia", "Elbrandt", "Fenglan", "Grauwald",
        "Gümüşdere", "Haiyue Islands", "Hohenmark", "Hoshijima", "Ilha da Bruma", "Ilhas Corvanas", "Isla Coralina",
        "Isola Serena", "Jeziorna", "Kalynia", "Kazahana", "Kedrovia", "Kresowia", "Kızılkaya", "Lagodoro",
        "Lianhua", "Lorvel", "Mavikent", "Minamikaze", "Montclair-sur-Mer", "Montecaldo", "Monteluna", "Ostrevie",
        "Polonyna", "Pribrezhye", "Qingmu", "Rhenstal", "Saint-Clairon", "San Aurelio", "San Velario", "Serra Alta",
        "Shiranami", "Siebenau", "Songlin", "Sonyachia", "Stepovia", "Taşkale", "Tsukiyama", "Valdenstein",
        "Valdoria", "Valmorée", "Valtorre", "Vitrova", "Wadi Zahra", "Westmarch", "Wolnogród", "Yeşilvadi",
        "Yunlan", "Zalesia", "Zarya", "Zelenodar", "Złotogóra", "akyali", "alqamar", "altamira", "aogiri",
        "ashbury", "belcastre", "belogorye", "borovia", "brennland", "bruma", "castellume", "coralina", "corvanas",
        "costaverde", "dnistrovia", "elbrandt", "fenglan", "grauwald", "gumusdere", "haiyue", "hohenmark",
        "hoshijima", "jeziorna", "kalynia", "kazahana", "kedrovia", "kizilkaya", "kresowia", "lagodoro", "lianhua",
        "lorvel", "mavikent", "minamikaze", "montclair", "montecaldo", "monteluna", "nakheel", "ostrevie",
        "polonyna", "portoserena", "pribrezhye", "qingmu", "rhenstal", "saintclairon", "sanaurelio", "sanvelario",
        "serraalta", "shiranami", "siebenau", "songlin", "sonyachia", "stepovia", "taskale", "tsukiyama",
        "valdenstein", "valdoria", "valmoree", "valtorre", "vitrova", "wadizahra", "westmarch", "wolnogrod",
        "yesilvadi", "yunlan", "zalesia", "zarya", "zelenodar", "zlotogora",
    )
    private val inventedHomes = setOf(
        "Ain Rummana", "Akyalı", "Al-Nakheel", "Al-Qamar", "Altamira", "Anse-Bleue", "Aogiri", "Ashbury Harbour",
        "Auerbrück", "Bahía Serena", "Baishui", "Beaumarais", "Belcastre", "Belogorsk", "Bilyi Bereh", "Borgo Alto",
        "Borowiec", "Brennstadt", "Brzozów Wielki", "Cala Verde", "Castellume", "Dalgalı", "Dnistrograd",
        "Dębowa Wola", "Elbrandt-Süd", "Fenford", "Fenglan", "Glutbach", "Grauwald", "Gümüşdere", "Haiyue", "Hehua",
        "Hohenbrück", "Hohenmark", "Hoshijima", "Hryva", "Ilıcaköy", "Isobe", "Jeziorna", "Kalynhrad", "Kayaköy",
        "Kazahana", "Kedrograd", "Kervansaray", "Kesselbach", "Kestrel Bay", "Kirimachi", "Krasnoborsk",
        "Kresy Wielkie", "Kızılkaya", "Lagodoro", "Lanshui", "Lianhua", "Lindtal", "Lisove", "Lorvel", "Marienfels",
        "Martıkent", "Mavikent", "Minamikaze", "Minato-Hoshi", "Montclair", "Montecaldo", "Monteluna", "Muxi",
        "Ostrevie-Centre", "Pedra Branca", "Pietrafonda", "Polonynske", "Port-Clairon", "Port-Sablon",
        "Porto Corvo", "Porto Nevoeiro", "Porto Serena", "Pribrezhny", "Puerto Alcázar", "Puerto Coral",
        "Puerto Santa Inés", "Qingmu", "Qingshan", "Ras Hilal", "Rhenstal", "Riva d'Oro", "Rocca Bruna",
        "Roquemaure", "Saint-Aubrac", "San Aurelio", "San Cristóbal del Sur", "San Telmo", "San Velario",
        "Serra Alta", "Shiohama", "Shiranami", "Siebenau-Nord", "Snezhnogorsk", "Songlin", "Sonyachnyi",
        "Sosnowa Góra", "Srebrny Potok", "Stare Wolno", "Steinach", "Stepove", "Tayozhny", "Taşkale", "Torre Lume",
        "Torreblanca", "Trzciniec", "Tsukiyama", "Valbonne-les-Pins", "Valdoria", "Valtorre", "Verbivka",
        "Verkhovyna", "Vila Nova das Corvanas", "Vila da Bruma", "Villaflor", "Villeneuve-d'Ostre", "Vitrove",
        "Wadi Zahra", "Wahat Salma", "Westmarch", "Wiesenthal", "Wolnogród", "Yamakage", "Yantarny", "Yeşilvadi",
        "Yuewan", "Yukimura", "Yunlan", "Zalesie Główne", "Zaryansk", "Zelenodar", "Zlatopil", "Zoriansk",
        "Złotogóra", "Çamlıköy",
    )

    data class Target(val countryId: String, val airportId: String)
    val aliases = mapOf(
        "valdenstein" to Target("austria", "inn"),
        "elbrandt" to Target("austria", "szg"),
        "grauwald" to Target("de", "fkb"),
        "rhenstal" to Target("de", "hhn"),
        "valmoree" to Target("fr", "mrs"),
        "montclair" to Target("fr", "nce"),
        "belcastre" to Target("fr", "tuf"),
        "ostrevie" to Target("fr", "lil"),
        "sanaurelio" to Target("colombia", "ctg"),
        "costaverde" to Target("colombia", "mde"),
        "valdoria" to Target("es", "vll"),
        "monteluna" to Target("argentina", "mdz"),
        "sanvelario" to Target("italy", "rmi"),
        "corvanas" to Target("portugal", "pdl"),
        "zalesia" to Target("poland", "krk"),
        "zarya" to Target("russia", "mmk"),
        "kalynia" to Target("ukraine", "kbp"),
        "stepovia" to Target("ukraine", "hrk"),
        "kizilkaya" to Target("turkey", "nav"),
        "alqamar" to Target("uae", "auh"),
        "hoshijima" to Target("japan", "hij"),
        "yunlan" to Target("china", "kwl"),
        "hohenmark" to Target("de", "bre"),
        "siebenau" to Target("de", "str"),
        "brennland" to Target("de", "dtm"),
        "canada_fr" to Target("canada", "yul"),
        "lorvel" to Target("fr", "avn"),
        "saintclairon" to Target("fr", "run"),
        "coralina" to Target("mexico", "cun"),
        "altamira" to Target("mexico", "cjs"),
        "valtorre" to Target("italy", "flr"),
        "portoserena" to Target("italy", "olb"),
        "montecaldo" to Target("italy", "nap"),
        "lagodoro" to Target("italy", "vrn"),
        "castellume" to Target("italy", "peg"),
        "bruma" to Target("portugal", "fnc"),
        "serraalta" to Target("portugal", "ter"),
        "borovia" to Target("poland", "ktw"),
        "wolnogrod" to Target("poland", "gdn"),
        "jeziorna" to Target("poland", "poz"),
        "kresowia" to Target("poland", "rze"),
        "zlotogora" to Target("poland", "wro"),
        "belogorye" to Target("russia", "ego"),
        "pribrezhye" to Target("russia", "arh"),
        "kedrovia" to Target("russia", "tof"),
        "zelenodar" to Target("ukraine", "dnk"),
        "sonyachia" to Target("ukraine", "ods"),
        "vitrova" to Target("ukraine", "ozh"),
        "dnistrovia" to Target("ukraine", "lwo"),
        "polonyna" to Target("ukraine", "ifo"),
        "yesilvadi" to Target("turkey", "rzv"),
        "akyali" to Target("turkey", "dlm"),
        "gumusdere" to Target("turkey", "asr"),
        "taskale" to Target("turkey", "adb"),
        "mavikent" to Target("turkey", "saw"),
        "nakheel" to Target("uae", "aan"),
        "wadizahra" to Target("morocco", "fez"),
        "shiranami" to Target("japan", "kmq"),
        "aogiri" to Target("japan", "kij"),
        "tsukiyama" to Target("japan", "koj"),
        "kazahana" to Target("japan", "cts"),
        "minamikaze" to Target("japan", "oka"),
        "lianhua" to Target("china", "hgh"),
        "songlin" to Target("china", "kwe"),
        "haiyue" to Target("china", "xmn"),
        "fenglan" to Target("china", "lhw"),
        "qingmu" to Target("china", "wus"),
        "ashbury" to Target("uk", "syy"),
        "westmarch" to Target("australia", "asp"),
    )

    val oldRealSlots = mapOf(
        "de" to Target("de", "fra"),
        "fr" to Target("fr", "cdg"),
        "es" to Target("es", "mad"),
        "italy" to Target("italy", "fco"),
        "portugal" to Target("portugal", "lis"),
        "poland" to Target("poland", "waw"),
        "russia" to Target("russia", "svo"),
        "turkey" to Target("turkey", "ist"),
        "uae" to Target("uae", "dxb"),
        "japan" to Target("japan", "nrt"),
        "china" to Target("china", "pek"),
        "austria" to Target("austria", "vie"),
        "switzerland" to Target("switzerland", "zrh"),
        "belgium" to Target("belgium", "bru"),
        "canada_fr" to Target("canada", "yul"),
        "senegal" to Target("senegal", "dss"),
        "mexico" to Target("mexico", "mex"),
        "argentina" to Target("argentina", "eze"),
        "colombia" to Target("colombia", "bog"),
        "brazil" to Target("brazil", "gru"),
        "angola" to Target("angola", "lad"),
        "capeverde" to Target("capeverde", "rai"),
        "kazakhstan" to Target("kazakhstan", "ala"),
        "kyrgyzstan" to Target("kyrgyzstan", "bsz"),
        "egypt" to Target("egypt", "cai"),
        "jordan" to Target("jordan", "amm"),
        "morocco" to Target("morocco", "cmn"),
        "uk" to Target("uk", "lhr"),
        "ireland" to Target("ireland", "dub"),
        "australia" to Target("australia", "syd"),
        "canada" to Target("canada", "yyz"),
    )
}

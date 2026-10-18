package com.lampensoft.borderlinefluent

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

sealed interface CareerRead {
    data class Ready(val snapshot: CareerSnapshot, val needsWrite: Boolean = false) : CareerRead
    data class Unreadable(val raw: String?, val snapshot: CareerSnapshot? = null, val preparationOnly: Boolean = false) : CareerRead
}

data class CareerInput(val snapshot: String? = null, val marker: Int? = null)

/** v2 only: [StoryReset] has already discarded older careers. A marker without a snapshot is damage, not a fresh start. */
fun loadCareer(input: CareerInput): CareerRead = when {
    input.snapshot != null -> CareerJson.decode(input.snapshot).let { read ->
        if (read is CareerRead.Ready && input.marker != read.snapshot.schemaVersion) read.copy(needsWrite = true) else read
    }
    input.marker != null -> CareerRead.Unreadable(null)
    else -> CareerRead.Ready(CareerSnapshot(), needsWrite = true)
}

object CareerJson {
    fun encode(snapshot: CareerSnapshot): String = snapshotJson(snapshot).toString()

    fun decode(raw: String): CareerRead {
        var history: CareerSnapshot? = null
        return try {
            val tokener = JSONTokener(raw)
            val root = tokener.nextValue() as? JSONObject ?: error("object")
            require(tokener.nextClean() == '\u0000')
            val r = Reader(root)
            require(r.int("schemaVersion") == StoryReset.VERSION)
            val version = r.int("contentVersion").also { require(it == 3) }
            val counters = counters(r.obj("counters"))
            val countries = r.strings("countries").toSet()
            val album = album(r.obj("album"))
            val next = r.long("nextAttemptId")
            val applied = r.long("appliedThroughId")
            require(next > applied && applied >= 0)
            val receipt = r.nullObj("receipt")?.let(::receipt)
            require(receipt == null || receipt.id == applied && receipt.id > 0)
            val pending = r.value("pendingAttempt")
            val identityRaw = r.optional("identity")
            val bookingRaw = r.optional("booking")
            val actRaw = r.optional("act")
            val seenRaw = r.optional("encountersSeen")
            val stage = runCatching { Act1Stage.valueOf(r.optional("act1Stage") as String) }.getOrNull() // unknown or damaged: derive it (legacy)
            val epilogueFrame = (r.optional("epilogueFrame") as? Int)?.coerceIn(0, 7)
            val epiloguePassed = r.optional("epiloguePassed") as? Boolean
            val epilogueDoses = (r.optional("epilogueDoses") as? Int)?.takeIf { it >= 0 }
            val frame = (r.optional("prologueFrame") as? Int ?: 0).coerceIn(0, 17)
            r.optional("storyTripsCleared") // discard the retired three-trip counter
            val storyLastCountry = (r.optional("storyLastCountry") as? String)?.takeIf { id -> Country.all.any { it.id == id } }
            r.optional("provenLanguages") // Desk passes never become machine training.
            val trained = (r.optional("trainedLanguages") as? JSONArray)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            val sessions = (r.optional("machineSessions") as? Int ?: 0).also { require(it >= 0) }
            val upgrades = (r.optional("upgradesGranted") as? Int ?: 0).also { require(it in 0..3) }
            val doses = (r.optional("doses") as? Int ?: 0).also { require(it >= 0) }
            // Lenient: only steers which news caption comes next; `true` is a save from before the world news (one caption).
            val news = when (val n = r.optional("newsSeen")) { true -> setOf(NEWS_THALASSIA); is JSONArray -> (0 until n.length()).map { n.optString(it) }.filter(String::isNotEmpty).toSet(); else -> emptySet() }
            // Lenient: unknown or damaged motif ids are dropped; they only steer echoes.
            val planted = runCatching { (r.optional("planted") as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { id -> Motif.find(id) != null } }.distinct() } }.getOrNull().orEmpty()
            // Lenient: only steers which room questions come next.
            val recentSeeds = runCatching { (r.optional("recentSeeds") as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotEmpty) }.distinct().takeLast(RECENT_SEEDS) } }.getOrNull().orEmpty()
            val points = (r.optional("progressPoints") as? Int ?: UPGRADE_THRESHOLDS.getOrNull(upgrades - 1) ?: 0).also { require(it >= 0) }
            val cutscenes = (r.optional("cutscenesSeen") as? JSONArray)?.let { a -> (0 until a.length()).map { a.getInt(it).also { n -> require(n in 1..upgrades) } }.toSet() }.orEmpty()
            // The USA opens when upgrade 3 is delivered; a missing flag follows the delivery.
            val usa = r.optional("usaUnlocked") as? Boolean ?: (3 in cutscenes)
            // Bad entries are dropped: the level gets a new destination on the next menu visit. So is a courier in a
            // held-back country (Language.HELD_BACK): nobody could fly there to collect the upgrade.
            val drops = runCatching { (r.optional("upgradeDrops") as? JSONObject)?.let { o ->
                o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.takeIf { it in 1..upgrades }?.let { it to o.getString(k) } }
                    .filter { (_, id) -> Country.shipped.any { it.id == id } }.toMap() } }.getOrNull().orEmpty()
            // A country or airport the catalog cut reads as none (a replaced airport as its replacement, CountryMigration).
            val last = r.nullString("lastPassedCountry")?.takeIf { it !in CountryMigration.removedCountries }
            val lastAirport = r.nullString("lastPassedAirport")?.let(CountryMigration::airportId)
                ?.takeIf { last != null && it !in CountryMigration.removedAirports }
            val flags = r.nullObj("flagged")?.let { f -> f.keys().asSequence().associateWith { f.getInt(it).also { n -> require(n in 1..3) } } }.orEmpty()
                .filterKeys { it !in CountryMigration.removedCountries }
            // Lenient: a bad closure entry is dropped, never a reason to lose the career.
            val closed = runCatching { (r.optional("closed") as? JSONObject)?.let { o ->
                o.keys().asSequence().filter { it !in CountryMigration.removedCountries }.mapNotNull { k -> o.optInt(k).takeIf { it in 1..REFUSAL_CLOSED_TRIPS }?.let { k to it } }.toMap() } }.getOrNull().orEmpty()
            // usaCompleted: a missing key is a legacy save, whose USA album mark came with the ending.
            history = CareerSnapshot(StoryReset.VERSION, counters, countries, album, next, applied, receipt = receipt,
                contentVersion = version, identity = null, trainedLanguages = trained, machineSessions = sessions, progressPoints = points, doses = doses, planted = planted, recentSeeds = recentSeeds, upgradesGranted = upgrades,
                cutscenesSeen = cutscenes, whySceneSeen = r.optional("whySceneSeen") as? Boolean ?: false, visitSceneSeen = r.optional("visitSceneSeen") as? Boolean ?: false, thalassiaSeen = r.optional("thalassiaSeen") as? Boolean ?: false, newsSeen = news, solvaneSeen = r.optional("solvaneSeen") as? Boolean ?: false, translatorUsed = r.optional("translatorUsed") as? Boolean ?: false, translatorUnlocked = r.optional("translatorUnlocked") as? Boolean ?: false, daysAway = (r.optional("daysAway") as? Int ?: 0).coerceAtLeast(0), walkedAway = r.optional("walkedAway") as? Boolean ?: false, marekReturned = r.optional("marekReturned") as? Boolean ?: false, upgradeDrops = drops, usaUnlocked = usa, usaCompleted = r.optional("usaCompleted") as? Boolean ?: ("usa" in album.infiltration), lastPassedCountry = last, lastPassedAirport = lastAirport, flagged = flags, closed = closed, extra = r.extra()) // extra last: it must see every key read above
            // A damaged or unknown identity is asked for again; it must not make the career unreadable.
            val identity = runCatching { (identityRaw as? JSONObject)?.let(::identity) }.getOrNull()
            // A damaged booking is dropped (the player books again); it must not make the career unreadable either.
            val booking = runCatching { (bookingRaw as? JSONObject)?.let(::booking) }.getOrNull()
            // An unknown act reads as unsaved ([actOf]).
            val act = runCatching { Act.valueOf(actRaw as String) }.getOrNull()
            // Seen encounters only steer the next roll: damage reads as none seen.
            val seen = runCatching { (seenRaw as? JSONArray)?.let { a -> (0 until a.length()).map { a.get(it) as String }.toSet() } }.getOrNull().orEmpty()
            history = history.copy(epilogueFrame = epilogueFrame,
                epiloguePassed = epiloguePassed, epilogueDoses = epilogueDoses, act1Stage = stage, prologueFrame = frame, identity = identity, booking = booking, act = act, encountersSeen = seen, storyLastCountry = storyLastCountry)
            // A trip to a country or airport cut from the catalog is dropped: nothing could fly there to resume it.
            val a = if (pending == JSONObject.NULL) null else (pending as JSONObject).takeUnless(::cutFromCatalog)?.let(::attempt)
            require(a == null || a.id > applied && a.id < next)
            CareerRead.Ready(history.copy(pendingAttempt = a))
        } catch (_: Throwable) { // StackOverflowError from deeply nested JSON too
            CareerRead.Unreadable(raw, history, history != null)
        }
    }

    private class Reader(val json: JSONObject) {
        private val used = mutableSetOf<String>()
        fun value(key: String): Any { used += key; return json.get(key) }
        fun string(key: String) = (value(key) as? String) ?: error(key)
        fun bool(key: String) = (value(key) as? Boolean) ?: error(key)
        fun long(key: String): Long {
            val n = value(key)
            require(n is Int || n is Long)
            return (n as Number).toLong()
        }
        fun int(key: String): Int = long(key).also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
        fun level(key: String) = int(key).also { require(it in 0..3) }
        fun fraction(key: String): Double = ((value(key) as? Number)?.toDouble() ?: error(key)).also { require(it.isFinite() && it in 0.0..1.0) }
        fun obj(key: String) = value(key) as JSONObject
        fun array(key: String) = value(key) as JSONArray
        fun strings(key: String): List<String> = array(key).let { a -> (0 until a.length()).map { a.get(it) as String } }
        fun optional(key: String): Any? { used += key; return json.opt(key)?.takeUnless { it == JSONObject.NULL } }
        fun nullString(key: String): String? = optional(key)?.let { it as String }
        fun nullLevel(key: String): Int? = optional(key)?.let { require(it is Int && it in 0..3); it }
        fun nullObj(key: String): JSONObject? = optional(key)?.let { it as JSONObject }
        fun extra(): CareerFields = json.keys().asSequence().filter { it !in used }.associateWith { JSONArray().put(json.get(it)).toString().let { value -> value.substring(1, value.length - 1) } }
    }

    private fun obj(extra: CareerFields, vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply {
        for ((k, raw) in extra) put(k, JSONTokener(raw).nextValue())
        for ((k, v) in fields) put(k, v ?: JSONObject.NULL)
    }
    private fun <T> map(json: JSONObject, read: (JSONObject) -> T): Map<String, T> =
        json.keys().asSequence().associateWith { read(json.getJSONObject(it)) }
    private fun <T> mapJson(map: Map<String, T>, write: (T) -> JSONObject): JSONObject = JSONObject().apply {
        for ((k, v) in map) put(k, write(v))
    }

    private fun counters(json: JSONObject): CareerCounters = Reader(json).let { r ->
        val missions = r.int("missions"); val cleared = r.int("cleared"); val streak = r.int("streak")
        val best = r.int("best"); val broken = r.int("broken"); val rooms = r.int("rooms")
        CareerCounters(missions, cleared, streak, best, broken, rooms, r.extra())
    }
    private fun countersJson(c: CareerCounters) = obj(c.extra, "missions" to c.missions, "cleared" to c.cleared,
        "streak" to c.streak, "best" to c.best, "broken" to c.broken, "rooms" to c.rooms)

    private fun meta(json: JSONObject): AwardMeta = Reader(json).let { r ->
        val id = r.long("attemptId").also { require(it > 0) }
        val country = r.string("countryId"); val officer = r.string("officerId")
        val stage = r.string("stage").also { require(it == "desk" || it == "assessment") }
        val level = r.nullLevel("effectiveLevel"); val loadout = r.nullString("loadoutId")
        val version = r.int("contentVersion").also { require(it > 0) }
        AwardMeta(id, country, officer, stage, level, loadout, version, r.extra())
    }
    internal fun metaJson(m: AwardMeta) = obj(m.extra, "attemptId" to m.attemptId, "countryId" to m.countryId,
        "officerId" to m.officerId, "stage" to m.stage, "effectiveLevel" to m.effectiveLevel,
        "loadoutId" to m.loadoutId, "contentVersion" to m.contentVersion)

    private fun album(json: JSONObject): Album = Reader(json).let { r ->
        val historical = r.strings("historicalCleared").toSet()
        val infiltration = map(r.obj("infiltration"), ::meta)
        val sabotage = map(r.obj("sabotage"), ::meta)
        val units = map(r.obj("units")) { map(it, ::meta) }
        require((infiltration.entries + sabotage.entries).all { it.key == it.value.countryId })
        require(units.all { (country, models) -> models.all { (officer, m) -> m.countryId == country && m.officerId == officer } })
        val clears = if (json.has("clears")) Reader(r.obj("clears")).let { counts ->
            counts.json.keys().asSequence().associateWith { counts.int(it).also { n -> require(n > 0) } }
        } else emptyMap()
        val contracts = if (json.has("contracts")) r.obj("contracts").let { awards ->
            awards.keys().asSequence().map { key ->
                val contract = legacyContract(key).also { require(Contract.fromId(it) != null) }
                contract to meta(awards.getJSONObject(key))
            }.toMap()
        } else emptyMap()
        Album(historical, infiltration, sabotage, units, r.extra(), clears, contracts)
    }
    private fun albumJson(a: Album) = obj(a.extra, "historicalCleared" to JSONArray(a.historicalCleared.toList()),
        "infiltration" to mapJson(a.infiltration, ::metaJson), "sabotage" to mapJson(a.sabotage, ::metaJson),
        "units" to mapJson(a.units) { mapJson(it, ::metaJson) }, "clears" to JSONObject(a.clears),
        "contracts" to mapJson(a.contracts, ::metaJson))

    private fun mark(json: JSONObject): NewMark = Reader(json).let { r ->
        val kind = r.string("kind"); val country = r.string("countryId"); val officer = r.nullString("officerId")
        val extra = r.extra().toMutableMap()
        if (kind == "contract" && json.opt("contract") == "disable")
            extra["contract"] = "\"${Contract.UNAIDED.id}\""
        NewMark(kind, country, officer, extra)
    }
    internal fun markJson(m: NewMark) = obj(m.extra, "kind" to m.kind, "countryId" to m.countryId, "officerId" to m.officerId)
    private fun receipt(json: JSONObject): Receipt = Reader(json).let { r ->
        val id = r.long("id"); val outcome = ReplayOutcome.valueOf(legacyOutcome(r.string("outcome")))
        val marks = r.array("newMarks").let { a -> (0 until a.length()).map { mark(a.getJSONObject(it)) } }
        val contract = r.nullString("contract")?.let(::legacyContract)?.also { require(Contract.fromId(it) != null) }
        require(marks.filter { it.kind == "contract" }.all { mark ->
            val awarded = markContract(mark)
            awarded != null && awarded.id == contract
        })
        val met = (r.optional("contractMet")?.let { it as Boolean } ?: false).also { require(!it || contract != null) }
        val ending = r.nullObj("ending")?.let { e -> Reader(e).let { x ->
            Ending(x.string("language").also { l -> require(Language.all.any { it.code == l }) }, x.bool("room"), x.bool("broken"), x.bool("home"), x.bool("story"), (x.optional("usaReleased") ?: x.optional("usaDosed")) as? Boolean ?: false) } }
        Receipt(id, outcome, marks, r.extra(), contract, met, ending)
    }
    // Disable was replaced without a schema bump; writes use the replacement id.
    private fun legacyContract(id: String) = if (id == "disable") Contract.UNAIDED.id else id
    // Names written before fb7ea02 (eff7629 builds); writing always uses the new ones.
    private fun legacyOutcome(n: String) = when (n) { "INFILTRATION", "CLEAR", "SABOTAGE" -> "CLEARED"; "FAILURE" -> "REFUSED"; else -> n }
    private fun legacyDecision(n: String, broken: String) = Decision.valueOf(when (n) { "SECONDARY" -> "REFUSED"; "BROKEN" -> broken; else -> n })
    private fun receiptJson(r: Receipt) = obj(r.extra, "id" to r.id, "outcome" to r.outcome.name,
        "newMarks" to JSONArray(r.newMarks.map(::markJson)), "contract" to r.contract, "contractMet" to r.contractMet,
        "ending" to r.ending?.let { obj(emptyMap(), "language" to it.language, "room" to it.room, "broken" to it.broken, "home" to it.home, "story" to it.story, "usaReleased" to it.usaReleased) })

    private fun preparation(json: JSONObject): Preparation = Reader(json).let { r ->
        val name = r.string("name"); val latin = r.string("latin"); val gender = Gender.valueOf(r.string("gender"))
        val p = Reader(r.obj("photo"))
        val photoFemale = p.bool("female"); val sheet = p.string("sheet"); val tile = p.int("tile").also { require(it in 0..5) }
        val photo = Person(photoFemale, sheet, tile)
        val country = r.string("countryId"); val passport = r.string("passportNo"); val born = r.string("born")
        val home = r.string("home"); val job = r.string("job"); val from = r.string("from"); val city = r.string("city")
        fun cover(key: String) = r.string(key).let { RETIRED_COVER_VALUES[it] ?: it }
        val purpose = cover("purpose"); val days = r.int("days"); val stayed = cover("stayed")
        val with = r.string("travelledWith"); val met = r.string("met"); val waiting = r.string("waiting"); val stamps = r.strings("stamps")
        val nationality = r.string("nationality"); val kind = TripKind.valueOf(r.string("kind")); val host = cover("host")
        val m = Reader(r.obj("persona"))
        val persona = Persona(m.fraction("patience"), m.fraction("suspicion"), m.fraction("strictness"),
            m.fraction("tolerance"), m.fraction("busy"), m.level("level"))
        // An encounter no longer in the catalog (or damaged) is dropped: the trip just has none.
        val encounter = runCatching { r.nullObj("encounter")?.let { e -> Reader(e).let { x ->
            QueueEncounter(x.string("id").also { require(Encounters.find(it) != null) },
                x.optional("choice")?.let { (it as Int).also { c -> require(c in 0..1) } }, x.bool("asked")) } } }.getOrNull()
        val homeArrival = r.optional("homeArrivalTraining") as? Boolean ?: false
        Preparation(name, latin, gender, photo, country, passport, born, home, job, from, city, purpose, days,
            stayed, with, met, waiting, stamps, nationality, kind, host, persona, r.extra(), p.extra(), m.extra(), encounter, homeArrival)
    }
    private fun preparationJson(p: Preparation) = obj(p.extra,
        "name" to p.name, "latin" to p.latin, "gender" to p.gender.name,
        "photo" to obj(p.photoExtra, "female" to p.photo.female, "sheet" to p.photo.sheet, "tile" to p.photo.tile),
        "countryId" to p.countryId, "passportNo" to p.passportNo, "born" to p.born, "home" to p.home, "job" to p.job,
        "from" to p.from, "city" to p.city, "purpose" to p.purpose, "days" to p.days, "stayed" to p.stayed,
        "travelledWith" to p.travelledWith, "met" to p.met, "waiting" to p.waiting, "stamps" to JSONArray(p.stamps),
        "nationality" to p.nationalityId, "kind" to p.kind.name, "host" to p.host,
        "persona" to obj(p.personaExtra, "patience" to p.persona.patience, "suspicion" to p.persona.suspicion,
            "strictness" to p.persona.strictness, "tolerance" to p.persona.tolerance, "busy" to p.persona.busy, "level" to p.persona.level))
        .apply { if (p.homeArrivalTraining) put("homeArrivalTraining", true) }
        .apply { p.encounter?.let { put("encounter", obj(emptyMap(), "id" to it.id, "choice" to it.choice, "asked" to it.asked)) } }

    private fun person(json: JSONObject) = Reader(json).let { p -> Person(p.bool("female"), p.string("sheet"), p.int("tile").also { require(it in 0..5) }) }
    private fun personJson(p: Person) = JSONObject().put("female", p.female).put("sheet", p.sheet).put("tile", p.tile)
    private fun identity(json: JSONObject): Identity = Reader(json).let { r ->
        // A held-back nationality (Nationality.shipped) fails too: its home desk's language can't be installed.
        val nationality = Nationality.shipped.firstOrNull { it.id == r.string("nationality") } ?: error("nationality")
        val gender = Gender.valueOf(r.string("gender")); val list = NameList.valueOf(r.string("nameList"))
        require(list == Identity.listFor(gender, list))
        Identity(nationality, gender, list, r.string("name"), r.string("latin"), r.string("born"), r.string("home"),
            r.string("job"), r.nullObj("photo")?.let(::person), r.string("passportNo"), extra = r.extra())
    }
    private fun identityJson(i: Identity) = obj(i.extra, "nationality" to i.nationality.id, "gender" to i.gender.name,
        "nameList" to i.nameList.name, "name" to i.name, "latin" to i.latin, "born" to i.born, "home" to i.home, "job" to i.job,
        "photo" to i.photo?.let(::personJson), "passportNo" to i.passportNo)

    private fun slot(country: String, airport: String?) = Country.find(country)?.airports?.find { it.id == airport }
    /** Only a trip to a country or airport the catalog cut (CountryMigration); other damage stays unreadable. */
    private fun cutFromCatalog(json: JSONObject) =
        json.opt("countryId") in CountryMigration.removedCountries || json.opt("airportId") in CountryMigration.removedAirports

    private fun booking(json: JSONObject): Booking = Reader(json).let { r ->
        val country = r.string("countryId"); val airport = CountryMigration.airportId(r.string("airportId")); val officer = r.string("officerId")
        val slot = slot(country, airport)
        require(slot != null && officer in slot.officers)
        Booking(country, airport, officer, preparation(r.obj("preparation")).also { require(it.countryId == country) })
    }
    private fun bookingJson(b: Booking) = obj(emptyMap(), "countryId" to b.countryId, "airportId" to b.airportId,
        "officerId" to b.officerId, "preparation" to preparationJson(b.preparation))

    private fun attempt(json: JSONObject): Attempt = Reader(json).let { r ->
        val id = r.long("id").also { require(it > 0) }; val country = r.string("countryId"); val officer = r.string("officerId")
        val language = r.string("language"); val training = r.bool("training"); val story = r.bool("story"); val eligible = r.bool("replayEligible")
        require(!eligible || !training && !story)
        val level = r.level("effectiveLevel"); val version = r.int("contentVersion").also { require(it == 2) }
        val airport = r.nullString("airportId")?.let(CountryMigration::airportId)
        val slot = slot(country, airport)
        require(slot != null && slot.lang == language && officer in slot.officers)
        val loadout = r.nullString("loadoutId")
        val desk = r.nullString("deskDecision")?.let { legacyDecision(it, "REFERRAL") }
        val assessment = r.nullString("assessmentDecision")?.let { legacyDecision(it, "RELEASED") }
        val broken = r.bool("everBroken"); val debug = r.bool("debugForced")
        val preparation = r.nullObj("preparation")?.let(::preparation)
        require(preparation != null && preparation.countryId == country && preparation.persona.level == level)
        val status = AttemptStatus.valueOf(r.string("status"))
        val contract = r.nullString("contract")?.let(::legacyContract)?.also { require(Contract.fromId(it) != null) }
        val native = (r.optional("nationalityLanguages") as? JSONArray)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val upgrades = (r.optional("upgradesHeld") as? Int ?: 0).also { require(it in 0..3) }
        val progressEligible = r.optional("progressEligible") as? Boolean ?: false
        r.optional("retinalOpened") // Discard obsolete display-open tracking from older saves.
        Attempt(id, country, officer, language, training, story, eligible, level, version, loadout, desk, assessment,
            broken, debug, preparation, status, r.extra(), airport, contract, native, upgrades, progressEligible)
    }
    private fun attemptJson(a: Attempt) = obj(a.extra, "id" to a.id, "countryId" to a.countryId, "officerId" to a.officerId,
        "airportId" to a.airportId, "language" to a.language, "training" to a.training, "story" to a.story, "replayEligible" to a.replayEligible,
        "effectiveLevel" to a.effectiveLevel, "contentVersion" to a.contentVersion, "loadoutId" to a.loadoutId,
        "deskDecision" to a.deskDecision?.name, "assessmentDecision" to a.assessmentDecision?.name,
        "everBroken" to a.everBroken, "debugForced" to a.debugForced, "preparation" to a.preparation?.let(::preparationJson), "status" to a.status.name,
        "contract" to a.contract, "nationalityLanguages" to a.nationalityLanguages?.let { JSONArray(it.toList()) },
        "upgradesHeld" to a.upgradesHeld, "progressEligible" to a.progressEligible)

    private fun snapshotJson(s: CareerSnapshot) = obj(s.extra, "schemaVersion" to s.schemaVersion,
        "contentVersion" to s.contentVersion, "counters" to countersJson(s.counters), "countries" to JSONArray(s.countries.toList()), "album" to albumJson(s.album),
        "nextAttemptId" to s.nextAttemptId, "appliedThroughId" to s.appliedThroughId,
        "pendingAttempt" to s.pendingAttempt?.let(::attemptJson), "receipt" to s.receipt?.let(::receiptJson),
        "identity" to s.identity?.let(::identityJson),
        "trainedLanguages" to JSONArray(s.trainedLanguages.toList()), "machineSessions" to s.machineSessions, "progressPoints" to s.progressPoints, "doses" to s.doses, "upgradesGranted" to s.upgradesGranted,
        "cutscenesSeen" to JSONArray(s.cutscenesSeen.toList()), "usaUnlocked" to s.usaUnlocked,
        "lastPassedCountry" to s.lastPassedCountry, "lastPassedAirport" to s.lastPassedAirport, "flagged" to JSONObject(s.flagged)).apply {
            if (s.closed.isNotEmpty()) put("closed", JSONObject(s.closed))
            if (s.planted.isNotEmpty()) put("planted", JSONArray(s.planted))
            if (s.recentSeeds.isNotEmpty()) put("recentSeeds", JSONArray(s.recentSeeds))
            s.booking?.let { put("booking", bookingJson(it)) }
            s.act?.let { put("act", it.name) }
            if (s.whySceneSeen) put("whySceneSeen", true)
            if (s.visitSceneSeen) put("visitSceneSeen", true)
            if (s.thalassiaSeen) put("thalassiaSeen", true)
            if (s.newsSeen.isNotEmpty()) put("newsSeen", JSONArray(s.newsSeen.toList()))
            if (s.solvaneSeen) put("solvaneSeen", true)
            if (s.translatorUsed) put("translatorUsed", true)
            if (s.translatorUnlocked) put("translatorUnlocked", true)
            if (s.daysAway > 0) put("daysAway", s.daysAway)
            if (s.walkedAway) put("walkedAway", true)
            if (s.marekReturned) put("marekReturned", true)
            s.epilogueFrame?.let { put("epilogueFrame", it) }
            s.epiloguePassed?.let { put("epiloguePassed", it) }
            s.epilogueDoses?.let { put("epilogueDoses", it) }
            put("usaCompleted", s.usaCompleted) // always written: the key marks the new format
            s.act1Stage?.let { put("act1Stage", it.name) }
            if (s.prologueFrame != 0) put("prologueFrame", s.prologueFrame)
            s.storyLastCountry?.let { put("storyLastCountry", it) }
            if (s.upgradeDrops.isNotEmpty()) put("upgradeDrops", JSONObject(s.upgradeDrops.mapKeys { it.key.toString() }))
            if (s.encountersSeen.isNotEmpty()) put("encountersSeen", JSONArray(s.encountersSeen.toList()))
        }
}

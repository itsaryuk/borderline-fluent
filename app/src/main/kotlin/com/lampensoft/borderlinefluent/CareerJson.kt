package com.lampensoft.borderlinefluent

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

sealed interface CareerRead {
    data class Ready(val snapshot: CareerSnapshot, val needsWrite: Boolean = false, val original: CareerSnapshot = snapshot) : CareerRead
    data class Unreadable(val raw: String?, val snapshot: CareerSnapshot? = null, val preparationOnly: Boolean = false) : CareerRead
}

data class CareerInput(
    val snapshot: String? = null, val marker: Int? = null,
    val counters: CareerCounters = CareerCounters(), val countries: Set<String> = emptySet(),
)

fun loadCareer(input: CareerInput): CareerRead {
    if (input.snapshot != null) return when (val read = CareerJson.decode(input.snapshot)) {
        is CareerRead.Ready -> {
            val candidate = CountryMigration.migrate(read.snapshot)
            read.copy(snapshot = candidate, original = read.snapshot,
                needsWrite = candidate != read.snapshot || input.marker != candidate.schemaVersion)
        }
        is CareerRead.Unreadable -> read
    }
    if (input.marker != null) return CareerRead.Unreadable(null)
    val snapshot = migrateCareer(input.counters, input.countries).copy(contentVersion = 1)
    return when (val read = CareerJson.decode(CareerJson.encode(snapshot))) {
        is CareerRead.Ready -> read.copy(snapshot = CountryMigration.migrate(read.snapshot), needsWrite = true)
        is CareerRead.Unreadable -> CareerRead.Unreadable(null)
    }
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
            require(r.int("schemaVersion") == 1)
            val version = if (root.has("contentVersion")) r.int("contentVersion") else 1
            require(version in 1..3)
            require(version != 1 || !root.has(CountryMigration.ARCHIVE))
            val counters = counters(r.obj("counters"))
            val countries = r.strings("countries").toSet()
            val album = album(r.obj("album"))
            val next = r.long("nextAttemptId")
            val applied = r.long("appliedThroughId")
            require(next > applied && applied >= 0)
            val receipt = r.nullObj("receipt")?.let(::receipt)
            require(receipt == null || receipt.id == applied && receipt.id > 0)
            val pending = r.value("pendingAttempt")
            history = CareerSnapshot(1, counters, countries, album, next, applied, receipt = receipt, extra = r.extra(), contentVersion = version)
            val a = if (pending == JSONObject.NULL) null else attempt(pending as JSONObject)
            require(a == null || a.id > applied && a.id < next)
            CareerRead.Ready(history.copy(pendingAttempt = a))
        } catch (_: Exception) {
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
        val contracts = if (json.has("contracts")) map(r.obj("contracts"), ::meta).also { m ->
            require(m.keys.all { Contract.fromId(it) != null })
        } else emptyMap()
        Album(historical, infiltration, sabotage, units, r.extra(), clears, contracts)
    }
    private fun albumJson(a: Album) = obj(a.extra, "historicalCleared" to JSONArray(a.historicalCleared.toList()),
        "infiltration" to mapJson(a.infiltration, ::metaJson), "sabotage" to mapJson(a.sabotage, ::metaJson),
        "units" to mapJson(a.units) { mapJson(it, ::metaJson) }, "clears" to JSONObject(a.clears),
        "contracts" to mapJson(a.contracts, ::metaJson))

    private fun mark(json: JSONObject): NewMark = Reader(json).let { r ->
        val kind = r.string("kind"); val country = r.string("countryId"); val officer = r.nullString("officerId")
        NewMark(kind, country, officer, r.extra())
    }
    internal fun markJson(m: NewMark) = obj(m.extra, "kind" to m.kind, "countryId" to m.countryId, "officerId" to m.officerId)
    private fun receipt(json: JSONObject): Receipt = Reader(json).let { r ->
        val id = r.long("id"); val outcome = ReplayOutcome.valueOf(r.string("outcome"))
        val marks = r.array("newMarks").let { a -> (0 until a.length()).map { mark(a.getJSONObject(it)) } }
        val contract = r.nullString("contract")?.also { require(Contract.fromId(it) != null) }
        require(marks.filter { it.kind == "contract" }.all { mark ->
            val awarded = markContract(mark)
            awarded != null && awarded.id == contract
        })
        Receipt(id, outcome, marks, r.extra(), contract)
    }
    private fun receiptJson(r: Receipt) = obj(r.extra, "id" to r.id, "outcome" to r.outcome.name,
        "newMarks" to JSONArray(r.newMarks.map(::markJson)), "contract" to r.contract)

    private fun preparation(json: JSONObject): Preparation = Reader(json).let { r ->
        val name = r.string("name"); val latin = r.string("latin"); val female = r.bool("female")
        val p = Reader(r.obj("photo"))
        val photoFemale = p.bool("female"); val sheet = p.string("sheet"); val tile = p.int("tile").also { require(it in 0..5) }
        val photo = Person(photoFemale, sheet, tile)
        val country = r.string("countryId"); val passport = r.string("passportNo"); val born = r.string("born")
        val home = r.string("home"); val job = r.string("job"); val from = r.string("from"); val city = r.string("city")
        val purpose = r.string("purpose"); val days = r.int("days"); val stayed = r.string("stayed")
        val with = r.string("travelledWith"); val met = r.string("met"); val waiting = r.string("waiting"); val stamps = r.strings("stamps")
        val m = Reader(r.obj("persona"))
        val persona = Persona(m.fraction("patience"), m.fraction("suspicion"), m.fraction("strictness"),
            m.fraction("tolerance"), m.fraction("busy"), m.level("level"))
        Preparation(name, latin, female, photo, country, passport, born, home, job, from, city, purpose, days,
            stayed, with, met, waiting, stamps, persona, r.extra(), p.extra(), m.extra())
    }
    private fun preparationJson(p: Preparation) = obj(p.extra,
        "name" to p.name, "latin" to p.latin, "female" to p.female,
        "photo" to obj(p.photoExtra, "female" to p.photo.female, "sheet" to p.photo.sheet, "tile" to p.photo.tile),
        "countryId" to p.countryId, "passportNo" to p.passportNo, "born" to p.born, "home" to p.home, "job" to p.job,
        "from" to p.from, "city" to p.city, "purpose" to p.purpose, "days" to p.days, "stayed" to p.stayed,
        "travelledWith" to p.travelledWith, "met" to p.met, "waiting" to p.waiting, "stamps" to JSONArray(p.stamps),
        "persona" to obj(p.personaExtra, "patience" to p.persona.patience, "suspicion" to p.persona.suspicion,
            "strictness" to p.persona.strictness, "tolerance" to p.persona.tolerance, "busy" to p.persona.busy, "level" to p.persona.level))

    private fun attempt(json: JSONObject): Attempt = Reader(json).let { r ->
        val id = r.long("id").also { require(it > 0) }; val country = r.string("countryId"); val officer = r.string("officerId")
        val language = r.string("language"); val training = r.bool("training"); val story = r.bool("story"); val eligible = r.bool("replayEligible")
        require(!eligible || !training && !story)
        val level = r.level("effectiveLevel"); val version = r.int("contentVersion").also { require(it > 0) }
        val airport = r.nullString("airportId")
        if (version == 2) {
            val slot = Country.find(country)?.airports?.find { it.id == airport }
            require(slot != null && slot.lang == language && officer in slot.officers)
        }
        val loadout = r.nullString("loadoutId")
        val desk = r.nullString("deskDecision")?.let(Decision::valueOf)
        val assessment = r.nullString("assessmentDecision")?.let(Decision::valueOf)
        val broken = r.bool("everBroken"); val debug = r.bool("debugForced")
        val preparation = r.nullObj("preparation")?.let(::preparation)
        require(preparation != null && preparation.countryId == country && preparation.persona.level == level)
        val status = AttemptStatus.valueOf(r.string("status"))
        val contract = r.nullString("contract")?.also { require(Contract.fromId(it) != null) }
        Attempt(id, country, officer, language, training, story, eligible, level, version, loadout, desk, assessment,
            broken, debug, preparation, status, r.extra(), airport, contract)
    }
    private fun attemptJson(a: Attempt) = obj(a.extra, "id" to a.id, "countryId" to a.countryId, "officerId" to a.officerId,
        "airportId" to a.airportId, "language" to a.language, "training" to a.training, "story" to a.story, "replayEligible" to a.replayEligible,
        "effectiveLevel" to a.effectiveLevel, "contentVersion" to a.contentVersion, "loadoutId" to a.loadoutId,
        "deskDecision" to a.deskDecision?.name, "assessmentDecision" to a.assessmentDecision?.name,
        "everBroken" to a.everBroken, "debugForced" to a.debugForced, "preparation" to a.preparation?.let(::preparationJson), "status" to a.status.name,
        "contract" to a.contract)

    private fun snapshotJson(s: CareerSnapshot) = obj(s.extra, "schemaVersion" to s.schemaVersion,
        "contentVersion" to s.contentVersion, "counters" to countersJson(s.counters), "countries" to JSONArray(s.countries.toList()), "album" to albumJson(s.album),
        "nextAttemptId" to s.nextAttemptId, "appliedThroughId" to s.appliedThroughId,
        "pendingAttempt" to s.pendingAttempt?.let(::attemptJson), "receipt" to s.receipt?.let(::receiptJson))
}

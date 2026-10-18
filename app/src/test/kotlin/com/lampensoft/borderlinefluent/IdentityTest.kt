package com.lampensoft.borderlinefluent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class IdentityTest {
    @Test fun rollUsesTheNationalitysNamesCitiesAndTheGendersList() {
        for (n in Nationality.all) for (g in Gender.entries) for (l in NameList.entries) repeat(5) { i ->
            val id = Identity.roll(Random(i + n.id.hashCode()), n, g, l)
            val list = when (g) { Gender.WOMAN -> NameList.FEMALE; Gender.MAN -> NameList.MALE; else -> l }
            assertEquals(list, id.nameList)
            val names = NAMES.getValue(n.nameLang).let { if (list == NameList.FEMALE) it.female else it.male }
            assertTrue(id.latin, names.any { it.latin in id.latin })
            assertTrue(id.home in CITIES.getValue(n.id))
            assertTrue(id.job.isEmpty())
            assertTrue(id.passportNo.startsWith(n.iso) && id.passportNo.length == n.iso.length + 7)
            assertNull(id.photo)
        }
    }

    @Test fun backstoryBridgeTakesThePassportHalfFromTheIdentity() {
        val c = Country.all.first(); val a = c.airports.first()
        val id = Identity.roll(Random(3), Nationality.USA, Gender.NONBINARY, NameList.FEMALE).copy(photo = Person(false, "travelers_sheet", 2))
        val s = Backstory.random(Random(4), Destination(c, a), id)
        assertEquals(listOf(id.name, id.latin, id.born, id.home, id.job, id.passportNo), listOf(s.name, s.latin, s.born, s.home, s.job, s.passportNo))
        assertEquals(Gender.NONBINARY, s.gender); assertEquals(id.photo, s.photo); assertEquals(c, s.country)
        val unsaid = Backstory.random(Random(4), Destination(c, a), id.copy(gender = Gender.UNSAID, nameList = NameList.MALE, photo = null))
        assertEquals(Gender.UNSAID, unsaid.gender); assertFalse(unsaid.photo.female) // the sample face follows the name list
    }

    @Test fun identityRoundTripsInTheCareerSnapshot() {
        val id = Identity.roll(Random(7), Nationality.find("ukraine")!!, Gender.UNSAID, NameList.MALE)
        for (s in listOf(CareerSnapshot(identity = id), CareerSnapshot(identity = id.copy(photo = Person(true, "travelers2_sheet", 5))), CareerSnapshot()))
            assertEquals(s, decoded(CareerJson.encode(s)))
        // A save from before the identity existed (no key) reads as "not rolled yet".
        val raw = JSONObject(CareerJson.encode(CareerSnapshot())).apply { remove("identity") }.toString()
        assertNull(decoded(raw).identity)
        // WOMAN with the male list, or an unknown nationality, is damage: the identity is asked for again, the career survives.
        for (bad in listOf("gender" to "WOMAN", "nationality" to "atlantis")) {
            val json = JSONObject(CareerJson.encode(CareerSnapshot(identity = id)))
            json.getJSONObject("identity").put(bad.first, bad.second)
            assertNull(decoded(json.toString()).identity)
        }
    }
}

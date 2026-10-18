package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeCourierTest {
    private val de = Country.of("de"); private val fr = Country.of("fr"); private val es = Country.of("es")
    private fun free(vararg p: Pair<String, Int>, granted: Int = 1, home: String? = null, last: String? = null) = CareerSnapshot(
        act = Act.FREE, upgradesGranted = granted, lastPassedCountry = last, album = Album(clears = p.toMap(), historicalCleared = p.map { it.first }.toSet()),
        identity = home?.let { Identity.roll(kotlin.random.Random(1), Nationality.find(it)!!, Gender.WOMAN, NameList.FEMALE) })

    @Test fun destinationPrefersPassedThenHomeThenAny() {
        val pool = listOf(es, fr, de)
        assertEquals(mapOf(1 to "fr"), chooseUpgradeDrops(free("fr" to 1, home = "de"), pool))
        assertEquals(mapOf(1 to "de"), chooseUpgradeDrops(free(home = "de"), pool))
        assertEquals(mapOf(1 to "de"), chooseUpgradeDrops(free(), pool)) // first by id
        assertTrue(chooseUpgradeDrops(free(), emptyList()).isEmpty())
    }

    @Test fun storedDestinationNeverChangesAndLevelsShareTheLowerCountry() {
        val s = free("fr" to 1, granted = 2).copy(upgradeDrops = mapOf(1 to "es"))
        assertEquals(mapOf(2 to "es"), chooseUpgradeDrops(s, listOf(de, fr, es)))
        // Levels 1 and 2 pending: both wait in the same country, so one crossing delivers level 1 and then level 2.
        val both = chooseUpgradeDrops(free(granted = 2), listOf(de, fr, es))
        assertEquals(mapOf(1 to "de", 2 to "de"), both)
        val two = free(granted = 2).copy(upgradeDrops = both)
        assertEquals(1, courierDelivery(two.copy(lastPassedCountry = "de")))
        assertEquals(2, courierDelivery(two.copy(lastPassedCountry = "de", cutscenesSeen = setOf(1))))
        // Stored destinations are never re-picked: only new levels are returned.
        assertTrue(chooseUpgradeDrops(s.copy(upgradeDrops = mapOf(1 to "es", 2 to "de")), listOf(fr)).isEmpty())
    }

    @Test fun courierDeliversOnlyAfterACrossingAtTheDestination() {
        val s = free(granted = 2).copy(upgradeDrops = mapOf(1 to "fr", 2 to "de"))
        assertNull(courierDelivery(s.copy(lastPassedCountry = "es")))
        assertNull(courierDelivery(s.copy(lastPassedCountry = "de"))) // level 2 waits for level 1
        assertEquals(1, courierDelivery(s.copy(lastPassedCountry = "fr")))
        assertEquals(2, courierDelivery(s.copy(lastPassedCountry = "de", cutscenesSeen = setOf(1))))
    }

    @Test fun perksBuildAndUsaFollowDeliveredUpgrades() {
        val s = free(granted = 3)
        assertEquals(0, deliveredUpgrades(s))
        assertEquals(1, deliveredUpgrades(s.copy(cutscenesSeen = setOf(1))))
        assertEquals(0, deliveredUpgrades(s.copy(cutscenesSeen = setOf(2)))) // in order only
        assertEquals(3, deliveredUpgrades(s.copy(cutscenesSeen = setOf(1, 2, 3))))
        assertTrue(!s.usaUnlocked)
    }

    @Test fun marekWritesOnceTheDestinationIsPicked() {
        val s = free(granted = 2)
        assertTrue(deliveredMessages(s, Act1Stage.DONE).none { it.id.startsWith("marek") })
        val m = deliveredMessages(s.copy(upgradeDrops = mapOf(1 to "fr", 2 to "de")), Act1Stage.DONE)
        assertEquals(listOf("valery_1", "marek_1", "marek_2"), m.map { it.id })
        assertEquals(listOf(null, "fr", "de"), m.map { it.country })
    }

    @Test fun dropsRoundTripAndOldSavesLoad() {
        val s = free(granted = 2).copy(upgradeDrops = mapOf(1 to "fr", 2 to "de"))
        assertEquals(s.upgradeDrops, decoded(CareerJson.encode(s)).upgradeDrops)
        val old = org.json.JSONObject(CareerJson.encode(s)).apply { remove("upgradeDrops") }
        assertTrue(decoded(old.toString()).upgradeDrops.isEmpty())
        val bad = org.json.JSONObject(CareerJson.encode(s)).apply { put("upgradeDrops", org.json.JSONObject("""{"1":"fr","3":"de","2":"nowhere","x":"de"}""")) }
        assertEquals(mapOf(1 to "fr"), decoded(bad.toString()).upgradeDrops)
    }
}

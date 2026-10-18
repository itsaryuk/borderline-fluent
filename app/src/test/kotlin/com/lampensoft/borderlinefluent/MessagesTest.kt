package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {
    @Test fun valeryTextArrivesWhenActOneIsDoneAndStaysUnreadUntilRead() {
        for (stage in Act1Stage.entries.filter { it != Act1Stage.DONE })
            assertTrue("$stage", deliveredMessages(CareerSnapshot(act = Act.STORY), stage).isEmpty())
        val delivered = deliveredMessages(CareerSnapshot(act = Act.STORY), Act1Stage.DONE)
        assertEquals(listOf("valery_1"), delivered.map { it.id })
        assertEquals(delivered, unreadMessages(delivered, emptySet()))
        assertTrue(unreadMessages(delivered, setOf("valery_1")).isEmpty())
        assertTrue(unreadMessages(delivered, setOf("other")).size == 1)
    }

    @Test fun timeStampsHaveNoYear() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val en = java.util.Locale.US
        val now = 1_800_000_000_000L // a Friday afternoon
        val h = 3_600_000L; val d = 24 * h
        fun t(at: Long, is24: Boolean = true) = messageTime(at, now, en, is24, "Yesterday", "d MMM", utc)
        assertEquals(java.text.SimpleDateFormat("HH:mm", en).apply { timeZone = utc }.format(now - 1000), t(now - 1000))
        assertTrue(t(now, false).endsWith("M"))
        assertEquals("Yesterday", t(now - d))
        assertEquals(java.text.SimpleDateFormat("EEEE", en).apply { timeZone = utc }.format(now - 3 * d), t(now - 3 * d))
        assertEquals(java.text.SimpleDateFormat("d MMM", en).apply { timeZone = utc }.format(now - 10 * d), t(now - 10 * d))
        assertTrue(!t(now - 400 * d).contains("20"))
    }

    @Test fun marekUsaArrivesOnceTheUsaIsUnlockedUnlessWalkedAway() {
        fun has(s: CareerSnapshot?) = deliveredMessages(s, Act1Stage.DONE).any { it.id == "marek_usa" }
        assertTrue(has(CareerSnapshot(usaUnlocked = true)))
        assertEquals(false, has(CareerSnapshot()))
        assertEquals(false, has(CareerSnapshot(usaUnlocked = true, walkedAway = true)))
        assertEquals(false, has(null))
    }
}

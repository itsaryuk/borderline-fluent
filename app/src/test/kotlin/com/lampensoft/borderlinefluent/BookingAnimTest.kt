package com.lampensoft.borderlinefluent

import kotlin.random.Random
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class BookingAnimTest {
    @Test fun decoysEndOnRealAndNeverContainItEarlier() {
        val pool = listOf("a", "b", "c", "d", "e", "f", "g", "real", "a")
        repeat(20) {
            val c = decoyCycle("real", pool, 5, Random(it))
            assertEquals("real", c.last())
            assertEquals(6, c.size)
            assertTrue("real" !in c.dropLast(1))
            assertEquals(c.size, c.distinct().size)
        }
    }
    @Test fun smallPoolStillEndsOnReal() = assertEquals(listOf("x", "real"), decoyCycle("real", listOf("real", "x"), 5, Random(1)))
}

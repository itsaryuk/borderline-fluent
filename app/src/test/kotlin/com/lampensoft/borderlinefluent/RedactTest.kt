package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {
    private val text = (1..200).joinToString(" ") { "w$it" }

    @Test fun deterministicKeepsFirstMergesAndRoughlyHalf() {
        val a = redact(text, 7, "[R]")
        assertEquals(a, redact(text, 7, "[R]"))
        assertEquals("w1", a.first().first.substringBefore(' '))
        assertTrue(!a.first().second)
        assertTrue(a.zipWithNext().none { (x, y) -> x.second && y.second })
        val kept = a.filter { !it.second }.sumOf { it.first.split(' ').count { w -> w.isNotBlank() } }
        assertTrue("kept $kept", kept in 60..140)
    }

    @Test fun cjkWithoutSpaces() {
        val t = "今日は天気がとてもいいので散歩に行きましょう明日も晴れるといいですね"
        val a = redact(t, 3, "[R]")
        assertTrue(a.any { it.second })
        assertTrue(!a.first().second && a.first().first.startsWith("今日は"))
        assertEquals(a, redact(t, 3, "[R]"))
    }
}

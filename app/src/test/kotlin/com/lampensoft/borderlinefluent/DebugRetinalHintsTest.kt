package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test

class DebugRetinalHintsTest {
    @Test fun childReplyIsAvailableOnlyAtTheDebugScriptedQuestion() {
        for (debug in listOf(false, true)) {
            for (scripted in listOf(false, true)) {
                for (line in listOf(ThalassiaDesk.CHILD, ThalassiaDesk.LINES.first(), ThalassiaDesk.CHILD_LAST)) {
                    val hints = debugRetinalHints(emptyList(), debug, scripted, line)
                    assertEquals(debug && scripted && line == ThalassiaDesk.CHILD, hints.isNotEmpty())
                    if (hints.isNotEmpty()) {
                        val desk = ThalassiaDesk(childTaken = true)
                        desk.answer("Here.")
                        desk.answer(hints.single().target)
                        assertTrue(desk.fatherAway)
                    }
                }
            }
        }
    }

    @Test fun existingSuggestionsArePreservedInBothBuilds() {
        val hints = listOf(Hint("Here.", "", ""))
        for (debug in listOf(false, true)) {
            for (line in listOf(ThalassiaDesk.CHILD, ThalassiaDesk.LINES.first(), "Ordinary question")) {
                assertSame(hints, debugRetinalHints(hints, debug, true, line))
            }
        }
    }
}

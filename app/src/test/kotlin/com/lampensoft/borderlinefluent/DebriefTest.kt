package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Test

class DebriefTest {
    @Test fun emptyLogIsClean() = assertEquals(listOf(Msg(R.string.debrief_none)), debrief(emptyList()))

    @Test fun groupsRepeatsAndLeadsWithStrongest() {
        val d = debrief(listOf(
            BehaviorEvent(Tell.LIPS, 2.5, "Why?"), BehaviorEvent(Tell.BLINKS, 3.0, "How long are you staying?"),
            BehaviorEvent(Tell.LIPS, 2.1, "Where to?"), BehaviorEvent(null, 0.0, "Where to?")))
        assertEquals(listOf(
            Msg(Tell.BLINKS.debriefAsk, listOf(1, "How long are you staying?"), 1),
            Msg(Tell.LIPS.debrief, listOf(2), 2),
            Msg(R.plurals.debrief_away, listOf(1), 1)), d)
    }

    @Test fun capsAtThreeSentences() {
        val log = Tell.entries.mapIndexed { i, t -> BehaviorEvent(t, 2.0 + i, "q") } + BehaviorEvent(null, 0.0, "q")
        assertEquals(3, debrief(log).size)
    }

    @Test fun manyLookAways() = assertEquals(listOf(Msg(R.plurals.debrief_away, listOf(3), 3)), debrief(List(3) { BehaviorEvent(null, 0.0, "q") }))
}

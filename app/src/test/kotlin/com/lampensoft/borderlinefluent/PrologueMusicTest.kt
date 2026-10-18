package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Test

class PrologueMusicTest {
    @Test fun everyFrameAndTurnHasTheOwnersCueIncludingColdStarts() {
        val soft = PrologueCue.SOFT
        val hard = PrologueCue.HARD
        val expected = listOf(
            listOf(soft), listOf(soft), listOf(soft), listOf(soft), listOf(soft),
            listOf(soft, soft, soft, soft), listOf(soft, soft), listOf(soft, soft),
            listOf(soft, soft, soft, hard), listOf(hard), listOf(hard, hard),
            listOf(hard, hard, hard, hard), listOf(hard, hard), listOf(hard, hard),
            listOf(hard), listOf(hard), listOf(hard), listOf(hard)
        )
        assertEquals(expected.map { it.size }, PROLOGUE_FRAMES.map { it.size })
        PROLOGUE_FRAMES.forEachIndexed { frame, turns ->
            turns.indices.forEach { turn ->
                assertEquals("frame $frame turn $turn", expected[frame][turn], cueFor(frame, turn))
            }
        }
        // Lookup is independent of playback history, including returning from the airport or a kill.
        PROLOGUE_FRAMES.indices.reversed().forEach { frame ->
            assertEquals(expected[frame][0], cueFor(frame, 0))
        }
    }

    @Test fun transitionsBelongToTheExactStoryLines() {
        val changes = mutableListOf<Pair<Int, PrologueCue>>()
        var previous: PrologueCue? = null
        PROLOGUE_FRAMES.forEachIndexed { frame, turns ->
            turns.forEachIndexed { turn, line ->
                val cue = cueFor(frame, turn)
                if (cue != previous) changes += line.text to cue
                previous = cue
            }
        }
        assertEquals(listOf(
            R.string.prologue_friend to PrologueCue.SOFT,
            R.string.prologue_realize to PrologueCue.HARD
        ), changes)
    }
}

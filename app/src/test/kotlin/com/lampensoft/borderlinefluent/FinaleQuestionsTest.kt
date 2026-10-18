package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FinaleQuestionsTest {
    @Test fun eitherQuestionCanBeAskedFirstAndOnlyAskedQuestionsDisappear() {
        for (first in FinaleQuestion.entries) {
            val other = FinaleQuestion.entries.single { it != first }
            val initial = FinaleQuestions()
            assertEquals(FinaleQuestion.entries.toList(), initial.available)
            val answering = initial.ask(first)
            assertEquals(first, answering.active)
            assertEquals(listOf(other), answering.available)
            assertEquals(answering, answering.ask(other))
            val returned = answering.finish()
            assertNull(returned.active)
            assertEquals(listOf(other), returned.available)
            assertEquals(returned, returned.ask(first))
            val finished = returned.ask(other).finish()
            assertNull(finished.active)
            assertTrue(finished.available.isEmpty())
            assertEquals(2, FinaleQuestions().available.size)
        }
    }

    @Test fun buttonTextIsThePlayerTurnFollowedByTheManOnTheCorrectSides() {
        for (question in FinaleQuestion.entries) {
            assertEquals(listOf(question.question, question.answer), question.turns.map { it.text })
            assertEquals(listOf(PrologueSpeaker.YOU, PrologueSpeaker.MAN_FRIEND), question.turns.map { it.speaker })
            assertEquals(listOf(PrologueSide.RIGHT, PrologueSide.LEFT), question.turns.map { it.side })
        }
    }

    @Test fun choicesStayOutsideTheDialogueBranchAndQuestionsUseTheAccessibleStoryRenderer() {
        val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
        val source = File(main, "kotlin/com/lampensoft/borderlinefluent/Ui.kt").readText()
        val finale = source.substringAfter("private fun FinaleScreen(").substringBefore("private val CREDITS")
        assertTrue(finale.contains("remember { mutableStateOf(FinaleQuestions()) }"))
        assertTrue(finale.contains("StoryTurns(g, active.ordinal, EPILOGUE_ART[7], active.turns)"))
        assertTrue(finale.contains("questions = questions.finish()"))
        assertTrue(finale.contains("PrimaryButton(stringResource(question.question), enabled = g.careerReady) { questions = questions.ask(question) }"))
        // Closing the active/choice branch before these controls keeps them available during either turn.
        assertTrue(finale.lines().joinToString("\n") { it.trim() }.contains("""
            }
            PrimaryButton(stringResource(R.string.finale_keep), enabled = g.careerReady) { keeping = true }
        """.trimIndent()))
        val renderer = source.substringAfter("private fun StoryTurns(").substringBefore("private fun DialogueCard(")
        assertTrue(renderer.contains("R.string.prologue_speaker_line, current.speaker.label(stranger, you, manStranger, friend, staff), line"))
        assertTrue(renderer.contains("contentDescription = announcement"))
        assertTrue(renderer.contains("liveRegion = LiveRegionMode.Polite"))
    }

    @Test fun keepHelpingBeatPlaysMarekNarratorMarek() {
        assertEquals(listOf(PrologueSpeaker.MAN_FRIEND, PrologueSpeaker.NARRATOR, PrologueSpeaker.MAN_FRIEND),
            FINALE_KEEP_TURNS.map { it.speaker })
        assertEquals(listOf(R.string.finale_keep_m1, R.string.finale_keep_n, R.string.finale_keep_m2),
            FINALE_KEEP_TURNS.map { it.text })
    }
}

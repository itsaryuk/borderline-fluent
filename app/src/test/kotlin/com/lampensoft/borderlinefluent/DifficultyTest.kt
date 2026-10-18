package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Test

class DifficultyTest {
    @Test fun contractsOpenFromActTwoRankZeroOutsideTraining() {
        assertEquals(true, contractsUnlocked(finaleSeen = true, training = false))
        assertEquals(false, contractsUnlocked(finaleSeen = false, training = false))
        assertEquals(false, contractsUnlocked(finaleSeen = true, training = true))
    }

    @Test fun trainingAlwaysUsesLevelZero() {
        for (finale in listOf(false, true)) for (rank in 0..3) for (chosen in listOf(-1, 0, 1, 2, 3, 7))
            assertEquals(0, effectiveLevel(training = true, finaleSeen = finale, careerLevel = rank, chosen = chosen))
    }

    @Test fun firstStoryKeepsCareerDifficulty() {
        for (rank in 0..3) for (chosen in 0..3)
            assertEquals(rank, effectiveLevel(training = false, finaleSeen = false, careerLevel = rank, chosen = chosen))
    }

    @Test fun replayUsesChosenDifficultyRegardlessOfRank() {
        for (rank in 0..3) for (chosen in 0..3)
            assertEquals(chosen, effectiveLevel(training = false, finaleSeen = true, careerLevel = rank, chosen = chosen))
    }

    @Test fun replayClampsChosenDifficulty() {
        assertEquals(3, effectiveLevel(false, true, 0, 7))
        assertEquals(0, effectiveLevel(false, true, 3, -1))
    }

    @Test fun trapsFollowTheAttemptLevel() {
        listOf(0.0, 0.15, 0.25, 0.35).forEachIndexed { level, chance ->
            assertEquals(chance, trapChance(level), 0.0)
        }
    }

    @Test fun trapChanceClampsBothEnds() {
        assertEquals(0.0, trapChance(-1), 0.0)
        assertEquals(0.35, trapChance(9), 0.0)
    }
}

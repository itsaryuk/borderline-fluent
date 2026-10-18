package com.lampensoft.borderlinefluent

import android.animation.ValueAnimator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Motion (glitches, flashes, typing, scanners, parallax) runs only when the phone allows animations AND the player has not turned on Reduce motion. */
fun motionAllowed(systemAnimates: Boolean, reduceMotion: Boolean) = systemAnimates && !reduceMotion

/** The in-app Reduce motion switch (owned and persisted by [Game]) and the one check every motion site reads. */
object Motion {
    var reduce by mutableStateOf(false)
    fun systemAnimates() = ValueAnimator.areAnimatorsEnabled()
    /** Default for a first run: on when the phone has animations off. */
    fun defaultReduce() = !systemAnimates()
    /** Reads Compose state, so a composable calling it recomposes when the switch flips. */
    fun allowed() = motionAllowed(systemAnimates(), reduce)
}

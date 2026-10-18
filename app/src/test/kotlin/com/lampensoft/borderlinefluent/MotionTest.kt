package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTest {
    @Test fun systemTimesUser() {
        assertEquals(true, motionAllowed(systemAnimates = true, reduceMotion = false))
        assertEquals(false, motionAllowed(systemAnimates = true, reduceMotion = true))
        assertEquals(false, motionAllowed(systemAnimates = false, reduceMotion = false))
        assertEquals(false, motionAllowed(systemAnimates = false, reduceMotion = true))
    }
}

package com.lampensoft.borderlinefluent

import com.google.mlkit.genai.common.GenAiException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundBlockedTest {
    @Test fun errorCode30AnywhereInTheChainIsBackgroundBlocked() {
        val blocked = GenAiException("Background usage is blocked", null, GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED)
        assertTrue(backgroundBlocked(blocked))
        assertTrue(backgroundBlocked(RuntimeException("wrapped", blocked)))
    }

    @Test fun otherFailuresAreNot() {
        assertFalse(backgroundBlocked(GenAiException("busy", null, GenAiException.ErrorCode.BUSY)))
        assertFalse(backgroundBlocked(IllegalStateException("no model")))
    }
}

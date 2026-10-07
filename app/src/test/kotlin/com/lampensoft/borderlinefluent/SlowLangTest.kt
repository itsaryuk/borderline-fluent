package com.lampensoft.borderlinefluent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowLangTest {
    @Test fun slowOnlyWhenProbedWithoutSystemRecognizer() {
        val probe = mapOf("de" to true, "ar" to false)
        assertTrue(slowLang(probe, "ar"))
        assertFalse(slowLang(probe, "de"))
        assertFalse(slowLang(probe, "xx")) // not probed
        assertFalse(slowLang(null, "ar")) // probe still running
    }
}

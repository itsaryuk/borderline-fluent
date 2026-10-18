package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureTurnTest {
    @Test fun emptyTranscriptsNeverSelectFragmentFollow() {
        listOf("", " ", "\n\t").forEach {
            assertEquals(TranscriptKind.EMPTY, transcriptKind(it))
        }
    }

    @Test fun realFragmentsStillSelectFollow() {
        listOf("Ich", "I…", "ähm").forEach {
            assertEquals(TranscriptKind.FRAGMENT, transcriptKind(it))
        }
    }

    @Test fun completeAnswersDoNotSelectFollow() {
        assertEquals(TranscriptKind.ANSWER, transcriptKind("Freitag."))
        assertEquals(TranscriptKind.ANSWER, transcriptKind("I am here for work."))
    }
}

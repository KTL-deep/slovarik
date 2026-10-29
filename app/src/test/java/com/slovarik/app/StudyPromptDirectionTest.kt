package com.slovarik.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyPromptDirectionTest {
    @Test fun defaultDirectionAlwaysStartsWithWord() {
        for (seed in 0L..20L) for (occurrence in 0L..20L) {
            assertTrue(promptIsWordFor(occurrence, false, seed))
        }
    }

    @Test fun randomDirectionIsStablePerOccurrenceButVariesAcrossSessions() {
        val firstSession = (0L..20L).map { promptIsWordFor(it, true, 0L) }
        assertEquals(firstSession, (0L..20L).map { promptIsWordFor(it, true, 0L) })
        assertTrue((1L..20L).any { seed ->
            (0L..20L).map { promptIsWordFor(it, true, seed) } != firstSession
        })
    }
}

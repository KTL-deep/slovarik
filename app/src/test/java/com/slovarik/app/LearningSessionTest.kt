package com.slovarik.app

import org.junit.Assert.*
import org.junit.Test

class LearningSessionTest {
    private fun card(id: Long, review: Review = Review()) = Card(id, 1, "word$id", "translation$id", review)

    @Test fun failureReturnsUpdatedCardAfterThreePendingCards() {
        val session = LearningSession.start((1L..5L).map(::card))
        val updated = card(1, Review(learningStep = 1, due = 600_000))
        val next = session.advance(updated, failed = true)
        assertEquals(listOf(2L, 3L, 4L, 1L, 5L), next.queue.drop(next.index).map { it.card.id })
        assertEquals(updated, next.queue[next.index + 3].card)
        assertNotEquals(session.current?.occurrence, next.queue[next.index + 3].occurrence)
    }

    @Test fun shortQueueRetriesImmediatelyButStopsAfterThreeFailures() {
        var session = LearningSession.start(listOf(card(1)))
        repeat(2) { attempt ->
            val previous = session.current!!
            session = session.advance(card(1, Review(learningStep = 1)), failed = true)
            assertNotEquals(previous.occurrence, session.current?.occurrence)
            assertEquals(attempt + 1, session.failures[1])
        }
        session = session.advance(card(1, Review(learningStep = 1)), failed = true)
        assertNull(session.current)
        assertEquals(3, session.failures[1])
    }

    @Test fun correctRetryDoesNotReturnToQueue() {
        var session = LearningSession.start(listOf(card(1)))
        session = session.advance(card(1, Review(learningStep = 1)), failed = true)
        session = session.advance(card(1, Review(learningStep = 2)), failed = false)
        assertNull(session.current)
        assertEquals(1, session.failures[1])
    }

    @Test fun undoRestoresSnapshotWithFreshOccurrenceEvenWhenSameCard() {
        val before = LearningSession.start(listOf(card(1)))
        val after = before.advance(card(1, Review(learningStep = 1)), failed = true)
        val restored = after.restore(before, card(1))
        assertEquals(card(1), restored.current?.card)
        assertNotEquals(before.current?.occurrence, restored.current?.occurrence)
        assertNotEquals(after.current?.occurrence, restored.current?.occurrence)
        assertTrue(restored.failures.isEmpty())
        assertEquals(1, restored.queue.drop(restored.index).count { it.card.id == 1L })
    }

    @Test fun recreatedSessionPrependsUndoCardWithoutPendingDuplicate() {
        val opened = LearningSession.start(listOf(card(2), card(1)))
        val restored = opened.prepend(card(1))
        assertEquals(listOf(1L, 2L), restored.queue.map { it.card.id })
        assertNotEquals(opened.queue[1].occurrence, restored.current?.occurrence)
    }
}

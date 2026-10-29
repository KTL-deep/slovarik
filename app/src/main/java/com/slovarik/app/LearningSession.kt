package com.slovarik.app

/** An occurrence, rather than a card ID, identifies the visible review and its UI state. */
data class LearningItem(val card: Card, val occurrence: Long)

data class LearningSession(
    val queue: List<LearningItem>,
    val index: Int = 0,
    val failures: Map<Long, Int> = emptyMap(),
    val nextOccurrence: Long = queue.size.toLong()
) {
    val current: LearningItem? get() = queue.getOrNull(index)

    /** Failed cards return after at most three others; the third failure waits for its DB due time. */
    fun advance(updated: Card, failed: Boolean): LearningSession {
        val count = failures.getOrDefault(updated.id, 0) + if (failed) 1 else 0
        val next = copy(index = index + 1, failures = if (failed) failures + (updated.id to count) else failures)
        if (!failed || count >= 3) return next
        val pending = next.queue.drop(next.index).filterNot { it.card.id == updated.id }
        val inserted = pending.toMutableList().apply { add(minOf(3, size), LearningItem(updated, nextOccurrence)) }
        return next.copy(queue = next.queue.take(next.index) + inserted, nextOccurrence = nextOccurrence + 1)
    }

    /** The restored card is always a fresh visual occurrence, even when it has the same ID. */
    fun restore(snapshot: LearningSession, card: Card): LearningSession {
        val generation = maxOf(nextOccurrence, snapshot.nextOccurrence)
        val remaining = snapshot.queue.drop(snapshot.index).filterNot { it.card.id == card.id }
        return snapshot.copy(queue = snapshot.queue.take(snapshot.index) + LearningItem(card, generation) + remaining,
            nextOccurrence = generation + 1)
    }

    /** Used when a newly opened Study screen has no local snapshot of the last grade. */
    fun prepend(card: Card): LearningSession {
        val pending = queue.drop(index).filterNot { it.card.id == card.id }
        return copy(queue = queue.take(index) + LearningItem(card, nextOccurrence) + pending, nextOccurrence = nextOccurrence + 1)
    }

    companion object {
        fun start(cards: List<Card>, generation: Long = 0): LearningSession =
            LearningSession(cards.mapIndexed { index, card -> LearningItem(card, generation + index) },
                nextOccurrence = generation + cards.size)
    }
}

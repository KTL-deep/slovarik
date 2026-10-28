package com.slovarik.app

import org.junit.Assert.*
import org.junit.Test

class StudyLogicTest {
    private val day = 86_400_000L

    @Test fun sm2FirstSecondAndLaterIntervals() {
        val first = schedule(Review(), 5, 0)
        assertEquals(1, first.intervalDays)
        assertEquals(day, first.due)
        val second = schedule(first, 5, day)
        assertEquals(6, second.intervalDays)
        val third = schedule(second, 5, second.due)
        assertTrue(third.intervalDays > 6)
        assertEquals(3, third.repetitions)
    }

    @Test fun failedReviewResetsStreakAndEaseIsBounded() {
        val previous = Review(8, 120, 1.3, 0)
        val failed = schedule(previous, 1, 123)
        assertEquals(0, failed.repetitions)
        assertEquals(1, failed.intervalDays)
        assertEquals(1.3, failed.ease, 0.00001)
        assertEquals(123 + day, failed.due)
    }

    @Test(expected = IllegalArgumentException::class) fun invalidGradeRejected() { schedule(Review(), 6, 0) }

    @Test fun csvTsvAndSemicolonParsing() {
        assertEquals(listOf(WordPair("good, morning", "доброе утро"), WordPair("hi", "привет"), WordPair("hello", "здравствуй")),
            parsePairs("word,translation\n\"good, morning\",\"доброе утро\"\nhi;привет\nhello\tздравствуй\ninvalid"))
    }

    @Test fun quotedEscapesAndBom() {
        assertEquals(listOf(WordPair("say \"yes\"", "сказать да")), parsePairs("\uFEFF\"say \"\"yes\"\"\";сказать да"))
        assertEquals("\"a\"\"b\"", csvField("a\"b"))
    }

    @Test fun separatorsInsideQuotesDoNotChangeCsvDialect() {
        assertEquals(listOf(WordPair("hello; friend", "привет, друг")),
            parsePairs("\"hello; friend\",\"привет, друг\""))
        assertEquals(listOf(WordPair("hello\tfriend", "привет")),
            parsePairs("\"hello\tfriend\";привет"))
    }

    @Test fun unclosedQuotedFieldIsNotImported() {
        assertTrue(parsePairs("hello;\"unfinished").isEmpty())
    }
}

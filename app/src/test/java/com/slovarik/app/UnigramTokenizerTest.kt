package com.slovarik.app

import org.junit.Assert.*
import org.junit.Test

class UnigramTokenizerTest {
    private val tokenizer = UnigramTokenizer(
        listOf("</s>", "<unk>", "▁book", "▁house", "▁", "b", "o", "k", "<pad>"),
        doubleArrayOf(0.0, 0.0, -2.0, -2.0, -5.0, -5.0, -5.0, -5.0, 0.0))

    @Test fun usesMaximumScorePathAndAddsEos() {
        assertArrayEquals(longArrayOf(2, 0), tokenizer.encode("book"))
        assertArrayEquals(longArrayOf(2, 3, 0), tokenizer.encode("  book   house "))
    }
    @Test fun normalizesCompatibilityCharacters() {
        assertArrayEquals(longArrayOf(2, 0), tokenizer.encode("ｂｏｏｋ"))
    }
    @Test fun decodesMetaspaceAndSkipsControlTokens() {
        assertEquals("book house", tokenizer.decode(listOf(8, 2, 3, 0)))
    }
    @Test fun unknownUnicodeDoesNotSplitSurrogatePairOrCrash() {
        assertTrue(tokenizer.encode("😀").contains(1L))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyInput() { tokenizer.encode("   ") }
}

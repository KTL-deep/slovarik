package com.slovarik.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationDirectionTest {
    @Test fun cyrillicInputIsTranslatedToEnglish() {
        assertEquals("ru" to "en", translationDirection("собака", "en", "ru"))
        assertEquals("ru" to "en", translationDirection("Привет, мир", "en", "ru"))
    }

    @Test fun latinInputIsTranslatedToRussian() {
        assertEquals("en" to "ru", translationDirection("dog", "ru", "en"))
        assertEquals("en" to "ru", translationDirection("Good morning", "ru", "en"))
    }

    @Test fun detectionOverridesTheConfiguredDirection() {
        assertEquals("ru" to "en", translationDirection("дом", "en", "ru"))
        assertEquals("en" to "ru", translationDirection("house", "en", "ru"))
    }

    @Test fun textWithoutLettersHasNoDirection() {
        assertNull(translationDirection("", "en", "ru"))
        assertNull(translationDirection(" 123 ", "en", "ru"))
        assertNull(translationDirection("!", "ru", "en"))
        assertNull(translationDirection("日本語", "en", "ru"))
    }

    @Test fun mixedTextUsesMajorityScriptAndConfiguredSourceOnTies() {
        assertEquals("ru" to "en", translationDirection("дом a", "en", "ru"))
        assertEquals("en" to "ru", translationDirection("house я", "ru", "en"))
        assertEquals("ru" to "en", translationDirection("я a", "ru", "en"))
        assertEquals("en" to "ru", translationDirection("я a", "en", "ru"))
    }
}

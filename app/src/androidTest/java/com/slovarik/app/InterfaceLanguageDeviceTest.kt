package com.slovarik.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Dedicated preferences: never change the user's language, reminders, or learning data. */
class InterfaceLanguageDeviceTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testPreferences = "slovarik-interface-language-tests"
    private val context get() = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences(testPreferences, mode)
    }

    @Before fun setUp() { base.getSharedPreferences(testPreferences, 0).edit().clear().commit() }
    @After fun tearDown() { base.getSharedPreferences(testPreferences, 0).edit().clear().commit() }

    @Test fun defaultsToRussianIndependentlyOfDeviceLocale() {
        assertEquals("ru", InterfaceLanguage.current(context))
        val localized = InterfaceLanguage.wrap(context)
        assertEquals("ru", localized.resources.configuration.locales[0].language)
        assertEquals("Пора повторить слова", localized.getString(R.string.reminder_title))
    }

    @Test fun switchingToEnglishDoesNotChangeVocabularyLanguages() {
        context.getSharedPreferences("settings", 0).edit()
            .putString("language", "de").putString("target", "fr").commit()
        InterfaceLanguage.set(context, "en")
        assertEquals("en", InterfaceLanguage.current(context))
        assertEquals("Time to review your words", InterfaceLanguage.wrap(context).getString(R.string.reminder_title))
        assertEquals("de", context.getSharedPreferences("settings", 0).getString("language", null))
        assertEquals("fr", context.getSharedPreferences("settings", 0).getString("target", null))
        InterfaceLanguage.set(context, "ru")
        assertEquals("ru", InterfaceLanguage.wrap(context).resources.configuration.locales[0].language)
    }

    @Test fun disabledReminderIsNeverReportedEnabled() {
        assertFalse(reminderEnabled(context))
        context.getSharedPreferences("settings", 0).edit().putBoolean("reminder", false).commit()
        assertFalse(reminderEnabled(context))
    }

    @Test fun unsupportedStoredLanguageFallsBackWithoutBreakingResources() {
        context.getSharedPreferences("settings", 0).edit().putString("interface_language", "xx").commit()
        assertEquals("ru", InterfaceLanguage.current(context))
        assertEquals("Карточек к повторению: 7", InterfaceLanguage.wrap(context).getString(R.string.reminder_count, 7))
    }
}

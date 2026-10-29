package com.slovarik.app

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class EditorFlowsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = "slovarik-editor-tests.db"
    private lateinit var store: Store

    @Before fun setUp() {
        context.deleteDatabase(database)
        store = Store(context, database)
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase(database)
    }

    private fun launch() {
        compose.setContent {
            val registryOwner = requireNotNull(LocalActivityResultRegistryOwner.current)
            LocalizedTestTheme("ru") {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                    App(store, speak = { _, _ -> }, skipWelcomeForTests = true)
                }
            }
        }
    }

    private fun fields() = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))

    private fun save() {
        // Leave the keyboard open: the dialog's Save button must remain reachable above the IME.
        compose.onNode((hasText("Сохранить") or hasText("Save")) and hasAnyAncestor(isDialog()))
            .performScrollTo().performClick()
    }

    @Test fun createsFolderDeckAndCardThenEditsBothValues() {
        val otherFolder = store.addFolder("Other folder")
        launch()

        compose.onNodeWithText("Новая папка").performClick()
        fields()[0].performTextInput("Editor folder")
        save()
        val folder = store.folders().single { it.name == "Editor folder" }
        assertTrue(folder.id != otherFolder)

        compose.onNodeWithText("Editor folder").performScrollTo().performClick()
        compose.onNodeWithText("Новая колода в «Editor folder»").performClick()
        compose.onNode(hasText("Папка: Editor folder") or hasText("Folder: Editor folder")).assertExists()
        fields()[0].performTextInput("Editor deck")
        save()
        val deck = store.decks(folder.id).single()
        assertEquals("Editor deck", deck.name)
        assertTrue(store.decks(otherFolder).isEmpty())

        compose.onNodeWithText("Editor deck").performClick()
        compose.onNodeWithText("Добавить карточку").performClick()
        fields()[0].performTextInput("book")
        fields()[1].performTextInput("книга")
        save()
        val card = store.cards(deck.id).single()
        assertEquals("book", card.word)
        assertEquals("книга", card.translation)

        compose.onNodeWithTag("word-tile-${card.id}").performClick()
        assertEquals("book", fields()[0].fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("книга", fields()[1].fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        fields()[0].performTextReplacement("books")
        fields()[1].performTextReplacement("книги")
        save()
        assertEquals("books", store.getCard(card.id)?.word)
        assertEquals("книги", store.getCard(card.id)?.translation)
    }

    @Test fun detectsBothDirectionsAndDisclosesTranslationMode() {
        val folder = store.addFolder("Translation folder")
        store.addDeck(folder, "Translation deck")
        launch()
        compose.onNodeWithText("Translation folder").performClick()
        compose.onNodeWithText("Translation deck").performClick()
        compose.onNodeWithText("Добавить карточку").performClick()

        for ((word, direction) in listOf("book" to "EN → RU", "книга" to "RU → EN")) {
            fields()[0].performTextReplacement(word)
            fields()[1].performTextReplacement("")
            compose.onNode(hasText("Определим язык: $direction") or hasText("Detected: $direction")).assertExists()
            if (TranslationModels.isInstalled(context)) {
                compose.onNode((hasText("Перевести офлайн") or hasText("Translate offline")) and hasAnyAncestor(isDialog())).assertExists()
            } else {
                compose.onNode((hasText("Перевести через MyMemory") or hasText("Translate via MyMemory")) and hasAnyAncestor(isDialog())).assertExists()
                compose.onNode((hasText("Без моделей введённый текст", substring = true) or
                    hasText("Without models, pressing Translate", substring = true)) and hasAnyAncestor(isDialog())).assertExists()
            }
        }
        assertTrue(store.cards(store.decks(folder).single().id).isEmpty())
    }

    @Test fun welcomeDoesNotRequireSyncOrDownload() {
        compose.setContent { LocalizedTestTheme("ru") { WelcomeScreen(null, {}) } }
        compose.onNode(hasText("Продолжить без синхронизации") or hasText("Continue without sync")).assertExists()
    }
}

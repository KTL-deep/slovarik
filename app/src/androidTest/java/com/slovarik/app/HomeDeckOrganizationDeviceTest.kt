package com.slovarik.app

import android.content.res.Configuration
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class HomeDeckOrganizationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.let { base ->
        base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag("ru")) })
    }
    private val database = "slovarik-home-organization-tests.db"
    private lateinit var store: Store

    @Before fun setUp() { context.deleteDatabase(database); store = Store(context, database) }
    @After fun tearDown() { store.close(); context.deleteDatabase(database) }

    private fun launch() {
        compose.setContent {
            val registryOwner = requireNotNull(LocalActivityResultRegistryOwner.current)
            LocalizedTestTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                    App(store, { _, _ -> }, skipWelcomeForTests = true)
                }
            }
        }
    }

    private fun dialogText(id: Int) = hasText(context.getString(id)) or
        hasText(InstrumentationRegistry.getInstrumentation().targetContext.getString(id))

    private fun createDeck(name: String, folderChoice: String? = null) {
        compose.onNodeWithText(context.getString(R.string.main_new_deck)).performClick()
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))[0].performTextInput(name)
        if (folderChoice != null) compose.onNode(hasText(folderChoice) and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(dialogText(R.string.main_save) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
    }

    @Test fun emptyGuestCanCreateUnfiledDeckWithoutCreatingFolder() {
        launch()
        compose.runOnIdle { assertTrue(store.folders().isEmpty()); assertTrue(store.allDecks().isEmpty()) }
        createDeck("Solo")
        compose.runOnIdle { assertTrue(store.folders().isEmpty()); assertNull(store.allDecks().single().folderId) }
        compose.onNodeWithTag("home-deck-${store.allDecks().single().id}").assertExists()
    }

    @Test fun existingFolderDoesNotBecomeDefaultAndCanBeExplicitlySelected() {
        val folder = store.addFolder("Destination")
        launch()
        createDeck("Unfiled")
        compose.runOnIdle { assertNull(store.allDecks().single().folderId) }
        createDeck("Filed", "Destination")
        compose.runOnIdle { assertEquals(folder, store.allDecks().first { it.name == "Filed" }.folderId) }
    }

    @Test fun draggingDeckIntoFolderMovesExistingDeckAndPreservesCard() {
        val folder = store.addFolder("Destination")
        val deck = store.addDeck(null, "Move me")
        val card = store.addCard(deck, "word", "translation")
        launch()
        val source = compose.onNodeWithTag("home-deck-$deck")
        val target = compose.onNodeWithTag("home-folder-$folder")
        val sourceBounds = source.fetchSemanticsNode().boundsInRoot
        val end = target.fetchSemanticsNode().boundsInRoot.center - sourceBounds.topLeft
        source.performTouchInput { down(center); advanceEventTime(750); moveTo(end) }
        compose.onNodeWithTag("drag-deck-preview").assertExists()
        source.performTouchInput { up() }
        compose.onNodeWithTag("drag-deck-preview").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(deck, store.decks(folder).single().id)
            assertTrue(store.decks(null).isEmpty())
            assertNotNull(store.getCard(card))
            assertEquals(1, store.allDecks().size)
        }
    }

    @Test fun canceledDragOutsideFoldersDoesNotMoveDeck() {
        val folder = store.addFolder("Destination")
        val deck = store.addDeck(null, "Stay here")
        launch()
        val source = compose.onNodeWithTag("home-deck-$deck")
        source.performTouchInput {
            down(center); advanceEventTime(750); moveTo(androidx.compose.ui.geometry.Offset(center.x + 30f, center.y))
        }
        compose.onNodeWithTag("drag-deck-preview").assertExists()
        source.performTouchInput { cancel() }
        compose.onNodeWithTag("drag-deck-preview").assertDoesNotExist()
        compose.runOnIdle { assertNull(store.allDecks().single().folderId); assertTrue(store.decks(folder).isEmpty()) }
    }

    @Test fun moveMenuCanDetachDeckToUnfiled() {
        val folder = store.addFolder("Destination")
        val deck = store.addDeck(folder, "Filed")
        launch()
        compose.onNodeWithTag("home-deck-$deck").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(context.getString(R.string.main_menu)).performClick()
        compose.waitForIdle()
        compose.onNode(dialogText(R.string.main_move_deck)).performClick()
        compose.onNodeWithTag("move-target-unfiled").performClick()
        compose.runOnIdle { assertTrue(store.decks(folder).isEmpty()); assertEquals(deck, store.decks(null).single().id) }
    }
}

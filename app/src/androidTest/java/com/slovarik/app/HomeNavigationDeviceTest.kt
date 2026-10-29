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

class HomeNavigationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.let { base ->
        base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ru"))
        })
    }
    private val database = "slovarik-home-navigation-tests.db"
    private lateinit var store: Store
    private var folderId = 0L
    private var deckId = 0L
    private var cardId = 0L

    @Before fun setUp() {
        context.deleteDatabase(database)
        store = Store(context, database)
        folderId = store.addFolder("Home fixture folder")
        deckId = store.addDeck(folderId, "Home fixture deck")
        cardId = store.addCard(deckId, "fixture word", "fixture translation")
    }

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

    private fun longPress(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput { longClick(center) }
    }

    private fun dialogText(id: Int) = hasText(context.getString(id)) or
        hasText(InstrumentationRegistry.getInstrumentation().targetContext.getString(id))

    @Test fun homeDeckLongPressRequiresConfirmationAndMovesDeckWithCardToTrash() {
        launch()
        longPress("home-deck-$deckId")
        compose.onNodeWithText(context.getString(R.string.main_delete_deck_title)).assertExists()
        compose.runOnIdle { assertEquals(deckId, store.decks(folderId).single().id) }
        compose.onNode(dialogText(R.string.main_cancel) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertNotNull(store.getCard(cardId)); assertTrue(store.trashItems().isEmpty()) }
        longPress("home-deck-$deckId")
        compose.onNode(dialogText(R.string.main_to_trash) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle {
            assertTrue(store.decks(folderId).isEmpty())
            assertNull(store.getCard(cardId))
            assertEquals(TrashKind.DECK, store.trashItems().single().kind)
            store.restoreTrash(TrashKind.DECK, deckId)
            assertNotNull(store.getCard(cardId))
        }
    }

    @Test fun homeFolderLongPressRequiresConfirmationAndMovesFolderWithChildrenToTrash() {
        launch()
        longPress("home-folder-$folderId")
        compose.onNodeWithText(context.getString(R.string.main_delete_folder_title)).assertExists()
        compose.onNode(dialogText(R.string.main_cancel) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(1, store.folders().size); assertNotNull(store.getCard(cardId)) }
        longPress("home-folder-$folderId")
        compose.onNode(dialogText(R.string.main_to_trash) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle {
            assertTrue(store.folders().isEmpty())
            assertNull(store.getCard(cardId))
            assertEquals(TrashKind.FOLDER, store.trashItems().single().kind)
            store.restoreTrash(TrashKind.FOLDER, folderId)
            assertEquals(deckId, store.decks(folderId).single().id)
            assertNotNull(store.getCard(cardId))
        }
    }

    @Test fun folderPageDeckLongPressAlsoConfirmsBeforeDeleting() {
        launch()
        compose.onNodeWithTag("home-folder-$folderId").performClick()
        compose.onNodeWithTag("folder-deck-$deckId").assertExists()
        longPress("folder-deck-$deckId")
        compose.onNodeWithText(context.getString(R.string.main_delete_deck_title)).assertExists()
        compose.onNode(dialogText(R.string.main_cancel) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(1, store.decks(folderId).size) }
        longPress("folder-deck-$deckId")
        compose.onNode(dialogText(R.string.main_to_trash) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertTrue(store.decks(folderId).isEmpty()); assertEquals(TrashKind.DECK, store.trashItems().single().kind) }
    }

    @Test fun homeShowsActualLauncherIconBesideTitle() {
        launch()
        val icon = compose.onNodeWithTag("home-app-icon").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText(context.getString(R.string.main_app_name)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(icon.right <= title.left)
        assertTrue(icon.center.y >= title.top && icon.center.y <= title.bottom)
    }
}

package com.slovarik.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TrashScreenDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = "slovarik-trash-ui-tests.db"
    private lateinit var store: Store
    private var folderId = 0L
    private var cardId = 0L

    @Before fun setUp() {
        context.deleteDatabase(database)
        store = Store(context, database)
        folderId = store.addFolder("Trash fixture")
        val deckId = store.addDeck(folderId, "Fixture")
        cardId = store.addCard(deckId, "book", "книга")
    }
    @After fun tearDown() { store.close(); context.deleteDatabase(database) }

    private fun launchTrash(language: String = "ru") {
        compose.setContent {
            var refresh by remember { mutableIntStateOf(0) }
            LocalizedTestTheme(language) {
                TrashScreen(store, refresh, { refresh++ }, {}, { error(it) })
            }
        }
    }

    @Test fun englishTrashRestoresWithoutChangingUserTitles() {
        store.deleteFolder(folderId)
        launchTrash("en")
        compose.onNodeWithText("Trash").assertExists()
        compose.onNodeWithText("Folder").assertExists()
        compose.onNodeWithText("Trash fixture").assertExists()
        compose.onNodeWithText("Restore").performClick()
        compose.onNodeWithText("Trash is empty").assertExists()
        compose.runOnIdle { assertEquals("Trash fixture", store.folders().single().name) }
    }

    @Test fun restoringParentDoesNotUntrashAnIndividuallyDeletedChild() {
        store.deleteCard(cardId)
        store.deleteFolder(folderId)
        launchTrash()
        compose.onNodeWithText("Trash fixture").assertExists()
        compose.onNodeWithText("Восстановить").performClick()
        compose.onNodeWithText("book").assertExists()
        compose.runOnIdle {
            assertEquals(folderId, store.folders().single().id)
            assertNull(store.getCard(cardId))
        }
        compose.onNodeWithText("Восстановить").performClick()
        compose.onNodeWithText("Корзина пуста").assertExists()
        compose.runOnIdle { assertNotNull(store.getCard(cardId)) }
    }

    @Test fun permanentPurgeRequiresAnExplicitConfirmation() {
        store.deleteFolder(folderId)
        launchTrash()
        compose.onNodeWithText("Удалить навсегда").performClick()
        // A Dialog is a separate Android window, so this test host supplies its locale.
        // The actual MainActivity has its selected locale in attachBaseContext.
        compose.onNodeWithText(context.getString(R.string.trash_confirm_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.trash_cancel)).performClick()
        compose.runOnIdle { assertEquals(1, store.trashItems().size) }
        compose.onNodeWithText("Удалить навсегда").performClick()
        compose.onNode(hasText(context.getString(R.string.trash_delete_forever)) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Корзина пуста").assertExists()
        compose.runOnIdle { assertTrue(store.trashItems().isEmpty()) }
    }
    @Test fun deleteEverythingRequiresConfirmationAndDisappearsWhenEmpty() {
        store.deleteFolder(folderId)
        launchTrash()
        compose.onNodeWithText("Удалить всё навсегда").performClick()
        compose.onNodeWithText(context.getString(R.string.trash_delete_all_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.trash_cancel)).performClick()
        compose.runOnIdle { assertEquals(1, store.trashItems().size) }
        compose.onNodeWithText("Удалить всё навсегда").performClick()
        compose.onNode(hasText(context.getString(R.string.trash_delete_all_forever)) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Корзина пуста").assertExists()
        compose.onNodeWithText("Удалить всё навсегда").assertDoesNotExist()
        compose.runOnIdle { assertTrue(store.trashItems().isEmpty()) }
    }
}

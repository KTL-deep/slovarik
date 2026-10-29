package com.slovarik.app

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Dedicated database and fixture preferences; never exercises the application's live data. */
class DeckOrganizationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = "slovarik-organization-tests.db"
    private lateinit var store: Store
    private var source = 0L
    private var target = 0L
    private var deckId = 0L

    @Before fun setUp() {
        context.deleteDatabase(database)
        store = Store(context, database)
        source = store.addFolder("Source fixture")
        target = store.addFolder("Target fixture")
        deckId = store.addDeck(source, "Travel fixture")
    }
    @After fun tearDown() { store.close(); context.deleteDatabase(database) }

    private fun uuid() = store.readableDatabase.rawQuery("SELECT uuid FROM sync_records WHERE kind='decks' AND local_id=?", arrayOf(deckId.toString())).use {
        assertTrue(it.moveToFirst()); it.getString(0)
    }

    @Test fun moveKeepsDeckCardReviewUndoAndSyncIdentity() {
        val card = store.addCard(deckId, "journey", "путешествие")
        val receipt = store.review(requireNotNull(store.getCard(card)), 4)
        val before = requireNotNull(store.getCard(card))
        val identity = uuid()
        store.moveDeck(deckId, target)
        assertTrue(store.decks(source).isEmpty())
        assertEquals(Deck(deckId, target, "Travel fixture"), store.decks(target).single())
        assertEquals(before, store.cards(deckId).single())
        assertEquals(identity, uuid())
        assertEquals(receipt.eventId, store.lastReviewUndo()?.eventId)
        assertEquals(1, store.stats().reviews)
        assertEquals(card, store.undoLastReview(receipt.eventId)?.id)
    }

    @Test fun missingOrDeletedTargetsAndSourcesCannotMove() {
        assertThrows(IllegalArgumentException::class.java) { store.moveDeck(deckId, -1) }
        store.deleteFolder(target)
        assertThrows(IllegalArgumentException::class.java) { store.moveDeck(deckId, target) }
        assertEquals(source, store.decks(source).single().folderId)
        store.deleteDeck(deckId)
        assertThrows(IllegalArgumentException::class.java) { store.moveDeck(deckId, source) }
        assertEquals(TrashKind.DECK, store.trashItems().first { it.id == deckId }.kind)
    }

    @Test fun purgeAllRemovesNestedTrashButKeepsActiveAndHistory() {
        val removedCard = store.addCard(deckId, "gone", "удалено")
        store.review(requireNotNull(store.getCard(removedCard)), 4)
        val activeDeck = store.addDeck(target, "Kept fixture")
        val activeCard = store.addCard(activeDeck, "kept", "сохранено")
        store.deleteCard(removedCard)
        store.deleteFolder(source)
        assertEquals(1, store.trashItems().size)
        store.purgeAllTrash()
        assertTrue(store.trashItems().isEmpty())
        assertEquals(activeCard, store.cards(activeDeck).single().id)
        assertEquals(1, store.stats().reviews)
        assertNull(store.lastReviewUndo())
        assertTrue(JSONObject(store.backup()).getJSONArray("events").getJSONObject(0).isNull("card"))
        assertTrue(store.readableDatabase.rawQuery("SELECT 1 FROM sync_records WHERE kind='decks' AND uuid=? AND deleted=1", arrayOf(uuidForDeletedDeck())).use { it.moveToFirst() })
    }

    private fun uuidForDeletedDeck() = store.readableDatabase.rawQuery("SELECT uuid FROM sync_records WHERE kind='decks' AND local_id IS NULL AND deleted=1 LIMIT 1", null).use {
        assertTrue(it.moveToFirst()); it.getString(0)
    }

    private fun launch() {
        compose.setContent {
            val owner = requireNotNull(LocalActivityResultRegistryOwner.current)
            LocalizedTestTheme("en") {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    App(store, { _, _ -> }, skipWelcomeForTests = true)
                }
            }
        }
    }

    @Test fun moveFromCardsCanCancelThenSelectTarget() {
        launch()
        compose.onNodeWithTag("home-deck-$deckId").performClick()
        compose.onNode(hasContentDescription("Menu") or hasContentDescription("Меню")).performClick()
        compose.onNode(hasText("Move to folder") or hasText("Переместить в папку")).performClick()
        compose.onNodeWithTag("move-target-$target").assertExists()
        compose.onNode((hasText("Cancel") or hasText("Отмена")) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(deckId, store.decks(source).single().id) }
        compose.onNode(hasContentDescription("Menu") or hasContentDescription("Меню")).performClick()
        compose.onNode(hasText("Move to folder") or hasText("Переместить в папку")).performClick()
        compose.onNodeWithTag("move-target-$target").performClick()
        compose.runOnIdle { assertEquals(deckId, store.decks(target).single().id); assertTrue(store.decks(source).isEmpty()) }
    }

    @Test fun addExistingFromFolderMovesInsteadOfCopying() {
        launch()
        compose.onNodeWithTag("home-folder-$target").performClick()
        compose.onNodeWithTag("add-existing-deck").performClick()
        compose.onNodeWithTag("existing-deck-$deckId").assertExists()
        compose.onNode((hasText("Cancel") or hasText("Отмена")) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertTrue(store.decks(target).isEmpty()) }
        compose.onNodeWithTag("add-existing-deck").performClick()
        compose.onNodeWithTag("existing-deck-$deckId").performClick()
        compose.onNodeWithTag("folder-deck-$deckId").assertExists()
        compose.runOnIdle { assertTrue(store.decks(source).isEmpty()); assertEquals(deckId, store.decks(target).single().id) }
    }

    @Test fun folderWithoutOtherDecksExplainsEmptySelection() {
        store.moveDeck(deckId, target)
        launch()
        compose.onNodeWithTag("home-folder-$target").performClick()
        compose.onNodeWithTag("add-existing-deck").performClick()
        compose.onNode(hasText("There are no decks in other folders to add.") or hasText("В других папках нет колод для добавления.")).assertExists()
        compose.runOnIdle { assertEquals(deckId, store.decks(target).single().id) }
    }
}

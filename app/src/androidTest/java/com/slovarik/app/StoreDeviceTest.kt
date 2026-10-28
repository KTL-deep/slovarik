package com.slovarik.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a dedicated database, never the user's application database. */
@RunWith(AndroidJUnit4::class)
class StoreDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testDatabase = "slovarik-instrumentation-test.db"
    private lateinit var store: Store

    @Before fun setUp() {
        context.deleteDatabase(testDatabase)
        store = Store(context, testDatabase)
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase(testDatabase)
    }

    private fun seed(): Card {
        val folder = store.addFolder("Тестовая папка")
        val deck = store.addDeck(folder, "English")
        store.addCard(deck, "journey", "путешествие")
        return store.cards(deck).single()
    }

    @Test fun backupRoundTripPreservesWordsAndHistory() {
        val card = seed()
        store.review(card, 4)
        val backup = store.backup()
        store.restore(backup)
        val deck = store.decks(store.folders().single().id).single()
        val restored = store.cards(deck.id).single()
        assertEquals("journey", restored.word)
        assertEquals("путешествие", restored.translation)
        assertEquals(1, restored.review.repetitions)
        assertEquals(1, store.stats().reviews)
        assertEquals(1, store.stats().correct)
        assertTrue(restored.review.due > System.currentTimeMillis())
    }

    @Test fun malformedBackupRollsBackWithoutLosingCurrentData() {
        seed()
        val before = store.backup()
        val bad = JSONObject(before)
        bad.getJSONArray("folders").getJSONObject(0).getJSONArray("decks")
            .getJSONObject(0).getJSONArray("cards").getJSONObject(0).remove("translation")
        assertThrows(Exception::class.java) { store.restore(bad.toString()) }
        assertEquals(before, store.backup())
    }

    @Test fun unknownEventReferenceIsRejectedAndOriginalDataPreserved() {
        val card = seed()
        store.review(card, 4)
        val before = store.backup()
        val bad = JSONObject(before)
        bad.getJSONArray("events").getJSONObject(0).put("card", 987654321L)
        assertThrows(Exception::class.java) { store.restore(bad.toString()) }
        assertEquals(before, store.backup())
    }

    @Test fun deletedCardHistorySurvivesBackup() {
        val card = seed()
        store.review(card, 4)
        store.deleteCard(card.id)
        val backup = store.backup()
        store.restore(backup)
        assertEquals(1, store.stats().reviews)
        assertEquals(1, store.stats().correct)
        assertTrue(JSONObject(store.backup()).getJSONArray("events").getJSONObject(0).isNull("card"))
    }

    @Test fun dataSurvivesDatabaseCloseAndReopen() {
        seed()
        store.close()
        store = Store(context, testDatabase)
        val deck = store.decks(store.folders().single().id).single()
        assertEquals("journey", store.cards(deck.id).single().word)
    }

    @Test fun editPreservesSpacedRepetitionProgress() {
        val card = seed()
        store.review(card, 4)
        val progress = store.cards(card.deckId).single().review
        store.updateCard(card.id, "journeys", "путешествия")
        val edited = store.cards(card.deckId).single()
        assertEquals("journeys", edited.word)
        assertEquals(progress, edited.review)
        assertEquals(1, store.stats().reviews)
    }

    @Test fun invalidReviewProgressCannotReplaceCurrentData() {
        seed()
        val before = store.backup()
        val bad = JSONObject(before)
        bad.getJSONArray("folders").getJSONObject(0).getJSONArray("decks")
            .getJSONObject(0).getJSONArray("cards").getJSONObject(0).put("interval", -5)
        assertThrows(Exception::class.java) { store.restore(bad.toString()) }
        assertEquals(before, store.backup())
    }
}

package com.slovarik.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
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

    @Test fun unfiledDecksMoveTrashAndBackupWithoutCreatingFolders() {
        val deck = store.addDeck(null, "Unfiled")
        val card = store.addCard(deck, "word", "translation")
        val event = store.review(requireNotNull(store.getCard(card)), 4)
        assertFalse(store.hasFolders())
        assertEquals(listOf(deck), store.decks(null).map { it.id })
        assertEquals(listOf(deck), store.allDecks().map { it.id })
        val folder = store.addFolder("Folder")
        store.moveDeck(deck, folder)
        assertTrue(store.decks(null).isEmpty())
        store.moveDeck(deck, null)
        store.deleteFolder(folder)
        assertEquals(deck, store.allDecks().single().id)
        assertEquals(event.eventId, store.lastReviewUndo()?.eventId)
        store.deleteDeck(deck)
        val trashed = store.trashItems().single { it.kind == TrashKind.DECK && it.id == deck }
        assertEquals("", trashed.subtitle)
        store.restoreTrash(TrashKind.DECK, deck)
        val backup = store.backup()
        assertEquals(3, JSONObject(backup).getInt("version"))
        assertEquals(1, JSONObject(backup).getJSONArray("unfiledDecks").length())
        store.restore(backup)
        assertEquals("word", store.cards(store.decks(null).single().id).single().word)
        assertEquals(1, store.stats().reviews)
        assertFalse(JSONObject(store.backup()).getJSONArray("events").getJSONObject(0).isNull("card"))
    }

    @Test fun v4MigrationPreservesRowsSyncIdentityAndForeignKeys() {
        val card = seed()
        val event = store.review(card, 4)
        val folder = store.folders().single().id
        val sync = SyncLocalStore(store)
        val profile = UserProfileStore(store)
        profile.setSetting("interface_language", "en")
        profile.setMatchRecord(card.deckId, 18)
        assertTrue(sync.bindAccount("migration-account"))
        val identities = store.readableDatabase.rawQuery("SELECT kind,uuid,local_id,stamp,device,deleted,dirty FROM sync_records ORDER BY kind,uuid", null).use { c ->
            buildList { while (c.moveToNext()) add((0 until 7).map { if (c.isNull(it)) "NULL" else c.getString(it) }) }
        }
        assertTrue(sync.pending().isNotEmpty())
        store.close()
        // Construct an actual v4 deck schema, leaving all other populated v4 tables intact.
        SQLiteDatabase.openDatabase(context.getDatabasePath(testDatabase).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.setForeignKeyConstraintsEnabled(false)
            val triggers = db.rawQuery("SELECT name,sql FROM sqlite_master WHERE type='trigger' ORDER BY name", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
            }
            triggers.forEach { db.execSQL("DROP TRIGGER \"${it.first}\"") }
            db.execSQL("CREATE TABLE decks_v4(id INTEGER PRIMARY KEY, folder_id INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE, name TEXT NOT NULL, deleted_at INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO decks_v4 SELECT * FROM decks")
            db.execSQL("DROP TABLE decks")
            db.execSQL("ALTER TABLE decks_v4 RENAME TO decks")
            triggers.forEach { db.execSQL(it.second) }
            db.version = 4
        }
        store = Store(context, testDatabase)
        assertEquals(folder, store.decks(folder).single().folderId)
        assertEquals(card.id, store.getCard(card.id)?.id)
        assertEquals(event.eventId, store.lastReviewUndo()?.eventId)
        assertEquals(1, store.stats().reviews)
        assertEquals("migration-account", SyncLocalStore(store).boundAccount())
        assertEquals("en", UserProfileStore(store).setting("interface_language"))
        assertEquals(18, UserProfileStore(store).matchRecord(card.deckId))
        val after = store.readableDatabase.rawQuery("SELECT kind,uuid,local_id,stamp,device,deleted,dirty FROM sync_records ORDER BY kind,uuid", null).use { c ->
            buildList { while (c.moveToNext()) add((0 until 7).map { if (c.isNull(it)) "NULL" else c.getString(it) }) }
        }
        assertEquals(identities, after)
        store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        store.readableDatabase.rawQuery("PRAGMA foreign_keys", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        store.addDeck(null, "new")
        assertThrows(Exception::class.java) { store.writableDatabase.execSQL("INSERT INTO decks(folder_id,name) VALUES(999999,'invalid')") }
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
        assertFalse(JSONObject(store.backup()).getJSONArray("events").getJSONObject(0).isNull("card"))
        store.purgeTrash(TrashKind.CARD, store.trashItems().single().id)
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

    @Test fun deletingDeckPreservesOtherFoldersAndReviewHistory() {
        val card = seed()
        store.review(card, 4)
        val otherFolder = store.addFolder("Keep")
        val otherDeck = store.addDeck(otherFolder, "Keep deck")
        store.addCard(otherDeck, "house", "дом")
        store.deleteDeck(card.deckId)
        assertEquals("house", store.cards(otherDeck).single().word)
        assertTrue(store.cards(card.deckId).isEmpty())
        assertEquals(1, store.stats().reviews)
        assertFalse(JSONObject(store.backup()).getJSONArray("events").getJSONObject(0).isNull("card"))
        store.purgeTrash(TrashKind.DECK, card.deckId)
        assertTrue(JSONObject(store.backup()).getJSONArray("events").getJSONObject(0).isNull("card"))
    }

    @Test fun deletingFolderCascadesOnlyItsOwnDecks() {
        val card = seed()
        store.review(card, 4)
        val deletedFolder = store.folders().single().id
        val keep = store.addFolder("Keep")
        val keepDeck = store.addDeck(keep, "Keep deck")
        store.addCard(keepDeck, "cat", "кот")
        store.deleteFolder(deletedFolder)
        assertEquals(keep, store.folders().single().id)
        assertTrue(store.decks(deletedFolder).isEmpty())
        assertEquals("cat", store.cards(keepDeck).single().word)
        assertEquals(1, store.stats().reviews)
    }

    @Test fun migrationKeepsLegacyProgressAndHistory() {
        store.close()
        context.deleteDatabase(testDatabase)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(testDatabase), null).use { db ->
            db.execSQL("CREATE TABLE folders(id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE decks(id INTEGER PRIMARY KEY, folder_id INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE, name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE cards(id INTEGER PRIMARY KEY, deck_id INTEGER NOT NULL REFERENCES decks(id) ON DELETE CASCADE, word TEXT NOT NULL, translation TEXT NOT NULL, reps INTEGER NOT NULL DEFAULT 0, interval_days INTEGER NOT NULL DEFAULT 0, ease REAL NOT NULL DEFAULT 2.5, due INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY, card_id INTEGER REFERENCES cards(id) ON DELETE SET NULL, day INTEGER NOT NULL, correct INTEGER NOT NULL)")
            db.execSQL("INSERT INTO folders VALUES(1,'Legacy')")
            db.execSQL("INSERT INTO decks VALUES(1,1,'Old')")
            db.execSQL("INSERT INTO cards VALUES(1,1,'word','translation',3,14,2.1,12345)")
            db.execSQL("INSERT INTO events VALUES(1,1,5,1)")
            db.version = 1
        }
        store = Store(context, testDatabase)
        assertEquals(Review(3, 14, 2.1, 12345), store.getCard(1)!!.review)
        assertEquals(1, store.stats().reviews)
        assertEquals(1, store.dueCount())
    }

    @Test fun undoUsesLatestReceiptAndPreservesEditedText() {
        val original = seed()
        val first = store.review(original, 2)
        assertEquals(1, first.card.review.learningStep)
        store.updateCard(original.id, "edited", "changed")
        assertEquals("edited", store.lastReviewUndo()!!.card.word)
        assertNull(store.undoLastReview(first.eventId + 1))
        assertEquals(1, store.stats().reviews)
        val undone = store.undoLastReview(first.eventId)!!
        assertEquals("edited", undone.word)
        assertEquals(original.review, undone.review)
        assertEquals(0, store.stats().reviews)
        assertNull(store.lastReviewUndo())
        val second = store.review(original, 4)
        store.review(original, 5)
        assertNull(store.undoLastReview(second.eventId))
        assertEquals(2, store.stats().reviews)
    }

    @Test fun reviewReadsFreshProgressAndUndoSurvivesReopenButRejectsChangedProgress() {
        val stale = seed()
        val first = store.review(stale, 1)
        val second = store.review(stale, 4)
        assertEquals(2, second.card.review.learningStep)
        assertEquals(0, second.card.review.repetitions)
        store.close()
        store = Store(context, testDatabase)
        assertEquals(second.eventId, store.lastReviewUndo()!!.eventId)
        assertEquals(first.card.review, store.undoLastReview(second.eventId)!!.review)
        assertEquals(1, store.stats().reviews)
        val third = store.review(stale, 4)
        store.writableDatabase.execSQL("UPDATE cards SET ease=3.0 WHERE id=?", arrayOf(third.card.id))
        assertNull(store.lastReviewUndo())
        assertNull(store.undoLastReview(third.eventId))
        assertEquals(2, store.stats().reviews)
    }

    @Test fun softDeleteNestedRestoreAndPurgePreserveHistoryUntilPurge() {
        val card = seed()
        val folder = store.folders().single().id
        store.review(card, 4)
        store.deleteCard(card.id)
        store.deleteFolder(folder)
        assertTrue(store.folders().isEmpty())
        assertEquals(0, store.dueCount())
        assertTrue(store.hasFolders())
        assertEquals(listOf(TrashKind.FOLDER), store.trashItems().map { it.kind })
        assertThrows(IllegalArgumentException::class.java) { store.addDeck(folder, "no") }
        store.restoreTrash(TrashKind.FOLDER, folder)
        assertNull(store.getCard(card.id))
        assertEquals(TrashKind.CARD, store.trashItems().single().kind)
        store.restoreTrash(TrashKind.CARD, card.id)
        assertNotNull(store.getCard(card.id))
        store.deleteDeck(card.deckId)
        assertThrows(IllegalArgumentException::class.java) { store.addCard(card.deckId, "no", "no") }
        assertFalse(JSONObject(store.backup()).getJSONArray("events").getJSONObject(0).isNull("card"))
        store.purgeTrash(TrashKind.DECK, card.deckId)
        assertTrue(JSONObject(store.backup()).getJSONArray("events").getJSONObject(0).isNull("card"))
        assertEquals(1, store.stats().reviews)
    }

    @Test fun v1AndV2RestoreAndTrashedLearningRoundTrip() {
        val card = seed()
        val v1 = JSONObject(store.backup()).put("version", 1)
        val folder = v1.getJSONArray("folders").getJSONObject(0)
        folder.remove("deletedAt")
        val deck = folder.getJSONArray("decks").getJSONObject(0)
        deck.remove("deletedAt")
        val oldCard = deck.getJSONArray("cards").getJSONObject(0)
        oldCard.remove("deletedAt"); oldCard.remove("learningStep")
        store.restore(v1.toString())
        assertEquals(0, store.cards(store.decks(store.folders().single().id).single().id).single().review.learningStep)
        val v2 = JSONObject(store.backup()).put("version", 2)
        v2.remove("unfiledDecks")
        store.restore(v2.toString())
        assertEquals(1, store.decks(store.folders().single().id).size)
        val current = store.cards(store.decks(store.folders().single().id).single().id).single()
        store.review(current, 1)
        store.deleteCard(current.id)
        val backup = store.backup()
        store.restore(backup)
        assertNull(store.lastReviewUndo())
        val restored = store.trashItems().single()
        store.restoreTrash(restored.kind, restored.id)
        assertEquals(1, store.getCard(restored.id)!!.review.learningStep)
        val bad = JSONObject(backup)
        bad.getJSONArray("folders").getJSONObject(0).put("deletedAt", -1)
        val before = store.backup()
        assertThrows(Exception::class.java) { store.restore(bad.toString()) }
        assertEquals(before, store.backup())
    }

    @Test fun undoneEventIdIsNeverReusedByNextGrade() {
        val card = seed()
        val first = store.review(card, 4)
        val restored = requireNotNull(store.undoLastReview(first.eventId))
        val second = store.review(restored, 4)
        assertTrue(second.eventId > first.eventId)
        assertNull(store.undoLastReview(first.eventId))
        assertEquals(1, store.stats().reviews)
        assertEquals(second.eventId, store.lastReviewUndo()!!.eventId)
    }

    @Test fun activeObjectsCannotBePermanentlyDeleted() {
        val card = seed()
        val folder = store.folders().single()
        assertThrows(IllegalArgumentException::class.java) { store.purgeTrash(TrashKind.CARD, card.id) }
        assertThrows(IllegalArgumentException::class.java) { store.purgeTrash(TrashKind.DECK, card.deckId) }
        assertThrows(IllegalArgumentException::class.java) { store.purgeTrash(TrashKind.FOLDER, folder.id) }
        assertEquals(card, store.getCard(card.id))
        assertEquals(folder, store.folders().single())
    }
}

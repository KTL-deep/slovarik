package com.slovarik.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncLocalStoreDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "slovarik-sync-device-test.db"
    private lateinit var store: Store
    private lateinit var sync: SyncLocalStore
    @Before fun setup() {
        context.deleteDatabase(databaseName)
        store = Store(context, databaseName)
        sync = SyncLocalStore(store)
    }
    @After fun cleanup() { store.close(); context.deleteDatabase(databaseName) }

    private fun card(): Card {
        val folder = store.addFolder("F")
        val deck = store.addDeck(folder, "D")
        return store.getCard(store.addCard(deck, "word", "translation"))!!
    }
    private fun local(kind: String) = sync.pending().single { it.kind == kind }

    @Test fun unfiledDeckSyncExportsAndAppliesNullParentWithoutSyntheticFolder() {
        val deck = store.addDeck(null, "Local")
        val card = store.addCard(deck, "word", "translation")
        val exported = local("decks")
        assertTrue(exported.fields.containsKey("folder"))
        assertNull(exported.fields["folder"])
        assertEquals(card, store.cards(deck).single().id)
        val remote = SyncRecord("remote-deck", "decks", exported.stamp + 1000, "peer", false, mapOf("folder" to null, "name" to "Remote", "deletedAt" to 0L))
        assertTrue(sync.applyRemote(listOf(remote)))
        assertEquals(2, store.decks(null).size)
        assertTrue(store.folders().isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            sync.applyRemote(listOf(remote.copy(stamp = remote.stamp + 1, fields = remote.fields + ("folder" to ""))))
        }
        val folder = store.addFolder("F")
        store.moveDeck(deck, folder)
        assertNotNull(local("decks").fields["folder"])
        store.moveDeck(deck, null)
        assertNull(local("decks").fields["folder"])
    }

    @Test fun migrationKeepsV2IdsProgressAndHistory() {
        store.close()
        context.deleteDatabase(databaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null).use { d ->
            d.execSQL("CREATE TABLE folders(id INTEGER PRIMARY KEY, name TEXT NOT NULL, deleted_at INTEGER NOT NULL DEFAULT 0)")
            d.execSQL("CREATE TABLE decks(id INTEGER PRIMARY KEY, folder_id INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE, name TEXT NOT NULL, deleted_at INTEGER NOT NULL DEFAULT 0)")
            d.execSQL("CREATE TABLE cards(id INTEGER PRIMARY KEY, deck_id INTEGER NOT NULL REFERENCES decks(id) ON DELETE CASCADE, word TEXT NOT NULL, translation TEXT NOT NULL, reps INTEGER NOT NULL DEFAULT 0, interval_days INTEGER NOT NULL DEFAULT 0, ease REAL NOT NULL DEFAULT 2.5, due INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER NOT NULL DEFAULT 0, learning_step INTEGER NOT NULL DEFAULT 0)")
            d.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY, card_id INTEGER REFERENCES cards(id) ON DELETE SET NULL, day INTEGER NOT NULL, correct INTEGER NOT NULL)")
            d.execSQL("CREATE TABLE review_undo(event_id INTEGER PRIMARY KEY,card_id INTEGER NOT NULL,before_reps INTEGER NOT NULL,before_interval INTEGER NOT NULL,before_ease REAL NOT NULL,before_due INTEGER NOT NULL,before_step INTEGER NOT NULL,after_reps INTEGER NOT NULL,after_interval INTEGER NOT NULL,after_ease REAL NOT NULL,after_due INTEGER NOT NULL,after_step INTEGER NOT NULL)")
            d.execSQL("CREATE TABLE review_counter(singleton INTEGER PRIMARY KEY CHECK(singleton=1),value INTEGER NOT NULL)")
            d.execSQL("INSERT INTO review_counter VALUES(1,9)")
            d.execSQL("INSERT INTO folders VALUES(5,'old',0)")
            d.execSQL("INSERT INTO decks VALUES(6,5,'deck',0)")
            d.execSQL("INSERT INTO cards VALUES(7,6,'word','translation',2,9,2.1,123,0,0)")
            d.execSQL("INSERT INTO events VALUES(9,7,1,1)")
            d.version = 2
        }
        store = Store(context, databaseName); sync = SyncLocalStore(store)
        assertEquals(2, store.getCard(7)!!.review.repetitions)
        assertEquals(1, store.stats().reviews)
        assertEquals(5, sync.pending().size)
        store.close(); store = Store(context, databaseName); sync = SyncLocalStore(store)
        assertEquals(5, sync.pending().size)
    }

    @Test fun concurrentContentAndProgressMergeAcrossDifferentLocalIds() {
        val a = card()
        val original = sync.pending()
        sync.acknowledge(original)
        val remoteProgress = original.single { it.kind == "progress" }.copy(stamp = original.maxOf { it.stamp } + 10, device = "peer", fields = original.single { it.kind == "progress" }.fields + ("reps" to 4))
        store.updateCard(a.id, "edited", "translation")
        assertTrue(sync.applyRemote(listOf(remoteProgress)))
        assertEquals("edited", store.getCard(a.id)!!.word)
        assertEquals(4, store.getCard(a.id)!!.review.repetitions)
        assertEquals("edited", local("cards").fields["word"])
    }

    @Test fun offlinePurgeTombstonesDescendantsAndUndoTombstonesEvent() {
        val c = card()
        val graded = store.review(c, 4)
        val event = local("events")
        sync.acknowledge(sync.pending())
        store.undoLastReview(graded.eventId)
        assertTrue(local("events").deleted)
        assertEquals(event.id, local("events").id)
        sync.acknowledge(sync.pending())
        store.deleteFolder(store.folders().single().id)
        store.purgeTrash(TrashKind.FOLDER, store.trashItems().single().id)
        val tombstones = sync.pending()
        assertTrue(tombstones.filter { it.kind in listOf("folders", "decks", "cards", "progress") }.all { it.deleted })
        assertEquals(4, tombstones.size)
    }

    @Test fun ackRaceAccountBindingAndBoundRestore() {
        val c = card()
        val before = local("cards")
        store.updateCard(c.id, "later", "translation")
        sync.acknowledge(listOf(before))
        assertEquals("later", local("cards").fields["word"])
        assertTrue(sync.bindAccount("uid-1"))
        assertTrue(sync.bindAccount("uid-1"))
        assertFalse(sync.bindAccount("uid-2"))
        assertEquals("uid-1", sync.boundAccount())
        val backup = store.backup()
        assertThrows(IllegalArgumentException::class.java) { store.restore(backup) }
        assertEquals(backup, store.backup())
    }

    @Test fun remoteDifferentIntegerIdsAndOldTombstonesNeverResurrect() {
        val localFolder = store.addFolder("local")
        val localDeck = store.addDeck(localFolder, "local deck")
        val remote = SyncRecord("remote-folder", "folders", 5000000000000L, "peer", false, mapOf("name" to "remote", "deletedAt" to 0L))
        val remoteDeck = SyncRecord("remote-deck", "decks", remote.stamp + 1, "peer", false, mapOf("folder" to remote.id, "name" to "from peer", "deletedAt" to 0L))
        val remoteCard = SyncRecord("remote-card", "cards", remote.stamp + 2, "peer", false, mapOf("deck" to remoteDeck.id, "word" to "new", "translation" to "новый", "deletedAt" to 0L))
        assertTrue(sync.applyRemote(listOf(remoteCard, remoteDeck, remote)))
        val id = store.folders().single { it.name == "remote" }.id
        assertNotEquals(localFolder, id)
        assertEquals("new", store.cards(store.decks(id).single().id).single().word)
        assertTrue(store.cards(localDeck).isEmpty())
        assertTrue(sync.applyRemote(listOf(remote.copy(stamp = remote.stamp + 3, deleted = true, fields = emptyMap()))))
        store.close(); store = Store(context, databaseName); sync = SyncLocalStore(store)
        assertFalse(sync.applyRemote(listOf(remote)))
        assertTrue(store.folders().none { it.id == id })
        assertTrue(sync.pending().none { it.id == remote.id })
        assertFalse(sync.applyRemote(listOf(SyncRecord("orphan", "decks", remote.stamp + 2, "peer", false, mapOf("folder" to "missing", "name" to "x", "deletedAt" to 0L)))))
    }

    @Test fun remoteProgressInvalidatesOnlyItsOwnUndo() {
        val c = card()
        val first = store.review(c, 4)
        val p = local("progress")
        val otherFolder = store.addFolder("other")
        val otherDeck = store.addDeck(otherFolder, "other")
        val other = store.getCard(store.addCard(otherDeck, "another", "другой"))!!
        val receipt = store.review(other, 4)
        assertNotNull(store.lastReviewUndo())
        val incoming = p.copy(stamp = sync.pending().maxOf { it.stamp } + 10, device = "peer", fields = p.fields + ("reps" to 9))
        sync.applyRemote(listOf(incoming))
        assertEquals(receipt.eventId, store.lastReviewUndo()!!.eventId)
        assertNotNull(store.undoLastReview(receipt.eventId))
        assertEquals(1, store.stats().reviews)
        assertNotEquals(first.eventId, receipt.eventId)
    }

    @Test fun fullTrashSnapshotKeepsParentsAndRestoresAcrossDevices() {
        val c = card()
        val folder = store.folders().single().id
        store.deleteCard(c.id)
        store.deleteFolder(folder)
        val snapshot = sync.pending()
        assertEquals(4, snapshot.size)
        assertTrue(snapshot.none { it.deleted })
        assertTrue(snapshot.filter { it.kind in listOf("folders", "cards") }.all { (it.fields["deletedAt"] as Long) > 0 })
        val peerName = "slovarik-sync-trash-peer-test.db"
        context.deleteDatabase(peerName)
        val peer = Store(context, peerName)
        try {
            val peerSync = SyncLocalStore(peer)
            assertTrue(peerSync.applyRemote(snapshot.reversed()))
            assertEquals(1, peer.trashItems().size)
            val peerFolder = peer.trashItems().single()
            peer.restoreTrash(peerFolder.kind, peerFolder.id)
            assertEquals(TrashKind.CARD, peer.trashItems().single().kind)
            val peerCard = peer.trashItems().single()
            peer.restoreTrash(peerCard.kind, peerCard.id)
            assertEquals("word", peer.getCard(peerCard.id)!!.word)
        } finally { peer.close(); context.deleteDatabase(peerName) }
    }

    @Test fun purgeCannotBeRevivedAndHistoryWithPurgedReferenceSurvives() {
        val c = card()
        store.review(c, 4)
        val snapshot = sync.pending()
        val cardRecord = snapshot.single { it.kind == "cards" }
        val eventRecord = snapshot.single { it.kind == "events" }
        val tombstone = cardRecord.copy(stamp = snapshot.maxOf { it.stamp } + 10, device = "peer", deleted = true, fields = emptyMap())
        assertTrue(sync.applyRemote(listOf(tombstone)))
        assertNull(store.getCard(c.id))
        assertEquals(1, store.stats().reviews)
        assertTrue(sync.applyRemote(listOf(cardRecord.copy(stamp = tombstone.stamp + 100, device = "offline"))))
        assertTrue(sync.pending().single { it.id == cardRecord.id }.stamp > tombstone.stamp + 100)
        assertNull(store.getCard(c.id))
        assertTrue(sync.applyRemote(listOf(eventRecord.copy(id = "remote-event", stamp = tombstone.stamp + 101, device = "peer"))))
        assertEquals(2, store.stats().reviews)
        assertEquals(2, store.writableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE card_id IS NULL", null).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun missingParentDefersAcrossReopenWithoutBlockingUnrelatedRecords() {
        val deck = SyncRecord("late-deck", "decks", 100L, "peer", false, mapOf("folder" to "late-folder", "name" to "deck", "deletedAt" to 0L))
        val folder = SyncRecord("late-folder", "folders", 99L, "peer", false, mapOf("name" to "parent", "deletedAt" to 0L))
        val independent = SyncRecord("independent", "folders", 101L, "peer", false, mapOf("name" to "other", "deletedAt" to 0L))
        assertTrue(sync.applyRemote(listOf(deck, independent)))
        assertEquals("other", store.folders().single().name)
        store.close(); store = Store(context, databaseName); sync = SyncLocalStore(store)
        assertTrue(sync.applyRemote(listOf(folder)))
        assertEquals("deck", store.decks(store.folders().single { it.name == "parent" }.id).single().name)
    }

    @Test fun malformedNumbersAndEqualVersionDifferentPayloadRollbackAtomically() {
        val c = card()
        val original = sync.pending().single { it.kind == "progress" }
        val valid = SyncRecord("valid-folder", "folders", 900L, "peer", false, mapOf("name" to "valid", "deletedAt" to 0L))
        for (invalid in listOf(
            original.copy(fields = original.fields + ("step" to 1.5)),
            original.copy(fields = original.fields + ("reps" to (Int.MAX_VALUE.toLong() + 1))),
            original.copy(fields = original.fields + ("due" to -1L)),
            original.copy(fields = original.fields + ("word" to "extra"))
        )) {
            assertThrows(IllegalArgumentException::class.java) { sync.applyRemote(listOf(valid, invalid)) }
            assertEquals(listOf("F"), store.folders().map { it.name })
        }
        val remote = valid.copy(stamp = sync.pending().maxOf { it.stamp } + 1)
        assertTrue(sync.applyRemote(listOf(remote)))
        assertThrows(IllegalArgumentException::class.java) { sync.applyRemote(listOf(remote.copy(fields = remote.fields + ("name" to "changed")))) }
        assertEquals("word", store.getCard(c.id)!!.word)
        assertEquals("valid", store.folders().single { it.name == "valid" }.name)
    }

    @Test fun unknownPurgedParentRetiresOldChildAndPreservesHistory() {
        val tombstone = SyncRecord("gone-folder", "folders", 300L, "peer", true, emptyMap())
        val oldChild = SyncRecord("old-deck", "decks", 301L, "offline", false, mapOf("folder" to tombstone.id, "name" to "old", "deletedAt" to 0L))
        assertTrue(sync.applyRemote(listOf(oldChild, tombstone)))
        assertTrue(store.folders().isEmpty())
        assertTrue(sync.applyRemote(listOf(oldChild.copy(stamp = 900L))))
        assertTrue(sync.pending().single { it.id == oldChild.id }.stamp > 900L)
        assertTrue(sync.pending().single { it.id == oldChild.id }.deleted)
        val event = SyncRecord("historic", "events", 302L, "offline", false, mapOf("card" to "gone-card", "day" to 10L, "correct" to 1))
        val cardTombstone = SyncRecord("gone-card", "cards", 303L, "peer", true, emptyMap())
        assertTrue(sync.applyRemote(listOf(event, cardTombstone)))
        assertEquals(1, store.stats().reviews)
        assertTrue(store.writableDatabase.rawQuery("SELECT card_id FROM events", null).use { it.moveToFirst(); it.isNull(0) })
    }

    @Test fun concurrentHigherRemoteLivePromotesLocalPurgeAndRemoteTombstoneAcknowledgesIt() {
        val c = card()
        val before = sync.pending().single { it.kind == "cards" }
        store.deleteCard(c.id)
        store.purgeTrash(TrashKind.CARD, c.id)
        val tombstone = sync.pending().single { it.id == before.id }
        val newerLive = before.copy(stamp = tombstone.stamp + 100, device = "peer")
        assertTrue(sync.applyRemote(listOf(newerLive)))
        val promoted = sync.pending().single { it.id == before.id }
        assertTrue(promoted.deleted)
        assertTrue(promoted.stamp > newerLive.stamp)
        assertNull(store.getCard(c.id))
        assertTrue(sync.applyRemote(listOf(newerLive.copy(stamp = promoted.stamp + 1, deleted = true, fields = emptyMap()))))
        assertTrue(sync.pending().none { it.id == before.id })
        assertNull(store.getCard(c.id))
    }

    @Test fun remoteOlderPurgeBeatsNewerLocalEditAndDeferredLive() {
        val c = card()
        val local = sync.pending().single { it.kind == "cards" }
        val olderPurge = local.copy(stamp = local.stamp - 1, device = "peer", deleted = true, fields = emptyMap())
        store.updateCard(c.id, "offline edit", "translation")
        assertTrue(sync.pending().single { it.id == local.id }.stamp > olderPurge.stamp)
        assertTrue(sync.applyRemote(listOf(olderPurge)))
        assertNull(store.getCard(c.id))
        assertTrue(sync.pending().none { it.id == local.id && !it.deleted })
        assertTrue(sync.applyRemote(listOf(local.copy(stamp = olderPurge.stamp + 1000, device = "offline"))))
        assertTrue(sync.pending().single { it.id == local.id }.deleted)
        assertNull(store.getCard(c.id))
    }

    @Test fun wireLimitsRejectMalformedBatchBeforeMutation() {
        val c = card()
        val original = sync.pending().single { it.kind == "cards" }
        val other = SyncRecord("other", "folders", 77L, "peer", false, mapOf("name" to "other", "deletedAt" to 0L))
        val invalid = listOf(
            original.copy(stamp = 9_007_199_254_740_992L),
            original.copy(fields = original.fields + ("word" to "w".repeat(65537))),
            other.copy(fields = other.fields + ("name" to "n".repeat(4097))),
            other.copy(fields = other.fields + ("deletedAt" to 9_007_199_254_740_992L)),
            sync.pending().single { it.kind == "progress" }.copy(fields = sync.pending().single { it.kind == "progress" }.fields + ("ease" to 1_000_001.0))
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { sync.applyRemote(listOf(other.copy(id = "valid"), it)) } }
        assertEquals("word", store.getCard(c.id)!!.word)
        assertEquals(1, store.folders().size)
    }
}

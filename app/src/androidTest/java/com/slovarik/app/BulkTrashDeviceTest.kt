package com.slovarik.app

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only its own database; never opens or deletes the application's database. */
@RunWith(AndroidJUnit4::class)
class BulkTrashDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = "slovarik-bulk-trash-atomicity-tests.db"
    private lateinit var store: Store

    @Before fun setUp() {
        context.deleteDatabase(database)
        store = Store(context, database)
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase(database)
    }

    private data class Fixture(
        val removedFolder: Long, val removedDeck: Long, val removedCard: Long,
        val keptFolder: Long, val keptDeck: Long, val keptCard: Long,
        val purgedCards: List<Long>, val identities: Map<Pair<String, Long>, String>
    )

    private fun identity(kind: String, id: Long): String = store.readableDatabase.rawQuery(
        "SELECT uuid FROM sync_records WHERE kind=? AND local_id=?", arrayOf(kind, id.toString())
    ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }

    private fun rows(sql: String): List<String> = store.readableDatabase.rawQuery(sql, null).use { c ->
        buildList {
            while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { index ->
                if (c.isNull(index)) "<null>" else c.getString(index)
            })
        }
    }

    private fun fixture(): Fixture {
        val removedFolder = store.addFolder("Deleted folder")
        val nestedDeck = store.addDeck(removedFolder, "Hidden deck")
        val nestedCard = store.addCard(nestedDeck, "hidden", "скрыта")
        val activeFolder = store.addFolder("Active folder")
        val removedDeck = store.addDeck(activeFolder, "Deleted deck")
        val deckCard = store.addCard(removedDeck, "deck child", "дочерняя")
        val keptDeck = store.addDeck(activeFolder, "Active deck")
        val removedCard = store.addCard(keptDeck, "Deleted card", "удалена")
        val keptCard = store.addCard(keptDeck, "Active card", "сохранена")
        val identities = mapOf(
            ("folders" to removedFolder) to identity("folders", removedFolder),
            ("decks" to nestedDeck) to identity("decks", nestedDeck),
            ("decks" to removedDeck) to identity("decks", removedDeck),
            ("cards" to nestedCard) to identity("cards", nestedCard),
            ("cards" to deckCard) to identity("cards", deckCard),
            ("cards" to removedCard) to identity("cards", removedCard)
        )
        for (id in listOf(nestedCard, deckCard, removedCard)) store.review(requireNotNull(store.getCard(id)), 4)
        store.deleteCard(removedCard)
        store.deleteDeck(removedDeck)
        store.deleteFolder(removedFolder)
        assertEquals(setOf(TrashKind.FOLDER, TrashKind.DECK, TrashKind.CARD), store.trashItems().map { it.kind }.toSet())
        return Fixture(removedFolder, removedDeck, removedCard, activeFolder, keptDeck, keptCard,
            listOf(nestedCard, deckCard, removedCard), identities)
    }

    @Test fun purgeAllRemovesMixedRootsAndHiddenChildrenButPreservesActiveDataHistoryAndTombstones() {
        val item = fixture()
        assertEquals(3, store.stats().reviews)
        store.purgeAllTrash()

        assertTrue(store.trashItems().isEmpty())
        assertEquals(listOf(item.keptFolder), store.folders().map { it.id })
        assertEquals(listOf(item.keptDeck), store.decks(item.keptFolder).map { it.id })
        assertEquals(listOf(item.keptCard), store.cards(item.keptDeck).map { it.id })
        assertEquals(3, store.stats().reviews)
        assertEquals(3, rows("SELECT id FROM events WHERE card_id IS NULL ORDER BY id").size)
        assertTrue(rows("SELECT event_id FROM review_undo").isEmpty())
        for ((key, uuid) in item.identities) {
            store.readableDatabase.rawQuery(
                "SELECT local_id,deleted FROM sync_records WHERE kind=? AND uuid=?", arrayOf(key.first, uuid)
            ).use { c -> assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)); assertEquals(1, c.getInt(1)) }
        }
        assertTrue(rows("SELECT id FROM folders WHERE deleted_at>0").isEmpty())
        assertTrue(rows("SELECT id FROM decks WHERE deleted_at>0").isEmpty())
        assertTrue(rows("SELECT id FROM cards WHERE deleted_at>0").isEmpty())
    }

    @Test fun failureAtLastStandaloneCardRollsBackEarlierFolderAndDeckDeletes() {
        val item = fixture()
        val beforeBackup = store.backup()
        val beforeSync = rows("SELECT kind,uuid,local_id,stamp,device,deleted,dirty FROM sync_records ORDER BY kind,uuid")
        val beforeUndo = rows("SELECT * FROM review_undo ORDER BY event_id")
        val beforeEvents = rows("SELECT id,card_id,day,correct FROM events ORDER BY id")
        val beforeTrash = store.trashItems()
        store.writableDatabase.execSQL(
            "CREATE TRIGGER fail_standalone_purge BEFORE DELETE ON cards " +
                "WHEN OLD.id=${item.removedCard} AND OLD.deleted_at>0 " +
                "BEGIN SELECT RAISE(ABORT, 'fixture card deletion blocked'); END"
        )

        assertThrows(SQLiteException::class.java) { store.purgeAllTrash() }

        assertEquals(beforeBackup, store.backup())
        assertEquals(beforeTrash, store.trashItems())
        assertEquals(beforeSync, rows("SELECT kind,uuid,local_id,stamp,device,deleted,dirty FROM sync_records ORDER BY kind,uuid"))
        assertEquals(beforeUndo, rows("SELECT * FROM review_undo ORDER BY event_id"))
        assertEquals(beforeEvents, rows("SELECT id,card_id,day,correct FROM events ORDER BY id"))
        assertEquals(3, store.stats().reviews)
        assertEquals(item.removedFolder, store.trashItems().first { it.kind == TrashKind.FOLDER }.id)
        assertEquals(item.removedDeck, store.trashItems().first { it.kind == TrashKind.DECK }.id)
        assertEquals(item.removedCard, store.trashItems().first { it.kind == TrashKind.CARD }.id)
        assertEquals(item.keptCard, store.getCard(item.keptCard)?.id)
    }
}

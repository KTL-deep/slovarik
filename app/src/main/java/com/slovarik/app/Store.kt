package com.slovarik.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class Folder(val id: Long, val name: String)
data class Deck(val id: Long, val folderId: Long, val name: String)
data class Card(val id: Long, val deckId: Long, val word: String, val translation: String, val review: Review)
data class Stats(val reviews: Int, val correct: Int, val days: Int)
enum class TrashKind { FOLDER, DECK, CARD }
data class TrashItem(val kind: TrashKind, val id: Long, val title: String, val subtitle: String, val deletedAt: Long)
data class ReviewUndo(val eventId: Long, val card: Card)
data class ReviewResult(val eventId: Long, val card: Card)

class Store(context: Context, databaseName: String = "slovarik.db") : SQLiteOpenHelper(context, databaseName, null, 4) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE folders(id INTEGER PRIMARY KEY, name TEXT NOT NULL, deleted_at INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE decks(id INTEGER PRIMARY KEY, folder_id INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE, name TEXT NOT NULL, deleted_at INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE cards(id INTEGER PRIMARY KEY, deck_id INTEGER NOT NULL REFERENCES decks(id) ON DELETE CASCADE, word TEXT NOT NULL, translation TEXT NOT NULL, reps INTEGER NOT NULL DEFAULT 0, interval_days INTEGER NOT NULL DEFAULT 0, ease REAL NOT NULL DEFAULT 2.5, due INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER NOT NULL DEFAULT 0, learning_step INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY, card_id INTEGER REFERENCES cards(id) ON DELETE SET NULL, day INTEGER NOT NULL, correct INTEGER NOT NULL)")
        createUndo(db)
        SyncLocalStore.createSchema(db)
        UserProfileStore.createSchema(db)
    }
    private fun createUndo(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE review_undo(event_id INTEGER PRIMARY KEY, card_id INTEGER NOT NULL, before_reps INTEGER NOT NULL, before_interval INTEGER NOT NULL, before_ease REAL NOT NULL, before_due INTEGER NOT NULL, before_step INTEGER NOT NULL, after_reps INTEGER NOT NULL, after_interval INTEGER NOT NULL, after_ease REAL NOT NULL, after_due INTEGER NOT NULL, after_step INTEGER NOT NULL)")
        // Undo deletes an event; plain INTEGER PRIMARY KEY would otherwise reuse its ID.
        // Keep a monotonic token so a stale undo action cannot target a later re-grade.
        db.execSQL("CREATE TABLE review_counter(singleton INTEGER PRIMARY KEY CHECK(singleton=1), value INTEGER NOT NULL)")
        db.execSQL("INSERT INTO review_counter SELECT 1,COALESCE(MAX(id),0) FROM events")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            for (table in listOf("folders", "decks", "cards")) db.execSQL("ALTER TABLE $table ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE cards ADD COLUMN learning_step INTEGER NOT NULL DEFAULT 0")
            createUndo(db)
        }
        if (oldVersion < 3) SyncLocalStore.createSchema(db)
        if (oldVersion < 4) UserProfileStore.createSchema(db)
    }

    fun folders(): List<Folder> = readableDatabase.rawQuery("SELECT id,name FROM folders WHERE deleted_at=0 ORDER BY id", null).use { c ->
        buildList { while (c.moveToNext()) add(Folder(c.getLong(0), c.getString(1))) }
    }
    fun decks(folder: Long): List<Deck> = readableDatabase.rawQuery("SELECT d.id,d.folder_id,d.name FROM decks d JOIN folders f ON f.id=d.folder_id WHERE d.folder_id=? AND d.deleted_at=0 AND f.deleted_at=0 ORDER BY d.id", arrayOf(folder.toString())).use { c ->
        buildList { while (c.moveToNext()) add(Deck(c.getLong(0), c.getLong(1), c.getString(2))) }
    }
    private fun cardFromCursor(c: android.database.Cursor) = Card(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), Review(c.getInt(4), c.getInt(5), c.getDouble(6), c.getLong(7), c.getInt(8)))
    private val cardColumns = "c.id,c.deck_id,c.word,c.translation,c.reps,c.interval_days,c.ease,c.due,c.learning_step"
    private val activeJoin = " FROM cards c JOIN decks d ON d.id=c.deck_id JOIN folders f ON f.id=d.folder_id WHERE c.deleted_at=0 AND d.deleted_at=0 AND f.deleted_at=0"
    fun cards(deck: Long): List<Card> = readableDatabase.rawQuery("SELECT $cardColumns $activeJoin AND c.deck_id=? ORDER BY c.id", arrayOf(deck.toString())).use { c ->
        buildList { while (c.moveToNext()) add(cardFromCursor(c)) }
    }
    fun getCard(id: Long): Card? = readableDatabase.rawQuery("SELECT $cardColumns $activeJoin AND c.id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) cardFromCursor(c) else null }
    fun addFolder(name: String): Long = writableDatabase.compileStatement("INSERT INTO folders(name) VALUES(?)").use { s -> s.bindString(1, name.trim()); s.executeInsert() }
    fun addDeck(folder: Long, name: String): Long {
        require(readableDatabase.rawQuery("SELECT 1 FROM folders WHERE id=? AND deleted_at=0", arrayOf(folder.toString())).use { it.moveToFirst() })
        return writableDatabase.compileStatement("INSERT INTO decks(folder_id,name) VALUES(?,?)").use { s -> s.bindLong(1, folder); s.bindString(2, name.trim()); s.executeInsert() }
    }
    fun moveDeck(deckId: Long, folderId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            require(db.rawQuery("SELECT 1 FROM decks d JOIN folders f ON f.id=d.folder_id WHERE d.id=? AND d.deleted_at=0 AND f.deleted_at=0", arrayOf(deckId.toString())).use { it.moveToFirst() }) { "Deck is not active" }
            require(db.rawQuery("SELECT 1 FROM folders WHERE id=? AND deleted_at=0", arrayOf(folderId.toString())).use { it.moveToFirst() }) { "Folder is not active" }
            db.execSQL("UPDATE decks SET folder_id=? WHERE id=? AND deleted_at=0 AND folder_id<>?", arrayOf(folderId, deckId, folderId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun deleteFolder(id: Long) { writableDatabase.execSQL("UPDATE folders SET deleted_at=? WHERE id=? AND deleted_at=0", arrayOf(System.currentTimeMillis().coerceAtLeast(1), id)) }
    fun deleteDeck(id: Long) { writableDatabase.execSQL("UPDATE decks SET deleted_at=? WHERE id=? AND deleted_at=0", arrayOf(System.currentTimeMillis().coerceAtLeast(1), id)) }
    fun addCard(deck: Long, word: String, translation: String): Long {
        require(readableDatabase.rawQuery("SELECT 1 FROM decks d JOIN folders f ON f.id=d.folder_id WHERE d.id=? AND d.deleted_at=0 AND f.deleted_at=0", arrayOf(deck.toString())).use { it.moveToFirst() })
        return writableDatabase.compileStatement("INSERT INTO cards(deck_id,word,translation) VALUES(?,?,?)").use { s ->
            s.bindLong(1, deck); s.bindString(2, word.trim()); s.bindString(3, translation.trim()); s.executeInsert()
        }
    }
    fun updateCard(id: Long, word: String, translation: String) {
        require(word.isNotBlank() && translation.isNotBlank())
        writableDatabase.compileStatement("UPDATE cards SET word=?,translation=? WHERE id=? AND deleted_at=0").use { s ->
            s.bindString(1, word.trim()); s.bindString(2, translation.trim()); s.bindLong(3, id); s.executeUpdateDelete()
        }
    }
    fun deleteCard(id: Long) { writableDatabase.execSQL("UPDATE cards SET deleted_at=? WHERE id=? AND deleted_at=0", arrayOf(System.currentTimeMillis().coerceAtLeast(1), id)) }

    fun trashItems(): List<TrashItem> {
        val result = mutableListOf<TrashItem>()
        readableDatabase.rawQuery("SELECT id,name,deleted_at FROM folders WHERE deleted_at>0 ORDER BY id", null).use { c -> while (c.moveToNext()) result += TrashItem(TrashKind.FOLDER, c.getLong(0), c.getString(1), "", c.getLong(2)) }
        readableDatabase.rawQuery("SELECT d.id,d.name,f.name,d.deleted_at FROM decks d JOIN folders f ON f.id=d.folder_id WHERE d.deleted_at>0 AND f.deleted_at=0 ORDER BY d.id", null).use { c -> while (c.moveToNext()) result += TrashItem(TrashKind.DECK, c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)) }
        readableDatabase.rawQuery("SELECT c.id,c.word,d.name,c.deleted_at FROM cards c JOIN decks d ON d.id=c.deck_id JOIN folders f ON f.id=d.folder_id WHERE c.deleted_at>0 AND d.deleted_at=0 AND f.deleted_at=0 ORDER BY c.id", null).use { c -> while (c.moveToNext()) result += TrashItem(TrashKind.CARD, c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)) }
        return result
    }
    private fun trashTable(kind: TrashKind) = when (kind) { TrashKind.FOLDER -> "folders"; TrashKind.DECK -> "decks"; TrashKind.CARD -> "cards" }
    fun restoreTrash(kind: TrashKind, id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            require(trashItems().any { it.kind == kind && it.id == id }) { "Объект уже восстановлен или скрыт удалённой папкой" }
            db.execSQL("UPDATE ${trashTable(kind)} SET deleted_at=0 WHERE id=? AND deleted_at>0", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun purgeTrash(kind: TrashKind, id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            require(trashItems().any { it.kind == kind && it.id == id }) { "Окончательно удалить можно только объект из корзины" }
            db.execSQL("DELETE FROM ${trashTable(kind)} WHERE id=? AND deleted_at>0", arrayOf(id))
            db.execSQL("DELETE FROM review_undo WHERE card_id NOT IN (SELECT id FROM cards)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun purgeAllTrash() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Delete roots first: foreign-key cascades remove their hidden descendants.
            db.execSQL("DELETE FROM folders WHERE deleted_at>0")
            db.execSQL("DELETE FROM decks WHERE deleted_at>0")
            db.execSQL("DELETE FROM cards WHERE deleted_at>0")
            db.execSQL("DELETE FROM review_undo WHERE card_id NOT IN (SELECT id FROM cards)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun review(card: Card, grade: Int): ReviewResult {
        require(grade in 0..5)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = requireNotNull(getCard(card.id)) { "Card is not active" }
            val now = System.currentTimeMillis()
            val next = schedule(current.review, grade, now)
            db.execSQL("UPDATE cards SET reps=?,interval_days=?,ease=?,due=?,learning_step=? WHERE id=?", arrayOf<Any?>(next.repetitions, next.intervalDays, next.ease, next.due, next.learningStep, card.id))
            val eventId = db.rawQuery("SELECT MAX(value,(SELECT COALESCE(MAX(id),0) FROM events))+1 FROM review_counter WHERE singleton=1", null).use { c -> c.moveToFirst(); c.getLong(0) }
            db.execSQL("UPDATE review_counter SET value=? WHERE singleton=1", arrayOf(eventId))
            db.compileStatement("INSERT INTO events(id,card_id,day,correct) VALUES(?,?,?,?)").use { s ->
                s.bindLong(1, eventId); s.bindLong(2, card.id); s.bindLong(3, now / 86_400_000); s.bindLong(4, if (grade >= 3) 1 else 0); s.executeInsert()
            }
            db.execSQL("DELETE FROM review_undo")
            val before = current.review
            db.execSQL("INSERT INTO review_undo VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(eventId, card.id, before.repetitions, before.intervalDays, before.ease, before.due, before.learningStep, next.repetitions, next.intervalDays, next.ease, next.due, next.learningStep))
            val result = ReviewResult(eventId, current.copy(review = next))
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }
    private fun validUndo(): ReviewUndo? = readableDatabase.rawQuery("SELECT event_id,card_id,before_reps,before_interval,before_ease,before_due,before_step,after_reps,after_interval,after_ease,after_due,after_step FROM review_undo", null).use { c ->
        if (!c.moveToFirst()) return@use null
        val eventId = c.getLong(0)
        val card = getCard(c.getLong(1)) ?: return@use null
        if (card.review != Review(c.getInt(7), c.getInt(8), c.getDouble(9), c.getLong(10), c.getInt(11))) return@use null
        val latest = readableDatabase.rawQuery("SELECT card_id FROM events WHERE id=?", arrayOf(eventId.toString())).use { e -> e.moveToFirst() && !e.isNull(0) && e.getLong(0) == card.id }
        if (!latest) return@use null
        ReviewUndo(eventId, card.copy(review = Review(c.getInt(2), c.getInt(3), c.getDouble(4), c.getLong(5), c.getInt(6))))
    }
    fun lastReviewUndo(): ReviewUndo? = validUndo()
    fun undoLastReview(expectedEventId: Long? = null): Card? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val receipt = validUndo() ?: return null
            if (expectedEventId != null && expectedEventId != receipt.eventId) return null
            val prior = receipt.card
            db.execSQL("UPDATE cards SET reps=?,interval_days=?,ease=?,due=?,learning_step=? WHERE id=?", arrayOf<Any?>(prior.review.repetitions, prior.review.intervalDays, prior.review.ease, prior.review.due, prior.review.learningStep, prior.id))
            db.execSQL("DELETE FROM events WHERE id=?", arrayOf(receipt.eventId))
            db.execSQL("DELETE FROM review_undo")
            db.setTransactionSuccessful()
            return prior
        } finally { db.endTransaction() }
    }
    fun stats(): Stats = readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(correct),0),COUNT(DISTINCT day) FROM events", null).use { c -> c.moveToFirst(); Stats(c.getInt(0), c.getInt(1), c.getInt(2)) }
    fun dueCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) $activeJoin AND c.due<=?", arrayOf(System.currentTimeMillis().toString())).use { c -> c.moveToFirst(); c.getInt(0) }
    fun hasFolders(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM folders LIMIT 1", null).use { it.moveToFirst() }

    /** Full versioned backup, including hidden descendants and history. */
    fun backup(): String {
        val root = JSONObject().put("version", 2)
        val foldersJson = JSONArray()
        readableDatabase.rawQuery("SELECT id,name,deleted_at FROM folders ORDER BY id", null).use { f -> while (f.moveToNext()) {
            val decksJson = JSONArray()
            readableDatabase.rawQuery("SELECT id,name,deleted_at FROM decks WHERE folder_id=? ORDER BY id", arrayOf(f.getLong(0).toString())).use { d -> while (d.moveToNext()) {
                val cardsJson = JSONArray()
                readableDatabase.rawQuery("SELECT id,word,translation,reps,interval_days,ease,due,deleted_at,learning_step FROM cards WHERE deck_id=? ORDER BY id", arrayOf(d.getLong(0).toString())).use { c -> while (c.moveToNext()) {
                    cardsJson.put(JSONObject().put("oldId", c.getLong(0)).put("word", c.getString(1)).put("translation", c.getString(2)).put("reps", c.getInt(3)).put("interval", c.getInt(4)).put("ease", c.getDouble(5)).put("due", c.getLong(6)).put("deletedAt", c.getLong(7)).put("learningStep", c.getInt(8)))
                } }
                decksJson.put(JSONObject().put("name", d.getString(1)).put("deletedAt", d.getLong(2)).put("cards", cardsJson))
            } }
            foldersJson.put(JSONObject().put("name", f.getString(1)).put("deletedAt", f.getLong(2)).put("decks", decksJson))
        } }
        val events = JSONArray()
        readableDatabase.rawQuery("SELECT card_id,day,correct FROM events ORDER BY id", null).use { c -> while (c.moveToNext()) events.put(JSONObject().put("card", if (c.isNull(0)) JSONObject.NULL else c.getLong(0)).put("day", c.getLong(1)).put("correct", c.getInt(2))) }
        return root.put("folders", foldersJson).put("events", events).toString(2)
    }

    /** Invalid input rolls back the entire replacement. */
    fun restore(text: String) {
        val root = JSONObject(text)
        val version = root.getInt("version")
        require(version in 1..2) { "Неподдерживаемая версия копии" }
        val folders = root.getJSONArray("folders")
        val events = root.getJSONArray("events")
        val db = writableDatabase
        db.beginTransaction()
        try {
            require(SyncLocalStore(this).boundAccount() == null) { "Синхронизированную базу нельзя заменить из резервной копии" }
            db.execSQL("DELETE FROM review_undo"); db.execSQL("DELETE FROM events"); db.execSQL("DELETE FROM folders")
            val ids = mutableMapOf<Long, Long>()
            fun deleted(obj: JSONObject): Long = if (version == 1) 0 else obj.getLong("deletedAt").also { require(it >= 0) }
            for (f in 0 until folders.length()) {
                val folder = folders.getJSONObject(f)
                val folderId = addFolder(folder.getString("name"))
                val decks = folder.getJSONArray("decks")
                for (d in 0 until decks.length()) {
                    val deck = decks.getJSONObject(d)
                    val deckId = addDeck(folderId, deck.getString("name"))
                    val cards = deck.getJSONArray("cards")
                    for (i in 0 until cards.length()) {
                        val card = cards.getJSONObject(i)
                        val oldId = card.getLong("oldId")
                        require(oldId > 0 && oldId !in ids) { "Повторяющийся или неверный ID карточки" }
                        require(card.getString("word").isNotBlank() && card.getString("translation").isNotBlank())
                        val reps = card.getInt("reps"); val interval = card.getInt("interval"); val due = card.getLong("due"); val ease = card.getDouble("ease")
                        val step = if (version == 1) 0 else card.getInt("learningStep")
                        require(reps >= 0 && interval >= 0 && due >= 0 && ease.isFinite() && ease >= 1.3 && step in 0..2)
                        val newId = addCard(deckId, card.getString("word"), card.getString("translation"))
                        ids[oldId] = newId
                        db.execSQL("UPDATE cards SET reps=?,interval_days=?,ease=?,due=?,learning_step=?,deleted_at=? WHERE id=?", arrayOf<Any?>(reps, interval, ease, due, step, deleted(card), newId))
                    }
                    db.execSQL("UPDATE decks SET deleted_at=? WHERE id=?", arrayOf(deleted(deck), deckId))
                }
                db.execSQL("UPDATE folders SET deleted_at=? WHERE id=?", arrayOf(deleted(folder), folderId))
            }
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                require(event.has("card") && event.getLong("day") >= 0 && event.getInt("correct") in 0..1)
                val cardId = if (event.isNull("card")) null else requireNotNull(ids[event.getLong("card")]) { "История ссылается на отсутствующую карточку" }
                db.execSQL("INSERT INTO events(card_id,day,correct) VALUES(?,?,?)", arrayOf<Any?>(cardId, event.getLong("day"), event.getInt("correct")))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}

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

class Store(context: Context, databaseName: String = "slovarik.db") : SQLiteOpenHelper(context, databaseName, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE folders(id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE decks(id INTEGER PRIMARY KEY, folder_id INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE cards(id INTEGER PRIMARY KEY, deck_id INTEGER NOT NULL REFERENCES decks(id) ON DELETE CASCADE, word TEXT NOT NULL, translation TEXT NOT NULL, reps INTEGER NOT NULL DEFAULT 0, interval_days INTEGER NOT NULL DEFAULT 0, ease REAL NOT NULL DEFAULT 2.5, due INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY, card_id INTEGER REFERENCES cards(id) ON DELETE SET NULL, day INTEGER NOT NULL, correct INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun folders(): List<Folder> = readableDatabase.rawQuery("SELECT id,name FROM folders ORDER BY id", null).use { c ->
        buildList { while (c.moveToNext()) add(Folder(c.getLong(0), c.getString(1))) }
    }
    fun decks(folder: Long): List<Deck> = readableDatabase.rawQuery("SELECT id,folder_id,name FROM decks WHERE folder_id=? ORDER BY id", arrayOf(folder.toString())).use { c ->
        buildList { while (c.moveToNext()) add(Deck(c.getLong(0), c.getLong(1), c.getString(2))) }
    }
    fun cards(deck: Long): List<Card> = readableDatabase.rawQuery("SELECT id,deck_id,word,translation,reps,interval_days,ease,due FROM cards WHERE deck_id=? ORDER BY id", arrayOf(deck.toString())).use { c ->
        buildList { while (c.moveToNext()) add(Card(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), Review(c.getInt(4), c.getInt(5), c.getDouble(6), c.getLong(7)))) }
    }
    fun addFolder(name: String): Long = writableDatabase.compileStatement("INSERT INTO folders(name) VALUES(?)").use { s -> s.bindString(1, name.trim()); s.executeInsert() }
    fun addDeck(folder: Long, name: String): Long = writableDatabase.compileStatement("INSERT INTO decks(folder_id,name) VALUES(?,?)").use { s -> s.bindLong(1, folder); s.bindString(2, name.trim()); s.executeInsert() }
    /** Foreign keys cascade to child decks/cards; review events retain their totals with a null card ID. */
    fun deleteFolder(id: Long) { writableDatabase.execSQL("DELETE FROM folders WHERE id=?", arrayOf(id)) }
    fun deleteDeck(id: Long) { writableDatabase.execSQL("DELETE FROM decks WHERE id=?", arrayOf(id)) }
    fun addCard(deck: Long, word: String, translation: String): Long = writableDatabase.compileStatement("INSERT INTO cards(deck_id,word,translation) VALUES(?,?,?)").use { s ->
        s.bindLong(1, deck); s.bindString(2, word.trim()); s.bindString(3, translation.trim()); s.executeInsert()
    }
    /** Correcting a card's text does not discard its review history. */
    fun updateCard(id: Long, word: String, translation: String) {
        require(word.isNotBlank() && translation.isNotBlank())
        writableDatabase.compileStatement("UPDATE cards SET word=?,translation=? WHERE id=?").use { s ->
            s.bindString(1, word.trim()); s.bindString(2, translation.trim()); s.bindLong(3, id); s.executeUpdateDelete()
        }
    }
    fun deleteCard(id: Long) { writableDatabase.execSQL("DELETE FROM cards WHERE id=?", arrayOf(id)) }
    fun review(card: Card, grade: Int) {
        val now = System.currentTimeMillis()
        val next = schedule(card.review, grade, now)
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("UPDATE cards SET reps=?,interval_days=?,ease=?,due=? WHERE id=?", arrayOf<Any?>(next.repetitions, next.intervalDays, next.ease, next.due, card.id))
            writableDatabase.execSQL("INSERT INTO events(card_id,day,correct) VALUES(?,?,?)", arrayOf<Any?>(card.id, now / 86_400_000, if (grade >= 3) 1 else 0))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    fun stats(): Stats = readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(correct),0),COUNT(DISTINCT day) FROM events", null).use { c ->
        c.moveToFirst(); Stats(c.getInt(0), c.getInt(1), c.getInt(2))
    }
    fun dueCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM cards WHERE due<=?", arrayOf(System.currentTimeMillis().toString())).use { c -> c.moveToFirst(); c.getInt(0) }
    fun hasFolders(): Boolean = folders().isNotEmpty()

    /** A full, versioned JSON backup including review progress and review history. */
    fun backup(): String {
        val root = JSONObject().put("version", 1)
        val foldersJson = JSONArray()
        for (folder in folders()) {
            val decksJson = JSONArray()
            for (deck in decks(folder.id)) {
                val cardsJson = JSONArray()
                for (card in cards(deck.id)) cardsJson.put(JSONObject().put("word", card.word).put("translation", card.translation)
                    .put("reps", card.review.repetitions).put("interval", card.review.intervalDays).put("ease", card.review.ease).put("due", card.review.due).put("oldId", card.id))
                decksJson.put(JSONObject().put("name", deck.name).put("cards", cardsJson))
            }
            foldersJson.put(JSONObject().put("name", folder.name).put("decks", decksJson))
        }
        val events = JSONArray()
        readableDatabase.rawQuery("SELECT card_id,day,correct FROM events ORDER BY id", null).use { c ->
            while (c.moveToNext()) events.put(JSONObject().put("card", if (c.isNull(0)) JSONObject.NULL else c.getLong(0)).put("day", c.getLong(1)).put("correct", c.getInt(2)))
        }
        return root.put("folders", foldersJson).put("events", events).toString(2)
    }

    /** Replaces data atomically; an invalid backup leaves the original database intact. */
    fun restore(text: String) {
        val root = JSONObject(text)
        require(root.getInt("version") == 1) { "Неподдерживаемая версия копии" }
        val folders = root.getJSONArray("folders")
        val events = root.getJSONArray("events")
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM events"); db.execSQL("DELETE FROM folders")
            val ids = mutableMapOf<Long, Long>()
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
                        require(card.getString("word").isNotBlank() && card.getString("translation").isNotBlank()) { "Пустое слово или перевод в копии" }
                        require(card.getInt("reps") >= 0 && card.getInt("interval") >= 0 && card.getLong("due") >= 0) { "Некорректный прогресс карточки" }
                        require(card.getDouble("ease").isFinite() && card.getDouble("ease") >= 1.3) { "Некорректный коэффициент повторения" }
                        val newId = addCard(deckId, card.getString("word"), card.getString("translation"))
                        ids[oldId] = newId
                        db.execSQL("UPDATE cards SET reps=?,interval_days=?,ease=?,due=? WHERE id=?", arrayOf<Any?>(card.getInt("reps"), card.getInt("interval"), card.getDouble("ease"), card.getLong("due"), newId))
                    }
                }
            }
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                require(event.has("card") && event.getLong("day") >= 0 && event.getInt("correct") in 0..1) { "Некорректная запись истории" }
                val cardId = if (event.isNull("card")) null else requireNotNull(ids[event.getLong("card")]) { "История ссылается на отсутствующую карточку" }
                db.execSQL("INSERT INTO events(card_id,day,correct) VALUES(?,?,?)", arrayOf<Any?>(cardId, event.getLong("day"), event.getInt("correct")))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}

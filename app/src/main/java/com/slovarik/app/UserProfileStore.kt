package com.slovarik.app

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/** SQLite-backed profile values; absent values are deliberately not uploaded as defaults. */
class UserProfileStore(private val store: Store) {
    private val db get() = store.writableDatabase

    fun setSetting(key: String, value: Any) {
        validateSetting(key, value)
        val database = db
        database.beginTransaction()
        try {
            database.execSQL("INSERT OR REPLACE INTO profile_settings(key,value) VALUES(?,?)", arrayOf(key, JSONObject().put("value", if (value is Map<*, *>) JSONObject(value) else value).toString()))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    fun setting(key: String): Any? {
        require(key in settingKeys)
        return db.rawQuery("SELECT value FROM profile_settings WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) decode(c.getString(0)) else null
        }
    }

    fun settings(): Map<String, Any> = db.rawQuery("SELECT key,value FROM profile_settings", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), decode(c.getString(1))) }
    }

    fun setMatchRecord(deckId: Long, seconds: Int): Boolean {
        require(seconds >= 0)
        val database = db
        database.beginTransaction()
        try {
            require(database.rawQuery("SELECT 1 FROM decks WHERE id=?", arrayOf(deckId.toString())).use { it.moveToFirst() })
            val old = matchRecord(deckId)
            if (old != null && old <= seconds) { database.setTransactionSuccessful(); return false }
            database.execSQL("INSERT OR REPLACE INTO profile_match(deck_id,seconds) VALUES(?,?)", arrayOf<Any?>(deckId, seconds))
            val deckUuid = database.rawQuery("SELECT uuid FROM sync_records WHERE kind='decks' AND local_id=?", arrayOf(deckId.toString())).use { c -> require(c.moveToFirst()); c.getString(0) }
            database.execSQL("UPDATE sync_meta SET clock=MAX(clock+1,CAST(strftime('%s','now') AS INTEGER)*1000) WHERE singleton=1")
            database.execSQL("INSERT OR REPLACE INTO sync_records(kind,uuid,local_id,stamp,device,deleted,dirty) SELECT 'match',?,?,clock,device,0,1 FROM sync_meta", arrayOf<Any?>(matchUuid(deckUuid), deckId))
            database.setTransactionSuccessful()
            return true
        } finally { database.endTransaction() }
    }

    fun matchRecord(deckId: Long): Int? = db.rawQuery("SELECT seconds FROM profile_match WHERE deck_id=?", arrayOf(deckId.toString())).use { c -> if (c.moveToFirst()) c.getInt(0) else null }

    companion object {
        internal val settingKeys = setOf("language", "target", "interface_language", "randomDirection", "reminder")
        internal fun settingUuid(key: String): String = UUID.nameUUIDFromBytes("slovarik:settings:$key".toByteArray(StandardCharsets.UTF_8)).toString()
        internal fun matchUuid(deckUuid: String): String = UUID.nameUUIDFromBytes("slovarik:match:$deckUuid".toByteArray(StandardCharsets.UTF_8)).toString()
        internal val UNDO_ID: String = UUID.nameUUIDFromBytes("slovarik:undo:last-review".toByteArray(StandardCharsets.UTF_8)).toString()

        internal fun validateSetting(key: String, value: Any?) {
            require(key in settingKeys)
            when (key) {
                "language", "target" -> require(value is String && value in setOf("en", "ru", "de", "es", "fr"))
                "interface_language" -> require(value is String && value in setOf("ru", "en"))
                "randomDirection" -> require(value is Boolean)
                "reminder" -> {
                    require(value is Map<*, *> && value.keys == setOf("enabled", "hour", "minute"))
                    require(value["enabled"] is Boolean && ((value["hour"] as? Number)?.toInt() ?: -1) in 0..23 && ((value["minute"] as? Number)?.toInt() ?: -1) in 0..59)
                    require(listOf("hour", "minute").all { (value[it] as? Number)?.let { n -> n.toDouble() == n.toInt().toDouble() } == true })
                }
            }
        }

        internal fun decode(json: String): Any {
            val value = JSONObject(json).get("value")
            if (value is JSONObject) return buildMap<String, Any> { for (key in value.keys()) put(key, value.get(key)) }
            return value
        }

        fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE profile_settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE profile_match(deck_id INTEGER PRIMARY KEY REFERENCES decks(id) ON DELETE CASCADE,seconds INTEGER NOT NULL)")
            val clock = "UPDATE sync_meta SET clock=MAX(clock+1,CAST(strftime('%s','now') AS INTEGER)*1000) WHERE singleton=1;"
            for (key in settingKeys) {
                val id = settingUuid(key)
                db.execSQL("CREATE TRIGGER sync_setting_${key}_insert AFTER INSERT ON profile_settings WHEN NEW.key='$key' AND (SELECT suppress FROM sync_meta)=0 BEGIN $clock INSERT OR REPLACE INTO sync_records(kind,uuid,local_id,stamp,device,deleted,dirty) SELECT 'settings','$id',NULL,clock,device,0,1 FROM sync_meta; END")
                db.execSQL("CREATE TRIGGER sync_setting_${key}_update AFTER UPDATE ON profile_settings WHEN NEW.key='$key' AND (SELECT suppress FROM sync_meta)=0 BEGIN $clock INSERT OR REPLACE INTO sync_records(kind,uuid,local_id,stamp,device,deleted,dirty) SELECT 'settings','$id',NULL,clock,device,0,1 FROM sync_meta; END")
            }
            // SQLite lacks nameUUIDFromBytes: local match writes are journaled in setMatchRecord instead.
            db.execSQL("CREATE TRIGGER sync_match_delete AFTER DELETE ON profile_match WHEN (SELECT suppress FROM sync_meta)=0 BEGIN $clock UPDATE sync_records SET local_id=NULL,stamp=(SELECT clock FROM sync_meta),device=(SELECT device FROM sync_meta),deleted=1,dirty=1 WHERE kind='match' AND local_id=OLD.deck_id; END")
            db.execSQL("CREATE TRIGGER sync_undo_insert AFTER INSERT ON review_undo WHEN (SELECT suppress FROM sync_meta)=0 BEGIN $clock INSERT OR REPLACE INTO sync_records(kind,uuid,local_id,stamp,device,deleted,dirty) SELECT 'undo','$UNDO_ID',1,clock,device,0,1 FROM sync_meta; END")
            db.execSQL("CREATE TRIGGER sync_undo_delete AFTER DELETE ON review_undo WHEN (SELECT suppress FROM sync_meta)=0 BEGIN $clock UPDATE sync_records SET stamp=(SELECT clock FROM sync_meta),device=(SELECT device FROM sync_meta),dirty=1 WHERE kind='undo' AND uuid='$UNDO_ID'; END")
            db.execSQL("INSERT INTO sync_records(kind,uuid,local_id,stamp,device,deleted,dirty) SELECT 'undo','$UNDO_ID',1,clock,device,0,1 FROM sync_meta WHERE EXISTS(SELECT 1 FROM review_undo)")
        }
    }
}

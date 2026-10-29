package com.slovarik.app

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.util.UUID

/** Durable sync journal; SQLite triggers journal changes even when callers use writableDatabase directly. */
class SyncLocalStore(private val store: Store) {
    private val db get() = store.writableDatabase

    fun boundAccount(): String? = db.rawQuery("SELECT uid FROM sync_meta WHERE singleton=1", null).use {
        it.moveToFirst(); if (it.isNull(0)) null else it.getString(0)
    }

    fun bindAccount(uid: String): Boolean {
        require(uid.isNotBlank())
        val database = db
        database.beginTransaction()
        try {
            val existing = boundAccount()
            if (existing != null && existing != uid) return false
            if (existing == null) database.execSQL("UPDATE sync_meta SET uid=? WHERE singleton=1 AND uid IS NULL", arrayOf(uid))
            database.setTransactionSuccessful()
            return true
        } finally { database.endTransaction() }
    }

    fun pending(): List<SyncRecord> {
        val database = db
        database.beginTransaction()
        try {
            val result = database.rawQuery("SELECT kind,uuid,local_id,stamp,device,deleted FROM sync_records WHERE dirty=1 ORDER BY CASE kind WHEN 'folders' THEN 0 WHEN 'decks' THEN 1 WHEN 'cards' THEN 2 WHEN 'progress' THEN 3 WHEN 'events' THEN 4 WHEN 'settings' THEN 5 WHEN 'match' THEN 6 ELSE 7 END,uuid", null).use { c ->
                buildList {
                    while (c.moveToNext()) add(record(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2), c.getLong(3), c.getString(4), c.getInt(5) != 0))
                }
            }
            // Do not upload an envelope that another device would reject on import.
            result.forEach(::validate)
            database.setTransactionSuccessful()
            return result
        } finally { database.endTransaction() }
    }

    fun acknowledge(records: List<SyncRecord>) {
        val database = db
        database.beginTransaction()
        try {
            for (r in records) database.execSQL("UPDATE sync_records SET dirty=0 WHERE kind=? AND uuid=? AND stamp=? AND device=? AND deleted=?", arrayOf<Any?>(r.kind, r.id, r.stamp, r.device, if (r.deleted) 1 else 0))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    /** Applies available parents first; valid missing dependencies remain in the durable inbox. */
    fun applyRemote(records: List<SyncRecord>): Boolean {
        val database = db
        database.beginTransaction()
        try {
            require(records.map { it.kind to it.id }.distinct().size == records.size) { "Duplicate sync record" }
            records.forEach(::validate)
            var changed = false
            val hadUndo = database.rawQuery("SELECT 1 FROM review_undo LIMIT 1", null).use { it.moveToFirst() }
            val undoBefore = lookup(database, "undo", UserProfileStore.UNDO_ID)?.let { record("undo", UserProfileStore.UNDO_ID, it.localId, it.stamp, it.device, it.deleted) }
            var appliedUndoClear = false
            var appliedUndoAvailable = false
            database.execSQL("UPDATE sync_meta SET suppress=1 WHERE singleton=1")
            for (r in records) database.execSQL("UPDATE sync_meta SET clock=MAX(clock,?) WHERE singleton=1", arrayOf(r.stamp))
            val incoming = linkedMapOf<Pair<String, String>, SyncRecord>()
            for (old in deferred(database)) incoming[old.kind to old.id] = old
            for (r in records) {
                val key = r.kind to r.id
                val old = incoming[key]
                if (old == null || r.deleted && !old.deleted && r.kind !in listOf("settings", "undo") || r.deleted == old.deleted && compareSyncVersions(r, old) > 0) incoming[key] = r
                else if (r.deleted == old.deleted && compareSyncVersions(r, old) == 0) require(samePayload(r, old)) { "Conflicting sync payload" }
            }
            for (r in incoming.values.sortedWith(compareBy<SyncRecord> { kinds.indexOf(it.kind) }.thenBy { it.id })) {
                database.execSQL("UPDATE sync_meta SET clock=MAX(clock,?) WHERE singleton=1", arrayOf(r.stamp))
                val current = lookup(database, r.kind, r.id)
                if (r.kind == "match" && !r.deleted && current != null && !current.deleted) {
                    val oldBest = current.localId?.let { id -> database.rawQuery("SELECT seconds FROM profile_match WHERE deck_id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.getInt(0) else null } }
                    val remoteBest = (r.fields.getValue("seconds") as Number).toInt()
                    if (oldBest != null && oldBest < remoteBest) {
                        if (compareSyncVersions(r, SyncRecord(r.id, r.kind, current.stamp, current.device, false, emptyMap())) >= 0) {
                            database.execSQL("UPDATE sync_meta SET clock=MAX(clock,?)+1 WHERE singleton=1", arrayOf(r.stamp))
                            database.execSQL("UPDATE sync_records SET stamp=(SELECT clock FROM sync_meta),device=(SELECT device FROM sync_meta),dirty=1 WHERE kind='match' AND uuid=?", arrayOf(r.id))
                            changed = true
                        }
                        clearDeferred(database, r)
                        continue
                    }
                }
                if (current?.deleted == true) {
                    if (r.deleted) {
                        // The server already has the irreversible deletion: replace any obsolete outbox version.
                        if (r.stamp >= current.stamp) {
                            if (r.stamp != current.stamp || r.device != current.device) changed = true
                            save(database, r, null)
                        }
                    } else if (r.stamp >= current.stamp) {
                        // Firestore rejects an older tombstone against a concurrent higher-stamp edit.
                        // Never revive the row: advance its tombstone beyond the observed server revision.
                        database.execSQL("UPDATE sync_meta SET clock=MAX(clock,?)+1 WHERE singleton=1", arrayOf(r.stamp))
                        val stamp = database.rawQuery("SELECT clock FROM sync_meta", null).use { it.moveToFirst(); it.getLong(0) }
                        require(stamp <= MAX_WIRE_INTEGER) { "Sync clock exceeds wire limit" }
                        database.execSQL("UPDATE sync_records SET stamp=?,device=(SELECT device FROM sync_meta),dirty=1 WHERE kind=? AND uuid=?", arrayOf<Any?>(stamp, r.kind, r.id))
                        changed = true
                    }
                    clearDeferred(database, r)
                    continue
                }
                if (r.deleted && r.kind !in listOf("settings", "undo")) {
                    // A server-side purge is irreversible even if an offline edit has a higher stamp.
                    val localId = apply(database, r, current?.localId)
                    save(database, r, localId)
                    clearDeferred(database, r)
                    changed = true
                    continue
                }
                if (current != null) {
                    val previous = if (r.kind == "undo" && undoBefore?.stamp == current.stamp && undoBefore.device == current.device) undoBefore else record(r.kind, r.id, current.localId, current.stamp, current.device, current.deleted)
                    val version = compareSyncVersions(r, previous)
                    val betterMatch = r.kind == "match" && !r.deleted && current.localId?.let { id -> database.rawQuery("SELECT seconds FROM profile_match WHERE deck_id=?", arrayOf(id.toString())).use { c -> c.moveToFirst() && c.getInt(0) > (r.fields.getValue("seconds") as Number).toInt() } } == true
                    if (version <= 0 && !betterMatch) {
                        if (version == 0) require(samePayload(r, previous)) { "Conflicting sync payload" }
                        clearDeferred(database, r)
                        continue
                    }
                }
                val parent = parentOf(r)
                if (r.kind == "undo" && r.fields["available"] == true && lookup(database, "cards", r.fields["card"] as String)?.localId == null) {
                    if (lookup(database, "cards", r.fields["card"] as String)?.deleted == true) {
                        database.execSQL("DELETE FROM review_undo")
                        save(database, r, 1L)
                        database.execSQL("UPDATE sync_meta SET clock=MAX(clock,?)+1 WHERE singleton=1", arrayOf(r.stamp))
                        database.execSQL("UPDATE sync_records SET stamp=(SELECT clock FROM sync_meta),device=(SELECT device FROM sync_meta),dirty=1 WHERE kind='undo' AND uuid=?", arrayOf(r.id))
                        clearDeferred(database, r); changed = true
                    } else defer(database, r)
                    continue
                }
                if (parent != null && lookup(database, parent.first, parent.second)?.localId == null) {
                    if (lookup(database, parent.first, parent.second)?.deleted == true) {
                        if (r.kind == "events") {
                            val withoutCard = r.copy(fields = r.fields + ("card" to null))
                            val localId = apply(database, withoutCard, current?.localId)
                            save(database, withoutCard, localId)
                            // Publish the cleared reference: remote history must not point at a purged card.
                            database.execSQL("UPDATE sync_meta SET clock=clock+1 WHERE singleton=1")
                            database.execSQL("UPDATE sync_records SET stamp=(SELECT clock FROM sync_meta),device=(SELECT device FROM sync_meta),dirty=1 WHERE kind='events' AND uuid=?", arrayOf(r.id))
                        } else if (r.kind == "undo") {
                            database.execSQL("DELETE FROM review_undo")
                            save(database, r, 1L)
                            database.execSQL("UPDATE sync_meta SET clock=MAX(clock,?)+1 WHERE singleton=1", arrayOf(r.stamp))
                            database.execSQL("UPDATE sync_records SET stamp=(SELECT clock FROM sync_meta),device=(SELECT device FROM sync_meta),dirty=1 WHERE kind='undo' AND uuid=?", arrayOf(r.id))
                        } else retire(database, r, current?.localId)
                        clearDeferred(database, r)
                        changed = true
                    } else defer(database, r)
                    continue
                }
                val localId = apply(database, r, current?.localId)
                save(database, r, localId)
                if (r.kind == "undo") { appliedUndoClear = r.fields["available"] == false; appliedUndoAvailable = r.fields["available"] == true }
                if (betterMatchVersion(database, r, current)) {
                    database.execSQL("UPDATE sync_meta SET clock=MAX(clock,?)+1 WHERE singleton=1", arrayOf(r.stamp))
                    database.execSQL("UPDATE sync_records SET stamp=(SELECT clock FROM sync_meta),device=(SELECT device FROM sync_meta),dirty=1 WHERE kind='match' AND uuid=?", arrayOf(r.id))
                }
                clearDeferred(database, r)
                changed = true
            }
            val invalidUndo = database.rawQuery("SELECT 1 FROM review_undo u JOIN cards c ON c.id=u.card_id JOIN events e ON e.id=u.event_id AND e.card_id=u.card_id WHERE c.reps=u.after_reps AND c.interval_days=u.after_interval AND c.ease=u.after_ease AND c.due=u.after_due AND c.learning_step=u.after_step LIMIT 1", null).use { valid ->
                !valid.moveToFirst() && database.rawQuery("SELECT 1 FROM review_undo LIMIT 1", null).use { it.moveToFirst() }
            }
            if (invalidUndo) database.execSQL("DELETE FROM review_undo")
            if (!appliedUndoClear && (hadUndo || appliedUndoAvailable) && (invalidUndo || !database.rawQuery("SELECT 1 FROM review_undo LIMIT 1", null).use { it.moveToFirst() })) {
                database.execSQL("UPDATE sync_meta SET clock=MAX(clock+1,CAST(strftime('%s','now') AS INTEGER)*1000) WHERE singleton=1")
                database.execSQL("UPDATE sync_records SET stamp=(SELECT clock FROM sync_meta),device=(SELECT device FROM sync_meta),dirty=1 WHERE kind='undo' AND uuid=?", arrayOf(UserProfileStore.UNDO_ID))
                changed = true
            }
            database.execSQL("UPDATE sync_meta SET suppress=0 WHERE singleton=1")
            database.setTransactionSuccessful()
            return changed
        } finally { database.endTransaction() }
    }

    private fun equalValue(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> java.math.BigDecimal(a.toString()).compareTo(java.math.BigDecimal(b.toString())) == 0
        a is Map<*, *> && b is Map<*, *> -> a.keys == b.keys && a.all { (key, value) -> equalValue(value, b[key]) }
        else -> a == b
    }
    private fun samePayload(a: SyncRecord, b: SyncRecord): Boolean = a.deleted == b.deleted && (a.deleted || equalValue(a.fields, b.fields))

    private fun parentOf(r: SyncRecord): Pair<String, String>? = if (r.deleted) null else when (r.kind) {
        "decks" -> "folders" to (r.fields.getValue("folder") as String)
        "cards" -> "decks" to (r.fields.getValue("deck") as String)
        "progress" -> "cards" to (r.fields.getValue("card") as String)
        "events" -> (r.fields["card"] as? String)?.let { "cards" to it }
        "match" -> "decks" to (r.fields.getValue("deck") as String)
        "undo" -> if (r.fields["available"] == true) "events" to (r.fields.getValue("event") as String) else null
        else -> null
    }
    private fun save(database: SQLiteDatabase, r: SyncRecord, id: Long?) = database.execSQL("INSERT OR REPLACE INTO sync_records(kind,uuid,local_id,stamp,device,deleted,dirty) VALUES(?,?,?,?,?,?,0)", arrayOf<Any?>(r.kind, r.id, id, r.stamp, r.device, if (r.deleted) 1 else 0))
    private fun clearDeferred(database: SQLiteDatabase, r: SyncRecord) = database.execSQL("DELETE FROM sync_inbox WHERE kind=? AND uuid=?", arrayOf(r.kind, r.id))
    private fun defer(database: SQLiteDatabase, r: SyncRecord) = database.execSQL("INSERT OR REPLACE INTO sync_inbox(kind,uuid,stamp,device,deleted,fields) VALUES(?,?,?,?,?,?)", arrayOf<Any?>(r.kind, r.id, r.stamp, r.device, if (r.deleted) 1 else 0, JSONObject(r.fields).toString()))
    private fun deferred(database: SQLiteDatabase): List<SyncRecord> = database.rawQuery("SELECT kind,uuid,stamp,device,deleted,fields FROM sync_inbox", null).use { c ->
        buildList { while (c.moveToNext()) {
            val json = JSONObject(c.getString(5))
            val fields = buildMap<String, Any?> { for (key in json.keys()) put(key, json.get(key).takeUnless { it == JSONObject.NULL }?.let { value -> if (value is JSONObject) buildMap<String, Any> { for (item in value.keys()) put(item, value.get(item)) } else value }) }
            add(SyncRecord(c.getString(1), c.getString(0), c.getLong(2), c.getString(3), c.getInt(4) != 0, fields))
        } }
    }

    private data class Entry(val localId: Long?, val stamp: Long, val device: String, val deleted: Boolean)
    private fun betterMatchVersion(database: SQLiteDatabase, r: SyncRecord, old: Entry?): Boolean = r.kind == "match" && old != null && compareSyncVersions(r, SyncRecord(r.id, r.kind, old.stamp, old.device, false, emptyMap())) < 0
    private fun lookup(database: SQLiteDatabase, kind: String, uuid: String): Entry? = database.rawQuery("SELECT local_id,stamp,device,deleted FROM sync_records WHERE kind=? AND uuid=?", arrayOf(kind, uuid)).use { c ->
        if (c.moveToFirst()) Entry(if (c.isNull(0)) null else c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3) != 0) else null
    }
    private fun localId(database: SQLiteDatabase, kind: String, uuid: String): Long =
        requireNotNull(lookup(database, kind, uuid)?.localId) { "Missing $kind parent $uuid" }

    private fun retire(database: SQLiteDatabase, r: SyncRecord, id: Long?) {
        database.execSQL("UPDATE sync_meta SET clock=MAX(clock+1,?) WHERE singleton=1", arrayOf(r.stamp + 1))
        database.execSQL("INSERT OR REPLACE INTO sync_records(kind,uuid,local_id,stamp,device,deleted,dirty) SELECT ?,?,NULL,clock,device,1,1 FROM sync_meta", arrayOf(r.kind, r.id))
        if (id != null) purge(database, r.kind, id)
    }

    private fun purge(database: SQLiteDatabase, kind: String, id: Long) {
        if (kind == "match") {
            database.execSQL("DELETE FROM profile_match WHERE deck_id=?", arrayOf(id))
            return
        }
        // Collect descendants before SQLite's cascading DELETE removes their integer IDs.
        val descendants = when (kind) {
            "folders" -> database.rawQuery("SELECT 'decks',id FROM decks WHERE folder_id=? UNION ALL SELECT 'cards',c.id FROM cards c JOIN decks d ON d.id=c.deck_id WHERE d.folder_id=?", arrayOf(id.toString(), id.toString()))
            "decks" -> database.rawQuery("SELECT 'cards',id FROM cards WHERE deck_id=?", arrayOf(id.toString()))
            else -> null
        }
        val children = descendants?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }.orEmpty()
        val cardIds = (children.filter { it.first == "cards" }.map { it.second } + if (kind == "cards") listOf(id) else emptyList()).distinct()
        for (card in cardIds) {
            // FK SET NULL preserves history. Journal only those event references which change.
            database.execSQL("UPDATE sync_meta SET clock=clock+1 WHERE singleton=1")
            database.execSQL("UPDATE sync_records SET stamp=(SELECT clock FROM sync_meta),device=(SELECT device FROM sync_meta),dirty=1 WHERE kind='events' AND local_id IN (SELECT id FROM events WHERE card_id=?)", arrayOf(card))
        }
        val deckIds = (children.filter { it.first == "decks" }.map { it.second } + if (kind == "decks") listOf(id) else emptyList()).distinct()
        for (deck in deckIds) {
            database.execSQL("DELETE FROM profile_match WHERE deck_id=?", arrayOf(deck))
            database.execSQL("UPDATE sync_meta SET clock=clock+1 WHERE singleton=1")
            database.execSQL("UPDATE sync_records SET local_id=NULL,deleted=1,dirty=1,stamp=(SELECT clock FROM sync_meta),device=(SELECT device FROM sync_meta) WHERE kind='match' AND local_id=?", arrayOf(deck))
        }
        for ((childKind, childId) in children + cardIds.map { "progress" to it }) {
            database.execSQL("UPDATE sync_meta SET clock=clock+1 WHERE singleton=1")
            database.execSQL("UPDATE sync_records SET local_id=NULL,deleted=1,dirty=1,stamp=(SELECT clock FROM sync_meta),device=(SELECT device FROM sync_meta) WHERE kind=? AND local_id=?", arrayOf(childKind, childId))
        }
        database.execSQL("DELETE FROM $kind WHERE id=?", arrayOf(id))
        database.execSQL("DELETE FROM review_undo WHERE card_id NOT IN (SELECT id FROM cards)")
    }

    private fun apply(database: SQLiteDatabase, r: SyncRecord, existing: Long?): Long? {
        val f = r.fields
        if (r.kind == "settings") {
            if (r.deleted) database.execSQL("DELETE FROM profile_settings WHERE key=?", arrayOf(f["key"] ?: UserProfileStore.settingKeys.single { UserProfileStore.settingUuid(it) == r.id }))
            else database.execSQL("INSERT OR REPLACE INTO profile_settings(key,value) VALUES(?,?)", arrayOf(f.getValue("key"), JSONObject().put("value", f.getValue("value").let { if (it is Map<*, *>) JSONObject(it) else it }).toString()))
            return null
        }
        if (r.kind == "match") {
            if (r.deleted) { if (existing != null) database.execSQL("DELETE FROM profile_match WHERE deck_id=?", arrayOf(existing)); return null }
            val deck = localId(database, "decks", f.getValue("deck") as String)
            database.execSQL("INSERT OR REPLACE INTO profile_match(deck_id,seconds) VALUES(?,?)", arrayOf(deck, f.getValue("seconds")))
            return deck
        }
        if (r.kind == "undo") {
            database.execSQL("DELETE FROM review_undo")
            if (f["available"] == true) {
                val card = localId(database, "cards", f.getValue("card") as String)
                val event = localId(database, "events", f.getValue("event") as String)
                val values = arrayOf<Any?>(event, card, f.getValue("beforeReps"), f.getValue("beforeInterval"), f.getValue("beforeEase"), f.getValue("beforeDue"), f.getValue("beforeStep"), f.getValue("afterReps"), f.getValue("afterInterval"), f.getValue("afterEase"), f.getValue("afterDue"), f.getValue("afterStep"))
                database.execSQL("INSERT INTO review_undo VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", values)
            }
            return 1L
        }
        if (r.deleted) {
            if (existing != null) {
                if (r.kind == "events") {
                    database.execSQL("DELETE FROM review_undo WHERE event_id=?", arrayOf(existing))
                    database.execSQL("DELETE FROM events WHERE id=?", arrayOf(existing))
                    return null
                }
                if (r.kind == "progress") {
                    database.execSQL("UPDATE sync_meta SET clock=clock+1 WHERE singleton=1")
                    database.execSQL("UPDATE sync_records SET local_id=NULL,deleted=1,dirty=1,stamp=(SELECT clock FROM sync_meta),device=(SELECT device FROM sync_meta) WHERE kind='cards' AND local_id=?", arrayOf(existing))
                    purge(database, "cards", existing)
                    return null
                }
                purge(database, r.kind, existing)
            }
            return null
        }
        fun str(key: String) = f.getValue(key) as String
        fun num(key: String) = f.getValue(key) as Number
        fun parent(kind: String, key: String) = localId(database, kind, str(key))
        if (r.kind == "progress") {
            val card = parent("cards", "card")
            database.execSQL("DELETE FROM review_undo WHERE card_id=? AND (after_reps!=? OR after_interval!=? OR after_ease!=? OR after_due!=? OR after_step!=?)", arrayOf(card, num("reps"), num("interval"), num("ease"), num("due"), num("step")))
            database.execSQL("UPDATE cards SET reps=?,interval_days=?,ease=?,due=?,learning_step=? WHERE id=?", arrayOf(num("reps"), num("interval"), num("ease"), num("due"), num("step"), card))
            return card
        }
        val table = r.kind
        val columns: List<String>
        val values: List<Any?>
        when (table) {
            "folders" -> { columns = listOf("name", "deleted_at"); values = listOf(str("name"), num("deletedAt")) }
            "decks" -> { columns = listOf("folder_id", "name", "deleted_at"); values = listOf(parent("folders", "folder"), str("name"), num("deletedAt")) }
            "cards" -> { columns = listOf("deck_id", "word", "translation", "deleted_at"); values = listOf(parent("decks", "deck"), str("word"), str("translation"), num("deletedAt")) }
            else -> { columns = listOf("card_id", "day", "correct"); values = listOf(f["card"]?.let { parent("cards", "card") }, num("day"), num("correct")) }
        }
        if (existing != null) {
            database.execSQL("UPDATE $table SET ${columns.joinToString { "$it=?" }} WHERE id=?", (values + existing).toTypedArray())
            return existing
        }
        database.execSQL("INSERT INTO $table(${columns.joinToString()}) VALUES(${columns.joinToString { "?" }})", values.toTypedArray())
        val id = database.rawQuery("SELECT last_insert_rowid()", null).use { it.moveToFirst(); it.getLong(0) }
        if (table == "cards") {
            database.execSQL("INSERT OR IGNORE INTO sync_records(kind,uuid,local_id,stamp,device,deleted,dirty) VALUES('progress',?,?,?,?,0,0)", arrayOf<Any?>("progress-${r.id}", id, 0L, r.device))
            database.execSQL("UPDATE sync_records SET local_id=? WHERE kind='progress' AND uuid=? AND local_id IS NULL", arrayOf<Any?>(id, "progress-${r.id}"))
        }
        return id
    }

    private fun validate(r: SyncRecord) {
        require(r.kind in kinds && r.id.isNotBlank() && r.id.length <= 256 && r.device.isNotBlank() && r.device.length <= 256 && r.stamp in 1..MAX_WIRE_INTEGER)
        if (r.deleted) { require(r.kind !in listOf("settings", "undo") && r.fields.isEmpty()); return }
        val f = r.fields
        val expected = when (r.kind) {
            "folders" -> setOf("name", "deletedAt")
            "decks" -> setOf("folder", "name", "deletedAt")
            "cards" -> setOf("deck", "word", "translation", "deletedAt")
            "progress" -> setOf("card", "reps", "interval", "ease", "due", "step")
            "events" -> setOf("card", "day", "correct")
            "settings" -> setOf("key", "value")
            "match" -> setOf("deck", "seconds")
            else -> if (f["available"] == false) setOf("available") else setOf("available", "card", "event", "beforeReps", "beforeInterval", "beforeEase", "beforeDue", "beforeStep", "afterReps", "afterInterval", "afterEase", "afterDue", "afterStep")
        }
        require(f.keys == expected) { "Invalid sync fields" }
        fun text(key: String, maxChars: Int) = require((f[key] as? String)?.let { it.isNotBlank() && it.length <= maxChars } == true) { "Invalid $key" }
        fun integer(key: String, max: Long = MAX_WIRE_INTEGER) = require((f[key] as? Number)?.let {
            runCatching { java.math.BigDecimal(it.toString()).toBigIntegerExact() }.getOrNull()?.let { value -> value.signum() >= 0 && value <= java.math.BigInteger.valueOf(max) }
        } == true) { "Invalid $key" }
        fun ease() = require((f["ease"] as? Number)?.toDouble()?.let { it.isFinite() && it >= 1.3 && it <= 1e6 } == true) { "Invalid ease" }
        when (r.kind) {
            "folders" -> { text("name", 4096); integer("deletedAt") }
            "decks" -> { text("folder", 256); text("name", 4096); integer("deletedAt") }
            "cards" -> { text("deck", 256); text("word", 65536); text("translation", 65536); integer("deletedAt") }
            "progress" -> { text("card", 256); require(r.id == "progress-${f["card"]}"); integer("reps", Int.MAX_VALUE.toLong()); integer("interval", Int.MAX_VALUE.toLong()); integer("due"); integer("step", 2); ease() }
            "events" -> { require(f.containsKey("card") && (f["card"] == null || f["card"] is String && (f["card"] as String).length <= 256)); integer("day"); integer("correct", 1) }
            "settings" -> { text("key", 64); require(r.id == UserProfileStore.settingUuid(f["key"] as String)); UserProfileStore.validateSetting(f["key"] as String, f["value"]) }
            "match" -> { text("deck", 256); require(r.id == UserProfileStore.matchUuid(f["deck"] as String)); integer("seconds", Int.MAX_VALUE.toLong()) }
            "undo" -> { require(r.id == UserProfileStore.UNDO_ID && f["available"] is Boolean); if (f["available"] == true) {
                text("card", 256); text("event", 256)
                for (prefix in listOf("before", "after")) {
                    integer("${prefix}Reps", Int.MAX_VALUE.toLong()); integer("${prefix}Interval", Int.MAX_VALUE.toLong()); integer("${prefix}Due"); integer("${prefix}Step", 2)
                    require((f["${prefix}Ease"] as? Number)?.toDouble()?.let { it.isFinite() && it in 1.3..1e6 } == true)
                }
            } }
        }
    }

    private fun record(kind: String, uuid: String, localId: Long?, stamp: Long, device: String, deleted: Boolean): SyncRecord {
        if (deleted || localId == null && kind !in listOf("settings")) return SyncRecord(uuid, kind, stamp, device, true, emptyMap())
        val fields: Map<String, Any?> = when (kind) {
            "folders" -> row("SELECT name,deleted_at FROM folders WHERE id=?", localId) { c -> mapOf("name" to c.getString(0), "deletedAt" to c.getLong(1)) }
            "decks" -> row("SELECT r.uuid,d.name,d.deleted_at FROM decks d JOIN sync_records r ON r.kind='folders' AND r.local_id=d.folder_id WHERE d.id=?", localId) { c -> mapOf("folder" to c.getString(0), "name" to c.getString(1), "deletedAt" to c.getLong(2)) }
            "cards" -> row("SELECT r.uuid,c.word,c.translation,c.deleted_at FROM cards c JOIN sync_records r ON r.kind='decks' AND r.local_id=c.deck_id WHERE c.id=?", localId) { c -> mapOf("deck" to c.getString(0), "word" to c.getString(1), "translation" to c.getString(2), "deletedAt" to c.getLong(3)) }
            "progress" -> row("SELECT r.uuid,c.reps,c.interval_days,c.ease,c.due,c.learning_step FROM cards c JOIN sync_records r ON r.kind='cards' AND r.local_id=c.id WHERE c.id=?", localId) { c -> mapOf("card" to c.getString(0), "reps" to c.getInt(1), "interval" to c.getInt(2), "ease" to c.getDouble(3), "due" to c.getLong(4), "step" to c.getInt(5)) }
            "settings" -> { val key = UserProfileStore.settingKeys.single { UserProfileStore.settingUuid(it) == uuid }; mapOf("key" to key, "value" to requireNotNull(UserProfileStore(store).setting(key))) }
            "match" -> row("SELECT r.uuid,m.seconds FROM profile_match m JOIN sync_records r ON r.kind='decks' AND r.local_id=m.deck_id WHERE m.deck_id=?", requireNotNull(localId)) { c -> mapOf("deck" to c.getString(0), "seconds" to c.getInt(1)) }
            "undo" -> db.rawQuery("SELECT cr.uuid,er.uuid,u.before_reps,u.before_interval,u.before_ease,u.before_due,u.before_step,u.after_reps,u.after_interval,u.after_ease,u.after_due,u.after_step FROM review_undo u JOIN sync_records cr ON cr.kind='cards' AND cr.local_id=u.card_id JOIN sync_records er ON er.kind='events' AND er.local_id=u.event_id", null).use { c -> if (!c.moveToFirst()) mapOf("available" to false) else mapOf("available" to true, "card" to c.getString(0), "event" to c.getString(1), "beforeReps" to c.getInt(2), "beforeInterval" to c.getInt(3), "beforeEase" to c.getDouble(4), "beforeDue" to c.getLong(5), "beforeStep" to c.getInt(6), "afterReps" to c.getInt(7), "afterInterval" to c.getInt(8), "afterEase" to c.getDouble(9), "afterDue" to c.getLong(10), "afterStep" to c.getInt(11)) }
            else -> row("SELECT r.uuid,e.day,e.correct FROM events e LEFT JOIN sync_records r ON r.kind='cards' AND r.local_id=e.card_id WHERE e.id=?", localId) { c -> mapOf("card" to if (c.isNull(0)) null else c.getString(0), "day" to c.getLong(1), "correct" to c.getInt(2)) }
        }
        return SyncRecord(uuid, kind, stamp, device, false, fields)
    }
    private fun row(sql: String, id: Long?, fields: (android.database.Cursor) -> Map<String, Any?>): Map<String, Any?> =
        db.rawQuery(sql, arrayOf(requireNotNull(id).toString())).use { c -> require(c.moveToFirst()) { "Missing synced row $id" }; fields(c) }

    companion object {
        private const val MAX_WIRE_INTEGER = 9_007_199_254_740_991L
        private val kinds = listOf("folders", "decks", "cards", "progress", "events", "settings", "match", "undo")
        private val uuidSql = "lower(hex(randomblob(16)))"
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sync_meta(singleton INTEGER PRIMARY KEY CHECK(singleton=1), uid TEXT, device TEXT NOT NULL, clock INTEGER NOT NULL, suppress INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO sync_meta(singleton,device,clock) VALUES(1,?,0)", arrayOf(UUID.randomUUID().toString()))
            db.execSQL("CREATE TABLE sync_records(kind TEXT NOT NULL, uuid TEXT NOT NULL, local_id INTEGER, stamp INTEGER NOT NULL, device TEXT NOT NULL, deleted INTEGER NOT NULL, dirty INTEGER NOT NULL, PRIMARY KEY(kind,uuid))")
            db.execSQL("CREATE UNIQUE INDEX sync_local_identity ON sync_records(kind,local_id)")
            db.execSQL("CREATE TABLE sync_inbox(kind TEXT NOT NULL, uuid TEXT NOT NULL, stamp INTEGER NOT NULL, device TEXT NOT NULL, deleted INTEGER NOT NULL, fields TEXT NOT NULL, PRIMARY KEY(kind,uuid))")
            // Seed before installing triggers: upgraded v2 rows retain their IDs and all history.
            for (kind in kinds.take(5)) {
                val table = if (kind == "progress") "cards" else kind
                db.rawQuery("SELECT id,${if (kind in listOf("folders", "decks", "cards")) "deleted_at" else "0"} FROM $table ORDER BY id", null).use { c ->
                    while (c.moveToNext()) {
                        db.execSQL("UPDATE sync_meta SET clock=MAX(clock+1,?) WHERE singleton=1", arrayOf(System.currentTimeMillis()))
                        val uuid = if (kind == "progress") db.rawQuery("SELECT uuid FROM sync_records WHERE kind='cards' AND local_id=?", arrayOf(c.getLong(0).toString())).use { card -> card.moveToFirst(); "progress-${card.getString(0)}" } else UUID.randomUUID().toString()
                        db.execSQL("INSERT INTO sync_records(kind,uuid,local_id,stamp,device,deleted,dirty) SELECT ?,?,?,clock,device,0,1 FROM sync_meta", arrayOf<Any?>(kind, uuid, c.getLong(0)))
                    }
                }
            }
            for (kind in kinds.take(5)) {
                val table = if (kind == "progress") "cards" else kind
                val deleted = "0"
                val mark = "UPDATE sync_meta SET clock=MAX(clock+1,CAST(strftime('%s','now') AS INTEGER)*1000) WHERE singleton=1; UPDATE sync_records SET stamp=(SELECT clock FROM sync_meta),device=(SELECT device FROM sync_meta),deleted=$deleted,dirty=1 WHERE kind='$kind' AND local_id=NEW.id;"
                val insert = "UPDATE sync_meta SET clock=MAX(clock+1,CAST(strftime('%s','now') AS INTEGER)*1000) WHERE singleton=1; INSERT INTO sync_records(kind,uuid,local_id,stamp,device,deleted,dirty) SELECT '$kind',$uuidSql,NEW.id,clock,device,$deleted,1 FROM sync_meta;" + if (kind == "cards") " INSERT INTO sync_records(kind,uuid,local_id,stamp,device,deleted,dirty) SELECT 'progress','progress-' || uuid,NEW.id,stamp,device,0,1 FROM sync_records WHERE kind='cards' AND local_id=NEW.id;" else ""
                if (kind != "progress") db.execSQL("CREATE TRIGGER sync_${kind}_insert AFTER INSERT ON $table WHEN (SELECT suppress FROM sync_meta)=0 BEGIN $insert END")
                val updateCols = when (kind) {
                    "folders" -> "name,deleted_at"
                    "decks" -> "folder_id,name,deleted_at"
                    "cards" -> "deck_id,word,translation,deleted_at"
                    "progress" -> "reps,interval_days,ease,due,learning_step"
                    else -> "card_id,day,correct"
                }
                db.execSQL("CREATE TRIGGER sync_${kind}_update AFTER UPDATE OF $updateCols ON $table WHEN (SELECT suppress FROM sync_meta)=0 BEGIN $mark END")
                db.execSQL("CREATE TRIGGER sync_${kind}_delete AFTER DELETE ON $table WHEN (SELECT suppress FROM sync_meta)=0 BEGIN UPDATE sync_meta SET clock=MAX(clock+1,CAST(strftime('%s','now') AS INTEGER)*1000) WHERE singleton=1; UPDATE sync_records SET local_id=NULL,stamp=(SELECT clock FROM sync_meta),device=(SELECT device FROM sync_meta),deleted=1,dirty=1 WHERE kind='$kind' AND local_id=OLD.id; END")
            }
        }
    }
}

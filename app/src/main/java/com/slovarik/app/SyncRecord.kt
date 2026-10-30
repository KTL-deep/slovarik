package com.slovarik.app

/** Wire format: name, deletedAt (folders/decks/cards, including soft trash);
 * folder (nullable for unfiled decks), deck/card (parent UUID), word,translation; reps,interval,ease,due,step;
 * or day,correct and nullable card for events. IDs are UUID/32-hex; progress
 * IDs are "progress-" plus the card ID. Fixtures may use shorter IDs.
 * settings carry key/value, match carries deck UUID/seconds (best time), undo carries
 * a singleton available flag and, when available, card/event UUIDs and before/after review.
 * deleted means irreversible purge (including match on deck purge), never reversible trash;
 * tombstones have empty fields. Undo clearing uses available=false, not a tombstone.
 */
data class SyncRecord(
    val id: String,
    val kind: String,
    val stamp: Long,
    val device: String,
    val deleted: Boolean,
    val fields: Map<String, Any?>
)

/** Positive means [a] wins. Equal stamps are broken by device ID. */
fun compareSyncVersions(a: SyncRecord, b: SyncRecord): Int =
    compareValues(a.stamp, b.stamp).takeIf { it != 0 } ?: a.device.compareTo(b.device)

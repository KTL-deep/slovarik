package com.slovarik.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserProfileStoreDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = listOf("profile-a.db", "profile-b.db")
    @After fun cleanup() { names.forEach(context::deleteDatabase) }

    @Test fun settingsAreSparseAndIndependent() {
        names.forEach(context::deleteDatabase)
        Store(context, names[0]).use { a -> Store(context, names[1]).use { b ->
            val ap = UserProfileStore(a); val bp = UserProfileStore(b)
            val asyn = SyncLocalStore(a); val bsyn = SyncLocalStore(b)
            assertTrue(asyn.pending().isEmpty())
            ap.setSetting("language", "en"); bp.setSetting("target", "fr")
            val first = asyn.pending().single()
            assertEquals(UserProfileStore.settingUuid("language"), first.id)
            bsyn.applyRemote(listOf(first)); asyn.applyRemote(bsyn.pending().filter { it.kind == "settings" && it.id != first.id })
            assertEquals(mapOf("language" to "en", "target" to "fr"), ap.settings())
            assertEquals(ap.settings(), bp.settings())
        } }
    }

    @Test fun matchUsesDeckUuidAndLowestTimeIncludingZero() {
        names.forEach(context::deleteDatabase)
        Store(context, names[0]).use { a -> Store(context, names[1]).use { b ->
            val af = a.addFolder("a"); val ad = a.addDeck(af, "d")
            b.addFolder("other"); b.addDeck(b.folders().single().id, "other")
            val asyn = SyncLocalStore(a); val bsyn = SyncLocalStore(b)
            bsyn.applyRemote(asyn.pending())
            val bd = b.decks(b.folders().single { it.name == "a" }.id).single().id
            assertNotEquals(ad, bd)
            val ap = UserProfileStore(a); val bp = UserProfileStore(b)
            assertTrue(ap.setMatchRecord(ad, 25)); assertTrue(bp.setMatchRecord(bd, 18))
            val aRecord = asyn.pending().single { it.kind == "match" }
            val bRecord = bsyn.pending().single { it.kind == "match" }
            assertEquals(aRecord.id, bRecord.id)
            asyn.applyRemote(listOf(bRecord)); bsyn.applyRemote(listOf(aRecord.copy(stamp = bRecord.stamp + 100)))
            assertEquals(18, ap.matchRecord(ad)); assertEquals(18, bp.matchRecord(bd))
            assertTrue(ap.setMatchRecord(ad, 0))
            bsyn.applyRemote(asyn.pending().filter { it.kind == "match" })
            assertEquals(0, bp.matchRecord(bd))
        } }
    }

    @Test fun receiptSurvivesOtherLocalEventIdsAndClearsBeforeNewReview() {
        names.forEach(context::deleteDatabase)
        Store(context, names[0]).use { a -> Store(context, names[1]).use { b ->
            val card = a.getCard(a.addCard(a.addDeck(a.addFolder("f"), "d"), "word", "translation"))!!
            val asyn = SyncLocalStore(a); val bsyn = SyncLocalStore(b)
            b.addFolder("local")
            bsyn.applyRemote(asyn.pending())
            a.review(card, 4)
            bsyn.applyRemote(asyn.pending())
            val bc = b.cards(b.decks(b.folders().single { it.name == "f" }.id).single().id).single()
            assertNotNull(b.lastReviewUndo())
            val receipt = b.lastReviewUndo()!!
            assertEquals(bc.id, receipt.card.id)
            assertNotNull(b.undoLastReview(receipt.eventId))
            assertEquals(0, b.stats().reviews)
            asyn.applyRemote(bsyn.pending().filter { it.kind == "undo" || it.kind == "events" || it.kind == "progress" })
            assertNull(a.lastReviewUndo())
            a.review(a.getCard(card.id)!!, 5)
            assertEquals(true, asyn.pending().single { it.kind == "undo" }.fields["available"])
        } }
    }

    @Test fun reminderEqualVersionAcceptsNestedLongNumbersAndUndoIdIsWireUuid() {
        names.forEach(context::deleteDatabase)
        Store(context, names[0]).use { a ->
            val sync = SyncLocalStore(a)
            val profile = UserProfileStore(a)
            profile.setSetting("reminder", mapOf("enabled" to true, "hour" to 9, "minute" to 0))
            val saved = sync.pending().single()
            assertFalse(sync.applyRemote(listOf(saved.copy(fields = saved.fields + ("value" to mapOf("enabled" to true, "hour" to 9L, "minute" to 0L))))))
            val card = a.getCard(a.addCard(a.addDeck(a.addFolder("f"), "d"), "w", "t"))!!
            a.review(card, 4)
            val id = sync.pending().single { it.kind == "undo" }.id
            assertEquals(id, java.util.UUID.fromString(id).toString())
        }
    }

    @Test fun matchSurvivesTrashButPurgePublishesPermanentTombstone() {
        names.forEach(context::deleteDatabase)
        Store(context, names[0]).use { a -> Store(context, names[1]).use { b ->
            val deck = a.addDeck(a.addFolder("f"), "d")
            val profile = UserProfileStore(a)
            val sync = SyncLocalStore(a); val peer = SyncLocalStore(b)
            profile.setMatchRecord(deck, 0)
            val snapshot = sync.pending()
            peer.applyRemote(snapshot)
            val match = snapshot.single { it.kind == "match" }
            a.deleteDeck(deck)
            assertEquals(0, profile.matchRecord(deck))
            assertFalse(sync.pending().single { it.kind == "match" }.deleted)
            a.purgeTrash(TrashKind.DECK, deck)
            val purged = sync.pending()
            assertTrue(purged.single { it.kind == "match" }.deleted)
            peer.applyRemote(purged)
            assertNull(UserProfileStore(b).matchRecord(b.decks(b.folders().single().id).firstOrNull()?.id ?: -1))
            assertTrue(peer.applyRemote(listOf(match.copy(stamp = purged.maxOf { it.stamp } + 20))))
            assertTrue(peer.pending().single { it.kind == "match" }.deleted)
        } }
    }

    @Test fun sharedOldReceiptWithNewProgressOrEventPurgeClearsAndConverges() {
        for (eventPurge in listOf(false, true)) {
            names.forEach(context::deleteDatabase)
            Store(context, names[0]).use { a -> Store(context, names[1]).use { b ->
                val card = a.getCard(a.addCard(a.addDeck(a.addFolder("f"), "d"), "w", "t"))!!
                a.review(card, 4)
                val asyn = SyncLocalStore(a); val bsyn = SyncLocalStore(b)
                val initial = asyn.pending()
                bsyn.applyRemote(initial)
                val oldReceipt = initial.single { it.kind == "undo" }
                val other = if (eventPurge) {
                    initial.single { it.kind == "events" }.copy(stamp = oldReceipt.stamp + 100, device = "peer", deleted = true, fields = emptyMap())
                } else {
                    val p = initial.single { it.kind == "progress" }
                    p.copy(stamp = oldReceipt.stamp + 100, device = "peer", fields = p.fields + ("reps" to 9))
                }
                assertTrue(bsyn.applyRemote(initial.map { if (it.kind == other.kind) other else it }))
                assertNull(b.lastReviewUndo())
                val clear = bsyn.pending().single { it.kind == "undo" }
                assertEquals(false, clear.fields["available"])
                assertTrue(clear.stamp > other.stamp)
                assertTrue(asyn.applyRemote(listOf(other, clear)))
                assertNull(a.lastReviewUndo())
                assertTrue(asyn.pending().none { it.kind == "undo" && it.fields["available"] == true })
            } }
        }
    }
}

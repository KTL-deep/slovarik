package com.slovarik.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Private DB/preferences and a fake scheduler: never alter the user's reminder or profile. */
class UserProfileBridgeDeviceTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "slovarik-profile-bridge-test.db"
    private val prefix = "slovarik-profile-bridge-tests-"
    private val context get() = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences(prefix + name, mode)
    }
    private lateinit var store: Store
    private val profile get() = UserProfileStore(store)

    @Before fun setup() {
        base.deleteDatabase(name)
        clearPreferences()
        store = Store(base, name)
    }
    @After fun cleanup() { store.close(); base.deleteDatabase(name); clearPreferences() }
    private fun clearPreferences() {
        listOf("settings", "match_records", "profile_sync_migration").forEach {
            base.getSharedPreferences(prefix + it, 0).edit().clear().commit()
        }
    }

    @Test fun legacyImportIncludesTrashRecordsButNotDefaultsOrHousekeeping() {
        val folder = store.addFolder("Folder")
        val active = store.addDeck(folder, "Active")
        val trash = store.addDeck(folder, "Trash")
        store.deleteDeck(trash)
        context.getSharedPreferences("settings", 0).edit()
            .putString("language", "de").putString("interface_language", "en")
            .putBoolean("randomDirection", true).putBoolean("seeded", true)
            .putBoolean("reminder", false).putInt("hour", 19).putInt("minute", 45).commit()
        context.getSharedPreferences("match_records", 0).edit()
            .putInt("deck_$active", 42).putInt("deck_$trash", 0).putInt("deck_999999", 5).commit()
        UserProfileBridge.migrateLegacy(context, store)
        assertEquals("de", profile.setting("language"))
        assertNull(profile.setting("target"))
        assertEquals(42, profile.matchRecord(active))
        assertEquals(0, profile.matchRecord(trash))
        assertFalse((profile.setting("reminder") as Map<*, *>)["enabled"] as Boolean)
        assertFalse(profile.settings().containsKey("seeded"))
        context.getSharedPreferences("settings", 0).edit().putString("language", "fr").commit()
        UserProfileBridge.migrateLegacy(context, store)
        assertEquals("de", profile.setting("language"))
    }

    @Test fun freshInstallDoesNotPublishDefaults() {
        context.getSharedPreferences("settings", 0).edit().putBoolean("seeded", true).commit()
        UserProfileBridge.migrateLegacy(context, store)
        assertTrue(profile.settings().isEmpty())
        assertTrue(SyncLocalStore(store).pending().isEmpty())
    }

    @Test fun projectionKeepsReminderIntentAndDoesNotEchoCloudState() {
        val folder = store.addFolder("Folder")
        val deck = store.addDeck(folder, "Deck")
        profile.setSetting("target", "en")
        profile.setSetting("interface_language", "en")
        profile.setSetting("randomDirection", true)
        profile.setSetting("reminder", mapOf("enabled" to true, "hour" to 7, "minute" to 30))
        profile.setMatchRecord(deck, 0)
        val before = SyncLocalStore(store).pending()
        var scheduled: Triple<Boolean, Int, Int>? = null
        UserProfileBridge.project(context, store) { _, enabled, hour, minute -> scheduled = Triple(enabled, hour, minute) }
        val prefs = context.getSharedPreferences("settings", 0)
        assertEquals("en", prefs.getString("interface_language", null))
        assertTrue(prefs.getBoolean("reminder", false))
        assertEquals(Triple(notificationsAllowed(context), 7, 30), scheduled)
        assertEquals(0, context.getSharedPreferences("match_records", 0).getInt("deck_$deck", -1))
        assertEquals(before, SyncLocalStore(store).pending())
        UserProfileBridge.project(context, store) { _, _, _, _ -> fail("Unchanged reminder must not be rescheduled") }
    }

    @Test fun purgeRemovesOnlyPurgedDecksLegacyRecord() {
        val folder = store.addFolder("Folder")
        val first = store.addDeck(folder, "First")
        val second = store.addDeck(folder, "Second")
        profile.setMatchRecord(first, 20)
        profile.setMatchRecord(second, 30)
        store.deleteDeck(first)
        UserProfileBridge.project(context, store) { _, _, _, _ -> }
        assertEquals(20, context.getSharedPreferences("match_records", 0).getInt("deck_$first", -1))
        store.purgeTrash(TrashKind.DECK, first)
        UserProfileBridge.project(context, store) { _, _, _, _ -> }
        assertFalse(context.getSharedPreferences("match_records", 0).contains("deck_$first"))
        assertEquals(30, context.getSharedPreferences("match_records", 0).getInt("deck_$second", -1))
        assertTrue(SyncLocalStore(store).pending().any { it.kind == "match" && it.deleted })
    }
}

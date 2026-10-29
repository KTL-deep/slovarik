package com.slovarik.app

import android.content.Context

/** Imports legacy user choices once, then mirrors SQLite state for existing UI and workers. */
internal object UserProfileBridge {
    private const val SETTINGS = "settings"
    private const val MATCH = "match_records"
    private const val MIGRATION = "profile_sync_migration"

    private fun marker(store: Store) = store.databaseName ?: "slovarik.db"

    private fun deckIds(store: Store): Set<Long> = store.writableDatabase.rawQuery("SELECT id FROM decks", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
    }

    fun migrateLegacy(context: Context, store: Store) {
        val state = context.getSharedPreferences(MIGRATION, Context.MODE_PRIVATE)
        val name = marker(store)
        if (state.getBoolean(name, false)) return
        val prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        val profile = UserProfileStore(store)
        for (key in listOf("language", "target", "interface_language", "randomDirection")) {
            val value = prefs.all[key] ?: continue
            if (profile.setting(key) == null) {
                try {
                    UserProfileStore.validateSetting(key, value)
                    profile.setSetting(key, value)
                } catch (_: IllegalArgumentException) { /* Ignore malformed legacy values. */ }
            }
        }
        if (profile.setting("reminder") == null && listOf("reminder", "hour", "minute").any { prefs.contains(it) }) {
            val reminder = mapOf("enabled" to (prefs.all["reminder"] as? Boolean ?: false),
                "hour" to (prefs.all["hour"] as? Int ?: 19), "minute" to (prefs.all["minute"] as? Int ?: 0))
            try {
                UserProfileStore.validateSetting("reminder", reminder)
                profile.setSetting("reminder", reminder)
            } catch (_: IllegalArgumentException) { /* Ignore malformed legacy times. */ }
        }
        val records = context.getSharedPreferences(MATCH, Context.MODE_PRIVATE)
        val ids = deckIds(store)
        for ((key, value) in records.all) {
            val id = key.takeIf { it.startsWith("deck_") }?.removePrefix("deck_")?.toLongOrNull() ?: continue
            val seconds = value as? Int ?: continue
            if (id in ids && seconds >= 0 && profile.matchRecord(id) == null) {
                profile.setMatchRecord(id, seconds)
            }
        }
        state.edit().putBoolean(name, true).apply()
    }

    fun project(context: Context, store: Store, scheduleReminder: (Context, Boolean, Int, Int) -> Unit = ::applyReminderSchedule) {
        val profile = UserProfileStore(store)
        val prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var changed = false
        var scheduleTime: Pair<Int, Int>? = null
        var scheduleEnabled = false
        for ((key, value) in profile.settings()) {
            when (key) {
                "language", "target", "interface_language" -> if (prefs.all[key] != value) { editor.putString(key, value as String); changed = true }
                "randomDirection" -> if (prefs.all[key] != value) { editor.putBoolean(key, value as Boolean); changed = true }
                "reminder" -> {
                    val reminder = value as Map<*, *>
                    val enabled = reminder["enabled"] as Boolean
                    val hour = (reminder["hour"] as Number).toInt()
                    val minute = (reminder["minute"] as Number).toInt()
                    if (prefs.all["reminder"] != enabled) { editor.putBoolean("reminder", enabled); changed = true }
                    if (prefs.all["hour"] != hour) { editor.putInt("hour", hour); changed = true }
                    if (prefs.all["minute"] != minute) { editor.putInt("minute", minute); changed = true }
                    scheduleTime = hour to minute
                    scheduleEnabled = enabled
                }
            }
        }
        if (changed) editor.apply()
        scheduleTime?.let { (hour, minute) ->
            val schedule = context.getSharedPreferences(MIGRATION, Context.MODE_PRIVATE)
            val scheduleKey = "schedule:${marker(store)}"
            val desired = if (scheduleEnabled && notificationsAllowed(context)) "$hour:$minute" else "off"
            if (schedule.getString(scheduleKey, null) != desired) {
                scheduleReminder(context, desired != "off", hour, minute)
                schedule.edit().putString(scheduleKey, desired).apply()
            }
        }
        val records = context.getSharedPreferences(MATCH, Context.MODE_PRIVATE)
        val recordEditor = records.edit()
        var recordsChanged = false
        val ids = deckIds(store)
        for (id in ids) {
            val value = profile.matchRecord(id)
            val key = "deck_$id"
            if (value != null && records.all[key] != value) { recordEditor.putInt(key, value); recordsChanged = true }
            if (value == null && records.contains(key)) { recordEditor.remove(key); recordsChanged = true }
        }
        for (key in records.all.keys) {
            if (key.startsWith("deck_") && key.removePrefix("deck_").toLongOrNull() !in ids) {
                recordEditor.remove(key); recordsChanged = true
            }
        }
        if (recordsChanged) recordEditor.apply()
    }

    fun setSetting(context: Context, store: Store, key: String, value: Any) {
        val profile = UserProfileStore(store)
        if (canonicalCloudValue(profile.setting(key)) != canonicalCloudValue(value)) profile.setSetting(key, value)
        project(context, store)
    }

    fun saveReminder(context: Context, store: Store, enabled: Boolean, hour: Int, minute: Int) {
        setSetting(context, store, "reminder", mapOf("enabled" to enabled, "hour" to hour, "minute" to minute))
    }

    fun saveMatch(context: Context, store: Store, deckId: Long, seconds: Int): Boolean {
        val improved = UserProfileStore(store).setMatchRecord(deckId, seconds)
        project(context, store)
        return improved
    }
}

package com.slovarik.app

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/** UI locale is deliberately independent from the vocabulary and translation languages. */
internal object InterfaceLanguage {
    fun current(context: Context): String =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("interface_language", "ru").takeIf { it == "en" } ?: "ru"

    fun set(context: Context, code: String, store: Store? = null) {
        require(code == "ru" || code == "en")
        if (store != null) { UserProfileBridge.setSetting(context, store, "interface_language", code); return }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("interface_language", code).apply()
    }

    fun wrap(context: Context): Context {
        val locale = Locale.forLanguageTag(current(context))
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }
}

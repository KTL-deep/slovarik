package com.slovarik.app

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** UI tests choose their locale explicitly, independently of emulator/user preferences. */
@Composable
internal fun LocalizedTestTheme(language: String = "ru", content: @Composable () -> Unit) {
    val base = LocalContext.current
    val configuration = LocalConfiguration.current
    val localized = remember(base, language, configuration) {
        base.createConfigurationContext(Configuration(configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(language)))
        })
    }
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
        MaterialTheme(colorScheme = darkColorScheme(), content = content)
    }
}

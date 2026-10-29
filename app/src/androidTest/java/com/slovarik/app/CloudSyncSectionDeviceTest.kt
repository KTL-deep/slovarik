package com.slovarik.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CloudSyncSectionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disabledCloudStateLeavesLocalDataUntouched() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "slovarik-cloud-section-test.db"
        context.deleteDatabase(name)
        try {
            Store(context, name).use { store ->
                store.addFolder("Offline words")
                val original = store.backup()
                CloudSyncController(context, store, enableCloud = false).use { controller ->
                    compose.setContent { LocalizedTestTheme { Column { CloudSyncSection(controller) } } }
                    compose.onNodeWithText("Аккаунт и синхронизация").assertIsDisplayed()
                    compose.onNodeWithText("Войти через Google").assertIsNotEnabled()
                    compose.onNodeWithText("Для этой сборки ещё не настроен Firebase.", substring = true).assertExists()
                    runBlocking { controller.syncNow(); controller.signOut() }
                    assertEquals(CloudSyncPhase.UNCONFIGURED, controller.state.value.phase)
                    assertNull(SyncLocalStore(store).boundAccount())
                    assertEquals(original, store.backup())
                }
            }
        } finally { context.deleteDatabase(name) }
    }
}

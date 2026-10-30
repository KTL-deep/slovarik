package com.slovarik.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WelcomeDesignDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun guestCanContinueWithoutDownloadingOrSigningIn() {
        var continued = 0
        compose.setContent { LocalizedTestTheme("ru") { WelcomeScreen(null) { continued++ } } }
        compose.onNodeWithTag("welcome-app-logo").assertExists()
        compose.onNodeWithTag("welcome-illustration").assertExists()
        compose.onNodeWithTag("welcome-offline-choice").assertExists()
        compose.onNodeWithText("~235 МБ", substring = true).assertExists()
        compose.onNodeWithText("MyMemory", substring = true).assertExists()
        compose.onNodeWithTag("welcome-continue").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, continued) }
    }

    @Test fun cloudChoiceIsOptionalAndDoesNotInitiateSignIn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "welcome-design-test.db"
        context.deleteDatabase(name)
        try {
            Store(context, name).use { store ->
                CloudSyncController(context, store, enableCloud = false).use { controller ->
                    compose.setContent { LocalizedTestTheme("en") { WelcomeScreen(controller) {} } }
                    compose.waitForIdle()
                    compose.onNodeWithTag("welcome-sync-choice").performScrollTo().assertIsDisplayed()
                    compose.onNodeWithText("Sign in with Google").assertIsNotEnabled()
                    compose.onNodeWithTag("welcome-continue").performScrollTo().assertIsDisplayed()
                    compose.runOnIdle { assertEquals(CloudSyncPhase.UNCONFIGURED, controller.state.value.phase) }
                }
            }
        } finally { context.deleteDatabase(name) }
    }
}

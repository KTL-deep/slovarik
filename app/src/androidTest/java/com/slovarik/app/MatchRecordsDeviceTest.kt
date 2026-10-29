package com.slovarik.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MatchRecordsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val prefs get() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("match_records", 0)
    private val deckId = -987655L
    private val recordKey = "deck_$deckId"

    @Before fun setUp() { prefs.edit().remove(recordKey).commit() }
    @After fun tearDown() { prefs.edit().remove(recordKey).commit() }

    private fun launch(count: Int) {
        val cards = (1..count).map { Card(it.toLong(), deckId, "word$it", "перевод$it", Review()) }
        compose.setContent {
            LocalizedTestTheme {
                Column(Modifier.fillMaxSize()) { MatchGame(cards, deckId) }
            }
        }
    }

    private fun completeVisibleRound(count: Int) {
        for (id in 1..count) {
            val word = compose.onNodeWithTag("match-tile-$id:w")
            if (compose.onAllNodesWithTag("match-tile-$id:w").fetchSemanticsNodes().isNotEmpty()) {
                word.performClick()
                compose.onNodeWithTag("match-tile-$id:t").performClick()
            }
        }
        compose.waitForIdle()
    }

    @Test fun recordIsSavedOnlyAfterAllRounds() {
        launch(7)
        completeVisibleRound(7)
        compose.onNodeWithText("Следующий раунд").assertExists()
        compose.runOnIdle { assertFalse(prefs.contains(recordKey)) }
        compose.onNodeWithText("Следующий раунд").performClick()
        completeVisibleRound(7)
        compose.onNodeWithText("Сыграть ещё раз").assertExists()
        compose.runOnIdle { assertTrue(prefs.getInt(recordKey, -1) >= 0) }
    }

    @Test fun zeroRecordIsDisplayedAndNeverReplacedBySlowerRun() {
        prefs.edit().putInt(recordKey, 0).commit()
        launch(2)
        compose.onNodeWithText("Рекорд: 0:00").assertExists()
        compose.onNodeWithTag("match-tile-1:w").performClick()
        compose.onNodeWithTag("match-tile-2:t").performClick()
        // Clear the error highlight before completing the pairs.
        compose.onNodeWithTag("match-mismatch-1:w").performClick()
        compose.onNodeWithTag("match-tile-1:t").performClick()
        completeVisibleRound(2)
        compose.runOnIdle { assertEquals(0, prefs.getInt(recordKey, -1)) }
        compose.onNodeWithText("Новый рекорд!").assertDoesNotExist()
    }
}

package com.slovarik.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Render the training UI against an isolated DB. Never grade the user's real cards. */
class StudyGesturesDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = "slovarik-gesture-tests.db"
    private val recordDeckId = -987654L
    private val recordKey = "deck_$recordDeckId"
    private lateinit var store: Store
    private lateinit var deck: Deck

    @Before fun setUp() {
        context.deleteDatabase(database)
        store = Store(context, database)
        val folder = store.addFolder("Gesture fixture")
        val id = store.addDeck(folder, "Fixture")
        deck = store.decks(folder).single { it.id == id }
        store.addCard(id, "book", "книга")
    }
    @After fun tearDown() {
        store.close()
        context.deleteDatabase(database)
        context.getSharedPreferences("match_records", 0).edit().remove(recordKey).commit()
    }

    private fun launchStudy(practice: Boolean = false) {
        compose.setContent {
            LocalizedTestTheme {
                Study(store, deck, "en", { _, _ -> }, {}, Modifier.fillMaxSize(), practice)
            }
        }
    }
    private fun assertNoModeControls() {
        listOf("Cards", "Quiz", "Type", "Pairs").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }
    private fun drag(grade: Int) {
        compose.onNodeWithTag("review-card").performTouchInput {
            when (grade) {
                5 -> swipeLeft()
                1 -> swipeRight()
                4 -> swipeUp()
                else -> swipeDown()
            }
        }
        compose.waitForIdle()
    }
    private fun flipCard() {
        compose.onNodeWithTag("review-card").performTouchInput { click(center) }
        compose.waitForIdle()
    }

    @Test fun englishQuizStartsDirectlyAndDoesNotGradeOnEntry() {
        compose.setContent {
            LocalizedTestTheme("en") {
                Study(store, deck, "en", { _, _ -> }, {}, initialMode = "Тест")
            }
        }
        assertNoModeControls()
        compose.onNodeWithText("книга").assertIsDisplayed()
        compose.onNodeWithText("book").assertIsDisplayed()
        compose.onNodeWithText("Show translation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, store.stats().reviews) }
    }

    @Test fun englishTypingStartsDirectly() {
        compose.setContent {
            LocalizedTestTheme("en") {
                Study(store, deck, "en", { _, _ -> }, {}, initialMode = "Ввод")
            }
        }
        assertNoModeControls()
        compose.onNodeWithText("Your translation").assertExists()
        compose.onNodeWithText("Check").assertExists()
        compose.runOnIdle { assertEquals(0, store.stats().reviews) }
    }

    @Test fun pronunciationOnlyAppearsOnVisibleEnglishFace() {
        compose.setContent {
            LocalizedTestTheme("en") {
                Study(store, deck, "en", { _, _ -> }, {})
            }
        }
        compose.onNodeWithText("book").assertIsDisplayed()
        compose.onNodeWithText("Pronounce").assertIsDisplayed()
        flipCard()
        compose.onNodeWithText("книга").assertIsDisplayed()
        compose.onNodeWithText("Pronounce").assertDoesNotExist()
        flipCard()
        compose.onNodeWithText("book").assertIsDisplayed()
        compose.onNodeWithText("Pronounce").assertIsDisplayed()
    }

    @Test fun randomDirectionPronunciationFollowsWordFace() {
        compose.setContent {
            LocalizedTestTheme("en") {
                Study(store, deck, "en", { _, _ -> }, {}, randomDirection = true)
            }
        }
        val wordOnFront = compose.onAllNodesWithText("book").fetchSemanticsNodes().isNotEmpty()
        if (wordOnFront) compose.onNodeWithText("Pronounce").assertIsDisplayed()
        else compose.onNodeWithText("Pronounce").assertDoesNotExist()
        flipCard()
        if (wordOnFront) {
            compose.onNodeWithText("книга").assertIsDisplayed()
            compose.onNodeWithText("Pronounce").assertDoesNotExist()
        } else {
            compose.onNodeWithText("book").assertIsDisplayed()
            compose.onNodeWithText("Pronounce").assertIsDisplayed()
        }
    }

    @Test fun pronunciationSpeaksSourceWithoutFlippingOrGrading() {
        val spoken = mutableListOf<Pair<String, String>>()
        compose.setContent {
            LocalizedTestTheme("en") {
                Study(store, deck, "en", { word, language -> spoken += word to language }, {})
            }
        }
        compose.onNodeWithText("Pronounce").performClick()
        compose.runOnIdle {
            assertEquals(listOf("book" to "en"), spoken)
            assertEquals(0, store.stats().reviews)
        }
        compose.onNodeWithText("book").assertIsDisplayed()
        compose.onNodeWithText("книга").assertDoesNotExist()
    }

    @Test fun typingFieldIsDarkWithLegibleInput() {
        compose.setContent {
            LocalizedTestTheme("en") {
                Study(store, deck, "en", { _, _ -> }, {}, initialMode = "Ввод")
            }
        }
        val field = compose.onNodeWithTag("study-typing-field")
        field.performTextInput("book")
        val pixels = field.captureToImage().toPixelMap()
        assertEquals(Color(0xFF242424), pixels[pixels.width - 16, pixels.height / 2])
        assertTrue((16 until pixels.width / 2).any { x ->
            (pixels.height / 3 until pixels.height * 2 / 3).any { y ->
                val color = pixels[x, y]
                color.red > .8f && color.green > .8f && color.blue > .8f
            }
        })
    }

    @Test fun englishPairsStartsDirectlyWithoutScheduling() {
        compose.setContent {
            LocalizedTestTheme("en") {
                Study(store, deck, "en", { _, _ -> }, {}, practice = true, initialMode = "Пары")
            }
        }
        assertNoModeControls()
        compose.onNodeWithText("Pairs needs at least two distinct words and translations in the deck.").assertExists()
        compose.runOnIdle { assertEquals(0, store.stats().reviews) }
    }

    @Test fun pairsCannotBypassSrsThroughInitialMode() {
        compose.setContent {
            LocalizedTestTheme("en") {
                Study(store, deck, "en", { _, _ -> }, {}, initialMode = "Пары")
            }
        }
        assertNoModeControls()
        compose.onNodeWithText("book").assertIsDisplayed()
        compose.onNodeWithText("Show translation").assertDoesNotExist()
    }
    private fun verify(grade: Int) {
        launchStudy()
        drag(grade)
        compose.runOnIdle {
            val expected = schedule(Review(), grade, 0)
            val actual = store.cards(deck.id).single().review
            assertEquals(expected.repetitions, actual.repetitions)
            assertEquals(expected.ease, actual.ease, 0.000001)
            assertEquals(1, store.stats().reviews)
        }
    }

    @Test fun leftMeansEasy() = verify(5)
    @Test fun downMeansHard() = verify(3)
    @Test fun rightMeansAgain() = verify(1)
    @Test fun upMeansGood() = verify(4)

    @Test fun failedSingleCardReturnsAsFreshRevealedOccurrence() {
        launchStudy()
        flipCard()
        drag(1)
        compose.onNodeWithText("Показать перевод").assertDoesNotExist()
        flipCard()
        compose.onNodeWithText("книга").assertExists()
        compose.onNodeWithText("Хорошо").performClick()
        compose.runOnIdle {
            assertEquals(2, store.stats().reviews)
            assertEquals(2, store.cards(deck.id).single().review.learningStep)
        }
    }

    @Test fun undoLastCompletedReviewRestoresCardAndStats() {
        launchStudy()
        flipCard()
        compose.onNodeWithText("Хорошо").performClick()
        compose.onNodeWithText("Отменить оценку").performClick()
        compose.runOnIdle {
            assertEquals(Review(), store.cards(deck.id).single().review)
            assertEquals(0, store.stats().reviews)
        }
        compose.onNodeWithText("Показать перевод").assertDoesNotExist()
        flipCard()
        compose.onNodeWithText("книга").assertExists()
    }

    @Test fun frontOfFlashcardCanBeGradedWithoutFlipping() {
        launchStudy()
        drag(5)
        compose.runOnIdle { assertEquals(1, store.stats().reviews) }
    }

    @Test fun globallyRestoredCardIsShownBeforeOtherDueCards() {
        val original = store.cards(deck.id).single()
        store.review(original, 4)
        store.addCard(deck.id, "house", "дом")
        compose.setContent {
            LocalizedTestTheme {
                Study(store, deck, "en", { _, _ -> }, {}, Modifier.fillMaxSize(), initialCardId = original.id)
            }
        }
        compose.onNodeWithText("book").assertIsDisplayed()
        compose.onNodeWithText("house").assertDoesNotExist()
    }
    @Test fun wordAndTranslationAreCenteredInTheCard() {
        launchStudy()
        fun assertCentered(text: String) {
            val card = compose.onNodeWithTag("review-card").fetchSemanticsNode().boundsInRoot
            val word = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
            assertEquals(card.center.x, word.center.x, 2f)
            assertEquals(card.center.y, word.center.y, 2f)
        }
        assertCentered("book")
        flipCard()
        assertCentered("книга")
    }
    @Test fun tappingTheCardFlipsItBackAndForth() {
        launchStudy()
        compose.onNodeWithText("книга").assertDoesNotExist()
        compose.onNodeWithText("Показать перевод").assertDoesNotExist()
        flipCard()
        compose.onNodeWithText("книга").assertIsDisplayed()
        compose.onNodeWithText("Показать перевод").assertDoesNotExist()
        flipCard()
        compose.onNodeWithText("book").assertIsDisplayed()
        compose.onNodeWithText("Показать перевод").assertDoesNotExist()
        flipCard()
        compose.onNodeWithText("книга").assertIsDisplayed()
        compose.onNodeWithText("Показать перевод").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, store.stats().reviews) }
        drag(4)
        compose.runOnIdle { assertEquals(1, store.stats().reviews) }
    }
    @Test fun freePracticeSwipeDoesNotWriteHistory() {
        launchStudy(practice = true)
        drag(5)
        compose.runOnIdle {
            assertEquals(0, store.stats().reviews)
            assertEquals(Review(), store.cards(deck.id).single().review)
        }
    }

    @Test fun shortDragDoesNotGrade() {
        launchStudy()
        val distance = 40f * context.resources.displayMetrics.density
        compose.onNodeWithTag("review-card").performTouchInput {
            swipe(center, center + Offset(distance, 0f))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, store.stats().reviews) }
        compose.onNodeWithText("book").assertIsDisplayed()
    }

    @Test fun gradingIsLockedWhileCardDismisses() {
        launchStudy()
        flipCard()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("review-card").performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Хорошо").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, store.stats().reviews) }
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, store.stats().reviews) }
    }

    @Test fun leavingStudyDuringDismissalDoesNotWriteAndReturnsCard() {
        var active by mutableStateOf(true)
        compose.setContent {
            LocalizedTestTheme {
                Study(store, deck, "en", { _, _ -> }, {}, Modifier.fillMaxSize(), active = active)
            }
        }
        val original = compose.onNodeWithTag("review-card").fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("review-card").performTouchInput { swipeLeft() }
        compose.runOnIdle { active = false }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, store.stats().reviews) }
        val restored = compose.onNodeWithTag("review-card").fetchSemanticsNode().boundsInRoot.center
        assertEquals(original.x, restored.x, 2f)
        assertEquals(original.y, restored.y, 2f)
    }

    private fun verifyUnansweredCannotSwipe(mode: String) {
        compose.setContent {
            LocalizedTestTheme("en") {
                Study(store, deck, "en", { _, _ -> }, {}, initialMode = mode)
            }
        }
        drag(5)
        compose.runOnIdle { assertEquals(0, store.stats().reviews) }
    }

    @Test fun quizCannotSwipeBeforeAnswer() = verifyUnansweredCannotSwipe("Тест")
    @Test fun typingCannotSwipeBeforeAnswer() = verifyUnansweredCannotSwipe("Ввод")

    @Test fun wrongQuizAnswerShowsRedResult() {
        store.addCard(deck.id, "house", "дом")
        compose.setContent {
            LocalizedTestTheme("en") {
                Study(store, deck, "en", { _, _ -> }, {}, initialMode = "Тест")
            }
        }
        val prompt = if (compose.onAllNodesWithText("book").fetchSemanticsNodes().isNotEmpty()) "book" else "house"
        compose.onNodeWithText(if (prompt == "book") "дом" else "книга").performClick()
        compose.waitForIdle()
        val pixels = compose.onNodeWithTag("review-card").captureToImage().toPixelMap()
        assertEquals(swipeRatingColor(1), pixels[pixels.width / 8, pixels.height / 3])
    }

    @Test fun correctTypingShowsResultWithoutTextField() {
        compose.setContent {
            LocalizedTestTheme("en") {
                Study(store, deck, "en", { _, _ -> }, {}, initialMode = "Ввод")
            }
        }
        compose.onNodeWithText("Your translation").performTextInput("книга")
        compose.onNodeWithText("Check").performClick()
        compose.onNodeWithText("Your translation").assertDoesNotExist()
        compose.onNodeWithText("Check").assertDoesNotExist()
        compose.onNodeWithText("Correct!").assertExists()
        compose.runOnIdle { assertEquals(0, store.stats().reviews) }
    }

    @Test fun matchDragRemovesPairAndCompletionNeverWritesReviews() {
        for (i in 2..6) store.addCard(deck.id, "word$i", "перевод$i")
        val cards = store.cards(deck.id)
        compose.setContent {
            LocalizedTestTheme {
                Column(Modifier.fillMaxSize()) { MatchGame(cards, recordDeckId) }
            }
        }
        val first = cards.first()
        val source = compose.onNodeWithTag("match-tile-${first.id}:w")
        val target = compose.onNodeWithTag("match-tile-${first.id}:t")
        val from = source.fetchSemanticsNode().boundsInRoot
        val to = target.fetchSemanticsNode().boundsInRoot
        source.performTouchInput {
            val destination = Offset(to.center.x - from.left, to.center.y - from.top)
            swipe(center, destination, durationMillis = 600)
        }
        compose.waitForIdle()
        source.assertDoesNotExist()
        target.assertDoesNotExist()
        cards.drop(1).forEach {
            compose.onNodeWithTag("match-tile-${it.id}:w").performClick()
            compose.onNodeWithTag("match-tile-${it.id}:t").performClick()
        }
        compose.onNodeWithText("Готово! Время:", substring = true).assertExists()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, store.stats().reviews)
            assertTrue(context.getSharedPreferences("match_records", 0).getInt(recordKey, -1) >= 0)
        }
    }

    @Test fun matchMismatchMarksBothTilesRed() {
        store.addCard(deck.id, "house", "дом")
        val cards = store.cards(deck.id)
        compose.setContent {
            LocalizedTestTheme {
                Column(Modifier.fillMaxSize()) { MatchGame(cards, recordDeckId) }
            }
        }
        val first = cards.first()
        val second = cards.last()
        compose.onNodeWithTag("match-tile-${first.id}:w").performClick()
        compose.onNodeWithTag("match-tile-${second.id}:t").performClick()
        compose.onNodeWithTag("match-mismatch-${first.id}:w").assertExists()
        compose.onNodeWithTag("match-mismatch-${second.id}:t").assertExists()
        compose.onNodeWithText("Не совпало · +10 секунд").assertExists()
        val mismatched = compose.onNodeWithTag("match-mismatch-${first.id}:w").captureToImage().toPixelMap()
        val normal = compose.onNodeWithTag("match-tile-${first.id}:t").captureToImage().toPixelMap()
        // Sample above the centered label, inside the button's flat top edge.
        assertNotEquals(normal[normal.width / 2, normal.height / 6],
            mismatched[mismatched.width / 2, mismatched.height / 6])
    }

    @Test fun matchRecordIsLoadedForTheDeck() {
        store.addCard(deck.id, "house", "дом")
        context.getSharedPreferences("match_records", 0).edit().putInt(recordKey, 42).commit()
        val cards = store.cards(deck.id)
        compose.setContent {
            LocalizedTestTheme {
                Column(Modifier.fillMaxSize()) { MatchGame(cards, recordDeckId) }
            }
        }
        compose.onNodeWithText("Рекорд: 0:42").assertExists()
        compose.onNodeWithText("Готово! Время:", substring = true).assertDoesNotExist()
    }
}

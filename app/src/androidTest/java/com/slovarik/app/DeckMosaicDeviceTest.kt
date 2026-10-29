package com.slovarik.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DeckMosaicDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun widthsFollowTextAndTilesWrapRatherThanFillEqualColumns() {
        val cards = listOf(
            Card(1, 1, "a", "я", Review()),
            Card(2, 1, "adventure", "приключение", Review()),
            Card(3, 1, "an extraordinary adventure", "необыкновенное приключение", Review()),
        )
        var selected: Long? = null
        compose.setContent {
            LocalizedTestTheme {
                Box(Modifier.width(300.dp).testTag("mosaic-container")) {
                    DeckMosaic(cards) { selected = it.id }
                }
            }
        }
        val short = compose.onNodeWithTag("word-tile-1").fetchSemanticsNode().boundsInRoot
        val medium = compose.onNodeWithTag("word-tile-2").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("word-tile-3").fetchSemanticsNode().boundsInRoot
        val parent = compose.onNodeWithTag("mosaic-container").fetchSemanticsNode().boundsInRoot
        assertTrue("Content must determine each tile's width", short.width < medium.width)
        assertTrue("Tiles must wrap to another row", last.top > short.top)
        assertTrue(last.right <= parent.right + 1f)
        (1..3).forEach { id ->
            val pixels = compose.onNodeWithTag("word-tile-$id").captureToImage().toPixelMap()
            assertEquals("All word tiles must use one color", Lemon, pixels[pixels.width / 2, 2])
        }
        compose.onNodeWithTag("word-tile-2").performClick()
        compose.runOnIdle { assertEquals(2L, selected) }
    }

    @Test fun longWordsStayWithinNarrowScreenAtLargeFontScale() {
        compose.setContent {
            LocalizedTestTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                    Box(Modifier.width(240.dp).testTag("mosaic-container")) {
                        DeckMosaic(listOf(Card(1, 1, "pneumonoultramicroscopicsilicovolcanoconiosis", "очень длинный перевод слова для проверки переноса", Review()))) {}
                    }
                }
            }
        }
        val parent = compose.onNodeWithTag("mosaic-container").fetchSemanticsNode().boundsInRoot
        val tile = compose.onNodeWithTag("word-tile-1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(tile.width <= parent.width + 1f)
        compose.onNodeWithText("очень длинный перевод слова для проверки переноса").assertIsDisplayed()
    }

    @Test fun allModesFitOneRowAtNarrowWidthAndLargeFontScale() {
        var selected: String? = null
        compose.setContent {
            LocalizedTestTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                    Box(Modifier.width(280.dp).testTag("mode-container")) {
                        DeckModeRow(true) { selected = it }
                    }
                }
            }
        }
        val parent = compose.onNodeWithTag("mode-container").fetchSemanticsNode().boundsInRoot
        val bounds = (0..3).map { compose.onNodeWithTag("mode-$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds.all { kotlin.math.abs(it.top - bounds.first().top) < 1f && it.right <= parent.right + 1f })
        listOf("Карточки", "Тест", "Ввод", "Пары").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("The entire mode label must fit: " + layouts.map { "size=${it.size} constraints=${it.layoutInput.constraints} overflow=${it.hasVisualOverflow} w=${it.didOverflowWidth} h=${it.didOverflowHeight} lines=${it.lineCount} font=${it.layoutInput.style.fontSize} mp=${it.multiParagraph.width}/${it.multiParagraph.maxIntrinsicWidth}" }, layouts.isNotEmpty() && layouts.all { !it.hasVisualOverflow && it.lineCount == 1 })
        }
        compose.onNodeWithTag("mode-2").performClick()
        compose.runOnIdle { assertEquals("Ввод", selected) }
    }
}

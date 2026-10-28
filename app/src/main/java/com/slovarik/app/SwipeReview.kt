package com.slovarik.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

private data class RatingDirection(val grade: Int, val label: String, val color: Color)

private fun direction(offset: Offset): RatingDirection = when (swipeGrade(offset.x, offset.y)) {
    5 -> RatingDirection(5, "Легко", Mint)
    1 -> RatingDirection(1, "Снова", Coral)
    4 -> RatingDirection(4, "Хорошо", Mint)
    else -> RatingDirection(3, "Трудно", Coral)
}

/** Gestures grade only on release beyond the threshold; cancellation always springs back. */
@Composable
internal fun SwipeReview(
    enabled: Boolean,
    practice: Boolean,
    baseColor: Color,
    onCommit: (Int) -> Unit,
    content: @Composable (Modifier, Color) -> Unit,
) {
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }
    val scope = rememberCoroutineScope()
    var offset by remember { mutableStateOf(Offset.Zero) }
    var springBack by remember { mutableStateOf<Job?>(null) }
    fun reset() {
        springBack?.cancel()
        val start = offset
        springBack = scope.launch {
            Animatable(start, Offset.VectorConverter).animateTo(Offset.Zero, spring()) { offset = value }
        }
    }
    val rating = direction(offset)
    val intensity = (max(abs(offset.x), abs(offset.y)) / threshold).coerceIn(0f, 1f)
    val label = if (practice) "Дальше · без оценки" else rating.label
    val tint = if (enabled && intensity > 0f) lerp(baseColor, if (practice) Mint else rating.color, intensity * .72f) else baseColor
    val gesture = if (enabled) Modifier
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(if (practice) "Следующее слово" else "Влево: Легко") { onCommit(5); true },
                CustomAccessibilityAction(if (practice) "Вниз: следующее слово" else "Вниз: Трудно") { onCommit(3); true },
                CustomAccessibilityAction(if (practice) "Вправо: следующее слово" else "Вправо: Снова") { onCommit(1); true },
                CustomAccessibilityAction(if (practice) "Вверх: следующее слово" else "Вверх: Хорошо") { onCommit(4); true },
            )
        }
        .pointerInput(enabled, practice) {
            detectDragGestures(
                onDragStart = { springBack?.cancel(); offset = Offset.Zero },
                onDragCancel = { reset() },
                onDragEnd = {
                    val moved = offset
                    if (max(abs(moved.x), abs(moved.y)) >= threshold) {
                        offset = Offset.Zero
                        onCommit(direction(moved).grade)
                    } else reset()
                },
            ) { change, amount ->
                change.consume()
                offset += amount
            }
        } else Modifier
    Box(Modifier.fillMaxWidth().testTag("review-card").then(gesture).graphicsLayer {
            translationX = offset.x
            translationY = offset.y
            rotationZ = (offset.x / threshold).coerceIn(-1f, 1f) * 5f
        }) {
        content(Modifier, tint)
        if (enabled && intensity > .15f) {
            Text(label, modifier = Modifier.align(Alignment.TopCenter), color = Night, fontWeight = FontWeight.Bold)
        }
    }
}

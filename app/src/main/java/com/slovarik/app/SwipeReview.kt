package com.slovarik.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
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
import kotlin.math.sign

private data class RatingDirection(val grade: Int, val color: Color)

internal fun swipeRatingColor(grade: Int): Color = when (grade) {
    5 -> Color(0xFF98DDB5) // Left: easy / green.
    3 -> Color(0xFFFFC98D) // Down: hard / orange.
    1 -> Color(0xFFFF858C) // Right: again / red.
    4 -> Color(0xFFA5D3FF) // Up: good / blue.
    else -> error("Unsupported swipe rating")
}

private fun direction(offset: Offset): RatingDirection = when (swipeGrade(offset.x, offset.y)) {
    5 -> RatingDirection(5, swipeRatingColor(5))
    1 -> RatingDirection(1, swipeRatingColor(1))
    4 -> RatingDirection(4, swipeRatingColor(4))
    else -> RatingDirection(3, swipeRatingColor(3))
}

/** Gestures grade only on release beyond the threshold; cancellation always springs back. */
@Composable
internal fun SwipeReview(
    enabled: Boolean,
    practice: Boolean,
    baseColor: Color,
    onBusyChange: (Boolean) -> Unit,
    onCommit: (Int) -> Boolean,
    content: @Composable (Modifier, Color) -> Unit,
) {
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }
    val rootView = LocalView.current.rootView
    val scope = rememberCoroutineScope()
    val currentEnabled = rememberUpdatedState(enabled)
    val currentCommit = rememberUpdatedState(onCommit)
    val currentBusyChange = rememberUpdatedState(onBusyChange)
    var offset by remember { mutableStateOf(Offset.Zero) }
    var motion by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf(0) }
    var height by remember { mutableStateOf(0) }
    fun reset() {
        motion?.cancel()
        val start = offset
        motion = scope.launch {
            Animatable(start, Offset.VectorConverter).animateTo(Offset.Zero, spring()) { offset = value }
        }
    }
    fun dismiss(grade: Int, moved: Offset) {
        if (busy || !currentEnabled.value) return
        busy = true
        currentBusyChange.value(true)
        motion?.cancel()
        motion = scope.launch {
            val horizontal = abs(moved.x) > abs(moved.y)
            // Move beyond the viewport, not just the card: a short card near the top
            // can otherwise still be visible when the next item replaces it.
            val destination = if (horizontal) Offset(moved.x.sign * (rootView.width + width + abs(moved.x)), moved.y)
                else Offset(moved.x, moved.y.sign * (rootView.height + height + abs(moved.y)))
            Animatable(moved, Offset.VectorConverter).animateTo(destination, tween(180)) { offset = value }
            if (!currentEnabled.value || !currentCommit.value(grade)) {
                Animatable(offset, Offset.VectorConverter).animateTo(Offset.Zero, spring()) { offset = value }
                busy = false
                currentBusyChange.value(false)
            }
        }
    }
    val rating = direction(offset)
    val intensity = (max(abs(offset.x), abs(offset.y)) / threshold).coerceIn(0f, 1f)
    val ratingLabel = stringResource(when (rating.grade) {
        5 -> R.string.swipe_easy
        1 -> R.string.swipe_again
        4 -> R.string.swipe_good
        else -> R.string.swipe_hard
    })
    val label = if (practice) stringResource(R.string.swipe_practice_rating, ratingLabel) else ratingLabel
    val leftAction = stringResource(if (practice) R.string.study_next_word else R.string.swipe_left_easy)
    val downAction = stringResource(if (practice) R.string.swipe_down_next else R.string.swipe_down_hard)
    val rightAction = stringResource(if (practice) R.string.swipe_right_next else R.string.swipe_right_again)
    val upAction = stringResource(if (practice) R.string.swipe_up_next else R.string.swipe_up_good)
    // Full, distinct direction color at the release threshold, also in free practice.
    // Short retargetable interpolation avoids abrupt jumps when the drag changes axis.
    val tint by animateColorAsState(
        targetValue = if (enabled) lerp(baseColor, rating.color, intensity) else baseColor,
        animationSpec = tween(100), label = "swipe rating background"
    )
    val actions = if (enabled && !busy) Modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(leftAction) { dismiss(5, Offset(-threshold, 0f)); true },
                CustomAccessibilityAction(downAction) { dismiss(3, Offset(0f, threshold)); true },
                CustomAccessibilityAction(rightAction) { dismiss(1, Offset(threshold, 0f)); true },
                CustomAccessibilityAction(upAction) { dismiss(4, Offset(0f, -threshold)); true },
            )
        } else Modifier
    val gesture = if (enabled) Modifier.pointerInput(enabled, practice) {
            detectDragGestures(
                onDragStart = { if (!busy) motion?.cancel() },
                onDragCancel = { if (!busy) reset() },
                onDragEnd = {
                    if (!busy) {
                        val moved = offset
                        if (max(abs(moved.x), abs(moved.y)) >= threshold) {
                            dismiss(direction(moved).grade, moved)
                        } else reset()
                    }
                },
            ) { change, amount ->
                if (!busy) {
                    change.consume()
                    offset += amount
                }
            }
        } else Modifier
    Box(Modifier.fillMaxWidth().testTag("review-card").then(actions).then(gesture).onSizeChanged { width = it.width; height = it.height }.graphicsLayer {
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

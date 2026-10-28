package com.slovarik.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Use the same source for the clickable and its feedback; do not add another gesture handler. */
@Composable
internal fun rememberTileMotion(): Pair<MutableInteractionSource, Modifier> {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) .97f else 1f, tween(if (pressed) 100 else 160, easing = FastOutSlowInEasing),
        label = "tile press"
    )
    return source to Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
}

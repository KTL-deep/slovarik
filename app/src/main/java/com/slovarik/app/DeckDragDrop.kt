package com.slovarik.app

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/** Window coordinates keep both horizontally scrolling rows in the same drop coordinate space. */
internal class DeckDragDropState {
    private val targets = mutableStateMapOf<Long, Rect>()
    private val sources = mutableMapOf<Long, Rect>()
    private val callbacks = mutableMapOf<Long, (Long, Long) -> Unit>()
    var hovering by mutableStateOf<Long?>(null)
        private set
    var draggingId by mutableStateOf<Long?>(null)
        private set
    var previewWindowTopLeft by mutableStateOf(Offset.Zero)
        private set
    var overlayWindowTopLeft by mutableStateOf(Offset.Zero)
        private set
    private var pointer = Offset.Zero
    private var grabOffset = Offset.Zero

    fun overlay(modifier: Modifier = Modifier): Modifier = modifier.onGloballyPositioned { overlayWindowTopLeft = it.boundsInWindow().topLeft }

    @Composable fun target(id: Long): Modifier {
        DisposableEffect(id) { onDispose { targets.remove(id); if (hovering == id) hovering = null } }
        return Modifier.onGloballyPositioned { targets[id] = it.boundsInWindow() }
    }

    @Composable fun source(id: Long, onDrop: (Long, Long) -> Unit): Modifier {
        DisposableEffect(id) { onDispose { sources.remove(id); callbacks.remove(id); if (draggingId == id) cancel() } }
        callbacks[id] = onDrop
        return Modifier
        .onGloballyPositioned { sources[id] = it.boundsInWindow() }
        .pointerInput(id) {
            detectDragGesturesAfterLongPress(
                onDragStart = { start ->
                    val buttonSize = 48.dp.toPx()
                    if (!(start.x >= size.width - buttonSize && start.y <= buttonSize)) {
                        draggingId = id
                        grabOffset = start
                        previewWindowTopLeft = sources[id]?.topLeft ?: Offset.Zero
                        pointer = previewWindowTopLeft + start
                        hovering = targets.entries.firstOrNull { it.value.contains(pointer) }?.key
                    }
                },
                onDragEnd = {
                    val destination = if (draggingId == id) hovering else null
                    cancel()
                    if (destination != null) callbacks[id]?.invoke(id, destination)
                },
                onDragCancel = { cancel() },
                onDrag = { change, amount ->
                    if (draggingId == id) {
                        change.consume()
                        pointer += amount
                        previewWindowTopLeft = pointer - grabOffset
                        hovering = targets.entries.firstOrNull { it.value.contains(pointer) }?.key
                    }
                }
            )
        }
    }

    fun cancel() { draggingId = null; hovering = null }
}

@Composable internal fun rememberDeckDragDropState(): DeckDragDropState = remember { DeckDragDropState() }

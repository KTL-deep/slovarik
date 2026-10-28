package com.slovarik.app

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private data class MatchTile(val card: Card, val term: Boolean) {
    val key: String get() = "${card.id}:${if (term) "w" else "t"}"
    val text: String get() = if (term) card.word else card.translation
}

/** Read-only practice: neither the timer nor mistakes modify SRS data. */
@Composable
internal fun MatchGame(cards: List<Card>) {
    var cycle by remember { mutableIntStateOf(0) }
    var round by remember { mutableIntStateOf(0) }
    val rounds = remember(cards, cycle) {
        val words = mutableSetOf<String>()
        val translations = mutableSetOf<String>()
        val chunks = cards.shuffled().filter {
            val word = it.word.trim().lowercase()
            val translation = it.translation.trim().lowercase()
            word.isNotEmpty() && translation.isNotEmpty() && word != translation &&
                word !in words && translation !in translations && words.add(word) && translations.add(translation)
        }.chunked(6).map { it.toMutableList() }.toMutableList()
        if (chunks.size > 1 && chunks.last().size == 1) {
            chunks.last().add(chunks[chunks.lastIndex - 1].removeAt(chunks[chunks.lastIndex - 1].lastIndex))
        }
        chunks
    }
    if (rounds.isEmpty() || rounds.sumOf { it.size } < 2) {
        Text("Для пар нужны хотя бы два разных слова и перевода в колоде.", color = Muted)
        return
    }
    val pairs = rounds[round]
    val tiles = remember(cycle, round) { pairs.flatMap { listOf(MatchTile(it, true), MatchTile(it, false)) }.shuffled() }
    var matched by remember(cycle, round) { mutableStateOf(setOf<Long>()) }
    var selected by remember(cycle, round) { mutableStateOf<MatchTile?>(null) }
    var feedback by remember(cycle, round) { mutableStateOf("Перетащите слово на перевод или выберите две плитки") }
    var seconds by remember(cycle, round) { mutableIntStateOf(0) }
    val finished = matched.size == pairs.size
    LaunchedEffect(cycle, round, finished) {
        while (!finished) { delay(1000); seconds++ }
    }
    fun attempt(source: MatchTile, target: MatchTile) {
        if (finished || source.card.id in matched || target.card.id in matched || source.key == target.key) return
        if (source.term == target.term) {
            feedback = "Выберите слово и перевод"
        } else if (source.card.id == target.card.id) {
            matched = matched + source.card.id
            feedback = "Верно!"
        } else {
            seconds += 10
            feedback = "Не совпало · +10 секунд"
        }
        selected = null
    }
    val bounds = remember(cycle, round) { mutableStateMapOf<String, Rect>() }
    var draggedKey by remember(cycle, round) { mutableStateOf<String?>(null) }
    Text("Match · раунд ${round + 1} / ${rounds.size}", style = MaterialTheme.typography.titleLarge)
    Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}  ·  ${matched.size} / ${pairs.size}", color = Mint)
    Text(feedback, color = if (feedback.startsWith("Не совпало")) Coral else Muted)
    Spacer(Modifier.height(12.dp))
    val columns = if (pairs.size >= 5) 3 else 2
    tiles.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth().zIndex(if (row.any { it.key == draggedKey }) 1f else 0f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { tile ->
                var dragging by remember(cycle, round, tile.key) { mutableStateOf(Offset.Zero) }
                var dragOrigin by remember(cycle, round, tile.key) { mutableStateOf(Offset.Zero) }
                val available = tile.card.id !in matched
                // Bounds belong to the UNTRANSLATED slot, not the dragged button.
                Box(Modifier.weight(1f).height(86.dp).zIndex(if (draggedKey == tile.key) 1f else 0f).onGloballyPositioned { bounds[tile.key] = it.boundsInRoot() }) {
                    if (available) {
                        OutlinedButton(
                            onClick = {
                                val previous = selected
                                if (previous == null || previous.key == tile.key) {
                                    selected = if (previous == null) tile else null
                                } else attempt(previous, tile)
                            },
                            modifier = Modifier.fillMaxWidth().height(86.dp).testTag("match-tile-${tile.key}")
                                .graphicsLayer { translationX = dragging.x; translationY = dragging.y }
                                .pointerInput(cycle, round, tile.key) {
                                    detectDragGestures(
                                        onDragStart = { dragOrigin = it; dragging = Offset.Zero; draggedKey = tile.key },
                                        onDragCancel = { dragging = Offset.Zero; draggedKey = null },
                                        onDragEnd = {
                                            val sourceBounds = bounds[tile.key]
                                            val destination = sourceBounds?.topLeft?.plus(dragOrigin)?.plus(dragging)
                                            val other = tiles.firstOrNull { it.key != tile.key && it.card.id !in matched &&
                                                destination != null && bounds[it.key]?.contains(destination) == true }
                                            if (other != null) attempt(tile, other)
                                             dragging = Offset.Zero
                                             draggedKey = null
                                        },
                                    ) { change, amount -> change.consume(); dragging += amount }
                                },
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (selected?.key == tile.key) Mint else MaterialTheme.colorScheme.onSurface),
                            contentPadding = PaddingValues(8.dp),
                        ) {
                            Text(tile.text, textAlign = TextAlign.Center, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (finished) {
        Text("Готово! Время: ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", color = Mint)
        Button(onClick = { if (round + 1 < rounds.size) round++ else { cycle++; round = 0 } }, modifier = Modifier.fillMaxWidth()) {
            Text(if (round + 1 < rounds.size) "Следующий раунд" else "Сыграть ещё раз")
        }
    } else {
        TextButton(onClick = { cycle++; round = 0 }) { Text("Начать заново") }
    }
}

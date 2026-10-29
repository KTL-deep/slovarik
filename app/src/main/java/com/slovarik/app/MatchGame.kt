package com.slovarik.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
internal fun MatchGame(cards: List<Card>, deckId: Long? = null, store: Store? = null) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("match_records", 0) }
    val recordKey = deckId?.let { "deck_$it" }
    var record by remember(recordKey, store) { mutableIntStateOf(if (store != null && deckId != null) UserProfileStore(store).matchRecord(deckId) ?: -1 else recordKey?.let { preferences.getInt(it, -1) } ?: -1) }
    var cycle by remember { mutableIntStateOf(0) }
    var round by remember { mutableIntStateOf(0) }
    var newRecord by remember(recordKey, cycle) { mutableStateOf(false) }
    var cycleSeconds by remember(cycle) { mutableIntStateOf(0) }
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
        Text(stringResource(R.string.match_needs_words), color = Muted)
        return
    }
    val pairs = rounds[round]
    val tiles = remember(cycle, round) { pairs.flatMap { listOf(MatchTile(it, true), MatchTile(it, false)) }.shuffled() }
    var matched by remember(cycle, round) { mutableStateOf(setOf<Long>()) }
    var selected by remember(cycle, round) { mutableStateOf<MatchTile?>(null) }
    var feedback by remember(cycle, round) { mutableIntStateOf(R.string.match_instructions) }
    var seconds by remember(cycle, round) { mutableIntStateOf(0) }
    var mismatchKeys by remember(cycle, round) { mutableStateOf(emptySet<String>()) }
    val finished = matched.size == pairs.size
    LaunchedEffect(cycle, round, finished) {
        while (!finished) { delay(1000); seconds++ }
    }
    // A full cycle is the record unit: all rounds of the deck in one run.
    LaunchedEffect(finished, cycle, round) {
        if (!finished || round + 1 < rounds.size) return@LaunchedEffect
        val total = cycleSeconds + seconds
        if (record < 0 || total < record) {
            record = total
            newRecord = true
            if (recordKey != null) {
                if (store != null) UserProfileBridge.saveMatch(context, store, requireNotNull(deckId), total)
                else preferences.edit().putInt(recordKey, total).apply()
            }
        }
    }
    fun attempt(source: MatchTile, target: MatchTile) {
        if (finished || source.card.id in matched || target.card.id in matched || source.key == target.key) return
        mismatchKeys = emptySet()
        if (source.term == target.term) {
            feedback = R.string.match_choose
        } else if (source.card.id == target.card.id) {
            matched = matched + source.card.id
            feedback = R.string.match_correct
        } else {
            seconds += 10
            feedback = R.string.match_mismatch
            mismatchKeys = setOf(source.key, target.key)
        }
        selected = null
    }
    val bounds = remember(cycle, round) { mutableStateMapOf<String, Rect>() }
    var draggedKey by remember(cycle, round) { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.match_round, round + 1, rounds.size), style = MaterialTheme.typography.titleLarge)
    if (record >= 0) Text(stringResource(R.string.match_record, record / 60, record % 60), color = Muted)
    val totalSeconds = cycleSeconds + seconds
    Text(stringResource(R.string.match_progress, totalSeconds / 60, totalSeconds % 60, matched.size, pairs.size), color = Mint)
    if (newRecord) Text(stringResource(R.string.match_new_record), color = Lemon)
    Text(stringResource(feedback), color = if (feedback == R.string.match_mismatch) Coral else Muted)
    Spacer(Modifier.height(12.dp))
    val columns = if (pairs.size >= 5) 3 else 2
    tiles.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth().zIndex(if (row.any { it.key == draggedKey }) 1f else 0f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { tile ->
                var dragging by remember(cycle, round, tile.key) { mutableStateOf(Offset.Zero) }
                var dragOrigin by remember(cycle, round, tile.key) { mutableStateOf(Offset.Zero) }
                val available = tile.card.id !in matched
                val mismatch = tile.key in mismatchKeys
                // Bounds belong to the UNTRANSLATED slot, not the dragged button.
                Box(Modifier.weight(1f).height(86.dp).zIndex(if (draggedKey == tile.key) 1f else 0f).onGloballyPositioned { bounds[tile.key] = it.boundsInRoot() }) {
                    if (available) {
                        OutlinedButton(
                            onClick = {
                                val previous = selected
                                if (previous == null || previous.key == tile.key) {
                                    mismatchKeys = emptySet()
                                    selected = if (previous == null) tile else null
                                } else attempt(previous, tile)
                            },
                            modifier = Modifier.fillMaxWidth().height(86.dp).testTag(if (mismatch) "match-mismatch-${tile.key}" else "match-tile-${tile.key}")
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
                            border = BorderStroke(if (mismatch) 2.dp else 1.dp, if (mismatch) Coral else MaterialTheme.colorScheme.outline),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (mismatch) Coral else if (selected?.key == tile.key) Mint else MaterialTheme.colorScheme.onSurface,
                                containerColor = if (mismatch) Coral.copy(alpha = .14f) else Color.Transparent),
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
        Text(stringResource(R.string.match_finished, (cycleSeconds + seconds) / 60, (cycleSeconds + seconds) % 60), color = Mint)
        Button(onClick = {
            if (round + 1 < rounds.size) {
                cycleSeconds += seconds
                round++
            } else {
                cycle++
                round = 0
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (round + 1 < rounds.size) R.string.match_next_round else R.string.match_play_again))
        }
    } else {
        TextButton(onClick = { cycle++; round = 0 }) { Text(stringResource(R.string.match_restart)) }
    }
}

package com.slovarik.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Study(store: Store, deck: Deck, language: String, speak: (String, String) -> Unit, update: () -> Unit, modifier: Modifier = Modifier, practice: Boolean = false, active: Boolean = true) {
    val all = remember(deck.id) { store.cards(deck.id) }
    val studyCards = remember(deck.id, practice) {
        (if (practice) all else all.filter { it.review.due <= System.currentTimeMillis() }).shuffled()
    }
    var index by remember(deck.id, practice) { mutableIntStateOf(0) }
    var mode by remember(deck.id, practice) { mutableStateOf("Карточки") }
    val currentCard = studyCards.getOrNull(index)
    var revealed by remember(currentCard?.id, mode) { mutableStateOf(false) }
    // This guard changes synchronously with the write, before a second tap can be dispatched.
    var answered by remember(currentCard?.id, mode) { mutableStateOf(false) }
    var wrongAttempts by remember(currentCard?.id, mode) { mutableIntStateOf(0) }
    val nudge = remember(currentCard?.id, mode) { Animatable(0f) }
    LaunchedEffect(wrongAttempts) {
        if (wrongAttempts > 0) {
            nudge.snapTo(0f)
            nudge.animateTo(-5f, tween(65))
            nudge.animateTo(0f, tween(110, easing = FastOutSlowInEasing))
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        Text(if (practice) "Тренировка" else "Повторение", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(if (practice) "Без расписания и статистики" else "SM-2 · с сохранением прогресса", color = Muted)
        if (mode != "Пары") Text("${minOf(index + 1, studyCards.size)} / ${studyCards.size}", color = Muted)
        Spacer(Modifier.height(12.dp))
        // In SM-2 the mode is locked once an answer is visible, so a wrong answer cannot be rerated.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (listOf("Карточки", "Тест", "Ввод") + if (practice) listOf("Пары") else emptyList()).forEach { option ->
                FilterChip(selected = mode == option, onClick = { keyboard?.hide(); mode = option }, enabled = practice || !revealed,
                    label = { Text(option) })
            }
        }
        Spacer(Modifier.height(18.dp))
        if (mode == "Пары" && practice) {
            MatchGame(all)
            return@Column
        }
        if (index >= studyCards.size) {
            Card(colors = CardDefaults.cardColors(containerColor = Mint, contentColor = Night), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                Text(if (practice) "Все слова пройдены. Можно начать заново или попробовать пары. Прогресс повторения не изменился."
                    else "Готово на сегодня! Возвращайтесь за следующими словами.", modifier = Modifier.padding(28.dp), fontSize = 20.sp)
            }
            if (practice) TextButton(onClick = { index = 0 }) { Text("Пройти ещё раз") }
            return@Column
        }
        val card = currentCard ?: return@Column
        var typed by remember(card.id) { mutableStateOf("") }
        var selection by remember(card.id) { mutableStateOf<String?>(null) }
        val options = remember(card.id) {
            (all.asSequence().filter { it.id != card.id }
                .map { it.translation }.filterNot { it.equals(card.translation, ignoreCase = true) }
                .distinctBy { it.trim().lowercase() }.toList().shuffled().take(3) + card.translation).shuffled()
        }
        val correct = (mode == "Тест" && selection == card.translation) ||
            (mode == "Ввод" && typed.trim().equals(card.translation.trim(), ignoreCase = true))
        fun advance(grade: Int) {
            if (!active || !revealed || answered) return
            answered = true
            if (!practice) {
                val wrong = (mode == "Тест" && selection != card.translation) ||
                    (mode == "Ввод" && !typed.trim().equals(card.translation.trim(), ignoreCase = true))
                store.review(card, if (wrong) 1 else grade)
            }
            index++
            if (!practice) update()
        }
        AnimatedContent(targetState = card to revealed, contentKey = { it.first.id }, modifier = Modifier.fillMaxWidth().graphicsLayer { translationX = nudge.value * density },
            transitionSpec = {
                (slideInHorizontally(tween(180, easing = FastOutSlowInEasing)) { 20 } + fadeIn(tween(160)))
                    .togetherWith(slideOutHorizontally(tween(160)) { -20 } + fadeOut(tween(140)))
            }, label = "next word") { (item, itemRevealed) ->
        val isCurrent = active && item.id == currentCard?.id
        // Outgoing items are read-only snapshots: never expose another grade or reveal action.
        val flip by animateFloatAsState(if (mode == "Карточки" && itemRevealed) 180f else 0f,
            tween(300, easing = FastOutSlowInEasing), label = "flashcard flip")
        SwipeReview(enabled = isCurrent && itemRevealed && !answered, practice = practice,
            baseColor = if (isCurrent && itemRevealed && correct) Mint else Lavender,
            onCommit = ::advance) { swipeModifier, tint ->
        Card(colors = CardDefaults.cardColors(containerColor = tint, contentColor = Night), shape = RoundedCornerShape(28.dp),
            modifier = swipeModifier.fillMaxWidth().graphicsLayer {
                if (mode == "Карточки") rotationY = flip
                cameraDistance = 12f * density * 100f
            }) {
            // Compose only the visible face: the hidden face cannot be focused by accessibility.
            Column(Modifier.fillMaxWidth().then(if (mode == "Карточки") Modifier.height(280.dp) else Modifier.heightIn(min = 240.dp)).graphicsLayer { if (mode == "Карточки" && flip > 90f) rotationY = 180f }.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (mode == "Карточки" && flip > 90f) "ПЕРЕВОД" else "ПЕРЕВЕДИТЕ", color = Night, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 110.dp), contentAlignment = Alignment.Center) {
                    Text(if (mode == "Карточки" && flip > 90f) item.translation else item.word,
                        fontSize = if (mode == "Карточки" && flip > 90f) 26.sp else 32.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                when (mode) {
                    "Карточки" -> {
                        if (!itemRevealed && isCurrent) Button(onClick = { if (!revealed) revealed = true }, colors = ButtonDefaults.buttonColors(containerColor = Night, contentColor = Color.White)) { Text("Показать перевод") }
                        else if (isCurrent) TextButton(onClick = { speak(item.word, language) }, colors = ButtonDefaults.textButtonColors(contentColor = Night)) { AppIcon("sound", Modifier.size(18.dp), Night); Spacer(Modifier.width(7.dp)); Text("Произнести") }
                        else Spacer(Modifier.height(48.dp))
                    }
                    "Тест" -> {
                        if (isCurrent) options.forEach { answer ->
                            OutlinedButton(onClick = { if (!revealed) { selection = answer; revealed = true; if (answer != item.translation) wrongAttempts++ } }, enabled = !revealed, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Night, disabledContentColor = Night.copy(alpha = .5f))) { Text(answer) }
                        }
                        if (revealed || !isCurrent) Text(if (isCurrent && selection == item.translation) "Верно!" else "Правильно: ${item.translation}", color = Night)
                    }
                    else -> {
                        if (isCurrent) {
                            OutlinedTextField(typed, { typed = it }, label = { Text("Ваш перевод") }, singleLine = true, enabled = !revealed, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Night, unfocusedTextColor = Night, focusedLabelColor = Night, unfocusedLabelColor = Night, focusedBorderColor = Night, unfocusedBorderColor = Night))
                            if (!revealed) TextButton(enabled = typed.isNotBlank(), onClick = { if (!revealed) { keyboard?.hide(); revealed = true; if (!typed.trim().equals(item.translation.trim(), ignoreCase = true)) wrongAttempts++ } }, colors = ButtonDefaults.textButtonColors(contentColor = Night)) { Text("Проверить") }
                        }
                        if (revealed || !isCurrent) Text(if (isCurrent && typed.trim().equals(item.translation.trim(), ignoreCase = true)) "Верно!" else "Правильно: ${item.translation}", color = Night)
                    }
                }
            }
        }
        }
        }
        if (revealed) {
            Spacer(Modifier.height(20.dp))
            if (practice) {
                Button(onClick = { advance(4) }, enabled = active && !answered, modifier = Modifier.fillMaxWidth()) { Text("Следующее слово") }
            } else {
                Text("Как хорошо вы вспомнили?", fontWeight = FontWeight.Bold)
                Text("Ответ в тесте и вводе проверяется автоматически; ошибка не засчитывается как успех.", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(1 to "Снова", 3 to "Трудно", 4 to "Хорошо", 5 to "Легко").forEach { (grade, label) ->
                        OutlinedButton(onClick = { advance(grade) }, enabled = active && !answered, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) {
                            Text(label, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

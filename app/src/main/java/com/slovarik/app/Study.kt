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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

@Composable
fun Study(store: Store, deck: Deck, language: String, speak: (String, String) -> Unit, update: () -> Unit, modifier: Modifier = Modifier, practice: Boolean = false, active: Boolean = true, initialCardId: Long? = null, initialMode: String = "Карточки", randomDirection: Boolean = false) {
    val context = LocalContext.current
    val all = remember(deck.id) { store.cards(deck.id) }
    val studyCards = remember(deck.id, practice, initialCardId) {
        val eligible = (if (practice) all else all.filter { it.review.due <= System.currentTimeMillis() || it.id == initialCardId }).shuffled()
        // A card returned by global Undo must be shown first, not lost in the shuffled queue.
        if (!practice && initialCardId != null) eligible.sortedBy { if (it.id == initialCardId) 0 else 1 } else eligible
    }
    var session by remember(deck.id, practice, initialCardId) { mutableStateOf(LearningSession.start(studyCards)) }
    var directionSeed by remember(deck.id, practice, initialCardId) { mutableLongStateOf(Random.nextLong()) }
    var lastWrite by remember(deck.id, practice, initialCardId) { mutableStateOf<Pair<Long, LearningSession>?>(null) }
    var undoing by remember(deck.id, practice, initialCardId) { mutableStateOf(false) }
    var sessionError by remember(deck.id, practice, initialCardId) { mutableStateOf<String?>(null) }
    val mode = remember(deck.id, practice, initialMode) {
        initialMode.takeIf { it in listOf("Карточки", "Тест", "Ввод") || (practice && it == "Пары") } ?: "Карточки"
    }
    val currentItem = session.current
    val currentCard = currentItem?.card
    val occurrence = currentItem?.occurrence
    var revealed by remember(occurrence, mode) { mutableStateOf(false) }
    // This guard changes synchronously with the write, before a second tap can be dispatched.
    var answered by remember(occurrence, mode) { mutableStateOf(false) }
    var swiping by remember(occurrence, mode) { mutableStateOf(false) }
    var wrongAttempts by remember(occurrence, mode) { mutableIntStateOf(0) }
    val nudge = remember(occurrence, mode) { Animatable(0f) }
    LaunchedEffect(wrongAttempts) {
        if (wrongAttempts > 0) {
            nudge.snapTo(0f)
            nudge.animateTo(-5f, tween(65))
            nudge.animateTo(0f, tween(110, easing = FastOutSlowInEasing))
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val undoEvent = if (!practice) store.lastReviewUndo()?.takeIf { it.card.deckId == deck.id } else null
    fun undo() {
        if (!active || undoing) return
        val latest = undoEvent ?: return
        undoing = true
        try {
            val restored = store.undoLastReview(latest.eventId)
            if (restored != null) {
                session = if (lastWrite?.first == latest.eventId) session.restore(lastWrite!!.second, restored)
                    else session.prepend(restored)
                lastWrite = null
                sessionError = null
            } else {
                sessionError = context.getString(R.string.study_undo_unavailable)
            }
            update()
        } catch (error: Exception) {
            sessionError = context.getString(R.string.study_undo_error, error.message)
        } finally { undoing = false }
    }
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        Text(stringResource(if (practice) R.string.study_practice else R.string.study_review), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (mode != "Пары") Text(stringResource(R.string.study_progress, minOf(session.index + 1, session.queue.size), session.queue.size), color = Muted)
        if (undoEvent != null) TextButton(onClick = ::undo, enabled = active && !undoing) { Text(stringResource(R.string.study_undo)) }
        sessionError?.let { Text(it, color = Coral, fontSize = 13.sp) }
        Spacer(Modifier.height(12.dp))
        if (mode == "Пары" && practice) {
            MatchGame(all, deckId = deck.id, store = store)
            return@Column
        }
        if (session.current == null) {
            Card(colors = CardDefaults.cardColors(containerColor = Mint, contentColor = Night), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (practice) R.string.study_practice_done else R.string.study_review_done), modifier = Modifier.padding(28.dp), fontSize = 20.sp)
            }
            if (practice) TextButton(onClick = {
                directionSeed = Random.nextLong()
                session = LearningSession.start(all, session.nextOccurrence)
            }) { Text(stringResource(R.string.study_again)) }
            return@Column
        }
        val card = currentCard ?: return@Column
        // In random mode the prompt is the translation for roughly half of the occurrences.
        val promptIsWord = promptIsWordFor(occurrence ?: 0L, randomDirection, directionSeed)
        val answerText = if (promptIsWord) card.translation else card.word
        var typed by remember(occurrence, mode) { mutableStateOf("") }
        var selection by remember(occurrence, mode) { mutableStateOf<String?>(null) }
        val options = remember(occurrence, promptIsWord) {
            (all.asSequence().filter { it.id != card.id }
                .map { if (promptIsWord) it.translation else it.word }
                .filterNot { it.equals(answerText, ignoreCase = true) }
                .distinctBy { it.trim().lowercase() }.toList().shuffled().take(3) + answerText).shuffled()
        }
        val correct = (mode == "Тест" && selection == answerText) ||
            (mode == "Ввод" && typed.trim().equals(answerText.trim(), ignoreCase = true))
        fun advance(grade: Int, fromSwipe: Boolean = false): Boolean {
            if (!active || (mode != "Карточки" && !revealed) || answered || (swiping && !fromSwipe) || session.current?.occurrence != occurrence) return false
            answered = true
            sessionError = null
            try {
                if (practice) session = session.copy(index = session.index + 1)
                else {
                    val wrong = (mode == "Тест" && selection != answerText) ||
                        (mode == "Ввод" && !typed.trim().equals(answerText.trim(), ignoreCase = true))
                    val effectiveGrade = if (wrong) 1 else grade
                    val snapshot = session
                    val result = store.review(card, effectiveGrade)
                    lastWrite = result.eventId to snapshot
                    session = session.advance(result.card, effectiveGrade < 3)
                    update()
                }
                return true
            } catch (error: Exception) {
                answered = false
                sessionError = context.getString(R.string.study_save_error, error.message)
                return false
            }
        }
        AnimatedContent(targetState = currentItem to revealed, contentKey = { it.first?.occurrence }, modifier = Modifier.fillMaxWidth().graphicsLayer { translationX = nudge.value * density },
            transitionSpec = {
                (slideInHorizontally(tween(180, easing = FastOutSlowInEasing)) { 20 } + fadeIn(tween(160)))
                    .togetherWith(slideOutHorizontally(tween(160)) { -20 } + fadeOut(tween(140)))
            }, label = "next word") { (target, itemRevealed) ->
        val item = target?.card ?: return@AnimatedContent
        val isCurrent = active && target.occurrence == occurrence
        val itemPromptIsWord = promptIsWordFor(target.occurrence, randomDirection, directionSeed)
        val itemPrompt = if (itemPromptIsWord) item.word else item.translation
        val itemAnswer = if (itemPromptIsWord) item.translation else item.word
        var resultSnapshot by remember(target.occurrence) { mutableStateOf<Boolean?>(null) }
        if (isCurrent && itemRevealed && mode != "Карточки") resultSnapshot = correct
        val itemCorrect = if (isCurrent) correct else resultSnapshot == true
        // Outgoing items are read-only snapshots: never expose another grade or reveal action.
        val flip by animateFloatAsState(if (mode == "Карточки" && itemRevealed) 180f else 0f,
            tween(380, easing = FastOutSlowInEasing), label = "flashcard flip")
        val answerFace = mode == "Карточки" && flip > 90f
        val visibleWord = if (answerFace) !itemPromptIsWord else itemPromptIsWord
        val flipLabel = stringResource(R.string.study_flip)
        key(target.occurrence) {
        SwipeReview(enabled = isCurrent && !answered && (mode == "Карточки" || itemRevealed), practice = practice,
            baseColor = if (itemRevealed && mode != "Карточки") {
                if (itemCorrect) Mint else swipeRatingColor(1)
            } else if (itemRevealed && itemCorrect) Mint else Lavender,
            onBusyChange = { swiping = it }, onCommit = { advance(it, fromSwipe = true) }) { swipeModifier, tint ->
        Card(colors = CardDefaults.cardColors(containerColor = tint, contentColor = Night), shape = RoundedCornerShape(28.dp),
            modifier = swipeModifier.fillMaxWidth()
                .then(if (mode == "Карточки" && isCurrent && !answered && !swiping) Modifier.clickable(onClickLabel = flipLabel) { revealed = !revealed } else Modifier)
                .graphicsLayer {
                if (mode == "Карточки") rotationY = flip
                cameraDistance = 12f * density * 100f
            }) {
            // Compose only the visible face: the hidden face cannot be focused by accessibility.
            Column(Modifier.fillMaxWidth().then(if (mode == "Карточки") Modifier.height(280.dp) else Modifier).graphicsLayer { if (answerFace) rotationY = 180f }.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(if (mode == "Карточки") 48.dp else 24.dp), contentAlignment = Alignment.Center) {
                     Text(stringResource(when {
                         mode != "Карточки" -> R.string.study_translate
                         answerFace -> if (itemPromptIsWord) R.string.study_translation else R.string.study_word
                         else -> R.string.study_translate
                     }), color = Night, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                // No weight here: a weighted box collapses inside the parent's vertical scroll.
                if (mode == "Карточки") {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(if (answerFace) itemAnswer else itemPrompt,
                            fontSize = if (answerFace) 26.sp else 32.sp,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                } else {
                    Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(itemPrompt, fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
                when (mode) {
                    "Карточки" -> {
                        if (visibleWord && isCurrent) TextButton(onClick = { speak(item.word, language) }, enabled = !swiping, modifier = Modifier.height(48.dp), colors = ButtonDefaults.textButtonColors(contentColor = Night)) { AppIcon("sound", Modifier.size(18.dp), Night); Spacer(Modifier.width(7.dp)); Text(stringResource(R.string.study_speak)) }
                        else Spacer(Modifier.height(48.dp))
                    }
                    "Тест" -> {
                        if (isCurrent && !itemRevealed) options.forEach { answer ->
                            OutlinedButton(onClick = { if (!revealed && !swiping) { selection = answer; revealed = true; if (answer != itemAnswer) wrongAttempts++ } }, enabled = !revealed && !swiping, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Night, disabledContentColor = Night.copy(alpha = .5f))) { Text(answer) }
                        }
                        if (itemRevealed || !isCurrent) Text(if (itemCorrect) stringResource(R.string.study_correct) else stringResource(R.string.study_answer, itemAnswer), color = Night)
                    }
                    else -> {
                        if (isCurrent && !itemRevealed) {
                            OutlinedTextField(typed, { typed = it }, modifier = Modifier.testTag("study-typing-field"), label = { Text(stringResource(R.string.study_your_translation)) }, singleLine = true, enabled = !swiping, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Muted, unfocusedLabelColor = Muted, focusedBorderColor = Lavender, unfocusedBorderColor = Muted, cursorColor = Lavender, focusedContainerColor = Color(0xFF242424), unfocusedContainerColor = Color(0xFF242424)))
                            TextButton(enabled = typed.isNotBlank() && !swiping, onClick = { if (!revealed && !swiping) { keyboard?.hide(); revealed = true; if (!typed.trim().equals(itemAnswer.trim(), ignoreCase = true)) wrongAttempts++ } }, colors = ButtonDefaults.textButtonColors(contentColor = Night)) { Text(stringResource(R.string.study_check)) }
                        }
                        if (itemRevealed || !isCurrent) Text(if (itemCorrect) stringResource(R.string.study_correct) else stringResource(R.string.study_answer, itemAnswer), color = Night)
                    }
                }
            }
        }
        }
        }
        }
        if (revealed) {
            Spacer(Modifier.height(20.dp))
            if (practice) {
                Button(onClick = { advance(4) }, enabled = active && !answered && !swiping, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.study_next_word)) }
            } else {
                Text(stringResource(R.string.study_rate_prompt), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(1 to R.string.swipe_again, 3 to R.string.swipe_hard, 4 to R.string.swipe_good, 5 to R.string.swipe_easy).forEach { (grade, label) ->
                        OutlinedButton(onClick = { advance(grade) }, enabled = active && !answered && !swiping, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) {
                            Text(stringResource(label), fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Session-specific prompt side, stable for each occurrence (including outgoing content). */
internal fun promptIsWordFor(occurrence: Long, randomDirection: Boolean, sessionSeed: Long): Boolean =
    !randomDirection || Random(sessionSeed xor (occurrence * 1_000_003L + 17L)).nextBoolean()

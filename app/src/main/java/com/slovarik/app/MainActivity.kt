package com.slovarik.app

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale

private val Ink = Night

private data class PageSnapshot(val name: String, val folder: Folder?, val deck: Deck?)

private fun pageDepth(page: String) = when (page) {
    "home", "stats", "settings" -> 0
    "decks" -> 1
    "cards" -> 2
    else -> 3
}

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        store = Store(this)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean("seeded", false)) {
            if (!store.hasFolders()) {
                val folder = store.addFolder("Мои слова")
                val deck = store.addDeck(folder, "Первые слова · English")
                listOf("hello" to "привет", "journey" to "путешествие", "curious" to "любопытный", "garden" to "сад", "bright" to "яркий", "remember" to "помнить").forEach { (word, translation) -> store.addCard(deck, word, translation) }
            }
            prefs.edit().putBoolean("seeded", true).apply()
        }
        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        setContent { App(store, { word, language ->
            if (language.startsWith("en", ignoreCase = true)) {
                val americanVoice = if (ttsReady) tts?.voices?.filter { it.locale.language == "en" && it.locale.country == "US" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
                    ?.sortedBy { it.isNetworkConnectionRequired }?.firstOrNull() else null
                if (americanVoice == null || tts?.setVoice(americanVoice) != TextToSpeech.SUCCESS) {
                    Toast.makeText(this, "Установите американский голос (English US) в настройках синтеза речи Android.", Toast.LENGTH_LONG).show()
                } else tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, "card")
            } else if (!ttsReady || (tts?.setLanguage(Locale.forLanguageTag(language)) ?: -1) < 0) {
                Toast.makeText(this, "Озвучка недоступна. Установите голос для этого языка в настройках Android.", Toast.LENGTH_LONG).show()
            } else tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, "card")
        }) }
    }
    override fun onDestroy() { tts?.stop(); tts?.shutdown(); store.close(); super.onDestroy() }
}

@Composable
private fun App(store: Store, speak: (String, String) -> Unit) {
    val context = LocalContext.current
    val bundledTranslation = remember(context) { TranslationController(context) }
    DisposableEffect(bundledTranslation) { onDispose { bundledTranslation.close() } }
    var page by remember { mutableStateOf("home") }
    var folder by remember { mutableStateOf<Folder?>(null) }
    var deck by remember { mutableStateOf<Deck?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var cardTranslation by remember { mutableStateOf("") }
    var editingCard by remember { mutableStateOf<Card?>(null) }
    var pendingDelete by remember { mutableStateOf<Card?>(null) }
    var pendingFolder by remember { mutableStateOf<Folder?>(null) }
    var pendingDeck by remember { mutableStateOf<Deck?>(null) }
    var selectedFolderId by remember { mutableStateOf<Long?>(null) }
    var importText by remember { mutableStateOf("") }
    var search by remember(page) { mutableStateOf("") }
    val preferences = remember { context.getSharedPreferences("settings", 0) }
    var language by remember { mutableStateOf(preferences.getString("language", "en") ?: "en") }
    var target by remember { mutableStateOf(preferences.getString("target", "ru") ?: "ru") }
    val update: () -> Unit = { tick++; Unit }
    val folders = remember(tick) { store.folders() }
    val notify: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            importText = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            dialog = "import"
        } catch (e: Exception) { notify("Не удалось прочитать файл: ${e.message}") }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            store.restore(text); folder = null; deck = null; page = "home"; update(); notify("Копия восстановлена")
        } catch (e: Exception) { notify("Не удалось восстановить копию: ${e.message}") }
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(store.backup().toByteArray(Charsets.UTF_8)) }
                ?: error("Нет доступа к файлу")
            notify("Копия сохранена")
        } catch (e: Exception) { notify("Не удалось сохранить: ${e.message}") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notify(if (granted) "Уведомления разрешены" else "Без разрешения уведомления не появятся")
    }
    val screen = PageSnapshot(page, folder, deck)
    BackHandler(enabled = dialog.isEmpty() && page != "home") {
        page = when (page) { "study", "practice" -> "cards"; "cards" -> "decks"; else -> "home" }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Lavender, onPrimary = Night, secondary = Mint, background = Night, surface = Color(0xFF242424), onSurface = Color.White)) {
        Scaffold(containerColor = Night, bottomBar = {
            NavigationBar(containerColor = Night) {
                listOf("home" to "Слова", "stats" to "Прогресс", "settings" to "Настройки").forEach { (key, label) ->
                    val selected = page == key || (key == "home" && page in listOf("decks", "cards", "study", "practice"))
                    val iconScale by animateFloatAsState(if (selected) 1.08f else 1f, tween(150), label = "nav icon scale")
                    val iconColor by androidx.compose.animation.animateColorAsState(if (selected) Lavender else Muted, tween(150), label = "nav icon color")
                    NavigationBarItem(selected = selected, colors = NavigationBarItemDefaults.colors(selectedIconColor = Lavender, selectedTextColor = Lavender, unselectedIconColor = Muted, unselectedTextColor = Muted, indicatorColor = Night),
                        onClick = { page = key }, icon = { AppIcon(if (key == "home") "home" else if (key == "stats") "stats" else "settings", Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale }, iconColor) }, label = { Text(label, fontSize = 11.sp) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(16.dp))
                AnimatedContent(targetState = screen, modifier = Modifier.weight(1f), contentKey = { it.name to (it.deck?.id ?: it.folder?.id) },
                    transitionSpec = {
                        val direction = if (pageDepth(targetState.name) >= pageDepth(initialState.name)) 1 else -1
                        (slideInHorizontally(tween(210, easing = FastOutSlowInEasing)) { direction * 24 } + fadeIn(tween(180)))
                            .togetherWith(slideOutHorizontally(tween(170)) { -direction * 24 } + fadeOut(tween(150)))
                    }, label = "page") { shown ->
                val currentDeck = shown.deck
                val shownFolder = shown.folder
                // AnimatedContent layers destinations; retain vertical layout within each one.
                Column(Modifier.fillMaxSize()) {
                when (shown.name) {
                    "home" -> {
                        val allDecks = folders.flatMap { store.decks(it.id) }
                        val active = allDecks.firstOrNull { candidate -> store.cards(candidate.id).any { it.review.due <= System.currentTimeMillis() } } ?: allDecks.firstOrNull()
                        val activeCards = active?.let { store.cards(it.id) }.orEmpty()
                        val due = activeCards.count { it.review.due <= System.currentTimeMillis() }
                        val learned = activeCards.count { it.review.repetitions > 0 && it.review.due > System.currentTimeMillis() }
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Text("Словарик", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = Lavender), shape = RoundedCornerShape(30.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp)) {
                                    Surface(color = Color.White, shape = RoundedCornerShape(40.dp)) { Text("В процессе", Modifier.padding(horizontal = 9.dp, vertical = 3.dp), color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Medium) }
                                    Spacer(Modifier.height(12.dp))
                                    Text(active?.name ?: "Начните с первой колоды", color = Ink, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(6.dp))
                                    Text("$due к повторению · ${activeCards.size} всего", color = Ink, fontSize = 13.sp)
                                    Spacer(Modifier.height(12.dp))
                                    val progress by animateFloatAsState(if (activeCards.isEmpty()) 0f else learned.toFloat() / activeCards.size, tween(180), label = "review progress")
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(5.dp), color = Ink, trackColor = Color.White, gapSize = 0.dp, drawStopIndicator = {})
                                    Spacer(Modifier.height(6.dp))
                                    Text("Повторено: $learned из ${activeCards.size}", color = Ink, fontSize = 11.sp)
                                    Spacer(Modifier.height(14.dp))
                                    Button(onClick = { if (active != null) { deck = active; folder = folders.firstOrNull { it.id == active.folderId }; page = if (due > 0) "study" else if (activeCards.isNotEmpty()) "practice" else "cards" } else { input = ""; dialog = "folder" } }, modifier = Modifier.fillMaxWidth().height(46.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White), shape = RoundedCornerShape(30.dp)) {
                                        AppIcon("play", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (active == null) "Создать папку" else if (due > 0) "Продолжить" else "Тренироваться", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                             Text("Мои колоды", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                             Spacer(Modifier.height(10.dp))
                             Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                 allDecks.forEach { item ->
                                     DeckTile(item.name, "${store.cards(item.id).size} слов", { deck = item; folder = folders.firstOrNull { it.id == item.folderId }; page = "cards" },
                                         { pendingDeck = item; dialog = "deleteDeck" }, Modifier.width(170.dp).height(125.dp))
                                 }
                                 DeckTile("Новая колода", "+ Добавить", { input = ""; selectedFolderId = folders.firstOrNull()?.id; dialog = "deck" }, modifier = Modifier.width(170.dp).height(125.dp))
                             }
                             Spacer(Modifier.height(20.dp))
                             Text("Мои папки", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                             Spacer(Modifier.height(10.dp))
                             Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                 folders.forEach { item -> FolderTile(item.name, "Колоды: ${store.decks(item.id).size}", { folder = item; page = "decks" }, { pendingFolder = item; dialog = "deleteFolder" }) }
                                 FolderTile("Новая папка", "+ Добавить", { input = ""; dialog = "folder" })
                            }
                            Spacer(Modifier.height(20.dp))
                             val (practiceSource, practiceMotion) = rememberTileMotion()
                             Card(onClick = { if (active != null) { deck = active; folder = folders.firstOrNull { it.id == active.folderId }; page = if (activeCards.isNotEmpty()) "practice" else "cards" } else page = "settings" }, interactionSource = practiceSource, colors = CardDefaults.cardColors(containerColor = Coral), shape = RoundedCornerShape(28.dp), modifier = practiceMotion.fillMaxWidth()) {
                                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Surface(color = Ink, shape = RoundedCornerShape(50)) { Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { AppIcon("play", Modifier.size(18.dp)) } }
                                    Column(Modifier.weight(1f)) {
                                        Text("Практика каждый день", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(4.dp))
                                        Text(if (activeCards.isNotEmpty()) "Начать тренировку без расписания" else "Сначала добавьте слова в колоду", color = Ink, fontSize = 12.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                        }
                    }
                     "decks" -> {
                         Back("Папки") { page = "home" }
                         Heading(shownFolder?.name.orEmpty(), "Выберите колоду для занятий")
                         if (shownFolder != null) TextButton(onClick = { pendingFolder = shownFolder; dialog = "deleteFolder" }) { Text("Удалить папку") }
                         SearchField(search) { search = it }
                         TextButton(onClick = { input = ""; selectedFolderId = shownFolder?.id; dialog = "deck" }) { Text("+ Новая колода") }
                        val matchingDecks = shownFolder?.let { store.decks(it.id) }.orEmpty().filter { it.name.contains(search, ignoreCase = true) }
                         LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(matchingDecks) { item ->
                                 DeckTile(item.name, "${store.cards(item.id).size} карточек", { deck = item; page = "cards" }, { pendingDeck = item; dialog = "deleteDeck" })
                            }
                        }
                    }
                    "cards" -> if (currentDeck != null) {
                         Back(shownFolder?.name.orEmpty()) { page = "decks" }
                         Heading(currentDeck.name, "Слова остаются на устройстве")
                         TextButton(onClick = { pendingDeck = currentDeck; dialog = "deleteDeck" }) { Text("Удалить колоду") }
                        val cards = store.cards(currentDeck.id)
                        val due = cards.count { it.review.due <= System.currentTimeMillis() }
                        SearchField(search) { search = it }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { page = "study" }, enabled = due > 0, modifier = Modifier.weight(1f)) { Text("Повторить · $due") }
                            OutlinedButton(onClick = { page = "practice" }, enabled = cards.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Тренироваться") }
                        }
                        Row {
                            TextButton(onClick = { input = ""; cardTranslation = ""; dialog = "card" }) { Text("+ Карточка") }
                            TextButton(onClick = { importText = ""; dialog = "import" }) { Text("Импорт") }
                            TextButton(onClick = { csv.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) }) { Text("CSV / TSV") }
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(cards.filter { it.word.contains(search, true) || it.translation.contains(search, true) }, key = { it.id }) { card ->
                                Card(colors = CardDefaults.cardColors(containerColor = Lemon, contentColor = Ink), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                             Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                                 Text(card.word, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
                                                 Text(card.translation, color = Ink.copy(alpha = .7f), textAlign = TextAlign.Center)
                                            }
                                            IconButton(onClick = { speak(card.word, language) }) { AppIcon("sound", color = Ink) }
                                        }
                                        Row {
                                             TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Ink), onClick = {
                                                editingCard = card; input = card.word; cardTranslation = card.translation; dialog = "edit"
                                            }) { Text("Изменить") }
                                             TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Ink), onClick = { pendingDelete = card; dialog = "delete" }) { Text("Удалить") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "study", "practice" -> if (currentDeck != null) {
                        Back(currentDeck.name) { page = "cards" }
                         Study(store, currentDeck, language, speak, update, Modifier.weight(1f), practice = shown.name == "practice", active = screen == shown)
                    }
                    "stats" -> {
                        Heading("Прогресс", "Каждое повторение имеет значение")
                        val stats = store.stats()
                        Hero("Всего повторений", "${stats.reviews}", "Верных ответов: ${stats.correct} · Дней занятий: ${stats.days}")
                        Text("Статистика сохраняется на устройстве и входит в резервную копию.", color = Muted)
                    }
                    "settings" -> {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Heading("Настройки", "Управляйте обучением и данными")
                        Text("Язык исходных слов", fontWeight = FontWeight.Bold)
                        LanguageRow(language) { language = it; preferences.edit().putString("language", it).apply() }
                        Text("Язык перевода", fontWeight = FontWeight.Bold)
                        LanguageRow(target) { target = it; preferences.edit().putString("target", it).apply() }
                        Spacer(Modifier.height(12.dp))
                          Text("Модели английский ↔ русский уже встроены в приложение. Интернет и первая загрузка не нужны. Для остальных языков перевод можно ввести вручную.", color = Muted)
                         TextButton(onClick = { dialog = "translationInfo" }) { Text("О встроенном переводе OPUS-MT") }
                        Spacer(Modifier.height(16.dp))
                        Text("Напоминания", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        val prefs = context.getSharedPreferences("settings", 0)
                        Text("Время: %02d:%02d (приблизительно)".format(prefs.getInt("hour", 19), prefs.getInt("minute", 0)))
                        Row {
                            TextButton(onClick = {
                                TimePickerDialog(context, { _, h, m ->
                                    setReminder(context, h, m); update()
                                    if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    notify("Напоминание включено")
                                }, prefs.getInt("hour", 19), prefs.getInt("minute", 0), true).show()
                            }) { Text("Выбрать время") }
                            TextButton(onClick = { cancelReminder(context); update(); notify("Напоминание выключено") }) { Text("Выключить") }
                        }
                        HorizontalDivider()
                        Text("Мои данные", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { backup.launch("slovarik-backup.json") }) { Text("Сохранить полную копию JSON") }
                        TextButton(onClick = { dialog = "restore" }) { Text("Восстановить копию JSON") }
                         Text("Восстановление заменит текущие папки, карточки и статистику.", color = Muted)
                        }
                    }
                }
                }
            }
        }
        }
        if (dialog.isNotEmpty()) MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF6849A0), surface = Color(0xFFF7F5F0), onSurface = Ink)) {
            AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(when (dialog) {
            "folder" -> "Новая папка"; "deck" -> "Новая колода"; "card" -> "Новая карточка"; "translationInfo" -> "О переводе"
            "edit" -> "Изменить карточку"; "delete" -> "Удалить карточку?"; "deleteFolder" -> "Удалить папку?"; "deleteDeck" -> "Удалить колоду?"; "import" -> "Импорт слов"; else -> "Восстановить копию?"
        }) }, text = {
            when (dialog) {
                "translationInfo" -> Text("Офлайн-перевод выполняют модели OPUS-MT Университета Хельсинки (Helsinki-NLP), преобразованные в ONNX компанией Xenova. Движок — ONNX Runtime (Microsoft).\n\nАнглийский → русский: Apache-2.0. Русский → английский: CC-BY-4.0. Веса квантованы. Лицензии и сведения об авторах включены в APK.\n\nМашинный перевод может ошибаться и зависит от контекста. Проверяйте подсказку перед сохранением. Сейчас поддерживаются слова и короткие фразы EN ↔ RU; тексты никуда не отправляются.", modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()))
                "import" -> Column { Text("Каждая строка: слово<TAB>перевод или слово;перевод. CSV с запятыми также поддерживается."); Spacer(Modifier.height(8.dp)); OutlinedTextField(importText, { importText = it }, label = { Text("Вставьте слова") }, minLines = 5) }
                "restore" -> Text("Текущие данные будут заменены. Сначала сохраните резервную копию.")
                "delete" -> Text("«${pendingDelete?.word.orEmpty()}» будет удалена. Повторения останутся в общей статистике.")
                "deleteFolder" -> Text("Папка «${pendingFolder?.name.orEmpty()}», все её колоды и карточки будут удалены без возможности отмены. История повторений останется в статистике.")
                "deleteDeck" -> Text("Колода «${pendingDeck?.name.orEmpty()}» и все её карточки будут удалены без возможности отмены. История повторений останется в статистике.")
                "deck" -> Column {
                    OutlinedTextField(input, { input = it }, label = { Text("Название") }, singleLine = true)
                    if (folders.isEmpty()) Text("Будет создана папка «Мои слова» для новой колоды.")
                    else {
                        Text("Папка для колоды")
                        folders.forEach { choice ->
                            Row(Modifier.fillMaxWidth().clickable { selectedFolderId = choice.id }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedFolderId == choice.id, onClick = { selectedFolderId = choice.id })
                                Text(choice.name)
                            }
                        }
                    }
                }
                "card", "edit" -> Column {
                    OutlinedTextField(input, { input = it }, label = { Text("Слово") }, singleLine = true)
                    OutlinedTextField(cardTranslation, { cardTranslation = it }, label = { Text("Перевод") }, singleLine = true)
                    Text("OPUS-MT · на устройстве · без сети", fontSize = 12.sp, color = Color.DarkGray)
                    var translating by remember { mutableStateOf(false) }
                    TextButton(enabled = input.isNotBlank() && BundledTranslator.supports(language, target) && !translating, onClick = {
                        translating = true
                        val requestedWord = input.trim()
                        val requestedCard = editingCard?.id
                        val requestedDialog = dialog
                        val requestedSource = language
                        val requestedTarget = target
                        bundledTranslation.translate(requestedWord, requestedSource, requestedTarget) { result ->
                            if (dialog == requestedDialog && input.trim() == requestedWord && editingCard?.id == requestedCard && language == requestedSource && target == requestedTarget) {
                                result.onSuccess { cardTranslation = it }.onFailure { notify("Ошибка перевода: ${it.message}") }
                            }
                            translating = false
                        }
                    }) { Text(if (translating) "Перевод на устройстве…" else "Перевести офлайн") }
                    if (!BundledTranslator.supports(language, target)) Text("Автоперевод доступен для пары EN ↔ RU", fontSize = 12.sp)
                }
                else -> OutlinedTextField(input, { input = it }, label = { Text("Название") }, singleLine = true)
            }
        }, confirmButton = {
            TextButton(onClick = {
                try {
                    when (dialog) {
                        "folder" -> if (input.isNotBlank()) store.addFolder(input)
                         "deck" -> if (input.isNotBlank()) {
                             val parentId = selectedFolderId?.takeIf { id -> folders.any { it.id == id } }
                                 ?: if (folders.isEmpty()) store.addFolder("Мои слова") else error("Выберите папку")
                             store.addDeck(parentId, input)
                         }
                         "card" -> if (input.isNotBlank() && cardTranslation.isNotBlank()) store.addCard(requireNotNull(deck).id, input, cardTranslation)
                         "edit" -> store.updateCard(requireNotNull(editingCard).id, input, cardTranslation)
                         "delete" -> pendingDelete?.let { store.deleteCard(it.id) }
                         "deleteFolder" -> pendingFolder?.let { removed ->
                             store.deleteFolder(removed.id)
                             if (folder?.id == removed.id || deck?.folderId == removed.id) { folder = null; deck = null; page = "home" }
                         }
                         "deleteDeck" -> pendingDeck?.let { removed ->
                             store.deleteDeck(removed.id)
                             if (deck?.id == removed.id) { deck = null; page = if (folder?.id == removed.folderId) "decks" else "home" }
                         }
                        "import" -> {
                            val pairs = parsePairs(importText)
                            if (pairs.isEmpty()) { notify("Нет строк со словом и переводом"); return@TextButton }
                             val deckId = requireNotNull(deck).id
                             pairs.forEach { store.addCard(deckId, it.word, it.translation) }
                            notify("Добавлено: ${pairs.size}")
                        }
                        "restore" -> { restore.launch(arrayOf("application/json", "text/*")); dialog = ""; return@TextButton }
                    }
                     update(); dialog = ""; cardTranslation = ""; editingCard = null; pendingDelete = null; pendingFolder = null; pendingDeck = null
                } catch (e: Exception) { notify("Не удалось сохранить: ${e.message}") }
            }, enabled = when (dialog) {
                "card", "edit" -> input.isNotBlank() && cardTranslation.isNotBlank()
                "folder", "deck" -> input.isNotBlank()
                else -> true
             }) { Text(when (dialog) { "restore" -> "Выбрать файл"; "delete", "deleteFolder", "deleteDeck" -> "Удалить"; "translationInfo" -> "Понятно"; else -> "Сохранить" }) }
        }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Отмена") } })
        }
    }
}

@Composable private fun Heading(title: String, subtitle: String) {
    Text(title, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Text(subtitle, color = Muted, fontSize = 14.sp)
    Spacer(Modifier.height(20.dp))
}

@Composable private fun Hero(label: String, amount: String, hint: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 26.dp), colors = CardDefaults.cardColors(containerColor = Lavender, contentColor = Ink), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(24.dp)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(amount, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Text(hint, fontSize = 14.sp)
        }
    }
}
@Composable private fun Back(label: String, onClick: () -> Unit) { TextButton(onClick = onClick) { Text("← $label") } }
@Composable private fun SearchField(value: String, onChange: (String) -> Unit) {
    TextField(value, onChange, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Поиск", color = Color.DarkGray) }, leadingIcon = { AppIcon("search", color = Ink) }, singleLine = true,
        shape = RoundedCornerShape(50.dp), colors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedTextColor = Ink, unfocusedTextColor = Ink, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Ink))
}
@Composable private fun FolderTile(title: String, subtitle: String, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    val (source, motion) = rememberTileMotion()
    Card(colors = CardDefaults.cardColors(containerColor = Mint, contentColor = Ink), shape = RoundedCornerShape(26.dp), modifier = motion.width(146.dp).height(125.dp)) {
        Box {
            Column(Modifier.fillMaxSize().clickable(interactionSource = source, indication = null, onClick = onClick).padding(16.dp)) {
                Surface(color = Color.White.copy(alpha = .52f), shape = RoundedCornerShape(50.dp)) { Box(Modifier.size(47.dp), contentAlignment = Alignment.Center) { AppIcon("folder", color = Ink) } }
                Spacer(Modifier.weight(1f))
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, color = Ink.copy(alpha = .65f))
            }
            if (onDelete != null) IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd).semantics { contentDescription = "Удалить папку $title" }) {
                Text("×", color = Ink, fontSize = 24.sp)
            }
        }
    }
}
@Composable private fun DeckTile(title: String, subtitle: String, onClick: () -> Unit, onDelete: (() -> Unit)? = null, modifier: Modifier = Modifier.fillMaxWidth().height(142.dp)) {
    val (source, motion) = rememberTileMotion()
    Card(colors = CardDefaults.cardColors(containerColor = Lemon, contentColor = Ink), shape = RoundedCornerShape(26.dp), modifier = motion.then(modifier)) {
        Box {
            Column(Modifier.fillMaxSize().clickable(interactionSource = source, indication = null, onClick = onClick).padding(20.dp)) {
                AppIcon("folder", color = Ink)
                Spacer(Modifier.weight(1f))
                Text(title, fontSize = 20.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 13.sp, color = Ink.copy(alpha = .65f))
            }
            if (onDelete != null) IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd).semantics { contentDescription = "Удалить колоду $title" }) {
                Text("×", color = Ink, fontSize = 24.sp)
            }
        }
    }
}
@Composable private fun LanguageRow(value: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState())) {
        listOf("en", "ru", "de", "es", "fr").forEach { lang ->
            FilterChip(selected = value == lang, onClick = { onChange(lang) }, label = { Text(lang.uppercase()) })
        }
    }
}

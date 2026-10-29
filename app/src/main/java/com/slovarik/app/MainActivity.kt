package com.slovarik.app

import android.Manifest
import android.app.TimePickerDialog
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import java.util.Locale

private val Ink = Night

private data class PageSnapshot(val name: String, val folder: Folder?, val deck: Deck?, val initialCardId: Long? = null, val mode: String = "Карточки")

private fun pageDepth(page: String) = when (page) {
    "home", "settings" -> 0
    "decks", "trash" -> 1
    "cards" -> 2
    else -> 3
}

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private var cloudSync: CloudSyncController? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    override fun attachBaseContext(base: Context) { super.attachBaseContext(InterfaceLanguage.wrap(base)) }
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
                val folder = store.addFolder(getString(R.string.main_seed_folder))
                val deck = store.addDeck(folder, getString(R.string.main_seed_deck))
                listOf("hello" to "привет", "journey" to "путешествие", "curious" to "любопытный", "garden" to "сад", "bright" to "яркий", "remember" to "помнить").forEach { (word, translation) -> store.addCard(deck, word, translation) }
            }
            prefs.edit().putBoolean("seeded", true).apply()
        }
        UserProfileBridge.migrateLegacy(this, store)
        UserProfileBridge.project(this, store)
        if (resources.configuration.locales[0].language != InterfaceLanguage.current(this)) {
            recreate()
            return
        }
        cloudSync = CloudSyncController(this, store)
        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        setContent { App(store, { word, language ->
            if (!ttsReady) {
                Toast.makeText(this, R.string.main_tts_starting, Toast.LENGTH_SHORT).show()
            } else if (language.startsWith("en", ignoreCase = true)) {
                tts?.setLanguage(Locale.US)
                val americanVoice = tts?.voices?.filter { it.locale.language == "en" && it.locale.country == "US" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                    ?.sortedWith(compareBy<android.speech.tts.Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality }.thenBy { it.latency })?.firstOrNull()
                if (americanVoice == null || tts?.setVoice(americanVoice) != TextToSpeech.SUCCESS) {
                    Toast.makeText(this, R.string.main_tts_us, Toast.LENGTH_LONG).show()
                } else tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, "card")
            } else if (!ttsReady || (tts?.setLanguage(Locale.forLanguageTag(language)) ?: -1) < 0) {
                Toast.makeText(this, R.string.main_tts_unavailable, Toast.LENGTH_LONG).show()
            } else tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, "card")
        }, cloudSync = cloudSync) }
    }
    override fun onDestroy() { cloudSync?.close(); tts?.stop(); tts?.shutdown(); store.close(); super.onDestroy() }
}

@Composable
internal fun App(store: Store, speak: (String, String) -> Unit, cloudSync: CloudSyncController? = null) {
    val context = LocalContext.current
    val bundledTranslation = remember(context) { TranslationController(context) }
    DisposableEffect(bundledTranslation) { onDispose { bundledTranslation.close() } }
    var page by rememberSaveable { mutableStateOf("home") }
    LaunchedEffect(Unit) { if (page !in listOf("home", "settings")) page = "home" }
    var folder by remember { mutableStateOf<Folder?>(null) }
    var deck by remember { mutableStateOf<Deck?>(null) }
    var forcedStudyCardId by remember { mutableStateOf<Long?>(null) }
    var studyMode by remember { mutableStateOf("Карточки") }
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
    var randomDirection by remember { mutableStateOf(preferences.getBoolean("randomDirection", false)) }
    var deckFolderLocked by remember { mutableStateOf(false) }
    val update: () -> Unit = { tick++; Unit }
    val folders = remember(tick) { store.folders() }
    val availableUndo = remember(tick) { store.lastReviewUndo() }
    val notify: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    val message: (Int) -> Unit = { notify(context.getString(it)) }
    var reminderRefresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var appResumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (cloudSync != null) UserProfileBridge.project(context, store)
                reminderRefresh++; appResumed = true
            }
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) appResumed = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val incomingRevision by CloudSyncRuntime.incomingRevision.collectAsState()
    LaunchedEffect(cloudSync, incomingRevision) {
        if (cloudSync != null && incomingRevision > 0) {
            language = preferences.getString("language", "en") ?: "en"
            target = preferences.getString("target", "ru") ?: "ru"
            randomDirection = preferences.getBoolean("randomDirection", false)
            reminderRefresh++
            val activity = context as? Activity
            if (activity != null && activity.resources.configuration.locales[0].language != InterfaceLanguage.current(activity)) {
                activity.recreate()
                return@LaunchedEffect
            }
            tick++
            val activeFolders = store.folders()
            folder = folder?.let { previous -> activeFolders.firstOrNull { it.id == previous.id } }
            deck = deck?.let { previous -> folder?.let { store.decks(it.id).firstOrNull { item -> item.id == previous.id } } }
            if ((page == "decks" && folder == null) || (page in listOf("cards", "study", "practice") && deck == null)) page = "home"
        }
    }
    SideEffect { cloudSync?.setStudyActive(page == "study" || page == "practice") }
    DisposableEffect(cloudSync) {
        onDispose { cloudSync?.setStudyActive(false) }
    }
    LaunchedEffect(cloudSync, page, appResumed) {
        if (cloudSync == null || !appResumed || page in listOf("study", "practice")) return@LaunchedEffect
        while (true) { cloudSync.syncNow(); delay(45_000) }
    }
    LaunchedEffect(cloudSync, tick) {
        if (cloudSync != null && appResumed && page !in listOf("study", "practice")) {
            delay(1_000)
            cloudSync.syncNow()
        }
    }
    val undoReview: () -> Unit = {
        try {
            val restored = availableUndo?.let { store.undoLastReview(it.eventId) }
            if (restored == null) message(R.string.main_undo_unavailable)
            else {
                val parent = store.folders().firstOrNull { f -> store.decks(f.id).any { it.id == restored.deckId } }
                val restoredDeck = parent?.let { store.decks(it.id).firstOrNull { d -> d.id == restored.deckId } }
                if (parent != null && restoredDeck != null) {
                    cloudSync?.setStudyActive(true)
                    folder = parent; deck = restoredDeck; forcedStudyCardId = restored.id; studyMode = "Карточки"; page = "study"
                }
                message(R.string.main_undo_done)
            }
            update()
        } catch (error: Exception) { notify(context.getString(R.string.main_undo_error, error.message)) }
    }
    val saveInput: () -> Unit = {
        try {
            when (dialog) {
                "folder" -> if (input.isNotBlank()) store.addFolder(input)
                "deck" -> if (input.isNotBlank()) {
                    val parentId = selectedFolderId?.takeIf { id -> folders.any { it.id == id } }
                        ?: if (folders.isEmpty()) store.addFolder(context.getString(R.string.main_seed_folder)) else error(context.getString(R.string.main_select_folder))
                    store.addDeck(parentId, input)
                }
                "card" -> if (input.isNotBlank() && cardTranslation.isNotBlank()) store.addCard(requireNotNull(deck).id, input, cardTranslation)
                "edit" -> store.updateCard(requireNotNull(editingCard).id, input, cardTranslation)
            }
            update(); dialog = ""; cardTranslation = ""; editingCard = null
        } catch (error: Exception) { notify(context.getString(R.string.main_save_error, error.message)) }
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            importText = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            dialog = "import"
        } catch (e: Exception) { notify(context.getString(R.string.main_read_error, e.message)) }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            store.restore(text); folder = null; deck = null; page = "home"; update(); message(R.string.main_restored)
        } catch (e: Exception) { notify(context.getString(R.string.main_restore_error, e.message)) }
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(store.backup().toByteArray(Charsets.UTF_8)) }
                ?: error(context.getString(R.string.main_no_file_access))
            message(R.string.main_backup_saved)
        } catch (e: Exception) { notify(context.getString(R.string.main_save_error, e.message)) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && notificationsAllowed(context)) {
            val prefs = context.getSharedPreferences("settings", 0)
            setReminder(context, prefs.getInt("hour", 19), prefs.getInt("minute", 0), store)
            message(R.string.main_reminder_on)
        } else if (granted) context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        else message(R.string.main_permission_denied)
        reminderRefresh++
    }
    val screen = PageSnapshot(page, folder, deck, if (page == "study") forcedStudyCardId else null, if (page == "study" || page == "practice") studyMode else "Карточки")
    BackHandler(enabled = dialog.isEmpty() && page != "home") {
        page = when (page) { "study", "practice" -> "cards"; "cards" -> "decks"; "trash" -> "settings"; else -> "home" }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Lavender, onPrimary = Night, secondary = Mint, background = Night, surface = Color(0xFF242424), onSurface = Color.White)) {
        Scaffold(containerColor = Night) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                var menuExpanded by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.semantics { contentDescription = context.getString(R.string.main_menu) }) {
                        Text("⋮", fontSize = 26.sp, color = Color.White)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.main_settings)) }, onClick = { menuExpanded = false; page = "settings" })
                        if (page == "cards" && deck != null) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.main_paste_import)) }, onClick = { menuExpanded = false; importText = ""; dialog = "import" })
                            DropdownMenuItem(text = { Text(stringResource(R.string.main_file_import)) }, onClick = { menuExpanded = false; csv.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.main_delete_deck)) }, onClick = { menuExpanded = false; pendingDeck = deck; dialog = "deleteDeck" })
                        }
                        if (page == "decks" && folder != null) DropdownMenuItem(text = { Text(stringResource(R.string.main_delete_folder)) }, onClick = { menuExpanded = false; pendingFolder = folder; dialog = "deleteFolder" })
                    }
                    }
                }
                AnimatedContent(targetState = screen, modifier = Modifier.weight(1f), contentKey = { listOf(it.name, it.deck?.id, it.folder?.id, it.initialCardId, it.mode) },
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
                         Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                             Text(stringResource(R.string.main_app_name), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                             Spacer(Modifier.height(20.dp))
                              if (availableUndo != null) TextButton(onClick = undoReview) { Text(stringResource(R.string.main_undo)) }
                              Text(stringResource(R.string.main_my_decks), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                             Spacer(Modifier.height(10.dp))
                             Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                 allDecks.forEach { item ->
                                        DeckTile(item.name, progressLabel(store.cards(item.id)), { deck = item; folder = folders.firstOrNull { it.id == item.folderId }; page = "cards" }, modifier = Modifier.width(170.dp).heightIn(min = 172.dp))
                                  }
                                   DeckTile(stringResource(R.string.main_new_deck), stringResource(R.string.main_add), { deckFolderLocked = false; input = ""; selectedFolderId = folders.firstOrNull()?.id; dialog = "deck" }, modifier = Modifier.width(170.dp).heightIn(min = 172.dp))
                             }
                             Spacer(Modifier.height(20.dp))
                              Text(stringResource(R.string.main_my_folders), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                             Spacer(Modifier.height(10.dp))
                             Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                  folders.forEach { item -> FolderTile(item.name, progressLabel(store.decks(item.id).flatMap { store.cards(it.id) }), { folder = item; page = "decks" }) }
                                  FolderTile(stringResource(R.string.main_new_folder), stringResource(R.string.main_add), { input = ""; dialog = "folder" })
                             }
                             Spacer(Modifier.height(18.dp))
                        }
                    }
                     "decks" -> {
                          Back(stringResource(R.string.main_folders)) { page = "home" }
                          Heading(shownFolder?.name.orEmpty(), stringResource(R.string.main_choose_deck))
                          if (shownFolder != null) Text(progressLabel(store.decks(shownFolder.id).flatMap { store.cards(it.id) }), color = Muted)
                          SearchField(search) { search = it }
                          Spacer(Modifier.height(10.dp))
                          if (shownFolder != null) Button(
                              onClick = { deckFolderLocked = true; input = ""; selectedFolderId = shownFolder.id; dialog = "deck" },
                              modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                              colors = ButtonDefaults.buttonColors(containerColor = Lemon, contentColor = Ink)
                          ) {
                              Text(stringResource(R.string.main_new_deck_here, shownFolder.name), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                          }
                          Spacer(Modifier.height(10.dp))
                        val matchingDecks = shownFolder?.let { store.decks(it.id) }.orEmpty().filter { it.name.contains(search, ignoreCase = true) }
                         LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(matchingDecks) { item ->
                                   DeckTile(item.name, progressLabel(store.cards(item.id)), { deck = item; page = "cards" })
                            }
                        }
                    }
                    "cards" -> if (currentDeck != null) {
                         Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                         Back(shownFolder?.name.orEmpty()) { page = "decks" }
                          Heading(currentDeck.name, stringResource(R.string.main_local_words))
                         val cards = store.cards(currentDeck.id)
                         val due = cards.count { it.review.due <= System.currentTimeMillis() }
                         Text(progressLabel(cards), color = Muted)
                         Spacer(Modifier.height(8.dp))
                           DeckModeRow(enabled = cards.isNotEmpty()) { mode ->
                               cloudSync?.setStudyActive(true)
                               forcedStudyCardId = null; studyMode = mode
                              page = if (mode == "Пары" || due == 0) "practice" else "study"
                          }
                         Spacer(Modifier.height(12.dp))
                          Button(onClick = { input = ""; cardTranslation = ""; dialog = "card" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), colors = ButtonDefaults.buttonColors(containerColor = Lavender, contentColor = Ink)) { Text(stringResource(R.string.main_add_card), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                         Spacer(Modifier.height(12.dp))
                         SearchField(search) { search = it }
                         Spacer(Modifier.height(12.dp))
                          DeckMosaic(cards.filter { it.word.contains(search, true) || it.translation.contains(search, true) }) { card ->
                              editingCard = card; input = card.word; cardTranslation = card.translation; dialog = "edit"
                          }
                          Spacer(Modifier.height(20.dp))
                         }
                    }
                    "study", "practice" -> if (currentDeck != null) {
                        Back(currentDeck.name) { page = "cards" }
                          Study(store, currentDeck, language, speak, update, Modifier.weight(1f), practice = shown.name == "practice", active = screen == shown, initialCardId = shown.initialCardId, initialMode = shown.mode, randomDirection = randomDirection)
                    }
                    "trash" -> TrashScreen(store, tick, update, { page = "settings" }, notify)
                    "settings" -> {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                         Heading(stringResource(R.string.main_settings), "")
                         Text(stringResource(R.string.main_interface_language), fontWeight = FontWeight.Bold)
                         val interfaceLanguage = InterfaceLanguage.current(context)
                         Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                             listOf("ru" to "Русский", "en" to "English").forEach { (code, label) ->
                                  FilterChip(selected = interfaceLanguage == code, onClick = { if (interfaceLanguage != code) { InterfaceLanguage.set(context, code, store); update(); (context as? Activity)?.recreate() } }, label = { Text(label) })
                             }
                         }
                         Text(stringResource(R.string.main_source_language), fontWeight = FontWeight.Bold)
                          LanguageRow(language) { UserProfileBridge.setSetting(context, store, "language", it); language = it; update() }
                         Text(stringResource(R.string.main_target_language), fontWeight = FontWeight.Bold)
                          LanguageRow(target) { UserProfileBridge.setSetting(context, store, "target", it); target = it; update() }
                         Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                             Column(Modifier.weight(1f)) {
                                 Text(stringResource(R.string.main_random_direction), fontWeight = FontWeight.Bold)
                                 Text(stringResource(R.string.main_random_direction_hint), color = Muted, fontSize = 12.sp)
                             }
                             Switch(checked = randomDirection, onCheckedChange = {
                                  UserProfileBridge.setSetting(context, store, "randomDirection", it)
                                  randomDirection = it
                                  update()
                             })
                         }
                         TextButton(onClick = { dialog = "translationInfo" }) { Text(stringResource(R.string.main_translation_info_action)) }
                         Spacer(Modifier.height(12.dp))
                         if (cloudSync != null) {
                             CloudSyncSection(cloudSync)
                             Spacer(Modifier.height(20.dp))
                         }
                         Spacer(Modifier.height(16.dp))
                         Text(stringResource(R.string.main_reminders), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                         val prefs = context.getSharedPreferences("settings", 0)
                         val enabled = reminderEnabled(context) && notificationsAllowed(context)
                         @Suppress("UNUSED_VARIABLE") val refresh = reminderRefresh
                         Text(stringResource(R.string.main_reminder_time, prefs.getInt("hour", 19), prefs.getInt("minute", 0)))
                         Row {
                             TextButton(onClick = {
                                 TimePickerDialog(context, { _, h, m ->
                                      UserProfileBridge.saveReminder(context, store, prefs.getBoolean("reminder", false), h, m)
                                      update()
                                      reminderRefresh++
                                 }, prefs.getInt("hour", 19), prefs.getInt("minute", 0), true).show()
                             }) { Text(stringResource(R.string.main_choose_time)) }
                             TextButton(onClick = {
                                  if (enabled) { cancelReminder(context, store); update(); message(R.string.main_reminder_off) }
                                  else if (!notificationsAllowed(context)) {
                                       UserProfileBridge.saveReminder(context, store, true, prefs.getInt("hour", 19), prefs.getInt("minute", 0)); update()
                                       if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                      else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                  } else { setReminder(context, prefs.getInt("hour", 19), prefs.getInt("minute", 0), store); update(); message(R.string.main_reminder_on) }
                                 reminderRefresh++
                             }) { Text(stringResource(if (enabled) R.string.main_disable else R.string.main_enable)) }
                         }
                         HorizontalDivider()
                         TextButton(onClick = { page = "trash" }) { Text(stringResource(R.string.main_trash)) }
                         TextButton(onClick = { backup.launch("slovarik-backup.json") }) { Text(stringResource(R.string.main_backup)) }
                          TextButton(onClick = {
                              if (cloudSync != null && SyncLocalStore(store).boundAccount() != null) message(R.string.sync_restore_blocked)
                              else dialog = "restore"
                          }) { Text(stringResource(R.string.main_restore)) }
                        }
                    }
                }
                }
            }
        }
        }
        val inputDialogs = setOf("folder", "deck", "card", "edit")
        if (dialog in inputDialogs) MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF6849A0), surface = Color(0xFFF7F5F0), onSurface = Ink)) {
            // Results from a dismissed editor must never populate a newly opened one.
            var editorActive by remember(dialog) { mutableStateOf(true) }
            DisposableEffect(dialog) { onDispose { editorActive = false } }
            Dialog(onDismissRequest = { dialog = "" }) {
                Surface(shape = RoundedCornerShape(30.dp), color = Color(0xFFF7F5F0), tonalElevation = 4.dp, shadowElevation = 12.dp) {
                    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text(stringResource(when (dialog) {
                            "folder" -> R.string.main_new_folder; "deck" -> R.string.main_new_deck; "card" -> R.string.main_new_card; else -> R.string.main_edit_card
                        }), fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text(stringResource(when (dialog) {
                            "folder" -> R.string.main_folder_hint; "deck" -> R.string.main_deck_hint; "card" -> R.string.main_card_hint; else -> R.string.main_edit_hint
                        }), fontSize = 13.sp, color = Ink.copy(alpha = .58f))
                        Spacer(Modifier.height(20.dp))
                        when (dialog) {
                            "folder" -> EditorField(input, { input = it }, stringResource(R.string.main_name))
                            "deck" -> {
                                EditorField(input, { input = it }, stringResource(R.string.main_name))
                                Spacer(Modifier.height(12.dp))
                                when {
                                    deckFolderLocked -> {
                                        val locked = folders.firstOrNull { it.id == selectedFolderId }
                                        if (locked != null) Surface(color = Color.White, shape = RoundedCornerShape(14.dp)) {
                                            Text(stringResource(R.string.main_folder_value, locked.name), Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = Ink, fontSize = 13.sp)
                                        } else Text(stringResource(R.string.main_select_folder), color = Coral, fontSize = 12.sp)
                                    }
                                    folders.isEmpty() -> Text(stringResource(R.string.main_auto_folder), color = Ink.copy(alpha = .58f), fontSize = 12.sp)
                                    else -> {
                                        Text(stringResource(R.string.main_deck_folder), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink)
                                        folders.forEach { choice ->
                                            Row(Modifier.fillMaxWidth().clickable { selectedFolderId = choice.id }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(selected = selectedFolderId == choice.id, onClick = { selectedFolderId = choice.id })
                                                Text(choice.name, color = Ink)
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {
                                EditorField(input, { input = it }, stringResource(R.string.main_word))
                                Spacer(Modifier.height(10.dp))
                                EditorField(cardTranslation, { cardTranslation = it }, stringResource(R.string.main_translation))
                                val direction = translationDirection(input, language, target)
                                var translating by remember(dialog) { mutableStateOf(false) }
                                Spacer(Modifier.height(4.dp))
                                TextButton(enabled = input.isNotBlank() && direction != null && !translating, onClick = {
                                    if (direction == null) return@TextButton
                                    translating = true
                                    val requestedWord = input.trim()
                                    val requestedCard = editingCard?.id
                                    val requestedDialog = dialog
                                    bundledTranslation.translate(requestedWord, direction.first, direction.second) { result ->
                                        if (editorActive && dialog == requestedDialog && input.trim() == requestedWord && editingCard?.id == requestedCard) {
                                            result.onSuccess { cardTranslation = it }.onFailure { notify(context.getString(R.string.main_translation_error, it.message)) }
                                        }
                                        translating = false
                                    }
                                }) {
                                    if (translating) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF6849A0))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(stringResource(if (translating) R.string.main_translating else R.string.main_translate))
                                }
                                if (direction != null) Text(stringResource(R.string.main_auto_direction, direction.first.uppercase(), direction.second.uppercase()), fontSize = 12.sp, color = Ink.copy(alpha = .58f))
                                if (dialog == "edit") Row {
                                    TextButton(onClick = { editingCard?.let { speak(it.word, language) } }) { Text(stringResource(R.string.main_pronounce)) }
                                    TextButton(onClick = { pendingDelete = editingCard; dialog = "delete" }) { Text(stringResource(R.string.main_delete)) }
                                }
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.main_cancel)) }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = saveInput,
                                enabled = when (dialog) {
                                    "card", "edit" -> input.isNotBlank() && cardTranslation.isNotBlank()
                                    else -> input.isNotBlank()
                                },
                                shape = RoundedCornerShape(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6849A0), contentColor = Color.White)
                            ) { Text(stringResource(R.string.main_save)) }
                        }
                    }
                }
            }
        }
        if (dialog.isNotEmpty() && dialog !in inputDialogs) MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF6849A0), surface = Color(0xFFF7F5F0), onSurface = Ink)) {
            AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(when (dialog) {
             "translationInfo" -> context.getString(R.string.main_translation_info_title)
              "delete" -> context.getString(R.string.main_delete_card_title); "deleteFolder" -> context.getString(R.string.main_delete_folder_title); "deleteDeck" -> context.getString(R.string.main_delete_deck_title); "import" -> context.getString(R.string.main_import_title); else -> context.getString(R.string.main_restore_title)
        }) }, text = {
            when (dialog) {
                 "translationInfo" -> Text(stringResource(R.string.main_attribution), modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()))
                 "import" -> Column { Text(stringResource(R.string.main_import_hint)); Spacer(Modifier.height(8.dp)); OutlinedTextField(importText, { importText = it }, label = { Text(stringResource(R.string.main_paste_words)) }, minLines = 5) }
                 "restore" -> Text(stringResource(R.string.main_restore_warning))
                 "delete" -> Text(stringResource(R.string.main_delete_card_warning, pendingDelete?.word.orEmpty()))
                 "deleteFolder" -> Text(stringResource(R.string.main_delete_folder_warning, pendingFolder?.name.orEmpty()))
                 "deleteDeck" -> Text(stringResource(R.string.main_delete_deck_warning, pendingDeck?.name.orEmpty()))
            }
        }, confirmButton = {
            TextButton(onClick = {
                try {
                    when (dialog) {
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
                             if (pairs.isEmpty()) { message(R.string.main_no_pairs); return@TextButton }
                             val deckId = requireNotNull(deck).id
                             pairs.forEach { store.addCard(deckId, it.word, it.translation) }
                             notify(context.getString(R.string.main_added, pairs.size))
                        }
                        "restore" -> { restore.launch(arrayOf("application/json", "text/*")); dialog = ""; return@TextButton }
                    }
                     update(); dialog = ""; pendingDelete = null; pendingFolder = null; pendingDeck = null
                 } catch (e: Exception) { notify(context.getString(R.string.main_save_error, e.message)) }
            }) { Text(stringResource(when (dialog) { "restore" -> R.string.main_select_file; "delete", "deleteFolder", "deleteDeck" -> R.string.main_to_trash; "translationInfo" -> R.string.main_ok; else -> R.string.main_save })) }
        }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.main_cancel)) } })
        }
    }
}

@Composable private fun progressLabel(cards: List<Card>): String {
    val reviewed = cards.count { it.review.repetitions > 0 && it.review.learningStep == 0 }
    val due = cards.count { it.review.due <= System.currentTimeMillis() }
    return stringResource(R.string.main_progress, reviewed, cards.size, due)
}

@Composable internal fun DeckMosaic(cards: List<Card>, onEdit: (Card) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val limit = maxWidth
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cards.forEach { card ->
                Card(onClick = { onEdit(card) }, shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Lemon, contentColor = Ink),
                    modifier = Modifier.widthIn(max = limit).testTag("word-tile-${card.id}")) {
                    Column(Modifier.widthIn(max = limit).padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(card.word, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 21.sp, softWrap = true)
                        Text(card.translation, fontSize = 13.sp, lineHeight = 17.sp, softWrap = true, color = Ink.copy(alpha = .72f))
                    }
                }
            }
        }
    }
}

/** All four choices share one row; only the label scales to fit a narrow button. */
@Composable internal fun DeckModeRow(enabled: Boolean, onModeSelected: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Карточки", "Тест", "Ввод", "Пары").forEachIndexed { index, mode ->
            val label = stringResource(when (index) {
                1 -> R.string.main_mode_test
                2 -> R.string.main_mode_input
                3 -> R.string.main_mode_match
                else -> R.string.main_mode_cards
            })
            Button(onClick = { onModeSelected(mode) }, enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("mode-$index"),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = listOf(Lavender, Mint, Lemon, Coral)[index], contentColor = Ink)) {
                BasicText(label,
                    style = TextStyle(fontWeight = FontWeight.Medium, letterSpacing = 0.sp, textAlign = TextAlign.Center),
                    color = { Ink }, maxLines = 1, softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 4.sp, maxFontSize = 14.sp, stepSize = 0.5.sp))
            }
        }
    }
}

@Composable private fun Heading(title: String, subtitle: String) {
    Text(title, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
    if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 14.sp)
    Spacer(Modifier.height(20.dp))
}

@Composable private fun Back(label: String, onClick: () -> Unit) { TextButton(onClick = onClick) { Text(stringResource(R.string.main_back, label)) } }
@Composable private fun SearchField(value: String, onChange: (String) -> Unit) {
    TextField(value, onChange, modifier = Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.main_search), color = Color.DarkGray) }, leadingIcon = { AppIcon("search", color = Ink) }, singleLine = true,
        shape = RoundedCornerShape(50.dp), colors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedTextColor = Ink, unfocusedTextColor = Ink, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Ink))
}
@Composable private fun FolderTile(title: String, subtitle: String, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    val (source, motion) = rememberTileMotion()
    val deleteDescription = stringResource(R.string.main_delete_folder_accessibility, title)
    Card(colors = CardDefaults.cardColors(containerColor = Mint, contentColor = Ink), shape = RoundedCornerShape(26.dp), modifier = motion.width(170.dp).heightIn(min = 172.dp)) {
        Box {
            Column(Modifier.fillMaxWidth().clickable(interactionSource = source, indication = null, onClick = onClick).padding(16.dp)) {
                Surface(color = Color.White.copy(alpha = .52f), shape = RoundedCornerShape(50.dp)) { Box(Modifier.size(47.dp), contentAlignment = Alignment.Center) { AppIcon("folder", color = Ink) } }
                Spacer(Modifier.height(12.dp))
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle.replace(" · ", "\n"), fontSize = 12.sp, color = Ink.copy(alpha = .65f))
            }
            if (onDelete != null) IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd).semantics { contentDescription = deleteDescription }) {
                Text("×", color = Ink, fontSize = 24.sp)
            }
        }
    }
}
@Composable private fun DeckTile(title: String, subtitle: String, onClick: () -> Unit, onDelete: (() -> Unit)? = null, modifier: Modifier = Modifier.fillMaxWidth().heightIn(min = 142.dp)) {
    val (source, motion) = rememberTileMotion()
    val deleteDescription = stringResource(R.string.main_delete_deck_accessibility, title)
    Card(colors = CardDefaults.cardColors(containerColor = Lemon, contentColor = Ink), shape = RoundedCornerShape(26.dp), modifier = motion.then(modifier)) {
        Box {
            Column(Modifier.fillMaxWidth().clickable(interactionSource = source, indication = null, onClick = onClick).padding(16.dp)) {
                AppIcon("folder", color = Ink)
                Spacer(Modifier.height(12.dp))
                Text(title, fontSize = 20.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle.replace(" · ", "\n"), fontSize = 13.sp, color = Ink.copy(alpha = .65f))
            }
            if (onDelete != null) IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd).semantics { contentDescription = deleteDescription }) {
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

/** Light rounded field used by the modern create/edit dialogs. */
@Composable private fun EditorField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Ink, unfocusedTextColor = Ink,
            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
            focusedBorderColor = Color(0xFF6849A0), unfocusedBorderColor = Ink.copy(alpha = .18f),
            focusedLabelColor = Color(0xFF6849A0), unfocusedLabelColor = Ink.copy(alpha = .6f),
            cursorColor = Color(0xFF6849A0)))
}

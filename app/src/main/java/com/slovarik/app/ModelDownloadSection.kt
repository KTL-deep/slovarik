package com.slovarik.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager

@Composable
internal fun ModelDownloadSection(compact: Boolean = false) {
    val context = LocalContext.current
    val revision by TranslationModels.revision.collectAsState()
    val flow = remember(context) { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(TranslationModels.workName) }
    val jobs by flow.collectAsState(initial = emptyList())
    val active = jobs.firstOrNull { !it.state.isFinished }
    val installed = remember(jobs, revision) { TranslationModels.isInstalled(context) }
    var details by remember { mutableStateOf(false) }
    Text(stringResource(R.string.model_title), style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (compact) {
        Text(stringResource(R.string.welcome_model_hint), color = Muted, fontSize = 13.sp)
        TextButton(onClick = { details = true }) { Text(stringResource(R.string.welcome_details)) }
    } else {
        Text(stringResource(R.string.model_description), color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
    }
    val status = when {
        installed -> R.string.model_installed
        active != null -> R.string.model_downloading
        TranslationModels.needsRepair(context) -> R.string.model_repair
        jobs.firstOrNull()?.state == WorkInfo.State.FAILED -> R.string.model_failed
        jobs.firstOrNull()?.state == WorkInfo.State.CANCELLED -> R.string.model_cancelled
        else -> R.string.model_not_installed
    }
    if (!compact || installed || active != null || status != R.string.model_not_installed) {
        Text(stringResource(status), color = if (jobs.firstOrNull()?.state == WorkInfo.State.FAILED) Coral else Muted, fontSize = if (compact) 13.sp else MaterialTheme.typography.bodyLarge.fontSize)
    }
    if (active != null) {
        val percent = active.progress.getInt("percent", 0)
        Text(stringResource(R.string.model_progress, percent), color = Muted, fontSize = 13.sp)
        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { TranslationModels.cancel(context) }) { Text(stringResource(R.string.model_cancel)) }
    } else if (!installed) {
        Button(onClick = { TranslationModels.start(context) }) { Text(stringResource(R.string.model_download)) }
    }
    if (details) AlertDialog(
        onDismissRequest = { details = false },
        title = { Text(stringResource(R.string.model_title)) },
        text = { Text(stringResource(R.string.model_description), modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.main_ok)) } }
    )
}

@Composable
private fun WelcomeCards() {
    Box(Modifier.fillMaxWidth().height(168.dp).testTag("welcome-illustration"), contentAlignment = Alignment.Center) {
        Box(Modifier.offset(x = (-36).dp, y = 6.dp).rotate(-12f).size(144.dp, 128.dp).clip(RoundedCornerShape(22.dp)).background(Mint)) {
            Text("RU", Modifier.align(Alignment.TopStart).padding(16.dp), color = Night, fontWeight = FontWeight.Bold)
            Text("дом", Modifier.align(Alignment.BottomStart).padding(16.dp), color = Night, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.offset(x = 42.dp, y = (-6).dp).rotate(9f).size(144.dp, 128.dp).clip(RoundedCornerShape(22.dp)).background(Lavender)) {
            Text("EN", Modifier.align(Alignment.TopStart).padding(16.dp), color = Night, fontWeight = FontWeight.Bold)
            Text("home", Modifier.align(Alignment.BottomStart).padding(16.dp), color = Night, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun WelcomeScreen(cloudSync: CloudSyncController?, onContinue: () -> Unit) {
    val syncState = cloudSync?.state?.collectAsState()?.value
    MaterialTheme(colorScheme = darkColorScheme(primary = Lavender, onPrimary = Night, secondary = Mint, background = Night, surface = androidx.compose.ui.graphics.Color(0xFF242424), onSurface = androidx.compose.ui.graphics.Color.White)) {
        Scaffold(containerColor = Night) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppLogo(Modifier.size(80.dp).testTag("welcome-app-logo"))
                    Column {
                        Text(stringResource(R.string.main_app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.welcome_tagline), color = Muted)
                    }
                }
                Spacer(Modifier.height(10.dp))
                WelcomeCards()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.welcome_choices), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Surface(modifier = Modifier.fillMaxWidth().testTag("welcome-offline-choice"), shape = RoundedCornerShape(20.dp), color = Color(0xFF242424)) {
                    Column(Modifier.padding(16.dp)) { ModelDownloadSection(compact = true) }
                }
                Spacer(Modifier.height(10.dp))
                if (cloudSync != null) {
                    Surface(modifier = Modifier.fillMaxWidth().testTag("welcome-sync-choice"), shape = RoundedCornerShape(20.dp), color = Color(0xFF242424)) {
                        Column(Modifier.padding(16.dp)) { CloudSyncSection(cloudSync, compact = true) }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Text(stringResource(R.string.welcome_online_disclosure), color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("welcome-continue")) {
                    Text(stringResource(if (syncState?.email != null) R.string.welcome_continue_signed_in else R.string.welcome_continue))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

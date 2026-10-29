package com.slovarik.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager

@Composable
internal fun ModelDownloadSection() {
    val context = LocalContext.current
    val revision by TranslationModels.revision.collectAsState()
    val flow = remember(context) { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(TranslationModels.workName) }
    val jobs by flow.collectAsState(initial = emptyList())
    val active = jobs.firstOrNull { !it.state.isFinished }
    val installed = remember(jobs, revision) { TranslationModels.isInstalled(context) }
    Text(stringResource(R.string.model_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.model_description), color = Muted, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(when {
        installed -> R.string.model_installed
        active != null -> R.string.model_downloading
        TranslationModels.needsRepair(context) -> R.string.model_repair
        jobs.firstOrNull()?.state == WorkInfo.State.FAILED -> R.string.model_failed
        jobs.firstOrNull()?.state == WorkInfo.State.CANCELLED -> R.string.model_cancelled
        else -> R.string.model_not_installed
    }), color = if (jobs.firstOrNull()?.state == WorkInfo.State.FAILED) Coral else Muted)
    if (active != null) {
        val percent = active.progress.getInt("percent", 0)
        Text(stringResource(R.string.model_progress, percent), color = Muted, fontSize = 13.sp)
        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { TranslationModels.cancel(context) }) { Text(stringResource(R.string.model_cancel)) }
    } else if (!installed) {
        Button(onClick = { TranslationModels.start(context) }) { Text(stringResource(R.string.model_download)) }
    }
}

@Composable
internal fun WelcomeScreen(cloudSync: CloudSyncController?, onContinue: () -> Unit) {
    val syncState = cloudSync?.state?.collectAsState()?.value
    MaterialTheme(colorScheme = darkColorScheme(primary = Lavender, onPrimary = Night, secondary = Mint, background = Night, surface = androidx.compose.ui.graphics.Color(0xFF242424), onSurface = androidx.compose.ui.graphics.Color.White)) {
        Scaffold(containerColor = Night) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp)) {
                Text("EN  ↔  RU", color = Lavender, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.welcome_subtitle), color = Muted)
                Spacer(Modifier.height(36.dp))
                ModelDownloadSection()
                Spacer(Modifier.height(36.dp))
                if (cloudSync != null) CloudSyncSection(cloudSync)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(if (syncState?.email != null) R.string.welcome_continue_signed_in else R.string.welcome_continue))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

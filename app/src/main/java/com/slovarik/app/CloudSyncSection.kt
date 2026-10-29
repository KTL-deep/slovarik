package com.slovarik.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CloudSyncSection(controller: CloudSyncController) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current.activityOrNull()
    var confirmation by remember { mutableStateOf("") }
    val working = state.phase == CloudSyncPhase.SYNCING
    Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.sync_description), fontSize = 13.sp, color = Muted)
    Spacer(Modifier.height(8.dp))
    state.email?.let { Text(it, fontWeight = FontWeight.Medium) }
    Text(stringResource(when (state.phase) {
        CloudSyncPhase.UNCONFIGURED -> R.string.sync_not_configured
        CloudSyncPhase.SIGNED_OUT -> R.string.sync_signed_out
        CloudSyncPhase.READY -> R.string.sync_ready
        CloudSyncPhase.SYNCING -> R.string.sync_working
        CloudSyncPhase.ACCOUNT_MISMATCH -> R.string.sync_wrong_account
        CloudSyncPhase.ERROR -> R.string.sync_error
    }), color = if (state.phase == CloudSyncPhase.ERROR || state.phase == CloudSyncPhase.ACCOUNT_MISMATCH) Coral else Muted, fontSize = 13.sp)
    if (state.lastSync > 0) Text(
        stringResource(R.string.sync_last_time, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.lastSync))),
        color = Muted, fontSize = 12.sp)
    if (state.email == null) {
        Button(onClick = { confirmation = "connect" }, enabled = state.configured && !working && activity != null) {
            Text(stringResource(R.string.sync_sign_in))
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !working, onClick = { scope.launch { controller.syncNow() } }) { Text(stringResource(R.string.sync_now)) }
            TextButton(enabled = !working, onClick = { confirmation = "disconnect" }) { Text(stringResource(R.string.sync_sign_out)) }
        }
    }
    if (working) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
    if (confirmation.isNotEmpty()) AlertDialog(
        onDismissRequest = { confirmation = "" },
        title = { Text(stringResource(if (confirmation == "connect") R.string.sync_consent_title else R.string.sync_sign_out_title)) },
        text = { Text(stringResource(if (confirmation == "connect") R.string.sync_consent_message else R.string.sync_sign_out_message),
            modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = {
                val connecting = confirmation == "connect"
                confirmation = ""
                scope.launch {
                    if (connecting) activity?.let { controller.signIn(it) }
                    else controller.signOut()
                }
            }) { Text(stringResource(if (confirmation == "connect") R.string.sync_consent_accept else R.string.sync_sign_out)) }
        },
        dismissButton = { TextButton(onClick = { confirmation = "" }) { Text(stringResource(R.string.main_cancel)) } }
    )
}

private tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.activityOrNull()
    else -> null
}

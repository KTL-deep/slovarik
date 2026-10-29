package com.slovarik.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/** Removing from the main UI is reversible. Physical deletion lives only behind this confirmation. */
@Composable
internal fun TrashScreen(store: Store, refresh: Int, onChanged: () -> Unit, onBack: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val entries = remember(store, refresh) { store.trashItems() }
    var pendingPurge by remember { mutableStateOf<TrashItem?>(null) }
    var pendingAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
    TextButton(onClick = onBack) { Text(stringResource(R.string.trash_back)) }
    Text(stringResource(R.string.trash_title), fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.trash_description), color = Muted)
    Spacer(Modifier.height(16.dp))
    if (entries.isEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = Mint, contentColor = Night), shape = RoundedCornerShape(24.dp)) {
            Text(stringResource(R.string.trash_empty), Modifier.padding(24.dp), fontSize = 20.sp)
        }
    } else {
    Button(onClick = { pendingAll = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Night)) {
        Text(stringResource(R.string.trash_delete_all_forever), fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(12.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
        items(entries, key = { "${it.kind}:${it.id}" }) { item ->
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Lavender, contentColor = Night)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(stringResource(when (item.kind) { TrashKind.FOLDER -> R.string.trash_folder; TrashKind.DECK -> R.string.trash_deck; TrashKind.CARD -> R.string.trash_card }), fontSize = 12.sp)
                    Text(item.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.subtitle.isNotEmpty()) Text(stringResource(if (item.kind == TrashKind.CARD) R.string.trash_in_deck else R.string.trash_in_folder, item.subtitle), fontSize = 13.sp)
                    Text(stringResource(R.string.trash_deleted_at, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(item.deletedAt))), fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            try { store.restoreTrash(item.kind, item.id); onChanged() }
                            catch (error: Exception) { onError(context.getString(R.string.trash_restore_error, error.message)) }
                        }, colors = ButtonDefaults.textButtonColors(contentColor = Night)) { Text(stringResource(R.string.trash_restore)) }
                        TextButton(onClick = { pendingPurge = item }, colors = ButtonDefaults.textButtonColors(contentColor = Night)) { Text(stringResource(R.string.trash_delete_forever)) }
                    }
                }
            }
        }
    }
    }
    }
    if (pendingAll) AlertDialog(onDismissRequest = { pendingAll = false },
        title = { Text(stringResource(R.string.trash_delete_all_title)) },
        text = { Text(stringResource(R.string.trash_delete_all_warning)) },
        confirmButton = { TextButton(onClick = {
            pendingAll = false
            try { store.purgeAllTrash(); onChanged() }
            catch (error: Exception) { onError(context.getString(R.string.trash_delete_error, error.message)) }
        }, colors = ButtonDefaults.textButtonColors(contentColor = Coral)) { Text(stringResource(R.string.trash_delete_all_forever)) } },
        dismissButton = { TextButton(onClick = { pendingAll = false }) { Text(stringResource(R.string.trash_cancel)) } })
    pendingPurge?.let { item ->
        AlertDialog(onDismissRequest = { pendingPurge = null },
            title = { Text(stringResource(R.string.trash_confirm_title)) },
            text = { Text(stringResource(R.string.trash_confirm_description, item.title)) },
            confirmButton = { TextButton(onClick = {
                try { store.purgeTrash(item.kind, item.id); pendingPurge = null; onChanged() }
                catch (error: Exception) { onError(context.getString(R.string.trash_delete_error, error.message)) }
            }) { Text(stringResource(R.string.trash_delete_forever)) } },
            dismissButton = { TextButton(onClick = { pendingPurge = null }) { Text(stringResource(R.string.trash_cancel)) } })
    }
}

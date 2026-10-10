package app.pureremote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureremote.net.Keys

/** Text vom Handy in ein Textfeld auf dem Fernseher schicken. */
@Composable
fun TextDialog(vm: RemoteVM, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun send() { if (text.isNotEmpty()) { vm.sendText(text); text = "" } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text an den Fernseher") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Wähle am Fernseher zuerst ein Textfeld aus, z. B. die Suche.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text("Text") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() })
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { send() }, enabled = text.isNotEmpty()) { Text("Senden") }
                    OutlinedButton(onClick = { vm.client.key(Keys.DEL) }) { Text("⌫") }
                    OutlinedButton(onClick = { vm.client.key(Keys.ENTER) }) { Text("Enter") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } }
    )
}

/** App-Schnellstart: eigene Liste von Apps, die per Tipp auf dem Fernseher gestartet werden. */
@Composable
fun AppsDialog(vm: RemoteVM, onDismiss: () -> Unit) {
    val apps by vm.shortcuts.collectAsStateWithLifecycle()
    val currentApp by vm.client.currentApp.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    if (adding) {
        AddAppDialog(
            onAdd = { name, target -> vm.addShortcut(name, target); adding = false },
            onDismiss = { adding = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Apps", Modifier.weight(1f))
                TextButton(onClick = { edit = !edit }) { Text(if (edit) "Fertig" else "Bearbeiten") }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(apps) { i, a ->
                        val trailing: (@Composable () -> Unit)? = if (edit) {
                            @Composable {
                                Row {
                                    IconButton(onClick = { vm.moveShortcutUp(i) }, enabled = i > 0) {
                                        Icon(Icons.Default.KeyboardArrowUp, "Nach oben")
                                    }
                                    IconButton(onClick = { vm.removeShortcut(i) }) { Icon(Icons.Default.Delete, "Entfernen") }
                                }
                            }
                        } else null
                        ListItem(
                            headlineContent = { Text(a.name) },
                            supportingContent = { Text(a.target, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = trailing,
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = !edit) { vm.launch(a); onDismiss() }
                        )
                    }
                }
                val cur = currentApp
                if (cur != null && apps.none { it.target == cur.pkg }) {
                    FilledTonalButton(onClick = { vm.addShortcut(cur.label, cur.pkg) }) {
                        Text("＋ Läuft gerade: ${cur.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = { adding = true }) { Text("App manuell hinzufügen") }
                Text(
                    "Tipp: Öffne eine App am Fernseher, dann kannst du sie hier mit einem Tipp zur Liste hinzufügen. " +
                        "Die Reihenfolge deines Google-TV-Startbildschirms kann die App nicht auslesen.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } }
    )
}

@Composable
fun AddAppDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App hinzufügen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    target, { target = it }, label = { Text("Paketname oder Link") }, singleLine = true,
                    supportingText = { Text("z. B. com.netflix.ninja oder https://www.youtube.com") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, target) }, enabled = name.isNotBlank() && target.isNotBlank()) { Text("Hinzufügen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

/** Gerät umbenennen oder vergessen (entfernt auch die Kopplung in dieser App). */
@Composable
fun RenameDialog(row: DeviceRow, onSave: (String) -> Unit, onForget: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(row.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fernseher") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Text(row.host, style = MaterialTheme.typography.bodySmall)
                if (row.saved) {
                    TextButton(onClick = onForget, contentPadding = PaddingValues(0.dp)) {
                        Text("Gerät vergessen (Kopplung entfernen)", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

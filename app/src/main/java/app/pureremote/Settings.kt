package app.pureremote

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Liste aller Sprachen mit Optionsfeldern. Ein Tipp stellt die Sprache sofort um. */
@Composable
fun LanguagePicker(modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf(Lang.current()) }
    val entries = listOf("" to stringResource(R.string.system_default)) + Lang.options
    LazyColumn(modifier) {
        items(entries, key = { it.first }) { (tag, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = tag == selected, role = Role.RadioButton) {
                        selected = tag
                        Lang.set(tag)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = tag == selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Begrüßung beim ersten Start mit Sprachauswahl. */
@Composable
fun WelcomeScreen(vm: RemoteVM) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(88.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.welcome_text), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        LanguagePicker(Modifier.weight(1f).fillMaxWidth())
        Button(onClick = { vm.finishWelcome() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.continue_btn))
        }
    }
}

/** Einstellungen: Sprache und Handy-Lautstärketasten. */
@Composable
fun SettingsDialog(vm: RemoteVM, onDismiss: () -> Unit) {
    val volumeKeys by vm.volumeKeys.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
                LanguagePicker(Modifier.heightIn(max = 280.dp))
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.volume_keys), Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = volumeKeys, onCheckedChange = { vm.setVolumeKeys(it) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

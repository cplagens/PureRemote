package app.pureremote

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureremote.net.Keys
import app.pureremote.net.PowerState

class MainActivity : ComponentActivity() {
    private val vm: RemoteVM by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App(vm) } } }
    }

    // Die Lautstärketasten des Handys steuern den Fernseher, solange die Fernbedienung verbunden ist
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        vm.handleVolumeKey(keyCode, event.repeatCount) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && vm.volumeKeyActive()) true
        else super.onKeyUp(keyCode, event)
}

@Composable
fun App(vm: RemoteVM) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val err by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    Column(Modifier.systemBarsPadding().padding(16.dp).fillMaxSize()) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        when (val s = screen) {
            is Screen.Devices -> Devices(vm)
            is Screen.Code -> CodeEntry(vm, s)
            is Screen.Remote -> RemotePad(vm, s)
        }
    }
}

@Composable
fun Devices(vm: RemoteVM) {
    val rows by vm.deviceRows.collectAsStateWithLifecycle()
    val connectingTo by vm.connectingTo.collectAsStateWithLifecycle()
    var manualIp by remember { mutableStateOf("") }
    var showAbout by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DeviceRow?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Text("PureRemote", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { showAbout = true }) { Icon(Icons.Default.Info, contentDescription = "Über PureRemote") }
        }
        if (showAbout) AboutDialog { showAbout = false }
        editing?.let { r ->
            RenameDialog(
                row = r,
                onSave = { vm.rename(r.host, it); editing = null },
                onForget = { vm.forget(r.host); editing = null },
                onDismiss = { editing = null }
            )
        }
        connectingTo?.let { Text("Verbinde mit $it …", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
        Text("Fernseher:", Modifier.padding(top = 12.dp, bottom = 4.dp))
        if (rows.isEmpty()) Text("Suche läuft …", color = MaterialTheme.colorScheme.outline)
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.host }) { d ->
                ListItem(
                    headlineContent = { Text(d.name) },
                    supportingContent = { Text(if (d.online) d.host else "${d.host} · gerade nicht sichtbar") },
                    trailingContent = {
                        IconButton(onClick = { editing = d }) { Icon(Icons.Default.Edit, contentDescription = "Umbenennen") }
                    },
                    modifier = Modifier.clickable { vm.select(d.host, d.name) }
                )
                HorizontalDivider()
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                manualIp, { manualIp = it }, label = { Text("IP manuell (z. B. 192.168.1.50)") },
                singleLine = true, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.select(manualIp.trim(), manualIp.trim()) }, enabled = manualIp.isNotBlank()) { Text("Los") }
        }
    }
}

@Composable
fun CodeEntry(vm: RemoteVM, s: Screen.Code) {
    var code by remember { mutableStateOf("") }
    Text("Koppeln mit ${s.name}", style = MaterialTheme.typography.headlineSmall)
    Text("Gib den 6-stelligen Code ein, der jetzt auf dem Fernseher angezeigt wird.", Modifier.padding(vertical = 12.dp))
    OutlinedTextField(code, { code = it.uppercase().take(6) }, label = { Text("Code") }, singleLine = true)
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.submitCode(s.host, s.name, code) }, enabled = code.length == 6) { Text("Koppeln") }
        TextButton(onClick = { vm.disconnect() }) { Text("Abbrechen") }
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val options = SupportLinks.donationOptions
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
        icon = { Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(64.dp)) },
        title = { Text("PureRemote") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (version.isNotEmpty()) Text("Version $version", style = MaterialTheme.typography.bodySmall)
                Text("Kostenlos, ohne Werbung und ohne Tracking. Steuert deinen Chromecast mit Google TV direkt im WLAN.")
                if (options.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Wenn dir die App gefällt, freue ich mich über eine freiwillige Unterstützung. Sie ist nie nötig und schaltet nichts frei.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { (name, url) ->
                            FilledTonalButton(onClick = { uri.openUri(url) }) { Text(name) }
                        }
                    }
                }
                TextButton(onClick = { uri.openUri(SupportLinks.SOURCE_CODE) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Quellcode auf GitHub")
                }
            }
        }
    )
}

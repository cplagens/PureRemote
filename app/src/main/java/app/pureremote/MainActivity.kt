package app.pureremote

import android.os.Bundle
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
    val devices by vm.devices.collectAsStateWithLifecycle()
    var manualIp by remember { mutableStateOf("") }
    var showAbout by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Text("PureRemote", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { showAbout = true }) { Icon(Icons.Default.Info, contentDescription = "Über PureRemote") }
        }
        if (showAbout) AboutDialog { showAbout = false }
        Text("Geräte im WLAN:", Modifier.padding(top = 12.dp, bottom = 4.dp))
        if (devices.isEmpty()) Text("Suche läuft …", color = MaterialTheme.colorScheme.outline)
        LazyColumn(Modifier.weight(1f)) {
            items(devices) { d ->
                ListItem(
                    headlineContent = { Text(d.name) },
                    supportingContent = { Text(d.host) },
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
fun RemotePad(vm: RemoteVM, s: Screen.Remote) {
    val connected by vm.client.connected.collectAsStateWithLifecycle()
    val power by vm.client.power.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    fun press(code: Int) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.client.key(code) }

    @Composable
    fun Btn(label: String, code: Int, mod: Modifier = Modifier.size(72.dp)) =
        FilledTonalButton(onClick = { press(code) }, modifier = mod, contentPadding = PaddingValues(0.dp)) { Text(label) }

    @Composable
    fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, code: Int) =
        FilledTonalButton(onClick = { press(code) }, modifier = Modifier.size(72.dp), contentPadding = PaddingValues(0.dp)) {
            Icon(icon, desc, Modifier.size(32.dp))
        }

    val (statusText, statusColor) = when {
        !connected -> "Nicht erreichbar – verbinde neu …" to MaterialTheme.colorScheme.error
        power == PowerState.ON -> "An" to Color(0xFF2E7D32)
        power == PowerState.STANDBY -> "Standby" to Color(0xFFF9A825)
        else -> "Verbunden" to MaterialTheme.colorScheme.outline
    }
    val powerLabel = when {
        connected && power == PowerState.ON -> "⏻ Ausschalten"
        connected && power == PowerState.STANDBY -> "⏻ Einschalten"
        else -> "⏻ Power"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(statusText, color = statusColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
        TextButton(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            vm.togglePower()
        }) { Text(powerLabel) }
        TextButton(onClick = { vm.disconnect() }) { Text("Trennen") }
    }
    Spacer(Modifier.height(24.dp))

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IconBtn(Icons.Default.KeyboardArrowUp, "Hoch", Keys.UP)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Links", Keys.LEFT)
            Btn("OK", Keys.CENTER, Modifier.size(88.dp))
            IconBtn(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Rechts", Keys.RIGHT)
        }
        IconBtn(Icons.Default.KeyboardArrowDown, "Runter", Keys.DOWN)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", Keys.BACK)
            IconBtn(Icons.Default.Home, "Home", Keys.HOME)
            IconBtn(Icons.Default.Menu, "Menü", Keys.MENU)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("⏪", Keys.REWIND)
            IconBtn(Icons.Default.PlayArrow, "Play/Pause", Keys.PLAY_PAUSE)
            Btn("⏩", Keys.FORWARD)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Vol −", Keys.VOL_DOWN)
            Btn("Mute", Keys.MUTE)
            Btn("Vol +", Keys.VOL_UP)
        }
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

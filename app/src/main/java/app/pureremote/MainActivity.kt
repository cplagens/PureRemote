package app.pureremote

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// AppCompatActivity wird für die App-Sprache (AppCompatDelegate.setApplicationLocales) benötigt
class MainActivity : AppCompatActivity() {
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
fun UiMsg.text(): String = if (arg != null) stringResource(res, arg) else stringResource(res)

@Composable
fun App(vm: RemoteVM) {
    val firstStart by vm.firstStart.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val err by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    Column(Modifier.systemBarsPadding().padding(16.dp).fillMaxSize()) {
        if (firstStart) {
            WelcomeScreen(vm)
        } else {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            err?.let { Text(it.text(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            when (val s = screen) {
                is Screen.Devices -> Devices(vm)
                is Screen.Code -> CodeEntry(vm, s)
                is Screen.Remote -> RemotePad(vm, s)
            }
        }
    }
}

@Composable
fun Devices(vm: RemoteVM) {
    val rows by vm.deviceRows.collectAsStateWithLifecycle()
    val connectingTo by vm.connectingTo.collectAsStateWithLifecycle()
    var manualIp by remember { mutableStateOf("") }
    var showAbout by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DeviceRow?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
            }
            IconButton(onClick = { showAbout = true }) {
                Icon(Icons.Default.Info, contentDescription = stringResource(R.string.about))
            }
        }
        if (showAbout) AboutDialog { showAbout = false }
        if (showSettings) SettingsDialog(vm) { showSettings = false }
        editing?.let { r ->
            RenameDialog(
                row = r,
                onSave = { vm.rename(r.host, it); editing = null },
                onForget = { vm.forget(r.host); editing = null },
                onDismiss = { editing = null }
            )
        }
        connectingTo?.let {
            Text(stringResource(R.string.connecting_to, it), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        }
        Text(stringResource(R.string.tv_list), Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
        if (rows.isEmpty()) Text(stringResource(R.string.searching), color = MaterialTheme.colorScheme.outline)
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.host }) { d ->
                ListItem(
                    headlineContent = { Text(d.name) },
                    supportingContent = { Text(if (d.online) d.host else stringResource(R.string.not_visible, d.host)) },
                    trailingContent = {
                        IconButton(onClick = { editing = d }) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.rename)) }
                    },
                    modifier = Modifier.clickable { vm.select(d.host, d.name) }
                )
                HorizontalDivider()
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                manualIp, { manualIp = it }, label = { Text(stringResource(R.string.manual_ip)) },
                singleLine = true, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.select(manualIp.trim(), manualIp.trim()) }, enabled = manualIp.isNotBlank()) {
                Text(stringResource(R.string.go))
            }
        }
    }
}

@Composable
fun CodeEntry(vm: RemoteVM, s: Screen.Code) {
    var code by remember { mutableStateOf("") }
    Text(stringResource(R.string.pair_title, s.name), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.pair_hint), Modifier.padding(vertical = 12.dp))
    OutlinedTextField(code, { code = it.uppercase().take(6) }, label = { Text(stringResource(R.string.code)) }, singleLine = true)
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.submitCode(s.host, s.name, code) }, enabled = code.length == 6) { Text(stringResource(R.string.pair)) }
        TextButton(onClick = { vm.disconnect() }) { Text(stringResource(R.string.cancel)) }
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        icon = { Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(64.dp)) },
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (version.isNotEmpty()) Text(stringResource(R.string.version, version), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.about_text))
                if (options.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.support_text))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { (name, url) ->
                            FilledTonalButton(onClick = { uri.openUri(url) }) { Text(name) }
                        }
                    }
                }
                TextButton(onClick = { uri.openUri(SupportLinks.SOURCE_CODE) }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.source_code))
                }
            }
        }
    )
}

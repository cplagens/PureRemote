package app.pureremote

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureremote.net.Keys
import app.pureremote.net.PowerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runde Fernbedienungstaste. Sendet beim Drücken sofort und wiederholt (wenn [repeat] gesetzt ist)
 * nach kurzer Verzögerung so lange, wie der Finger auf der Taste bleibt.
 */
@Composable
fun KeyButton(
    desc: String,
    modifier: Modifier = Modifier.size(72.dp),
    repeat: Boolean = false,
    onKey: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnKey by rememberUpdatedState(onKey)
    var pressed by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = desc
                role = Role.Button
                onClick(label = desc) { currentOnKey(); true }   // für TalkBack & Co.
            }
            .pointerInput(repeat) {
                detectTapGestures(onPress = {
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentOnKey()
                    val job = if (repeat) scope.launch {
                        delay(400)
                        while (true) { currentOnKey(); delay(110) }
                    } else null
                    try { tryAwaitRelease() } finally { job?.cancel(); pressed = false }
                })
            },
        shape = CircleShape,
        color = if (pressed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

/** Doppelpfeil für Vor-/Zurückspulen in der Tastenfarbe (die Emoji ⏪/⏩ zeichnet das System farbig). */
@Composable
private fun SeekIcon(forward: Boolean) {
    Row(Modifier.scale(scaleX = if (forward) 1f else -1f, scaleY = 1f), horizontalArrangement = Arrangement.spacedBy((-12).dp)) {
        repeat(2) { Icon(Icons.Default.PlayArrow, null, Modifier.size(28.dp)) }
    }
}

@Composable
private fun RemoteKey(vm: RemoteVM, code: Int, desc: String, size: Dp, repeat: Boolean = false, content: @Composable () -> Unit) {
    KeyButton(desc, Modifier.size(size), repeat, onKey = { vm.client.key(code) }, content = content)
}

@Composable
fun RemotePad(vm: RemoteVM, s: Screen.Remote) {
    val connected by vm.client.connected.collectAsStateWithLifecycle()
    val power by vm.client.power.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    var showApps by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    val (statusText, statusColor) = when {
        !connected -> stringResource(R.string.status_offline) to MaterialTheme.colorScheme.error
        power == PowerState.ON -> stringResource(R.string.status_on) to Color(0xFF2E7D32)
        power == PowerState.STANDBY -> stringResource(R.string.status_standby) to Color(0xFFF9A825)
        else -> stringResource(R.string.status_connected) to MaterialTheme.colorScheme.outline
    }
    val powerLabel = when {
        connected && power == PowerState.ON -> stringResource(R.string.power_off)
        connected && power == PowerState.STANDBY -> stringResource(R.string.power_on)
        else -> stringResource(R.string.power_generic)
    }

    if (showApps) AppsDialog(vm) { showApps = false }
    if (showText) TextDialog(vm) { showText = false }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
    if (showAbout) AboutDialog { showAbout = false }

    Column(Modifier.fillMaxSize()) {
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
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings)) },
                        onClick = { menuOpen = false; showSettings = true }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.switch_device)) },
                        onClick = { menuOpen = false; vm.disconnect() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.about)) },
                        onClick = { menuOpen = false; showAbout = true }
                    )
                }
            }
        }

        // Die Tasten passen sich der verfügbaren Höhe an, damit auch kleine Displays nicht scrollen müssen
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val k = (maxHeight.value / 580f).coerceIn(0.7f, 1f)
            val d = (72 * k).dp
            val ok = (88 * k).dp
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
            ) {
                RemoteKey(vm, Keys.UP, stringResource(R.string.key_up), d, repeat = true) { Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(32.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RemoteKey(vm, Keys.LEFT, stringResource(R.string.key_left), d, repeat = true) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(32.dp)) }
                    RemoteKey(vm, Keys.CENTER, "OK", ok) { Text("OK", style = MaterialTheme.typography.titleMedium) }
                    RemoteKey(vm, Keys.RIGHT, stringResource(R.string.key_right), d, repeat = true) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(32.dp)) }
                }
                RemoteKey(vm, Keys.DOWN, stringResource(R.string.key_down), d, repeat = true) { Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(32.dp)) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteKey(vm, Keys.BACK, stringResource(R.string.key_back), d) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(32.dp)) }
                    RemoteKey(vm, Keys.HOME, stringResource(R.string.key_home), d) { Icon(Icons.Default.Home, null, Modifier.size(32.dp)) }
                    RemoteKey(vm, Keys.MENU, stringResource(R.string.key_menu), d) { Icon(Icons.Default.Menu, null, Modifier.size(32.dp)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteKey(vm, Keys.REWIND, stringResource(R.string.key_rewind), d) { SeekIcon(forward = false) }
                    RemoteKey(vm, Keys.PLAY_PAUSE, stringResource(R.string.key_play_pause), d) { Icon(Icons.Default.PlayArrow, null, Modifier.size(32.dp)) }
                    RemoteKey(vm, Keys.FORWARD, stringResource(R.string.key_forward), d) { SeekIcon(forward = true) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteKey(vm, Keys.VOL_DOWN, stringResource(R.string.key_vol_down), d, repeat = true) { Text("Vol −") }
                    RemoteKey(vm, Keys.MUTE, stringResource(R.string.key_mute), d) { Text("Mute") }
                    RemoteKey(vm, Keys.VOL_UP, stringResource(R.string.key_vol_up), d, repeat = true) { Text("Vol +") }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { showText = true }) { Text(stringResource(R.string.btn_text)) }
                    FilledTonalButton(onClick = { showApps = true }) { Text(stringResource(R.string.btn_apps)) }
                }
            }
        }
    }
}

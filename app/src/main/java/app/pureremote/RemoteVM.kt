package app.pureremote

import android.app.Application
import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pureremote.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Devices : Screen
    data class Code(val host: String, val name: String) : Screen
    data class Remote(val name: String, val host: String) : Screen
}

/** Ein übersetzbarer Hinweis für die Oberfläche (Text aus strings.xml, optional mit einem Platzhalter). */
data class UiMsg(@StringRes val res: Int, val arg: String? = null)

/** Eine Zeile der Geräteliste: gespeicherte und/oder aktuell im WLAN sichtbare Fernseher. */
data class DeviceRow(val host: String, val name: String, val online: Boolean, val saved: Boolean)

class RemoteVM(app: Application) : AndroidViewModel(app) {
    private val discovery = Discovery(app)
    private val store = Store(app)
    val client = RemoteClient(app)
    private val pairing = Pairing(app)

    private val savedDevices = MutableStateFlow(store.devices())
    val deviceRows: StateFlow<List<DeviceRow>> = combine(discovery.devices, savedDevices) { found, saved ->
        saved.map { s -> DeviceRow(s.host, s.name, online = found.any { it.host == s.host }, saved = true) } +
            found.filter { f -> saved.none { it.host == f.host } }.map { DeviceRow(it.host, it.name, online = true, saved = false) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val screen = MutableStateFlow<Screen>(Screen.Devices)
    val error = MutableStateFlow<UiMsg?>(null)
    val firstStart = MutableStateFlow(!store.welcomeDone)
    val busy = MutableStateFlow(false)
    val connectingTo = MutableStateFlow<String?>(null)
    val shortcuts = MutableStateFlow(store.shortcuts())
    val volumeKeys = MutableStateFlow(store.volumeKeys)
    private var keepAlive: Job? = null

    init {
        discovery.start()
        // Beim Start direkt mit dem zuletzt genutzten (oder einzigen gekoppelten) Fernseher verbinden
        val target = store.lastHost ?: store.pairedHosts().singleOrNull()
        if (target != null && store.isPaired(target)) {
            select(target, savedDevices.value.firstOrNull { it.host == target }?.name ?: target)
        }
    }

    fun finishWelcome() { store.welcomeDone = true; firstStart.value = false }

    // ---------------- Verbinden / Koppeln ----------------

    fun select(host: String, name: String) = viewModelScope.launch {
        error.value = null
        if (store.isPaired(host)) connect(host, name) else startPairing(host, name)
    }

    private suspend fun connect(host: String, name: String) {
        busy.value = true; connectingTo.value = name
        try {
            client.connect(host)
            onConnected(host, name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnreachableException) {
            // Nur nicht erreichbar -> Kopplung bleibt erhalten
            error.value = UiMsg(R.string.err_unreachable, name)
        } catch (e: Exception) {
            // Kopplung evtl. am TV gelöscht -> neu koppeln
            store.setPaired(host, false)
            startPairing(host, name)
        } finally { busy.value = false; connectingTo.value = null }
    }

    private suspend fun startPairing(host: String, name: String) {
        busy.value = true
        try {
            pairing.start(host)
            screen.value = Screen.Code(host, name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = UiMsg(R.string.err_connect, e.message ?: e.javaClass.simpleName)
        } finally { busy.value = false }
    }

    fun submitCode(host: String, name: String, code: String) = viewModelScope.launch {
        busy.value = true; error.value = null
        try {
            pairing.finish(code)
            store.setPaired(host, true)
            delay(500)
            client.connect(host)
            onConnected(host, name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = UiMsg(if (e is WrongCodeException) R.string.err_wrong_code else R.string.err_pairing)
        } finally { busy.value = false }
    }

    private fun onConnected(host: String, name: String) {
        // Eigener Name > im WLAN gefundener Name > übergebener Name (bei manueller IP = die IP)
        val shown = savedDevices.value.firstOrNull { it.host == host }?.name?.takeIf { it != host }
            ?: discovery.devices.value.firstOrNull { it.host == host }?.name
            ?: name
        store.saveDevice(host, shown)
        savedDevices.value = store.devices()
        store.lastHost = host
        screen.value = Screen.Remote(shown, host)
        keepConnected(host)
    }

    /** Baut die Verbindung automatisch wieder auf (z. B. wenn das TV aus dem Standby aufwacht). */
    private fun keepConnected(host: String) {
        keepAlive?.cancel()
        keepAlive = viewModelScope.launch {
            var wait = 2000L
            while (true) {
                if (!client.connected.value) {
                    wait = if (runCatching { client.connect(host) }.isSuccess) 2000L else minOf(wait * 2, 15000L)
                }
                delay(wait)
            }
        }
    }

    fun disconnect() {
        keepAlive?.cancel(); keepAlive = null
        client.close(); pairing.close(); screen.value = Screen.Devices
    }

    // ---------------- Geräte verwalten ----------------

    fun rename(host: String, newName: String) {
        store.saveDevice(host, newName)
        savedDevices.value = store.devices()
    }

    fun forget(host: String) {
        store.forget(host)
        savedDevices.value = store.devices()
    }

    // ---------------- Steuern ----------------

    /**
     * Ein-/Ausschalten. Kennt die App den Zustand, wird nach 3,5 s geprüft, ob er sich geändert hat;
     * falls nicht, wird zusätzlich WAKEUP bzw. SLEEP gesendet (manche Firmware reagiert nur darauf).
     */
    fun togglePower() = viewModelScope.launch {
        val before = client.power.value
        client.key(Keys.POWER)
        if (before == PowerState.UNKNOWN) return@launch
        delay(3500)
        if (client.connected.value && client.power.value == before) {
            client.key(if (before == PowerState.STANDBY) Keys.WAKEUP else Keys.SLEEP)
        }
    }

    fun sendText(text: String) = client.sendText(text)

    // ---------------- Apps ----------------

    fun launch(app: AppShortcut) = client.launchApp(app.target)

    fun addShortcut(name: String, target: String) {
        if (name.isBlank() || target.isBlank()) return
        updateShortcuts(shortcuts.value + AppShortcut(name.trim(), target.trim()))
    }

    fun removeShortcut(index: Int) {
        updateShortcuts(shortcuts.value.filterIndexed { i, _ -> i != index })
    }

    fun moveShortcutUp(index: Int) {
        if (index <= 0 || index >= shortcuts.value.size) return
        val l = shortcuts.value.toMutableList()
        l.add(index - 1, l.removeAt(index))
        updateShortcuts(l)
    }

    private fun updateShortcuts(list: List<AppShortcut>) {
        shortcuts.value = list
        store.saveShortcuts(list)
    }

    // ---------------- Lautstärketasten des Handys ----------------

    fun setVolumeKeys(on: Boolean) { volumeKeys.value = on; store.volumeKeys = on }

    fun volumeKeyActive() = volumeKeys.value && screen.value is Screen.Remote && client.connected.value

    /** Gibt true zurück, wenn die Taste an den Fernseher geschickt (und damit verbraucht) wurde. */
    fun handleVolumeKey(keyCode: Int, repeatCount: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!volumeKeyActive()) return false
        // Beim Gedrückthalten nicht jeden System-Repeat weitergeben
        if (repeatCount == 0 || repeatCount % 3 == 0) {
            client.key(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) Keys.VOL_UP else Keys.VOL_DOWN)
        }
        return true
    }

    override fun onCleared() { discovery.stop(); client.close(); pairing.close() }
}

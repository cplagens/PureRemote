package app.pureremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pureremote.net.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Devices : Screen
    data class Code(val host: String, val name: String) : Screen
    data class Remote(val name: String) : Screen
}

class RemoteVM(app: Application) : AndroidViewModel(app) {
    private val discovery = Discovery(app)
    val devices = discovery.devices
    val client = RemoteClient(app)
    private val pairing = Pairing(app)
    private val prefs = app.getSharedPreferences("pureremote", 0)

    val screen = MutableStateFlow<Screen>(Screen.Devices)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    init { discovery.start() }

    fun select(host: String, name: String) = viewModelScope.launch {
        error.value = null
        if (prefs.getBoolean("paired:$host", false)) connect(host, name) else startPairing(host, name)
    }

    private suspend fun connect(host: String, name: String) {
        busy.value = true
        try {
            client.connect(host)
            screen.value = Screen.Remote(name)
        } catch (e: Exception) {
            // Kopplung evtl. am TV gelöscht -> neu koppeln
            prefs.edit().remove("paired:$host").apply()
            startPairing(host, name)
        } finally { busy.value = false }
    }

    private suspend fun startPairing(host: String, name: String) {
        busy.value = true
        try {
            pairing.start(host)
            screen.value = Screen.Code(host, name)
        } catch (e: Exception) {
            error.value = "Verbindung fehlgeschlagen: ${e.message}"
        } finally { busy.value = false }
    }

    fun submitCode(host: String, name: String, code: String) = viewModelScope.launch {
        busy.value = true; error.value = null
        try {
            pairing.finish(code)
            prefs.edit().putBoolean("paired:$host", true).apply()
            delay(500)
            client.connect(host)
            screen.value = Screen.Remote(name)
        } catch (e: Exception) {
            error.value = e.message ?: "Fehler beim Koppeln"
        } finally { busy.value = false }
    }

    fun disconnect() { client.close(); pairing.close(); screen.value = Screen.Devices }

    override fun onCleared() { discovery.stop(); client.close(); pairing.close() }
}

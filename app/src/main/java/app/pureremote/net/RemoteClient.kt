package app.pureremote.net

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket

object Keys {
    const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22; const val CENTER = 23
    const val BACK = 4; const val HOME = 3; const val MENU = 82; const val SETTINGS = 176
    const val PLAY_PAUSE = 85; const val POWER = 26
    const val VOL_UP = 24; const val VOL_DOWN = 25; const val MUTE = 164
    const val REWIND = 89; const val FORWARD = 90
    const val SLEEP = 223; const val WAKEUP = 224
}

/** Zustand des Fernsehers, wie ihn das TV selbst meldet. UNKNOWN = noch keine Meldung / nicht verbunden. */
enum class PowerState { ON, STANDBY, UNKNOWN }

class RemoteClient(private val ctx: Context) {
    private var sock: SSLSocket? = null
    private var reader: Job? = null
    private val readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Einzelner Schreib-Thread, damit Tastendrücke in der richtigen Reihenfolge ankommen
    private val writeScope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    val connected = MutableStateFlow(false)
    val power = MutableStateFlow(PowerState.UNKNOWN)

    suspend fun connect(host: String) = withContext(Dispatchers.IO) {
        close()
        val ks = CertStore.keyStore(ctx)
        val s = CertStore.sslContext(ks).socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(host, 6466), 5000)
        s.soTimeout = 5000
        s.startHandshake()
        sock = s
        // Das TV sendet zuerst "configure". Schlägt das fehl, ist das Gerät nicht (mehr) gekoppelt.
        handle(PbMsg.parse(s.inputStream.readFrame()))
        // Das TV sendet regelmäßig Pings. Bleibt es länger still, gilt die Verbindung als tot.
        s.soTimeout = 45000
        connected.value = true
        reader = readScope.launch {
            try { while (isActive) handle(PbMsg.parse(s.inputStream.readFrame())) }
            catch (_: Exception) { }
            finally {
                // Nur zurücksetzen, wenn dies noch die aktuelle Verbindung ist (nicht eine neu aufgebaute)
                if (sock === s) { connected.value = false; power.value = PowerState.UNKNOWN }
            }
        }
    }

    private fun deviceInfo() = PbWriter()
        .string(1, "Android").string(2, "PureRemote").int(3, 1).string(4, "1")
        .string(5, "app.pureremote").string(6, "1.0.0")

    private fun handle(m: PbMsg) {
        when {
            m.has(1) -> write(PbWriter().msg(1, PbWriter().int(1, 622).msg(2, deviceInfo())))   // configure
            m.has(2) -> write(PbWriter().msg(2, PbWriter().int(1, 622)))                          // set_active
            m.has(8) -> write(PbWriter().msg(9, PbWriter().int(1, m.msg(8)?.int(1) ?: 0)))        // ping -> pong
            m.has(40) -> power.value =                                                            // remote_start
                if ((m.msg(40)?.int(1) ?: 0) != 0) PowerState.ON else PowerState.STANDBY
        }
    }

    private fun write(w: PbWriter) {
        val s = sock ?: return
        synchronized(this) { s.outputStream.writeFrame(w.toByteArray()) }
    }

    /** direction: 1 = START_LONG, 2 = END_LONG, 3 = SHORT (normaler Tastendruck) */
    fun key(code: Int, direction: Int = 3) {
        writeScope.launch {
            runCatching { write(PbWriter().msg(10, PbWriter().int(1, code).int(2, direction))) }
                .onFailure { connected.value = false }
        }
    }

    fun close() {
        reader?.cancel(); reader = null
        runCatching { sock?.close() }; sock = null
        connected.value = false
        power.value = PowerState.UNKNOWN
    }
}

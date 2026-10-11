package app.pureremote.net

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket

object Keys {
    const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22; const val CENTER = 23
    const val BACK = 4; const val HOME = 3; const val MENU = 82; const val SETTINGS = 176
    const val PLAY_PAUSE = 85; const val POWER = 26
    const val VOL_UP = 24; const val VOL_DOWN = 25; const val MUTE = 164
    const val REWIND = 89; const val FORWARD = 90
    const val ENTER = 66; const val DEL = 67
    const val SLEEP = 223; const val WAKEUP = 224
}

/** Zustand des Fernsehers, wie ihn das TV selbst meldet. UNKNOWN = noch keine Meldung / nicht verbunden. */
enum class PowerState { ON, STANDBY, UNKNOWN }

/** Die aktuell im Vordergrund laufende App auf dem TV. */
data class AppInfo(val label: String, val pkg: String)

/** Das TV ist per TCP nicht erreichbar (aus, anderes Netz, ...) - im Gegensatz zu "nicht gekoppelt". */
class UnreachableException(cause: Throwable) : IOException(cause)

class RemoteClient(private val ctx: Context) {
    private companion object {
        // PING | KEY | IME | POWER | VOLUME | APP_LINK (Sprachsteuerung wird nicht unterstützt)
        const val FEATURES = 1 or 2 or 4 or 32 or 64 or 512

        // Manche Geräte (z. B. Chromecast mit Google TV) lehnen "market://launch?id=" ab.
        // Für bekannte Apps wird deshalb deren eigener Link verwendet.
        val APP_LINKS = mapOf(
            "com.google.android.youtube.tv" to "https://www.youtube.com",
            "com.netflix.ninja" to "https://www.netflix.com/title",
            "com.amazon.amazonvideo.livingroom" to "https://app.primevideo.com",
            "com.disney.disneyplus" to "https://www.disneyplus.com",
            "com.spotify.tv.android" to "spotify://",
            "com.plexapp.android" to "plex://",
        )
    }

    private var sock: SSLSocket? = null
    private var reader: Job? = null
    private val readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Einzelner Schreib-Thread, damit Tastendrücke in der richtigen Reihenfolge ankommen
    private val writeScope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    val connected = MutableStateFlow(false)
    val power = MutableStateFlow(PowerState.UNKNOWN)
    val currentApp = MutableStateFlow<AppInfo?>(null)

    @Volatile private var activeFeatures = FEATURES
    @Volatile private var imeCounter = 0
    @Volatile private var imeFieldCounter = 0

    suspend fun connect(host: String) = withContext(Dispatchers.IO) {
        close()
        val ks = CertStore.keyStore(ctx)
        val s = CertStore.sslContext(ks).socketFactory.createSocket() as SSLSocket
        try {
            s.connect(InetSocketAddress(host, 6466), 5000)
        } catch (e: IOException) {
            runCatching { s.close() }
            throw UnreachableException(e)
        }
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
                if (sock === s) { connected.value = false; power.value = PowerState.UNKNOWN; currentApp.value = null }
            }
        }
    }

    private fun deviceInfo() = PbWriter()
        .string(1, "Android").string(2, "PureRemote").int(3, 1).string(4, "1")
        .string(5, "app.pureremote").string(6, "1.0.0")

    private fun handle(m: PbMsg) {
        when {
            m.has(1) -> {                                                                         // configure
                // Wir aktivieren nur Funktionen, die wir kennen UND das TV anbietet.
                activeFeatures = (m.msg(1)?.int(1) ?: FEATURES) and FEATURES
                write(PbWriter().msg(1, PbWriter().int(1, activeFeatures).msg(2, deviceInfo())))
            }
            m.has(2) -> write(PbWriter().msg(2, PbWriter().int(1, activeFeatures)))              // set_active
            m.has(8) -> write(PbWriter().msg(9, PbWriter().int(1, m.msg(8)?.int(1) ?: 0)))        // ping -> pong
            m.has(20) -> {                                                                        // ime_key_inject: aktuelle App
                val app = m.msg(20)?.msg(1)
                val pkg = app?.string(12).orEmpty()
                if (pkg.isNotEmpty()) {
                    currentApp.value = AppInfo(app?.string(10)?.takeIf { it.isNotBlank() } ?: pkg, pkg)
                }
            }
            m.has(21) -> {                                                                        // ime_batch_edit: Zähler für Texteingabe
                imeCounter = m.msg(21)?.int(1) ?: 0
                imeFieldCounter = m.msg(21)?.int(2) ?: 0
            }
            m.has(40) -> power.value =                                                            // remote_start
                if ((m.msg(40)?.int(1) ?: 0) != 0) PowerState.ON else PowerState.STANDBY
        }
    }

    private fun write(w: PbWriter) {
        val s = sock ?: return
        synchronized(this) { s.outputStream.writeFrame(w.toByteArray()) }
    }

    private fun send(w: PbWriter) {
        writeScope.launch { runCatching { write(w) }.onFailure { connected.value = false } }
    }

    /** direction: 1 = START_LONG, 2 = END_LONG, 3 = SHORT (normaler Tastendruck) */
    fun key(code: Int, direction: Int = 3) = send(PbWriter().msg(10, PbWriter().int(1, code).int(2, direction)))

    /** Schickt Text in das aktuell auf dem TV ausgewählte Textfeld. */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val pos = text.length - 1
        send(
            PbWriter().msg(
                21, PbWriter().int(1, imeCounter).int(2, imeFieldCounter)
                    .msg(3, PbWriter().int(1, 1).msg(2, PbWriter().int(1, pos).int(2, pos).string(3, text)))
            )
        )
    }

    /** Startet eine App. [target] ist ein Link (z. B. https://…) oder ein Paketname (com.netflix.ninja). */
    fun launchApp(target: String) {
        val link = if (target.contains("://")) target else APP_LINKS[target] ?: "market://launch?id=$target"
        send(PbWriter().msg(90, PbWriter().string(1, link)))
    }

    fun close() {
        reader?.cancel(); reader = null
        runCatching { sock?.close() }; sock = null
        connected.value = false
        power.value = PowerState.UNKNOWN
        currentApp.value = null
    }
}

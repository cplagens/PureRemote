package app.pureremote.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow

data class TvDevice(val name: String, val host: String)

/** Findet Geräte im WLAN über den mDNS-Dienst "_androidtvremote2._tcp". */
class Discovery(ctx: Context) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    val devices = MutableStateFlow<List<TvDevice>>(emptyList())
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var running = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
        override fun onServiceLost(info: NsdServiceInfo) {
            devices.value = devices.value.filterNot { it.name == info.serviceName }
        }
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, e: Int) {}
        override fun onStopDiscoveryFailed(t: String, e: Int) {}
    }

    fun start() {
        if (running) return
        running = true
        nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { nsd.stopServiceDiscovery(listener) }
    }

    @Synchronized private fun enqueue(info: NsdServiceInfo) { queue.addLast(info); next() }

    // NsdManager kann nur einen Resolve gleichzeitig -> seriell abarbeiten
    @Synchronized private fun next() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, e: Int) { done() }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val host = i.host?.hostAddress
                if (host != null && devices.value.none { it.host == host }) {
                    devices.value = devices.value + TvDevice(i.serviceName, host)
                }
                done()
            }
        })
    }

    @Synchronized private fun done() { resolving = false; next() }
}

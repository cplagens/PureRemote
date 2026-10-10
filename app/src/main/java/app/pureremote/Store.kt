package app.pureremote

import android.content.Context
import app.pureremote.net.TvDevice

data class AppShortcut(val name: String, val target: String)

/** Einfache Ablage in SharedPreferences: gekoppelte Geräte, zuletzt genutztes Gerät, App-Verknüpfungen, Einstellungen. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("pureremote", Context.MODE_PRIVATE)

    fun isPaired(host: String) = p.getBoolean("paired:$host", false)
    fun setPaired(host: String, paired: Boolean) {
        p.edit().apply { if (paired) putBoolean("paired:$host", true) else remove("paired:$host") }.apply()
    }
    fun pairedHosts(): List<String> = p.all.keys.filter { it.startsWith("paired:") }.map { it.removePrefix("paired:") }

    var lastHost: String?
        get() = p.getString("last_host", null)
        set(v) { p.edit().putString("last_host", v).apply() }

    /** Wurde die Sprachauswahl beim ersten Start schon abgeschlossen? */
    var welcomeDone: Boolean
        get() = p.getBoolean("welcome_done", false)
        set(v) { p.edit().putBoolean("welcome_done", v).apply() }

    var volumeKeys: Boolean
        get() = p.getBoolean("volume_keys", true)
        set(v) { p.edit().putBoolean("volume_keys", v).apply() }

    // ---- Geräte (host, Name) ----
    fun devices(): List<TvDevice> {
        val saved = decode(p.getString("devices", "").orEmpty())
        val known = saved.map { it.first }.toSet()
        val extra = pairedHosts().filter { it !in known }.map { it to it }   // schon gekoppelte Geräte aus früheren Versionen
        return (saved + extra).map { TvDevice(name = it.second, host = it.first) }
    }

    fun saveDevice(host: String, name: String) {
        val list = decode(p.getString("devices", "").orEmpty()).toMutableList()
        val entry = host to clean(name, host)
        val i = list.indexOfFirst { it.first == host }
        if (i >= 0) list[i] = entry else list.add(entry)
        p.edit().putString("devices", encode(list)).apply()
    }

    fun forget(host: String) {
        val list = decode(p.getString("devices", "").orEmpty()).filter { it.first != host }
        p.edit().putString("devices", encode(list)).apply()
        setPaired(host, false)
        if (lastHost == host) p.edit().remove("last_host").apply()
    }

    // ---- App-Verknüpfungen ----
    fun shortcuts(): List<AppShortcut> {
        val raw = p.getString("apps", null) ?: return DEFAULT_APPS
        return decode(raw).map { AppShortcut(it.first, it.second) }
    }

    fun saveShortcuts(list: List<AppShortcut>) {
        p.edit().putString("apps", encode(list.map { it.name to it.target })).apply()
    }

    private fun clean(s: String, fallback: String) = s.replace(Regex("[\\t\\r\\n]"), " ").trim().ifEmpty { fallback }
    private fun encode(l: List<Pair<String, String>>) =
        l.joinToString("\n") { "${clean(it.first, "?")}\t${clean(it.second, "?")}" }
    private fun decode(s: String): List<Pair<String, String>> =
        s.lines().mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }

    companion object {
        // Paketnamen der Android-TV-Versionen; "market://launch?id=" startet die App, falls installiert.
        val DEFAULT_APPS = listOf(
            AppShortcut("YouTube", "com.google.android.youtube.tv"),
            AppShortcut("Netflix", "com.netflix.ninja"),
            AppShortcut("Prime Video", "com.amazon.amazonvideo.livingroom"),
            AppShortcut("Disney+", "com.disney.disneyplus"),
            AppShortcut("Spotify", "com.spotify.tv.android"),
            AppShortcut("Plex", "com.plexapp.android"),
        )
    }
}

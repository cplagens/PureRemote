# PureRemote

Open-source remote for Google TV. Ad-free &amp; privacy-friendly.

Android-Fernbedienung für Chromecast mit Google TV (Android TV Remote Protocol v2), gebaut mit Kotlin und Jetpack Compose.

## Starten

1. Projekt in Android Studio öffnen (Gradle-Sync erzeugt den Wrapper).
2. Handy und Chromecast müssen im selben WLAN sein (kein Gast-WLAN / keine Client-Isolation).
3. App starten, TV auswählen, den 6-stelligen Code vom Fernseher eingeben.

## Aufbau

- `net/Proto.kt`: minimaler Protobuf-Encoder/-Decoder und Längenpräfix-Framing
- `net/CertStore.kt`: selbstsigniertes Client-Zertifikat (BouncyCastle) und TLS-Kontext
- `net/Pairing.kt`: Pairing auf Port 6467 (Code-Abgleich per SHA-256)
- `net/RemoteClient.kt`: Steuerverbindung auf Port 6466 (Tasten, Ping/Pong)
- `net/Discovery.kt`: mDNS-Suche nach `_androidtvremote2._tcp`
- `RemoteVM.kt` und `MainActivity.kt`: Zustand und Compose-UI

Hinweis: Das Protokoll ist nicht offiziell dokumentiert und kann sich mit Firmware-Updates ändern.

## Unterstützen
PureRemote ist und bleibt kostenlos, ohne Werbung und ohne Tracking. Wer möchte, kann die Entwicklung freiwillig unterstützen.
Die Links werden in `app/src/main/java/app/pureremote/SupportLinks.kt` eingetragen; leere Einträge werden in der App ausgeblendet.

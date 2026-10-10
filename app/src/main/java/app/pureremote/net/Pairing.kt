package app.pureremote.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

/**
 * Pairing-Protokoll ("Polo") auf Port 6467:
 * Request -> Option -> Configuration -> TV zeigt 6-stelligen Hex-Code -> Secret (SHA-256).
 */
class Pairing(private val ctx: Context) {
    private var sock: SSLSocket? = null
    private var ks: KeyStore? = null

    private fun polo(field: Int, body: PbWriter) =
        PbWriter().int(1, 2 /* protocol_version */).int(2, 200 /* STATUS_OK */).msg(field, body)

    private fun send(w: PbWriter) = sock!!.outputStream.writeFrame(w.toByteArray())

    private fun receive(): PbMsg {
        val m = PbMsg.parse(sock!!.inputStream.readFrame())
        check(m.int(2) == 200) { "TV hat das Pairing abgelehnt (Status ${m.int(2)})" }
        return m
    }

    /** Verbindet und löst die Code-Anzeige auf dem TV aus. */
    suspend fun start(host: String) = withContext(Dispatchers.IO) {
        close()
        val keyStore = CertStore.keyStore(ctx).also { ks = it }
        val s = CertStore.sslContext(keyStore).socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(host, 6467), 5000)
        s.soTimeout = 15000
        s.startHandshake()
        sock = s

        send(polo(10, PbWriter().string(1, "androidtvremote2").string(2, "PureRemote")))
        receive() // PairingRequestAck
        val hexEncoding = PbWriter().int(1, 3 /* HEXADECIMAL */).int(2, 6 /* Länge */)
        send(polo(20, PbWriter().msg(1, hexEncoding).int(3, 1 /* ROLE_INPUT */)))
        receive() // Option des TV
        send(polo(30, PbWriter().msg(1, hexEncoding).int(2, 1)))
        receive() // ConfigurationAck -> TV zeigt jetzt den Code
    }

    /** Schließt das Pairing mit dem auf dem TV angezeigten Code ab. */
    suspend fun finish(code: String) = withContext(Dispatchers.IO) {
        val c = code.trim().uppercase()
        require(c.length == 6 && c.all { it in "0123456789ABCDEF" }) { "Der Code besteht aus 6 Zeichen (0-9, A-F)." }
        val cb = ByteArray(3) { c.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        val s = sock ?: error("Pairing nicht gestartet")
        val serverKey = s.session.peerCertificates[0].publicKey as RSAPublicKey
        val clientKey = ks!!.getCertificate("client").publicKey as RSAPublicKey

        val md = MessageDigest.getInstance("SHA-256")
        listOf(clientKey.modulus, clientKey.publicExponent, serverKey.modulus, serverKey.publicExponent)
            .forEach { md.update(strip(it)) }
        md.update(cb.copyOfRange(1, 3))
        val hash = md.digest()
        check(hash[0] == cb[0]) { "Falscher Code" }

        send(polo(40, PbWriter().bytes(1, hash)))
        receive() // SecretAck
        close()
    }

    private fun strip(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    fun close() { runCatching { sock?.close() }; sock = null }
}

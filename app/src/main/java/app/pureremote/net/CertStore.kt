package app.pureremote.net

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Das Handy identifiziert sich beim TV über ein selbstsigniertes Client-Zertifikat. */
object CertStore {
    private val pass = "pureremote".toCharArray()

    fun keyStore(ctx: Context): KeyStore {
        val file = File(ctx.filesDir, "client.p12")
        val ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            file.inputStream().use { ks.load(it, pass) }
            return ks
        }
        ks.load(null, null)
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=pure-remote")
        val now = Date()
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(System.currentTimeMillis()),
            now, Date(now.time + 20L * 365 * 24 * 3600 * 1000), name, kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert: X509Certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        ks.setKeyEntry("client", kp.private, pass, arrayOf(cert))
        file.outputStream().use { ks.store(it, pass) }
        return ks
    }

    /** Das TV-Zertifikat ist selbstsigniert -> wird nicht über eine CA geprüft. */
    fun sslContext(ks: KeyStore): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(trustAll), null) }
    }
}

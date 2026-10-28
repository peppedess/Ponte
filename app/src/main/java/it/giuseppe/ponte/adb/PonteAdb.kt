package it.giuseppe.ponte.adb

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Identità ADB di Ponte (chiave RSA + certificato autofirmato, salvati nella cartella privata
 * dell'app) e connessione al Debug wireless del Galaxy.
 */
class PonteAdb private constructor(context: Context) : AbsAdbConnectionManager() {

    private val key: PrivateKey
    private val cert: Certificate

    init {
        // Il Galaxy usa un adbd moderno: protocollo e dimensione dei pacchetti di Android 9+
        setApi(36)
        setTimeout(10, TimeUnit.SECONDS)

        val keyFile = File(context.filesDir, "adb.key")
        val certFile = File(context.filesDir, "adb.crt")
        var loadedKey: PrivateKey? = null
        var loadedCert: Certificate? = null
        if (keyFile.exists() && certFile.exists()) {
            runCatching {
                loadedKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                loadedCert = certFile.inputStream().use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it)
                }
            }
        }
        if (loadedKey == null || loadedCert == null) {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom())
            val pair = generator.generateKeyPair()
            val name = X500Name("CN=Ponte")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(now),
                Date(now - 86_400_000L),
                Date(now + 100L * 365 * 86_400_000L),
                name,
                pair.public,
            )
            val bc = BouncyCastleProvider()
            val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(pair.private)
            val x509 = JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(signer))
            keyFile.writeBytes(pair.private.encoded)
            certFile.writeBytes(x509.encoded)
            loadedKey = pair.private
            loadedCert = x509
        }
        key = loadedKey!!
        cert = loadedCert!!
    }

    override fun getPrivateKey(): PrivateKey = key

    override fun getCertificate(): Certificate = cert

    override fun getDeviceName(): String = "Ponte"

    /** Esegue un comando e ne restituisce l'output completo. */
    fun shell(command: String): String {
        val stream: AdbStream = openStream("shell:$command")
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val n = stream.read(buffer, 0, buffer.size)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        } catch (_: Exception) {
            // Il flusso si chiude a fine comando
        } finally {
            runCatching { stream.close() }
        }
        return out.toString(Charsets.UTF_8.name()).trim()
    }

    fun disconnectQuietly() {
        runCatching { disconnect() }
    }

    companion object {
        @Volatile
        private var instance: PonteAdb? = null

        fun get(context: Context): PonteAdb =
            instance ?: synchronized(this) {
                instance ?: PonteAdb(context.applicationContext).also { instance = it }
            }
    }
}

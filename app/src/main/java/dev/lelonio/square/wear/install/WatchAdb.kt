package dev.lelonio.square.wear.install

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.jce.X509Principal
import org.bouncycastle.x509.X509V3CertificateGenerator
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

/**
 * The phone as an ADB host, for the one time the watch app has to be installed
 * without the Play Store: a key of its own (kept, so the watch remembers the
 * pairing) and a self-signed certificate for the TLS that Wear OS's wireless
 * debugging speaks.
 *
 * The key never leaves the phone, and it authorises nothing until the person
 * holding the watch types the pairing code shown on it.
 */
class WatchAdb(context: Context) : AbsAdbConnectionManager() {

    private val dir = File(context.filesDir, "adb").apply { mkdirs() }
    private val keyFile = File(dir, "key.pk8")
    private val certFile = File(dir, "cert.der")

    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val kept = runCatching { readKey() to readCert() }.getOrNull()
        if (kept != null) {
            key = kept.first
            cert = kept.second
        } else {
            val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS, SecureRandom()) }
            val pair = generator.generateKeyPair()
            key = pair.private
            @Suppress("DEPRECATION")
            val built = X509V3CertificateGenerator().apply {
                val name = X509Principal("CN=$DEVICE_NAME")
                setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                setIssuerDN(name)
                setSubjectDN(name)
                setNotBefore(Date(System.currentTimeMillis() - DAY_MS))
                setNotAfter(Date(System.currentTimeMillis() + VALIDITY_MS))
                setPublicKey(pair.public)
                setSignatureAlgorithm("SHA512withRSA")
            }.generate(key)
            cert = built
            keyFile.writeBytes(key.encoded)
            certFile.writeBytes(cert.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = key

    override fun getCertificate(): Certificate = cert

    override fun getDeviceName(): String = DEVICE_NAME

    private fun readKey(): PrivateKey =
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))

    private fun readCert(): Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())

    private companion object {
        const val DEVICE_NAME = "Fluidify"
        const val KEY_BITS = 2048
        const val DAY_MS = 24 * 60 * 60_000L
        const val VALIDITY_MS = 10L * 365 * DAY_MS
    }
}

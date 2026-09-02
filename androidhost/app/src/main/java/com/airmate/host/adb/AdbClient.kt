package com.airmate.host.adb

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.security.auth.x500.X500Principal

/**
 * This app talking to its own device's adb daemon, over the loopback.
 *
 * The point of it is one capability: only the shell user may create a *trusted* virtual display,
 * and only a trusted display is one Samsung's desktop will attach itself to. Wireless debugging is
 * the sanctioned way for an app to become the shell user on its own device, and this is the client
 * for it — no Shizuku to install, no root, no desktop adb at the other end of a cable.
 *
 * The heavy parts — SPAKE2, the TLS handshake, the adb stream framing — are libadb-android's.
 */
class AdbClient(private val context: Context) : AbsAdbConnectionManager() {
    private val keys by lazy { AdbKeys.loadOrGenerate(context) }
    private val identity by lazy { loadOrGenerateCertificate(keys.keyPair) }

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(8, TimeUnit.SECONDS)
        setThrowOnUnauthorised(true)
    }

    override fun getPrivateKey(): PrivateKey = identity.first
    override fun getCertificate(): X509Certificate = identity.second
    override fun getDeviceName(): String = DEVICE_NAME

    /**
     * Trade the six-digit code for a lasting trust.
     *
     * This is the only moment a human is needed. Afterwards adbd knows our key and [connect] needs
     * no code, which is why the code is asked for once and never stored.
     */
    suspend fun pairWith(host: String, port: Int, code: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(super.pair(host, port, code)) { "the device rejected the pairing code" }
                Log.i(TAG, "paired with $host:$port")
                Unit
            }
        }

    suspend fun connectTo(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            runCatching { disconnect() }
            check(super.connect(host, port)) { "adb refused the connection" }
            Log.i(TAG, "connected to $host:$port")
            Unit
        }
    }

    /** Run a command and read everything it prints. For commands that end on their own. */
    suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        openStream("shell:$command").use { stream ->
            stream.openInputStream().bufferedReader().use { it.readText() }
        }
    }

    /**
     * Start a command and keep holding it.
     *
     * The host is launched this way rather than with [shell], because the stream *is* its lifetime:
     * the host watches its own stdin, so closing this stream is what tells it to release the
     * display and exit. A host started and forgotten would hold a display and a hardware encoder
     * until the phone was restarted.
     */
    fun openShell(command: String): AdbStream = openStream("shell:$command")

    val connected: Boolean get() = runCatching { isConnected }.getOrDefault(false)

    fun release() {
        runCatching { disconnect() }
    }

    /**
     * The certificate the pairing handshake presents, kept beside the key it belongs to.
     *
     * Regenerated whenever it does not match the stored key: a certificate for a key we no longer
     * have authenticates as nobody, and the failure surfaces halfway through a TLS handshake rather
     * than anywhere near the two files that caused it.
     */
    private fun loadOrGenerateCertificate(keyPair: KeyPair): Pair<PrivateKey, X509Certificate> {
        val file = File(context.filesDir, CERTIFICATE)
        if (file.exists()) {
            runCatching {
                val factory = CertificateFactory.getInstance("X.509")
                val certificate = file.inputStream().use {
                    factory.generateCertificate(it) as X509Certificate
                }
                require(certificate.publicKey.encoded.contentEquals(keyPair.public.encoded)) {
                    "stored certificate belongs to a different key"
                }
                return keyPair.private to certificate
            }.onFailure {
                Log.w(TAG, "regenerating the ADB certificate", it)
                file.delete()
            }
        }
        val certificate = selfSigned(keyPair)
        file.writeBytes(certificate.encoded)
        return keyPair.private to certificate
    }

    private fun selfSigned(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val subject = X500Principal("CN=$DEVICE_NAME")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            Date(now - ONE_DAY),
            Date(now + TWENTY_FIVE_YEARS),
            subject,
            keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    companion object {
        private const val TAG = "AirMate.Host.Adb"
        private const val CERTIFICATE = "airmate_adb.crt"

        /** What the device calls us in its list of paired debugging clients. */
        private const val DEVICE_NAME = "AirMate Host"

        private const val ONE_DAY = 86_400_000L
        private const val TWENTY_FIVE_YEARS = 25L * 365 * ONE_DAY
    }
}

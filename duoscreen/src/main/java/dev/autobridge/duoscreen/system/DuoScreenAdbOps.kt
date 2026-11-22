package dev.autobridge.duoscreen.system

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.view.KeyEvent
import android.view.MotionEvent
import dev.autobridge.duoscreen.DuoLog
import dev.autobridge.duoscreen.system.DuoScreenRootProtocol.Command
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.BufferedWriter
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Duo Screen's privileged ops through this phone's own **Wireless debugging**, with neither
 * Shizuku nor root: AutoBridge pairs with the phone's adbd once (a six-digit code, see
 * [DuoScreenAdbPairing]), connects to it, and through ADB's `exec:` service starts the very helper
 * the root backend starts with `su` - [DuoScreenRootServer] - which then runs as the shell user,
 * the same user Shizuku lends. Requests and replies use [DuoScreenRootProtocol] over the ADB
 * stream exactly as they do over the `su` pipe.
 *
 * What it can do is what the root backend can: launches, touch, keys, the task query and the
 * keyboard. It cannot create *trusted* pane displays (that call carries the car's Surface, a
 * binder, which a byte stream cannot), so on ADB alone the panes stop when the phone sleeps;
 * [DuoScreenOps] still takes trusted displays from Shizuku whenever Shizuku is granted too.
 *
 * Android turns Wireless debugging off after a restart, so [start] has to be run again then (one
 * tap: Connect); the pairing itself is kept.
 *
 * Experimental: added alongside Shizuku and root, which it does not change.
 */
object DuoScreenAdbOps : DuoScreenPrivilegedOps {
    private const val TAG = "AutoBridgeDuoAdb"

    /** How long to look for Wireless debugging on the network before calling it off. */
    private const val CONNECT_TIMEOUT_MS = 10_000L

    /** The helper prints READY once it is up; app_process starts in well under this. */
    private const val START_TIMEOUT_MS = 15_000L

    /** As [DuoScreenRootOps]: under the five seconds that would ANR a pane launch. */
    private const val REPLY_TIMEOUT_MS = 3_000L

    /** Why [start] did not bring the helper up, for the setup screen to say the right thing. */
    enum class Result { READY, NEEDS_PAIRING, WIRELESS_DEBUGGING_OFF, FAILED }

    @Volatile
    var lastError: String? = null
        private set

    private class Session(
        val stream: AdbStream,
        val writer: BufferedWriter,
        val replies: LinkedBlockingQueue<String>,
    )

    @Volatile
    private var session: Session? = null
    private var manager: Manager? = null

    private val requestLock = Any()
    private val startLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    override val isAvailable: Boolean
        get() = session?.stream?.isClosed == false

    /**
     * Connects to Wireless debugging and starts the helper, unless it is already running. Blocks
     * for up to [CONNECT_TIMEOUT_MS] + [START_TIMEOUT_MS]; never call it on the main thread.
     */
    fun start(context: Context): Result = synchronized(startLock) {
        if (isAvailable) return Result.READY
        val app = context.applicationContext
        val adb = runCatching { manager(app) }.getOrElse { error ->
            lastError = error.message
            DuoLog.w(TAG, "Could not set up the ADB key", error)
            return Result.FAILED
        }
        if (!adb.isConnected) {
            val connected = try {
                adb.connectTls(app, CONNECT_TIMEOUT_MS)
            } catch (e: AdbPairingRequiredException) {
                lastError = "pairing required"
                DuoLog.i(TAG, "Wireless debugging found, but AutoBridge is not paired yet")
                return Result.NEEDS_PAIRING
            } catch (e: InterruptedException) {
                lastError = "Wireless debugging not found"
                DuoLog.i(TAG, "Wireless debugging not found on the network within ${CONNECT_TIMEOUT_MS} ms")
                return Result.WIRELESS_DEBUGGING_OFF
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                DuoLog.w(TAG, "Could not connect to Wireless debugging", e)
                return Result.FAILED
            }
            if (!connected && !adb.isConnected) {
                lastError = "Wireless debugging not found"
                return Result.WIRELESS_DEBUGGING_OFF
            }
        }
        val apk = app.applicationInfo.sourceDir
        val command = "exec:CLASSPATH='$apk' exec /system/bin/app_process /system/bin ${DuoScreenRootServer::class.java.name}"
        val stream = runCatching { adb.openStream(command) }.getOrElse { error ->
            lastError = error.message ?: error.javaClass.simpleName
            DuoLog.w(TAG, "Could not start the helper over ADB", error)
            return Result.FAILED
        }
        val replies = LinkedBlockingQueue<String>()
        thread(name = "DuoAdbOut", isDaemon = true) {
            runCatching {
                stream.openInputStream().bufferedReader().forEachLine { line ->
                    val body = DuoScreenRootProtocol.replyBody(line)
                    if (body != null) replies.put(body) else DuoLog.i(TAG, "helper: $line")
                }
            }
            DuoLog.i(TAG, "ADB helper stream closed")
        }
        val first = replies.poll(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (first != DuoScreenRootProtocol.READY) {
            lastError = "helper did not start (${first ?: "no answer"})"
            DuoLog.w(TAG, "ADB helper did not start (${first ?: "no answer"})")
            runCatching { stream.close() }
            return Result.FAILED
        }
        session = Session(stream, stream.openOutputStream().bufferedWriter(), replies)
        lastError = null
        DuoLog.i(TAG, "ADB helper running as the shell user")
        Result.READY
    }

    /** [start] on a worker thread; [onDone] gets the outcome on the main thread. */
    fun startAsync(context: Context, onDone: (Result) -> Unit = {}) {
        val app = context.applicationContext
        thread(name = "DuoAdbStart", isDaemon = true) {
            val result = start(app)
            mainHandler.post { onDone(result) }
        }
    }

    /**
     * Pairs with Wireless debugging's "Pair device with pairing code" dialog listening on [port].
     * Blocks; never call it on the main thread. The key is kept, so this is done once.
     */
    fun pair(context: Context, host: String, port: Int, code: String): Boolean {
        val adb = manager(context.applicationContext)
        return try {
            adb.pair(host, port, code.trim())
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            DuoLog.w(TAG, "Pairing on $host:$port failed", e)
            false
        }
    }

    /** Ends the helper and the ADB connection. */
    fun stop() {
        val ended = synchronized(requestLock) { session.also { session = null } }
        if (ended != null) {
            runCatching { ended.writer.close() }
            runCatching { ended.stream.close() }
            DuoLog.i(TAG, "ADB helper stopped")
        }
        runCatching { manager?.disconnect() }
    }

    override fun launchOnDisplay(
        displayId: Int,
        packageName: String,
        component: ComponentName?,
        allowSecondInstance: Boolean,
    ): Boolean = request(
        Command.Launch(displayId, packageName, component?.flattenToString(), allowSecondInstance)
    ) == true

    override fun hasTaskOnDisplay(displayId: Int): Boolean? = request(Command.Tasks(displayId))

    override fun showImeOnDisplay(displayId: Int): Boolean = request(Command.Ime(displayId)) == true

    override fun injectMotion(event: MotionEvent, displayId: Int): Boolean =
        request(Command.Motion(displayId, marshall(event))) == true

    override fun injectKey(event: KeyEvent, displayId: Int): Boolean =
        request(Command.Key(displayId, marshall(event))) == true

    /** Sends [command] and waits for its reply; null when the helper is gone or does not answer. */
    private fun request(command: Command): Boolean? = synchronized(requestLock) {
        val active = session ?: return null
        if (active.stream.isClosed) {
            session = null
            DuoLog.w(TAG, "ADB helper has exited")
            return null
        }
        active.replies.clear()
        val reply = runCatching {
            active.writer.write(DuoScreenRootProtocol.encode(command))
            active.writer.newLine()
            active.writer.flush()
            active.replies.poll(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull()
        if (reply == null) {
            // As with root: a late reply would answer the next request, so start afresh next time.
            DuoLog.w(TAG, "ADB helper did not answer ${command.javaClass.simpleName}; stopping it")
            session = null
            runCatching { active.stream.close() }
            return null
        }
        DuoScreenRootProtocol.parseResult(reply)
    }

    private fun marshall(event: Parcelable): ByteArray {
        val parcel = Parcel.obtain()
        try {
            event.writeToParcel(parcel, 0)
            return parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    @Synchronized
    private fun manager(context: Context): Manager =
        manager ?: Manager(context).also { manager = it }

    /**
     * The ADB key AutoBridge pairs with: an RSA key and the self-signed certificate TLS carries it
     * in (adbd checks the key, not the certificate). Made once and kept in app-private storage, so
     * the pairing survives restarts.
     */
    private class Manager(context: Context) : AbsAdbConnectionManager() {
        private val privateKey: PrivateKey
        private val certificate: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            setHostAddress("127.0.0.1")
            setTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val dir = File(context.filesDir, "duo_adb").apply { mkdirs() }
            val keyFile = File(dir, "adbkey.pk8")
            val certFile = File(dir, "adbkey.crt")
            val loaded = runCatching {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
                key to cert
            }.getOrNull()
            if (loaded != null) {
                privateKey = loaded.first
                certificate = loaded.second
            } else {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val name = X500Name("CN=AutoBridge")
                val now = System.currentTimeMillis()
                val holder = JcaX509v3CertificateBuilder(
                    name,
                    BigInteger.valueOf(now),
                    Date(now - 86_400_000L),
                    Date(now + 30L * 365 * 86_400_000L),
                    name,
                    pair.public,
                ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
                privateKey = pair.private
                certificate = JcaX509CertificateConverter().getCertificate(holder)
                keyFile.writeBytes(privateKey.encoded)
                certFile.writeBytes(certificate.encoded)
            }
        }

        override fun getPrivateKey(): PrivateKey = privateKey
        override fun getCertificate(): Certificate = certificate
        override fun getDeviceName(): String = "AutoBridge"
    }
}

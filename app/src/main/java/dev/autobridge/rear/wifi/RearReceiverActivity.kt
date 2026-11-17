package dev.autobridge.rear.wifi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.R
import dev.autobridge.logging.StructuredLog
import dev.autobridge.rear.RearPlayer
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * Rear screen mode, on the passengers' tablet. See `docs/REAR_SCREEN.md`, phase 2.
 *
 * While open it listens for a paired phone ([RearServer]), announces itself on the Wi-Fi (NSD) and
 * shows how to pair: its name, address and the current code. A command from the phone plays over
 * that screen; Back or the phone's Stop returns to it. Closing this screen ends rear screen mode.
 */
class RearReceiverActivity : Activity() {

    companion object {
        private const val TAG = "REAR_WIFI_RX"
        private const val PREFS = "rear_receiver"
        private const val KEY_ID = "id"
        private const val KEY_TOKENS = "tokens"

        fun intent(context: Context) = Intent(context, RearReceiverActivity::class.java)

        /** The open receiver, for the on-device test. */
        @androidx.annotation.VisibleForTesting
        @Volatile
        internal var shown: RearReceiverActivity? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var rear: RearPlayer
    private lateinit var gate: PairingGate
    private var server: RearServer? = null
    private var port = 0
    private var registration: NsdManager.RegistrationListener? = null

    private lateinit var idle: View
    private lateinit var playerView: View
    private lateinit var codeView: TextView
    private lateinit var addressView: TextView
    private lateinit var pairedView: TextView

    /** Read by the server thread; written on the main thread whenever the player changes. */
    @Volatile private var status = RearProtocol.Status(playing = false, muted = false, title = "")

    internal val portForTest: Int get() = port
    internal val codeForTest: String get() = gate.code
    internal val statusForTest: RearProtocol.Status get() = status

    private val refresh = object : Runnable {
        override fun run() {
            showIdleFacts()
            main.postDelayed(this, 1_000L)
        }
    }

    private val target = object : RearServer.Target {
        override fun play(command: RearProtocol.Play) = onMain {
            rear.play(command.url, command.title, command.positionMs, command.muted)
            showPlayer(true)
            StructuredLog.i(TAG, "play from ${command.positionMs}ms muted=${command.muted}")
        }
        override fun toggle() = onMain { rear.toggle() }
        override fun mute() = onMain { rear.setMuted(!rear.muted) }
        override fun stop() = onMain { stopToIdle() }
        override fun status(): RearProtocol.Status = status
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val id = prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_ID, it).apply()
        }
        gate = PairingGate(
            tokens = prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty(),
            onTokensChanged = { prefs.edit().putStringSet(KEY_TOKENS, it).apply() },
        )
        rear = RearPlayer(this) {
            status = RearProtocol.Status(rear.isPlaying, rear.muted, rear.title)
        }

        playerView = rear.view(this).apply { visibility = View.GONE }
        idle = buildIdle()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(idle)
            addView(playerView)
        })

        val name = deviceName()
        server = RearServer(gate, RearProtocol.Info(id, name), target).also { port = it.start() }
        StructuredLog.i(TAG, "listening on $port")
        announce(name, id)
        // Back leaves a video for the pairing screen first, then closes rear screen mode.
        releaseBack = dev.autobridge.ui.SystemBack.register(this) {
            if (rear.hasMedia) stopToIdle() else finish()
        }
        shown = this
    }

    private var releaseBack: () -> Unit = {}

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(refresh)
    }


    override fun onDestroy() {
        if (shown === this) shown = null
        releaseBack()
        registration?.let { runCatching { getSystemService(NsdManager::class.java)?.unregisterService(it) } }
        server?.stop()
        rear.release()
        StructuredLog.i(TAG, "closed")
        super.onDestroy()
    }

    private fun stopToIdle() {
        rear.stop()
        showPlayer(false)
    }

    private fun showPlayer(show: Boolean) {
        playerView.visibility = if (show) View.VISIBLE else View.GONE
        idle.visibility = if (show) View.GONE else View.VISIBLE
        showIdleFacts()
    }

    private fun onMain(block: () -> Unit) {
        main.post(block)
    }

    private fun buildIdle(): View {
        fun text(size: Float, bold: Boolean = false, color: Int = 0xFFFFFFFF.toInt()) = TextView(this).apply {
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(6))
        }
        codeView = text(56f, bold = true)
        addressView = text(16f, color = 0xFFB0B0B0.toInt())
        pairedView = text(14f, color = 0xFFB0B0B0.toInt())
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(text(28f, bold = true).apply { text = getString(R.string.rear_rx_title) })
            addView(text(16f, color = 0xFFB0B0B0.toInt()).apply { text = getString(R.string.rear_rx_hint) })
            addView(codeView)
            addView(addressView)
            addView(pairedView)
            addView(Button(context).apply {
                text = getString(R.string.rear_rx_forget_all)
                setOnClickListener { gate.forgetAll(); showIdleFacts() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            })
        }
    }

    private fun showIdleFacts() {
        if (!::codeView.isInitialized) return
        codeView.text = getString(R.string.rear_rx_code, gate.code)
        val addresses = localAddresses().joinToString("  ·  ") { "$it:$port" }
        addressView.text = getString(R.string.rear_rx_address, deviceName(), addresses.ifEmpty { "—" })
        pairedView.text = getString(R.string.rear_rx_paired, pairedCount())
    }

    private fun pairedCount(): Int =
        getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_TOKENS, emptySet())?.size ?: 0

    private fun announce(name: String, id: String) {
        val manager = getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "AutoBridge rear · $name"
            serviceType = RearProtocol.SERVICE_TYPE
            port = this@RearReceiverActivity.port
            setAttribute("id", id)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = StructuredLog.i(TAG, "announced as ${info.serviceName}")
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = StructuredLog.w(TAG, "announce failed $code")
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { StructuredLog.w(TAG, "announce refused: $it") }
    }

    private fun deviceName(): String = Build.MODEL.orEmpty().ifBlank { "Tablet" }

    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

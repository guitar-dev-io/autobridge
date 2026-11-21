package dev.autobridge.aapdirect

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.autobridge.R
import dev.autobridge.aap.AapChannel
import dev.autobridge.aap.AapHeadUnit
import dev.autobridge.aap.AapKey
import dev.autobridge.aap.AapSensorEvent
import dev.autobridge.aap.AapSession
import dev.autobridge.aap.AapTouch
import dev.autobridge.aap.AapTransport
import dev.autobridge.aap.AapVideoConfig

/**
 * Owns one Android Auto direct connection: the link (USB, or Bluetooth + Wi-Fi), the protocol
 * session, the encoder, the car display and, when the user allows it, the sound sent to the car.
 *
 * USB: started by [UsbAccessoryActivity] when a car attaches the phone. Wireless: started from the
 * phone screen ("Listen for a wireless car"); it then keeps listening, and goes back to listening
 * after a session ends so the car can reconnect on the next drive. Ends on Stop.
 */
class DirectProjectionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var session: AapSession? = null
    @Volatile private var encoder: CarVideoEncoder? = null
    @Volatile private var compositor: MarginCompositor? = null
    @Volatile private var display: CarDisplay? = null
    private var wireless: WirelessLink? = null
    private var rfcomm: BluetoothSocket? = null
    private var listening = false
    private var projection: MediaProjection? = null
    private var audioChannel: AapChannel.Audio? = null
    private var audio: CarAudioCapture? = null
    private var lastNight: Boolean? = null
    private var lastDriving: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                DirectLog.add("stopped from the phone")
                listening = false
                wireless?.stop()
                wireless = null
                session?.stop() ?: stopSelf()
                publishCurrent()
                return START_NOT_STICKY
            }
            ACTION_DENSITY -> { applyDensity(); return START_NOT_STICKY }
            ACTION_SHOW_CAR -> { display?.let { if (!it.hasFront) it.launch(CarHomeActivity.intent(this)) }; return START_NOT_STICKY }
            ACTION_STOP_LISTEN -> {
                listening = false
                wireless?.stop()
                wireless = null
                DirectLog.add("wireless: stopped listening")
                if (session == null) stopSelf()
                publishCurrent()
                return START_NOT_STICKY
            }
        }
        if (!goForeground(withProjection = false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_LISTEN -> {
                listening = true
                if (session == null) startListening()
                publishCurrent()
            }
            ACTION_AUDIO -> startSound(intent)
            else -> {
                if (session != null) return START_NOT_STICKY
                val accessory = accessoryFrom(intent)
                if (accessory == null) {
                    DirectLog.add("no Android Auto accessory attached")
                    if (!listening) stopSelf()
                    return START_NOT_STICKY
                }
                val transport = UsbAccessoryTransport.open(getSystemService(UsbManager::class.java), accessory)
                if (transport == null) {
                    DirectLog.add("could not open the USB accessory (permission refused, or Android Auto has it)")
                    if (!listening) stopSelf()
                    return START_NOT_STICKY
                }
                wireless?.stop()
                wireless = null
                connect(transport)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        listening = false
        wireless?.stop()
        session?.stop()
        tearDown()
        projection?.stop()
        projection = null
        publish(Status(AapSession.State.CLOSED, null, null))
        super.onDestroy()
    }

    private fun accessoryFrom(intent: Intent?): UsbAccessory? {
        @Suppress("DEPRECATION")
        val passed = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }
        if (passed != null) return passed
        val manager = getSystemService(UsbManager::class.java)
        return manager.accessoryList?.firstOrNull { UsbAccessoryTransport.isAndroidAuto(it) && manager.hasPermission(it) }
    }

    private fun startListening() {
        if (DirectStore.loadCredentials(this) == null) {
            DirectLog.add("no certificate uploaded: upload one before listening")
            listening = false
            return
        }
        wireless?.stop()
        val link = WirelessLink(this) { socket, transport ->
            main.post {
                if (session != null) { transport.close(); runCatching { socket.close() }; return@post }
                rfcomm = socket
                connect(transport)
            }
        }
        wireless = link
        if (!link.start()) {
            wireless = null
            listening = false
        }
    }

    private fun connect(transport: AapTransport) {
        val credentials = DirectStore.loadCredentials(this)
        if (credentials == null) {
            DirectLog.add("no certificate uploaded: open AutoBridge › Android Auto direct and upload one")
            transport.close()
            stopSelf()
            return
        }
        DirectLog.add("certificate: ${credentials.subject}")
        val identity = AapSession.Identity(
            deviceName = Build.MODEL.orEmpty().ifBlank { "Android" },
            brand = Build.MANUFACTURER.orEmpty().ifBlank { "Android" },
            sendAudio = DirectStore.sendAudio(this),
        )
        val created = try {
            AapSession(transport, credentials, identity, listener)
        } catch (e: Exception) {
            DirectLog.add("TLS set-up failed: ${e.message}")
            transport.close()
            return
        }
        session = created
        publishCurrent()
        Thread({ created.run() }, "aa-direct-session").start()
    }

    private val listener = object : AapSession.Listener {
        override fun onLog(line: String) = DirectLog.add(line)

        override fun onState(state: AapSession.State) = publishCurrent()

        override fun onHeadUnit(headUnit: AapHeadUnit) = publishCurrent()

        override fun onVideoStart(config: AapVideoConfig) {
            main.post { startVideo(config) }
        }

        override fun onVideoStop() {
            main.post { tearDownVideo() }
        }

        override fun onVideoFocus(focused: Boolean) {
            if (focused) encoder?.requestKeyFrame()
        }

        override fun onAudioStart(channel: AapChannel.Audio) {
            main.post {
                audioChannel = channel
                if (projection != null) startCapture() else DirectLog.add("sound: car is ready; allow sound on the phone screen to send it")
                publishCurrent()
            }
        }

        override fun onAudioStop() {
            main.post { stopCapture(); publishCurrent() }
        }

        override fun onTouch(touch: AapTouch, touchWidth: Int, touchHeight: Int) {
            display?.touch(touch, touchWidth, touchHeight)
        }

        override fun onKey(key: AapKey) {
            display?.key(key)
        }

        override fun onSensor(event: AapSensorEvent) {
            event.night?.let { if (it != lastNight) { lastNight = it; DirectLog.add("car: night mode ${if (it) "on" else "off"}") } }
            event.drivingStatus?.let { if (it != lastDriving) { lastDriving = it; DirectLog.add("car: driving status $it") } }
        }

        override fun onClosed(reason: String) {
            main.post {
                tearDown()
                session = null
                runCatching { rfcomm?.close() }
                rfcomm = null
                lastEnd = reason
                if (listening) {
                    DirectLog.add("wireless: listening again for the next connection")
                    startListening()
                }
                publishCurrent()
                if (!listening) stopSelf()
            }
        }
    }

    private var lastEnd: String? = null

    private fun startVideo(config: AapVideoConfig) {
        tearDownVideo()
        val s = session ?: return
        val dpi = DirectStore.dpi(this).takeIf { it > 0 } ?: config.dpi.takeIf { it > 0 } ?: 160
        try {
            val enc = CarVideoEncoder(
                config,
                onConfig = { runCatching { s.sendVideoConfig(it) } },
                onFrame = { pts, data, _ -> runCatching { s.sendVideoFrame(pts, data) } },
                onError = { DirectLog.add(it) },
            )
            encoder = enc
            val cropped = config.marginWidth > 0 || config.marginHeight > 0
            val target = if (cropped) {
                DirectLog.add("car crops ${config.marginWidth}x${config.marginHeight}; drawing into the visible ${config.visibleWidth}x${config.visibleHeight}")
                MarginCompositor(enc.inputSurface, config.width, config.height, config.visibleWidth, config.visibleHeight)
                    .also { compositor = it }.inputSurface
            } else {
                enc.inputSurface
            }
            val car = CarDisplay(this, config.visibleWidth, config.visibleHeight, dpi, target)
            display = car
            DirectLog.add("car display ${config.visibleWidth}x${config.visibleHeight} at $dpi dpi")
            car.showSplash(getString(R.string.aa_direct_car_starting))
            car.launch(CarHomeActivity.intent(this))
            main.postDelayed({
                if (display === car && !car.hasFront) {
                    DirectLog.add("Android did not open the car screen from the background; open AutoBridge on the phone once")
                }
            }, 4000)
            publishCurrent()
        } catch (e: Exception) {
            DirectLog.add("could not start video: ${e.message}")
            s.stop()
        }
    }

    // ── Sound ────────────────────────────────────────────────────────────────

    /** The phone screen got the screen-capture consent; it arrives here as result code + data. */
    private fun startSound(intent: Intent) {
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        }
        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        if (data == null || code != Activity.RESULT_OK) return
        // The projection may only be created once the service runs with the mediaProjection type.
        if (!goForeground(withProjection = true)) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        val created = runCatching { manager.getMediaProjection(code, data) }.getOrNull()
        if (created == null) {
            DirectLog.add("sound: Android did not grant the capture")
            return
        }
        created.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post {
                    stopCapture()
                    projection = null
                    publishCurrent()
                }
            }
        }, main)
        projection?.stop()
        projection = created
        if (audioChannel != null) startCapture()
        publishCurrent()
    }

    private fun startCapture() {
        val p = projection ?: return
        val channel = audioChannel ?: return
        val s = session ?: return
        if (audio != null) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            DirectLog.add("sound: microphone permission (needed for capture) is not granted")
            return
        }
        val capture = CarAudioCapture(p, channel) { pts, pcm, length -> s.sendAudio(pts, pcm, 0, length) }
        if (capture.start()) audio = capture
    }

    private fun stopCapture() {
        audio?.stop()
        audio = null
    }

    /** The phone screen's Smaller / Larger buttons land here while a car is connected. */
    private fun applyDensity() {
        val dpi = DirectStore.dpi(this).takeIf { it > 0 } ?: return
        display?.setDensity(dpi)
    }

    private fun tearDownVideo() {
        display?.release()
        display = null
        compositor?.release()
        compositor = null
        encoder?.release()
        encoder = null
    }

    private fun tearDown() {
        stopCapture()
        audioChannel = null
        tearDownVideo()
    }

    private fun goForeground(withProjection: Boolean): Boolean = runCatching {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.aa_direct_title), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, DirectProjectionActivity.intent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DirectProjectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (session == null && listening) R.string.aa_direct_notification_listening else R.string.aa_direct_notification_text
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_car)
            .setContentTitle(getString(R.string.aa_direct_title))
            .setContentText(getString(text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.aa_direct_stop), stop).build())
            .setOngoing(true)
            .build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (withProjection || projection != null) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        startForeground(NOTIFICATION_ID, notification, type)
    }.onFailure { DirectLog.add("foreground service refused: ${it.message}") }.isSuccess

    private fun publishCurrent() {
        val s = session
        publish(
            Status(
                state = s?.state ?: AapSession.State.CLOSED,
                headUnit = s?.headUnit,
                video = s?.videoConfig,
                endReason = lastEnd,
                listening = listening && s == null,
                soundReady = audioChannel != null,
                soundOn = audio != null,
            )
        )
    }

    /** What the phone screen shows about the connection. */
    data class Status(
        val state: AapSession.State,
        val headUnit: AapHeadUnit?,
        val video: AapVideoConfig?,
        val endReason: String? = null,
        val listening: Boolean = false,
        val soundReady: Boolean = false,
        val soundOn: Boolean = false,
    )

    companion object {
        private const val CHANNEL_ID = "aa_direct"
        private const val NOTIFICATION_ID = 4311
        private const val ACTION_STOP = "dev.autobridge.aapdirect.STOP"
        private const val ACTION_DENSITY = "dev.autobridge.aapdirect.DENSITY"
        private const val ACTION_LISTEN = "dev.autobridge.aapdirect.LISTEN"
        private const val ACTION_STOP_LISTEN = "dev.autobridge.aapdirect.STOP_LISTEN"
        private const val ACTION_AUDIO = "dev.autobridge.aapdirect.AUDIO"
        private const val ACTION_SHOW_CAR = "dev.autobridge.aapdirect.SHOW_CAR"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        @Volatile var status: Status = Status(AapSession.State.CLOSED, null, null)
            private set
        private val listeners = LinkedHashSet<() -> Unit>()

        @Synchronized fun listen(listener: () -> Unit) { listeners += listener }
        @Synchronized fun unlisten(listener: () -> Unit) { listeners -= listener }

        private fun publish(next: Status) {
            status = next
            val copy = synchronized(this) { listeners.toList() }
            Handler(Looper.getMainLooper()).post { copy.forEach { it() } }
        }

        val running: Boolean get() = status.state != AapSession.State.CLOSED

        fun start(context: Context, accessory: UsbAccessory?) {
            val intent = Intent(context, DirectProjectionService::class.java)
            if (accessory != null) intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
            foreground(context, intent)
        }

        fun listenWireless(context: Context) =
            foreground(context, Intent(context, DirectProjectionService::class.java).setAction(ACTION_LISTEN))

        fun stopListening(context: Context) = plain(context, ACTION_STOP_LISTEN)

        fun stop(context: Context) = plain(context, ACTION_STOP)

        fun densityChanged(context: Context) {
            if (running) plain(context, ACTION_DENSITY)
        }

        /** Re-opens the car's home screen when Android blocked it from the background. */
        fun showCarHome(context: Context) {
            if (status.video != null) plain(context, ACTION_SHOW_CAR)
        }

        /** Hands the screen-capture consent result over, for sending sound to the car. */
        fun soundConsent(context: Context, code: Int, data: Intent) = foreground(
            context,
            Intent(context, DirectProjectionService::class.java).setAction(ACTION_AUDIO)
                .putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data),
        )

        private fun foreground(context: Context, intent: Intent) {
            runCatching { context.startForegroundService(intent) }
                .onFailure { DirectLog.add("could not start: ${it.message}") }
        }

        private fun plain(context: Context, action: String) {
            runCatching { context.startService(Intent(context, DirectProjectionService::class.java).setAction(action)) }
        }
    }
}

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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import java.net.InetAddress
import java.net.ServerSocket
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
    private var speedKmh: Int? = null
    private var parkingBrake: Boolean? = null

    // Video target kept so the car can switch between AutoBridge screens and the mirrored phone.
    private var videoConfig: AapVideoConfig? = null
    private var videoTarget: Surface? = null
    private var videoDpi = 160
    /** One per screen-capture consent: Android allows a projection a single virtual display. */
    private var mirrorDisplay: VirtualDisplay? = null
    @Volatile private var mirrorTouch: MirrorTouch? = null

    @Volatile private var dhuServer: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                DirectLog.add("stopped from the phone")
                listening = false
                wireless?.stop()
                wireless = null
                stopDhu()
                session?.stop() ?: stopSelf()
                publishCurrent()
                return START_NOT_STICKY
            }
            ACTION_DENSITY -> { applyDensity(); return START_NOT_STICKY }
            ACTION_SHOW_CAR -> { display?.let { if (!it.hasFront) it.launch(CarHomeActivity.intent(this)) }; return START_NOT_STICKY }
            ACTION_DHU_OFF -> {
                stopDhu()
                if (session == null && !listening) stopSelf()
                publishCurrent()
                return START_NOT_STICKY
            }
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
            ACTION_AUDIO -> onCaptureConsent(intent)
            ACTION_DHU_ON -> startDhu()
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
        stopDhu()
        session?.stop()
        tearDown()
        releaseMirror()
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

    /**
     * Test mode for Google's Desktop Head Unit on a computer: `adb forward tcp:5277 tcp:5277`, then
     * start desktop-head-unit, which connects to this port as the car would. Loopback only, so
     * nothing on the Wi-Fi can reach it; adb's forward arrives on loopback.
     */
    private fun startDhu() {
        if (dhuServer != null) return
        val server = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), DHU_PORT), 1)
            }
        }.onFailure { DirectLog.add("DHU: could not open port $DHU_PORT: ${it.message}") }.getOrNull() ?: return
        dhuServer = server
        DirectLog.add("DHU: waiting on port $DHU_PORT. On the computer: adb forward tcp:$DHU_PORT tcp:$DHU_PORT, then run desktop-head-unit")
        Thread({
            while (dhuServer === server) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                DirectLog.add("DHU: head unit connected")
                main.post {
                    if (session != null) runCatching { socket.close() }
                    else connect(TcpTransport(socket, null, this))
                }
            }
        }, "aa-direct-dhu").start()
        publishCurrent()
    }

    private fun stopDhu() {
        val server = dhuServer ?: return
        dhuServer = null
        runCatching { server.close() }
        DirectLog.add("DHU: stopped")
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
            mirrorTouch?.touch(touch, touchWidth, touchHeight) ?: display?.touch(touch, touchWidth, touchHeight)
        }

        override fun onKey(key: AapKey) {
            mirrorTouch?.key(key) ?: display?.key(key)
        }

        override fun onSensor(event: AapSensorEvent) {
            event.night?.let { if (it != lastNight) { lastNight = it; DirectLog.add("car: night mode ${if (it) "on" else "off"}") } }
            event.drivingStatus?.let { if (it != lastDriving) { lastDriving = it; DirectLog.add("car: driving status $it") } }
            var changed = false
            event.speedMps?.let { val kmh = (it * 3.6).toInt(); if (kmh != speedKmh) { speedKmh = kmh; changed = true } }
            event.parkingBrake?.let { if (it != parkingBrake) { parkingBrake = it; changed = true } }
            if (changed) main.post { publishCurrent() }
        }

        override fun onClosed(reason: String) {
            main.post {
                tearDown()
                releaseMirror()
                session = null
                speedKmh = null
                parkingBrake = null
                runCatching { rfcomm?.close() }
                rfcomm = null
                lastEnd = reason
                if (listening) {
                    DirectLog.add("wireless: listening again for the next connection")
                    startListening()
                }
                publishCurrent()
                if (!listening && dhuServer == null) stopSelf()
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
            videoConfig = config
            videoTarget = target
            videoDpi = dpi
            if (DirectStore.showPhoneScreen(this) && projection != null) startMirror() else startAppDisplay()
            publishCurrent()
        } catch (e: Exception) {
            DirectLog.add("could not start video: ${e.message}")
            s.stop()
        }
    }

    /** AutoBridge's own screens on a private display of their own. */
    private fun startAppDisplay() {
        val config = videoConfig ?: return
        val target = videoTarget ?: return
        if (DirectStore.showPhoneScreen(this)) {
            DirectLog.add("car shows AutoBridge until screen capture is allowed on the phone (needed to show the phone screen)")
        }
        val car = CarDisplay(this, config.visibleWidth, config.visibleHeight, videoDpi, target)
        display = car
        DirectLog.add("car display ${config.visibleWidth}x${config.visibleHeight} at $videoDpi dpi")
        car.showSplash(getString(R.string.aa_direct_car_starting))
        car.launch(CarHomeActivity.intent(this))
        main.postDelayed({
            if (display === car && !car.hasFront) {
                DirectLog.add("Android did not open the car screen from the background; open AutoBridge on the phone once")
            }
        }, 4000)
    }

    /** The whole phone screen, mirrored onto the car. */
    private fun startMirror() {
        val config = videoConfig ?: return
        val target = videoTarget ?: return
        val p = projection ?: return
        display?.release()
        display = null
        val w = config.visibleWidth
        val h = config.visibleHeight
        val existing = mirrorDisplay
        if (existing != null) {
            existing.resize(w, h, videoDpi)
            existing.surface = target
        } else {
            mirrorDisplay = runCatching {
                p.createVirtualDisplay(
                    "AutoBridge car mirror", w, h, videoDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, target, null, main,
                )
            }.onFailure { DirectLog.add("could not mirror the phone: ${it.message}") }.getOrNull()
        }
        if (mirrorDisplay == null) {
            startAppDisplay()
            return
        }
        val touch = MirrorTouch(this, w, h)
        mirrorTouch = touch
        DirectLog.add("car shows the phone screen ${w}x$h")
        if (!touch.available) {
            DirectLog.add("touch from the car needs Shizuku or the accessibility service (Settings › Car connection)")
        }
    }

    private fun releaseMirror() {
        mirrorTouch?.release()
        mirrorTouch = null
        runCatching { mirrorDisplay?.release() }
        mirrorDisplay = null
    }

    // ── Sound ────────────────────────────────────────────────────────────────

    /**
     * The phone screen got the screen-capture consent; it arrives here as result code + data. One
     * consent covers both uses: the phone's sound and, when chosen, the phone screen on the car.
     */
    private fun onCaptureConsent(intent: Intent) {
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
                    val wasMirroring = mirrorDisplay != null
                    releaseMirror()
                    projection = null
                    if (wasMirroring && videoTarget != null) startAppDisplay()
                    publishCurrent()
                }
            }
        }, main)
        releaseMirror()
        projection?.stop()
        projection = created
        if (session == null) {
            DirectLog.add("screen capture allowed; the car will show the phone screen as soon as it connects")
        }
        if (audioChannel != null) startCapture()
        if (DirectStore.showPhoneScreen(this) && videoTarget != null) startMirror()
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
        mirrorTouch?.release()
        mirrorTouch = null
        // The mirror display stays (a projection gets only one); it just loses its surface.
        runCatching { mirrorDisplay?.surface = null }
        videoTarget = null
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
                // Capture can be granted ahead of the car: while listening, or once connected. The
                // held projection then turns into the phone-screen mirror (or sound) the moment the
                // car's video starts, so nothing has to be tapped while driving up.
                needsCapture = projection == null &&
                    (DirectStore.showPhoneScreen(this) || DirectStore.sendAudio(this)) &&
                    (s != null || listening),
                captureHeld = projection != null,
                mirroring = mirrorDisplay != null && mirrorTouch != null,
                dhu = dhuServer != null,
                speedKmh = speedKmh,
                parkingBrake = parkingBrake,
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
        val needsCapture: Boolean = false,
        val captureHeld: Boolean = false,
        val mirroring: Boolean = false,
        val dhu: Boolean = false,
        val speedKmh: Int? = null,
        val parkingBrake: Boolean? = null,
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
        private const val ACTION_DHU_ON = "dev.autobridge.aapdirect.DHU_ON"
        private const val ACTION_DHU_OFF = "dev.autobridge.aapdirect.DHU_OFF"
        private const val DHU_PORT = 5277
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

        fun dhu(context: Context, on: Boolean) {
            if (on) foreground(context, Intent(context, DirectProjectionService::class.java).setAction(ACTION_DHU_ON))
            else plain(context, ACTION_DHU_OFF)
        }

        fun stop(context: Context) = plain(context, ACTION_STOP)

        fun densityChanged(context: Context) {
            if (running) plain(context, ACTION_DENSITY)
        }

        /** Re-opens the car's home screen when Android blocked it from the background. */
        fun showCarHome(context: Context) {
            if (status.video != null) plain(context, ACTION_SHOW_CAR)
        }

        /** Hands the screen-capture consent over, for sound and for showing the phone screen. */
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

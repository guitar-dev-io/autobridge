package dev.autobridge.aapdirect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.autobridge.R
import dev.autobridge.aap.AapHeadUnit
import dev.autobridge.aap.AapKey
import dev.autobridge.aap.AapSensorEvent
import dev.autobridge.aap.AapSession
import dev.autobridge.aap.AapTouch
import dev.autobridge.aap.AapVideoConfig

/**
 * Owns one Android Auto direct connection: the USB link, the protocol session, the encoder and the
 * car display. Started by [UsbAccessoryActivity] when a car attaches the phone; ends when the car
 * goes away, asks to stop, or the user presses Stop.
 */
class DirectProjectionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: AapSession? = null
    @Volatile private var encoder: CarVideoEncoder? = null
    @Volatile private var display: CarDisplay? = null
    private var lastNight: Boolean? = null
    private var lastDriving: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DirectLog.add("stopped from the phone")
            session?.stop() ?: stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DENSITY) {
            applyDensity()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (session != null) return START_NOT_STICKY
        val accessory = accessoryFrom(intent)
        if (accessory == null) {
            DirectLog.add("no Android Auto accessory attached")
            stopSelf()
            return START_NOT_STICKY
        }
        connect(accessory)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.stop()
        tearDownVideo()
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

    private fun connect(accessory: UsbAccessory) {
        val credentials = DirectStore.loadCredentials(this)
        if (credentials == null) {
            DirectLog.add("no certificate uploaded: open AutoBridge › Android Auto direct and upload one")
            stopSelf()
            return
        }
        val transport = UsbAccessoryTransport.open(getSystemService(UsbManager::class.java), accessory)
        if (transport == null) {
            DirectLog.add("could not open the USB accessory (permission refused, or Android Auto has it)")
            stopSelf()
            return
        }
        DirectLog.add("certificate: ${credentials.subject}")
        val identity = AapSession.Identity(
            deviceName = Build.MODEL.orEmpty().ifBlank { "Android" },
            brand = Build.MANUFACTURER.orEmpty().ifBlank { "Android" },
        )
        val created = AapSession(transport, credentials, identity, listener)
        session = created
        publish(Status(created.state, null, null))
        Thread({ created.run() }, "aa-direct-session").start()
    }

    private val listener = object : AapSession.Listener {
        override fun onLog(line: String) = DirectLog.add(line)

        override fun onState(state: AapSession.State) {
            val s = session
            publish(Status(state, s?.headUnit, s?.videoConfig))
        }

        override fun onHeadUnit(headUnit: AapHeadUnit) = publish(Status(session?.state ?: AapSession.State.AUTHENTICATED, headUnit, null))

        override fun onVideoStart(config: AapVideoConfig) {
            main.post { startVideo(config) }
        }

        override fun onVideoStop() {
            main.post { tearDownVideo() }
        }

        override fun onVideoFocus(focused: Boolean) {
            if (focused) encoder?.requestKeyFrame()
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
                tearDownVideo()
                session = null
                publish(Status(AapSession.State.CLOSED, null, null, reason))
                stopSelf()
            }
        }
    }

    private fun startVideo(config: AapVideoConfig) {
        tearDownVideo()
        val s = session ?: return
        if (config.marginWidth > 0 || config.marginHeight > 0) {
            DirectLog.add("car crops ${config.marginWidth}x${config.marginHeight} of the stream; edges may be hidden")
        }
        val dpi = DirectStore.dpi(this).takeIf { it > 0 } ?: config.dpi.takeIf { it > 0 } ?: 160
        try {
            val enc = CarVideoEncoder(
                config,
                onConfig = { runCatching { s.sendVideoConfig(it) } },
                onFrame = { pts, data, _ -> runCatching { s.sendVideoFrame(pts, data) } },
                onError = { DirectLog.add(it) },
            )
            encoder = enc
            val car = CarDisplay(this, config.width, config.height, dpi, enc.inputSurface)
            display = car
            DirectLog.add("car display ${config.width}x${config.height} at $dpi dpi")
            car.launch(CarHomeActivity.intent(this))
            publish(Status(s.state, s.headUnit, config))
        } catch (e: Exception) {
            DirectLog.add("could not start video: ${e.message}")
            s.stop()
        }
    }

    /** The phone screen's Smaller / Larger buttons land here while a car is connected. */
    private fun applyDensity() {
        val dpi = DirectStore.dpi(this).takeIf { it > 0 } ?: return
        display?.setDensity(dpi)
    }

    private fun tearDownVideo() {
        display?.release()
        display = null
        encoder?.release()
        encoder = null
    }

    private fun goForeground(): Boolean = runCatching {
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
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_car)
            .setContentTitle(getString(R.string.aa_direct_title))
            .setContentText(getString(R.string.aa_direct_notification_text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.aa_direct_stop), stop).build())
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }.onFailure { DirectLog.add("foreground service refused: ${it.message}") }.isSuccess

    /** What the phone screen shows about the connection. */
    data class Status(
        val state: AapSession.State,
        val headUnit: AapHeadUnit?,
        val video: AapVideoConfig?,
        val endReason: String? = null,
    )

    companion object {
        private const val CHANNEL_ID = "aa_direct"
        private const val NOTIFICATION_ID = 4311
        private const val ACTION_STOP = "dev.autobridge.aapdirect.STOP"
        private const val ACTION_DENSITY = "dev.autobridge.aapdirect.DENSITY"

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
            runCatching { context.startForegroundService(intent) }
                .onFailure { DirectLog.add("could not start: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, DirectProjectionService::class.java).setAction(ACTION_STOP))
            }
        }

        fun densityChanged(context: Context) {
            if (!running) return
            runCatching {
                context.startService(Intent(context, DirectProjectionService::class.java).setAction(ACTION_DENSITY))
            }
        }
    }
}

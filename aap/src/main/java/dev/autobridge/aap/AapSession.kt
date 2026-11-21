package dev.autobridge.aap

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The physical link: USB accessory streams today, a Wi-Fi socket later. */
interface AapTransport {
    val input: InputStream
    val output: OutputStream
    val description: String
    fun close()
}

/**
 * The phone side of one Android Auto connection.
 *
 * The head unit leads: it asks for the protocol version, opens TLS (it is the client, the phone the
 * server), and reports AUTH_COMPLETE. The phone then asks what the car offers (service discovery),
 * opens the channels it wants - video, touch input, sensors - and streams H.264 to the video
 * channel. Audio channels are not opened yet, so sound stays on the phone or its Bluetooth link.
 *
 * [run] reads the link on the calling thread until it closes. The video encoder calls
 * [sendVideoConfig] / [sendVideoFrame] from its own thread; every write goes out under one lock so
 * the frames of a message are never interleaved with another message's.
 */
class AapSession(
    private val transport: AapTransport,
    credentials: AapCredentials,
    private val identity: Identity,
    private val listener: Listener,
) {
    data class Identity(val deviceName: String, val brand: String)

    enum class State { WAITING_FOR_CAR, HANDSHAKE, AUTHENTICATED, CHANNELS_OPEN, STREAMING, CLOSED }

    interface Listener {
        fun onLog(line: String)
        fun onState(state: State) {}
        fun onHeadUnit(headUnit: AapHeadUnit) {}
        /** Start the encoder at [config]; frames go to [sendVideoFrame]. */
        fun onVideoStart(config: AapVideoConfig) {}
        fun onVideoStop() {}
        /** The car gave the screen back to this phone (true) or took it for its own UI (false). */
        fun onVideoFocus(focused: Boolean) {}
        fun onTouch(touch: AapTouch, touchWidth: Int, touchHeight: Int) {}
        fun onKey(key: AapKey) {}
        fun onSensor(event: AapSensorEvent) {}
        fun onClosed(reason: String) {}
    }

    private val tls = AapTls(credentials)
    private val writeLock = ReentrantLock()
    private val ackLock = ReentrantLock()
    private val ackChanged = ackLock.newCondition()
    private val partial = HashMap<Int, ByteArrayOutputStream>()

    @Volatile var state: State = State.WAITING_FOR_CAR
        private set
    @Volatile var headUnit: AapHeadUnit? = null
        private set
    @Volatile var videoConfig: AapVideoConfig? = null
        private set
    @Volatile private var videoFocused = true
    @Volatile private var closed = false
    private var maxUnacked = 1
    private var unacked = 0
    private var videoChannel = -1
    private var inputChannel: AapChannel.Input? = null
    private var pings = 0

    /** Reads until the link closes. Returns the reason it ended. */
    fun run(): String {
        listener.onLog("link up: ${transport.description}")
        setState(State.WAITING_FOR_CAR)
        val reason = try {
            while (!closed) handle(readMessage())
            "stopped"
        } catch (e: Exception) {
            if (closed) "stopped" else (e.message ?: e.javaClass.simpleName)
        }
        finish(reason)
        return reason
    }

    /** Asks the car to end the session, then drops the link. */
    fun stop() {
        if (closed) return
        runCatching {
            if (tls.handshakeDone) send(0, AapId.SHUTDOWN_REQUEST, AapMessages.shutdownRequest())
        }
        closed = true
        transport.close()
    }

    fun sendVideoConfig(data: ByteArray) {
        if (videoChannel < 0) return
        send(videoChannel, AapId.MEDIA_DATA, data)
    }

    /**
     * Sends one encoded frame. Waits briefly while the car has [maxUnacked] frames outstanding; a
     * frame that still has no room is sent anyway rather than dropped, since dropping a P-frame
     * would corrupt every frame after it until the next key frame. Returns false when the car is
     * not showing the phone (focus lost) and the frame was not sent.
     */
    fun sendVideoFrame(presentationUs: Long, data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Boolean {
        if (videoChannel < 0 || closed || !videoFocused) return false
        ackLock.withLock {
            var waited = 0L
            while (unacked >= maxUnacked && waited < ACK_WAIT_MS) {
                val start = System.nanoTime()
                ackChanged.await(ACK_WAIT_MS - waited, TimeUnit.MILLISECONDS)
                waited += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            }
            unacked++
        }
        val body = ByteArray(8 + length)
        for (i in 0 until 8) body[i] = (presentationUs ushr (56 - 8 * i)).toByte()
        System.arraycopy(data, offset, body, 8, length)
        send(videoChannel, AapId.MEDIA_WITH_TIMESTAMP, body)
        if (state != State.STREAMING) setState(State.STREAMING)
        return true
    }

    // ── Receiving ────────────────────────────────────────────────────────────

    private fun readMessage(): AapMessage {
        while (true) {
            val frame = FrameIo.read(transport.input)
            val plain = if (frame.encrypted) tls.decrypt(frame.payload) else frame.payload
            when (frame.position) {
                AapFrame.FLAG_BULK -> return AapMessage.parse(frame.channel, frame.control, frame.encrypted, plain)
                AapFrame.FLAG_FIRST -> partial[frame.channel] = ByteArrayOutputStream(frame.totalSize).also { it.write(plain) }
                AapFrame.FLAG_MIDDLE -> partial[frame.channel]?.write(plain)
                AapFrame.FLAG_LAST -> {
                    val buffer = partial.remove(frame.channel) ?: continue
                    buffer.write(plain)
                    return AapMessage.parse(frame.channel, frame.control, frame.encrypted, buffer.toByteArray())
                }
            }
        }
    }

    private fun handle(message: AapMessage) {
        if (message.channel == 0 && !message.control) {
            handleControl(message)
            return
        }
        if (message.id == AapId.CHANNEL_OPEN_RESPONSE) {
            onChannelOpened(message.channel, AapMessages.status(message.body) == 0)
            return
        }
        val hu = headUnit
        when (hu?.channels?.firstOrNull { it.id == message.channel }) {
            is AapChannel.Video -> handleVideo(message)
            is AapChannel.Input -> handleInput(message)
            is AapChannel.Sensor -> handleSensor(message)
            else -> listener.onLog("ignored $message")
        }
    }

    private fun handleControl(message: AapMessage) {
        when (message.id) {
            AapId.VERSION_REQUEST -> {
                val (major, minor) = AapMessages.parseVersionRequest(message.body)
                listener.onLog("car asks for protocol $major.$minor")
                send(0, AapId.VERSION_RESPONSE, AapMessages.versionResponse(major, minor, match = major == 1), encrypted = false)
                setState(State.HANDSHAKE)
            }
            AapId.SSL_HANDSHAKE -> {
                val reply = tls.handshake(message.body)
                if (reply.isNotEmpty()) send(0, AapId.SSL_HANDSHAKE, reply, encrypted = false)
                if (tls.handshakeDone) listener.onLog("TLS done: ${tls.protocol} ${tls.cipherSuite}")
            }
            AapId.AUTH_COMPLETE -> {
                val status = AapMessages.status(message.body)
                if (status != 0) throw IllegalStateException("car rejected authentication (status $status)")
                listener.onLog("car accepted this phone")
                setState(State.AUTHENTICATED)
                send(0, AapId.SERVICE_DISCOVERY_REQUEST, AapMessages.serviceDiscoveryRequest(identity.deviceName, identity.brand))
            }
            AapId.SERVICE_DISCOVERY_RESPONSE -> {
                val hu = AapHeadUnit.parse(message.body)
                headUnit = hu
                listener.onLog("car: ${hu.label} ${hu.carModel} ${hu.carYear} sw ${hu.swVersion}".trim())
                hu.channels.forEach { listener.onLog("  offers $it") }
                listener.onHeadUnit(hu)
                val wanted = listOfNotNull(hu.video, hu.input, hu.sensor)
                if (hu.video == null) throw IllegalStateException("car offers no video channel")
                wanted.forEach { send(it.id, AapId.CHANNEL_OPEN_REQUEST, AapMessages.channelOpenRequest(it.id), control = true) }
            }
            AapId.PING_REQUEST -> {
                send(0, AapId.PING_RESPONSE, AapMessages.pingResponse(message.body), encrypted = message.encrypted)
                if (++pings % 60 == 1) listener.onLog("ping #$pings answered")
            }
            AapId.PING_RESPONSE -> Unit
            AapId.SHUTDOWN_REQUEST -> {
                listener.onLog("car asked to end the session")
                runCatching { send(0, AapId.SHUTDOWN_RESPONSE, AapMessages.shutdownResponse()) }
                closed = true
            }
            AapId.SHUTDOWN_RESPONSE -> closed = true
            AapId.AUDIO_FOCUS_RESPONSE, AapId.NAVIGATION_FOCUS_RESPONSE ->
                listener.onLog("${AapId.controlName(message.id)} ${message.body.size}B")
            else -> listener.onLog("unhandled control ${AapId.controlName(message.id)}")
        }
    }

    private fun onChannelOpened(channel: Int, ok: Boolean) {
        val target = headUnit?.channels?.firstOrNull { it.id == channel }
        listener.onLog("channel $channel ${if (ok) "open" else "REFUSED"}: $target")
        if (!ok) return
        when (target) {
            is AapChannel.Video -> {
                videoChannel = channel
                send(channel, AapId.MEDIA_SETUP_REQUEST, AapMessages.mediaSetupRequest(MEDIA_CODEC_VIDEO_H264_BP))
            }
            is AapChannel.Input -> {
                inputChannel = target
                send(channel, AapId.INPUT_BINDING_REQUEST, AapMessages.bindingRequest(target.keycodes))
            }
            is AapChannel.Sensor -> {
                listOf(AapSensor.DRIVING_STATUS, AapSensor.NIGHT_DATA, AapSensor.CAR_SPEED, AapSensor.PARKING_BRAKE, AapSensor.GEAR)
                    .filter { it in target.sensors }
                    .forEach { send(channel, AapId.SENSOR_START_REQUEST, AapMessages.sensorStartRequest(it)) }
            }
            else -> Unit
        }
        setState(State.CHANNELS_OPEN)
    }

    private fun handleVideo(message: AapMessage) {
        when (message.id) {
            AapId.MEDIA_SETUP_RESPONSE -> {
                val setup = AapMessages.parseMediaSetupResponse(message.body)
                val configs = headUnit?.video?.configs.orEmpty()
                val index = setup.configs.firstOrNull { it in configs.indices } ?: 0
                val config = configs.getOrNull(index) ?: AapVideoConfig(0, 1, 1, 0, 0, 160)
                ackLock.withLock {
                    maxUnacked = setup.maxUnacked.coerceAtLeast(1)
                    unacked = 0
                }
                listener.onLog("video setup ${if (setup.ok) "ok" else "status not OK"}: $config, window ${setup.maxUnacked}")
                videoConfig = config
                send(message.channel, AapId.MEDIA_START, AapMessages.mediaStart(0, index))
                send(message.channel, AapId.VIDEO_FOCUS_REQUEST, AapMessages.videoFocusRequest(true))
                listener.onVideoStart(config)
            }
            AapId.MEDIA_ACK -> {
                val (_, count) = AapMessages.parseAck(message.body)
                ackLock.withLock {
                    unacked = (unacked - count.coerceAtLeast(1)).coerceAtLeast(0)
                    ackChanged.signalAll()
                }
            }
            AapId.VIDEO_FOCUS_INDICATION -> {
                val focused = AapMessages.parseVideoFocus(message.body)
                if (focused != videoFocused) listener.onLog(if (focused) "car shows this phone" else "car switched to its own screen")
                videoFocused = focused
                if (focused) ackLock.withLock { unacked = 0; ackChanged.signalAll() }
                listener.onVideoFocus(focused)
            }
            AapId.MEDIA_STOP -> listener.onVideoStop()
            else -> listener.onLog("video: unhandled 0x${message.id.toString(16)}")
        }
    }

    private fun handleInput(message: AapMessage) {
        when (message.id) {
            AapId.INPUT_EVENT -> {
                val event = AapMessages.parseInputEvent(message.body)
                val input = inputChannel
                event.touch?.let { listener.onTouch(it, input?.touchWidth ?: 0, input?.touchHeight ?: 0) }
                event.keys.forEach(listener::onKey)
            }
            AapId.INPUT_BINDING_RESPONSE -> listener.onLog("key binding ${if (AapMessages.status(message.body) == 0) "ok" else "refused"}")
            else -> listener.onLog("input: unhandled 0x${message.id.toString(16)}")
        }
    }

    private fun handleSensor(message: AapMessage) {
        when (message.id) {
            AapId.SENSOR_EVENT -> listener.onSensor(AapMessages.parseSensorEvent(message.body))
            AapId.SENSOR_START_RESPONSE -> Unit
            else -> listener.onLog("sensor: unhandled 0x${message.id.toString(16)}")
        }
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    private fun send(channel: Int, id: Int, body: ByteArray, encrypted: Boolean = true, control: Boolean = false) {
        val payload = ByteArray(2 + body.size)
        payload[0] = (id ushr 8).toByte()
        payload[1] = id.toByte()
        System.arraycopy(body, 0, payload, 2, body.size)
        val base = (if (encrypted) AapFrame.FLAG_ENCRYPTED else 0) or (if (control) AapFrame.FLAG_CONTROL else 0)
        writeLock.withLock {
            val out = transport.output
            if (payload.size < AapFrame.MAX_FRAME_PAYLOAD) {
                out.write(FrameIo.encode(channel, base or AapFrame.FLAG_BULK, wrap(payload, 0, payload.size, encrypted), payload.size))
            } else {
                var offset = 0
                while (offset < payload.size) {
                    val size = minOf(AapFrame.MAX_FRAME_PAYLOAD, payload.size - offset)
                    val position = when {
                        offset == 0 -> AapFrame.FLAG_FIRST
                        offset + size >= payload.size -> AapFrame.FLAG_LAST
                        else -> AapFrame.FLAG_MIDDLE
                    }
                    out.write(FrameIo.encode(channel, base or position, wrap(payload, offset, size, encrypted), payload.size))
                    offset += size
                }
            }
            out.flush()
        }
    }

    private fun wrap(payload: ByteArray, offset: Int, size: Int, encrypted: Boolean): ByteArray =
        if (encrypted) tls.encrypt(payload, offset, size) else payload.copyOfRange(offset, offset + size)

    private fun setState(next: State) {
        if (state == next) return
        state = next
        listener.onState(next)
    }

    private fun finish(reason: String) {
        closed = true
        runCatching { transport.close() }
        ackLock.withLock { ackChanged.signalAll() }
        setState(State.CLOSED)
        listener.onLog("session ended: $reason")
        listener.onClosed(reason)
    }

    private companion object {
        const val ACK_WAIT_MS = 500L
    }
}

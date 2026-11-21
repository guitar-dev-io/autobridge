package dev.autobridge.aap

/** Message ids. Control ids are used on channel 0 and, for channel open, on every channel. */
object AapId {
    const val VERSION_REQUEST = 0x0001
    const val VERSION_RESPONSE = 0x0002
    const val SSL_HANDSHAKE = 0x0003
    const val AUTH_COMPLETE = 0x0004
    const val SERVICE_DISCOVERY_REQUEST = 0x0005
    const val SERVICE_DISCOVERY_RESPONSE = 0x0006
    const val CHANNEL_OPEN_REQUEST = 0x0007
    const val CHANNEL_OPEN_RESPONSE = 0x0008
    const val PING_REQUEST = 0x000b
    const val PING_RESPONSE = 0x000c
    const val NAVIGATION_FOCUS_REQUEST = 0x000d
    const val NAVIGATION_FOCUS_RESPONSE = 0x000e
    const val SHUTDOWN_REQUEST = 0x000f
    const val SHUTDOWN_RESPONSE = 0x0010
    const val VOICE_SESSION_REQUEST = 0x0011
    const val AUDIO_FOCUS_REQUEST = 0x0012
    const val AUDIO_FOCUS_RESPONSE = 0x0013

    // Audio/video channels.
    const val MEDIA_WITH_TIMESTAMP = 0x0000
    const val MEDIA_DATA = 0x0001
    const val MEDIA_SETUP_REQUEST = 0x8000
    const val MEDIA_START = 0x8001
    const val MEDIA_STOP = 0x8002
    const val MEDIA_SETUP_RESPONSE = 0x8003
    const val MEDIA_ACK = 0x8004
    const val VIDEO_FOCUS_REQUEST = 0x8007
    const val VIDEO_FOCUS_INDICATION = 0x8008

    // Input channel.
    const val INPUT_EVENT = 0x8001
    const val INPUT_BINDING_REQUEST = 0x8002
    const val INPUT_BINDING_RESPONSE = 0x8003

    // Sensor channel.
    const val SENSOR_START_REQUEST = 0x8001
    const val SENSOR_START_RESPONSE = 0x8002
    const val SENSOR_EVENT = 0x8003

    fun controlName(id: Int): String = when (id) {
        VERSION_REQUEST -> "VERSION_REQUEST"
        VERSION_RESPONSE -> "VERSION_RESPONSE"
        SSL_HANDSHAKE -> "SSL_HANDSHAKE"
        AUTH_COMPLETE -> "AUTH_COMPLETE"
        SERVICE_DISCOVERY_REQUEST -> "SERVICE_DISCOVERY_REQUEST"
        SERVICE_DISCOVERY_RESPONSE -> "SERVICE_DISCOVERY_RESPONSE"
        CHANNEL_OPEN_REQUEST -> "CHANNEL_OPEN_REQUEST"
        CHANNEL_OPEN_RESPONSE -> "CHANNEL_OPEN_RESPONSE"
        PING_REQUEST -> "PING_REQUEST"
        PING_RESPONSE -> "PING_RESPONSE"
        NAVIGATION_FOCUS_REQUEST -> "NAVIGATION_FOCUS_REQUEST"
        NAVIGATION_FOCUS_RESPONSE -> "NAVIGATION_FOCUS_RESPONSE"
        SHUTDOWN_REQUEST -> "SHUTDOWN_REQUEST"
        SHUTDOWN_RESPONSE -> "SHUTDOWN_RESPONSE"
        VOICE_SESSION_REQUEST -> "VOICE_SESSION_REQUEST"
        AUDIO_FOCUS_REQUEST -> "AUDIO_FOCUS_REQUEST"
        AUDIO_FOCUS_RESPONSE -> "AUDIO_FOCUS_RESPONSE"
        else -> "0x${id.toString(16)}"
    }
}

/** Sensor types the head unit may offer (SensorType in the protocol). */
object AapSensor {
    const val LOCATION = 1
    const val COMPASS = 2
    const val CAR_SPEED = 3
    const val RPM = 4
    const val ODOMETER = 5
    const val FUEL_LEVEL = 6
    const val PARKING_BRAKE = 7
    const val GEAR = 8
    const val NIGHT_DATA = 10
    const val DRIVING_STATUS = 13
}

/** Codec type the phone announces in its video setup request: H.264 baseline. */
const val MEDIA_CODEC_VIDEO_H264_BP = 3

/** One video mode the head unit can show: the stream size, frame rate, the margins it crops, and its density. */
data class AapVideoConfig(
    val index: Int,
    val resolution: Int,
    val fps: Int,
    val marginWidth: Int,
    val marginHeight: Int,
    val dpi: Int,
) {
    val width: Int get() = when (resolution) {
        1 -> 800
        2 -> 1280
        3 -> 1920
        4 -> 2560
        5 -> 3840
        6 -> 720
        7 -> 1080
        8 -> 1440
        9 -> 2160
        else -> 800
    }
    val height: Int get() = when (resolution) {
        1 -> 480
        2 -> 720
        3 -> 1080
        4 -> 1440
        5 -> 2160
        6 -> 1280
        7 -> 1920
        8 -> 2560
        9 -> 3840
        else -> 480
    }
    val framesPerSecond: Int get() = if (fps == 2) 60 else 30
    /** The part of the stream the car actually shows. */
    val visibleWidth: Int get() = (width - marginWidth).coerceAtLeast(1)
    val visibleHeight: Int get() = (height - marginHeight).coerceAtLeast(1)

    override fun toString(): String =
        "#$index ${width}x$height@$framesPerSecond margins ${marginWidth}x$marginHeight dpi $dpi"
}

sealed class AapChannel(val id: Int) {
    class Video(id: Int, val configs: List<AapVideoConfig>) : AapChannel(id)
    class Audio(id: Int, val audioType: Int, val sampleRate: Int, val bits: Int, val channels: Int) : AapChannel(id)
    class Input(id: Int, val keycodes: List<Int>, val touchWidth: Int, val touchHeight: Int) : AapChannel(id)
    class Sensor(id: Int, val sensors: List<Int>) : AapChannel(id)
    class Microphone(id: Int) : AapChannel(id)
    class Bluetooth(id: Int, val address: String) : AapChannel(id)
    class Navigation(id: Int) : AapChannel(id)
    class Other(id: Int, val field: Int) : AapChannel(id)

    override fun toString(): String = when (this) {
        is Video -> "video#$id ${configs.joinToString()}"
        is Audio -> "audio#$id type=$audioType ${sampleRate}Hz/$bits/$channels"
        is Input -> "input#$id touch ${touchWidth}x$touchHeight keys=${keycodes.size}"
        is Sensor -> "sensor#$id ${sensors.joinToString(",")}"
        is Microphone -> "mic#$id"
        is Bluetooth -> "bluetooth#$id"
        is Navigation -> "navigation#$id"
        is Other -> "other#$id(field $field)"
    }
}

/** What the head unit said about itself and the channels it offers. */
data class AapHeadUnit(
    val name: String,
    val carModel: String,
    val carYear: String,
    val manufacturer: String,
    val model: String,
    val swVersion: String,
    val leftHandDrive: Boolean,
    val channels: List<AapChannel>,
) {
    val video: AapChannel.Video? get() = channels.filterIsInstance<AapChannel.Video>().firstOrNull()
    val input: AapChannel.Input? get() = channels.filterIsInstance<AapChannel.Input>().firstOrNull()
    val sensor: AapChannel.Sensor? get() = channels.filterIsInstance<AapChannel.Sensor>().firstOrNull()

    val label: String get() = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ")
        .ifBlank { name.ifBlank { "head unit" } }

    companion object {
        fun parse(body: ByteArray): AapHeadUnit {
            val f = ProtoReader.fields(body)
            val channels = f.all(1).mapNotNull { it.bytes?.let(::parseChannel) }
            return AapHeadUnit(
                name = f.first(2)?.string.orEmpty(),
                carModel = f.first(3)?.string.orEmpty(),
                carYear = f.first(4)?.string.orEmpty(),
                manufacturer = f.first(7)?.string.orEmpty(),
                model = f.first(8)?.string.orEmpty(),
                swVersion = f.first(10)?.string.orEmpty(),
                leftHandDrive = f.first(6)?.bool ?: true,
                channels = channels,
            )
        }

        private fun parseChannel(bytes: ByteArray): AapChannel {
            val f = ProtoReader.fields(bytes)
            val id = f.first(1)?.int ?: -1
            f.first(2)?.bytes?.let { sensor ->
                val types = ProtoReader.fields(sensor).all(1).mapNotNull { s ->
                    s.bytes?.let { ProtoReader.fields(it).first(1)?.int }
                }
                return AapChannel.Sensor(id, types)
            }
            f.first(3)?.bytes?.let { av ->
                val a = ProtoReader.fields(av)
                val streamType = a.first(1)?.int ?: 0
                if (streamType == 3) {
                    val configs = a.all(4).mapIndexedNotNull { index, c ->
                        c.bytes?.let {
                            val v = ProtoReader.fields(it)
                            AapVideoConfig(
                                index = index,
                                resolution = v.first(1)?.int ?: 1,
                                fps = v.first(2)?.int ?: 1,
                                marginWidth = v.first(3)?.int ?: 0,
                                marginHeight = v.first(4)?.int ?: 0,
                                dpi = v.first(5)?.int ?: 0,
                            )
                        }
                    }
                    return AapChannel.Video(id, configs)
                }
                val audio = a.first(3)?.bytes?.let(ProtoReader::fields)
                return AapChannel.Audio(
                    id,
                    audioType = a.first(2)?.int ?: 0,
                    sampleRate = audio?.first(1)?.int ?: 0,
                    bits = audio?.first(2)?.int ?: 0,
                    channels = audio?.first(3)?.int ?: 0,
                )
            }
            f.first(4)?.bytes?.let { input ->
                val i = ProtoReader.fields(input)
                val touch = i.first(2)?.bytes?.let(ProtoReader::fields)
                return AapChannel.Input(
                    id,
                    keycodes = i.varints(1).map { it.toInt() },
                    touchWidth = touch?.first(1)?.int ?: 0,
                    touchHeight = touch?.first(2)?.int ?: 0,
                )
            }
            if (f.first(5) != null) return AapChannel.Microphone(id)
            f.first(6)?.bytes?.let { return AapChannel.Bluetooth(id, ProtoReader.fields(it).first(1)?.string.orEmpty()) }
            if (f.first(8) != null) return AapChannel.Navigation(id)
            return AapChannel.Other(id, f.firstOrNull { it.number != 1 }?.number ?: 0)
        }
    }
}

/** A touch from the car screen, in the head unit's touch coordinate space. */
data class AapTouch(
    val action: Int,
    val actionIndex: Int,
    val pointers: List<Pointer>,
    val timestampNanos: Long,
) {
    data class Pointer(val id: Int, val x: Int, val y: Int)

    companion object {
        const val PRESS = 0
        const val RELEASE = 1
        const val DRAG = 2
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
    }
}

/** A hardware key from the car (steering wheel, rotary, back button), as an Android keycode. */
data class AapKey(val keycode: Int, val down: Boolean, val longPress: Boolean)

/** Sensor readings the session forwards; null fields were not in this event. */
data class AapSensorEvent(
    val night: Boolean? = null,
    val drivingStatus: Int? = null,
    /** Metres per second, when the car reports speed. */
    val speedMps: Double? = null,
    val parkingBrake: Boolean? = null,
    val gear: Int? = null,
)

/** Encoders and decoders for the message bodies the phone sends or reads. */
object AapMessages {
    fun versionResponse(major: Int, minor: Int, match: Boolean): ByteArray = byteArrayOf(
        (major ushr 8).toByte(), major.toByte(),
        (minor ushr 8).toByte(), minor.toByte(),
        if (match) 0 else 0xFF.toByte(), if (match) 0 else 0xFF.toByte(),
    )

    fun parseVersionRequest(body: ByteArray): Pair<Int, Int> {
        val major = if (body.size >= 2) ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF) else 1
        val minor = if (body.size >= 4) ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF) else 0
        return major to minor
    }

    fun status(body: ByteArray): Int = ProtoReader.fields(body).first(1)?.int ?: 0

    fun serviceDiscoveryRequest(deviceName: String, brand: String): ByteArray =
        ProtoWriter().string(4, deviceName).string(5, brand).toByteArray()

    fun channelOpenRequest(channel: Int, priority: Int = 0): ByteArray =
        ProtoWriter().int(1, priority).int(2, channel).toByteArray()

    fun pingResponse(requestBody: ByteArray): ByteArray {
        val ts = ProtoReader.fields(requestBody).first(1)?.varint ?: 0L
        return ProtoWriter().int(1, ts).toByteArray()
    }

    fun shutdownResponse(): ByteArray = ByteArray(0)

    fun shutdownRequest(reason: Int = 1): ByteArray = ProtoWriter().int(1, reason).toByteArray()

    fun mediaSetupRequest(codec: Int): ByteArray = ProtoWriter().uint(1, codec).toByteArray()

    fun mediaStart(session: Int, config: Int): ByteArray = ProtoWriter().int(1, session).uint(2, config).toByteArray()

    fun mediaStop(): ByteArray = ByteArray(0)

    /** FOCUSED = 1, UNFOCUSED = 2. */
    fun videoFocusRequest(focused: Boolean): ByteArray =
        ProtoWriter().int(1, 0).int(2, if (focused) 1 else 2).int(3, 0).toByteArray()

    fun bindingRequest(keycodes: List<Int>): ByteArray = ProtoWriter().apply {
        keycodes.forEach { int(1, it) }
    }.toByteArray()

    fun sensorStartRequest(type: Int): ByteArray = ProtoWriter().int(1, type).int(2, 0).toByteArray()

    /** media_status (2 = OK), max_unacked, preferred config indexes. */
    class MediaSetup(val ok: Boolean, val maxUnacked: Int, val configs: List<Int>)

    fun parseMediaSetupResponse(body: ByteArray): MediaSetup {
        val f = ProtoReader.fields(body)
        return MediaSetup(
            ok = (f.first(1)?.int ?: 0) == 2,
            maxUnacked = f.first(2)?.int ?: 1,
            configs = f.varints(3).map { it.toInt() },
        )
    }

    /** (session, acknowledged count). */
    fun parseAck(body: ByteArray): Pair<Int, Int> {
        val f = ProtoReader.fields(body)
        return (f.first(1)?.int ?: 0) to (f.first(2)?.int ?: 1)
    }

    /** True when the car gives this phone the screen. */
    fun parseVideoFocus(body: ByteArray): Boolean = (ProtoReader.fields(body).first(1)?.int ?: 1) == 1

    class InputEvent(val touch: AapTouch?, val keys: List<AapKey>)

    fun parseInputEvent(body: ByteArray): InputEvent {
        val f = ProtoReader.fields(body)
        val ts = f.first(1)?.varint ?: 0L
        val touch = f.first(3)?.bytes?.let { t ->
            val tf = ProtoReader.fields(t)
            val pointers = tf.all(1).mapNotNull { p ->
                p.bytes?.let {
                    val pf = ProtoReader.fields(it)
                    AapTouch.Pointer(pf.first(3)?.int ?: 0, pf.first(1)?.int ?: 0, pf.first(2)?.int ?: 0)
                }
            }
            AapTouch(tf.first(3)?.int ?: 0, tf.first(2)?.int ?: 0, pointers, ts)
        }
        val keys = f.first(4)?.bytes?.let { b ->
            ProtoReader.fields(b).all(1).mapNotNull { e ->
                e.bytes?.let {
                    val k = ProtoReader.fields(it)
                    AapKey(k.first(1)?.int ?: 0, k.first(2)?.bool ?: false, k.first(4)?.bool ?: false)
                }
            }
        }.orEmpty()
        return InputEvent(touch, keys)
    }

    fun parseSensorEvent(body: ByteArray): AapSensorEvent {
        val f = ProtoReader.fields(body)
        fun inner(field: Int): List<ProtoField>? = f.all(field).lastOrNull()?.bytes?.let(ProtoReader::fields)
        return AapSensorEvent(
            night = inner(10)?.first(1)?.bool,
            drivingStatus = inner(13)?.first(1)?.int,
            speedMps = inner(3)?.first(1)?.int?.let { it / 1000.0 },
            parkingBrake = inner(7)?.first(1)?.bool,
            gear = inner(8)?.first(1)?.int,
        )
    }
}

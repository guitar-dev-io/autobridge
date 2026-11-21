package dev.autobridge.aap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.X509TrustManager

/**
 * Runs a whole session against a scripted head unit over in-memory pipes: version exchange, TLS
 * with the phone as server, service discovery, channel opens, video setup, a video frame big
 * enough to be split over several encrypted frames, a touch, a ping, and shutdown.
 */
class AapSessionTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private fun credentials() = AapCredentials.fromPem(resource("test_cert.pem") + resource("test_key_pkcs8.pem"))

    @Test
    fun pkcs1KeyIsAccepted() {
        val c = AapCredentials.fromPem(resource("test_cert.pem") + "\n" + resource("test_key_pkcs1.pem"))
        assertEquals("RSA", c.key.algorithm)
        assertTrue(c.subject.contains("AutoBridge test phone"))
    }

    @Test
    fun wholeSession() {
        val carToPhonePipe = Pipe()
        val phoneToCarPipe = Pipe()
        val carToPhone = carToPhonePipe.output
        val phoneIn = carToPhonePipe.input
        val phoneToCar = phoneToCarPipe.output
        val carIn = phoneToCarPipe.input
        val transport = object : AapTransport {
            override val input: InputStream = phoneIn
            override val output: OutputStream = phoneToCar
            override val description = "pipe"
            override fun close() {
                runCatching { phoneIn.close() }
                runCatching { phoneToCar.close() }
            }
        }

        val videoStarted = CountDownLatch(1)
        val audioStarted = CountDownLatch(1)
        val touches = LinkedBlockingQueue<AapTouch>()
        val sensors = LinkedBlockingQueue<AapSensorEvent>()
        val log = StringBuffer()
        lateinit var session: AapSession
        session = AapSession(transport, credentials(), AapSession.Identity("Test phone", "AutoBridge", sendAudio = true), object : AapSession.Listener {
            override fun onLog(line: String) { log.append(line).append('\n') }
            override fun onVideoStart(config: AapVideoConfig) { videoStarted.countDown() }
            override fun onAudioStart(channel: AapChannel.Audio) { audioStarted.countDown() }
            override fun onTouch(touch: AapTouch, touchWidth: Int, touchHeight: Int) { touches.add(touch) }
            override fun onSensor(event: AapSensorEvent) { sensors.add(event) }
        })
        val phone = Thread { session.run() }.apply { start() }

        val car = FakeCar(carIn, carToPhone)

        // Version.
        car.send(0, AapId.VERSION_REQUEST, byteArrayOf(0, 1, 0, 1), encrypted = false)
        val version = car.read()
        assertEquals(AapId.VERSION_RESPONSE, version.id)
        assertArrayEquals(byteArrayOf(0, 1, 0, 1, 0, 0), version.body)

        // TLS, car as client.
        car.handshake()

        car.send(0, AapId.AUTH_COMPLETE, ProtoWriter().int(1, 0).toByteArray(), encrypted = false)
        val discovery = car.read()
        assertEquals(AapId.SERVICE_DISCOVERY_REQUEST, discovery.id)
        assertTrue(discovery.encrypted)
        assertEquals("Test phone", ProtoReader.fields(discovery.body).first(4)?.string)

        // Discovery: video (one 720p config), input with touch 1280x720, sensors, the media audio channel.
        val video = ProtoWriter().uint(1, 3).message(4, ProtoWriter().uint(1, 2).uint(2, 1).uint(3, 0).uint(4, 0).uint(5, 160))
        val input = ProtoWriter().uint(1, 3).uint(1, 4).message(2, ProtoWriter().uint(1, 1280).uint(2, 720))
        val sensor = ProtoWriter().message(1, ProtoWriter().uint(1, AapSensor.DRIVING_STATUS)).message(1, ProtoWriter().uint(1, AapSensor.NIGHT_DATA))
        val audio = ProtoWriter().uint(1, 1).uint(2, 3).message(3, ProtoWriter().uint(1, 48000).uint(2, 16).uint(3, 2))
        val response = ProtoWriter()
            .message(1, ProtoWriter().uint(1, 1).message(4, input))
            .message(1, ProtoWriter().uint(1, 2).message(2, sensor))
            .message(1, ProtoWriter().uint(1, 3).message(3, video))
            .message(1, ProtoWriter().uint(1, 4).message(3, audio))
            .string(2, "Test HU").string(7, "Maker").string(8, "Model X")
        car.send(0, AapId.SERVICE_DISCOVERY_RESPONSE, response.toByteArray())

        val opens = try { (1..4).map { car.read() } } catch (e: Exception) { throw AssertionError("phone log:\n$log", e) }
        assertTrue(opens.all { it.id == AapId.CHANNEL_OPEN_REQUEST && it.control })
        assertEquals(setOf(1, 2, 3, 4), opens.map { it.channel }.toSet())
        opens.forEach { car.send(it.channel, AapId.CHANNEL_OPEN_RESPONSE, ProtoWriter().int(1, 0).toByteArray(), control = true) }

        // Follow-ups: binding on input, sensor starts, video setup. Order across channels may vary.
        val followUps = (1..5).map { car.read() }
        assertEquals(AapId.MEDIA_SETUP_REQUEST, followUps.single { it.channel == 4 }.id)
        val setup = followUps.single { it.channel == 3 }
        assertEquals(AapId.MEDIA_SETUP_REQUEST, setup.id)
        assertEquals(1, followUps.count { it.channel == 1 && it.id == AapId.INPUT_BINDING_REQUEST })
        assertEquals(2, followUps.count { it.channel == 2 && it.id == AapId.SENSOR_START_REQUEST })

        car.send(3, AapId.MEDIA_SETUP_RESPONSE, ProtoWriter().uint(1, 2).uint(2, 4).uint(3, 0).toByteArray())
        assertEquals(AapId.MEDIA_START, car.read().id)
        assertEquals(AapId.VIDEO_FOCUS_REQUEST, car.read().id)
        assertTrue(videoStarted.await(5, TimeUnit.SECONDS))
        assertEquals(1280, session.videoConfig?.width)

        // A 50 kB frame: split over four encrypted frames and put back together by the car.
        val frame = ByteArray(50_000) { (it * 7).toByte() }
        Thread { session.sendVideoFrame(123_456L, frame) }.start()
        val media = car.read()
        assertEquals(AapId.MEDIA_WITH_TIMESTAMP, media.id)
        assertEquals(123_456L, ByteBuffer.wrap(media.body, 0, 8).long)
        assertArrayEquals(frame, media.body.copyOfRange(8, media.body.size))
        assertTrue("expected the frame to be split", car.lastMessageFrames > 1)
        car.send(3, AapId.MEDIA_ACK, ProtoWriter().int(1, 0).uint(2, 1).toByteArray())

        // Audio: setup, start + focus request, then a block of PCM.
        car.send(4, AapId.MEDIA_SETUP_RESPONSE, ProtoWriter().uint(1, 2).uint(2, 2).uint(3, 0).toByteArray())
        val audioFollow = listOf(car.read(), car.read())
        assertEquals(AapId.MEDIA_START, audioFollow.single { it.channel == 4 }.id)
        assertEquals(AapId.AUDIO_FOCUS_REQUEST, audioFollow.single { it.channel == 0 }.id)
        assertTrue(audioStarted.await(5, TimeUnit.SECONDS))
        val pcm = ByteArray(4096) { it.toByte() }
        assertTrue(session.sendAudio(1_000L, pcm))
        val audioMessage = car.read()
        assertEquals(4, audioMessage.channel)
        assertArrayEquals(pcm, audioMessage.body.copyOfRange(8, audioMessage.body.size))

        // Touch: one finger down at (640, 360).
        val touch = ProtoWriter().uint(1, 99L)
            .message(3, ProtoWriter().message(1, ProtoWriter().uint(1, 640).uint(2, 360).uint(3, 0)).uint(2, 0).uint(3, AapTouch.PRESS))
        car.send(1, AapId.INPUT_EVENT, touch.toByteArray())
        val got = touches.poll(5, TimeUnit.SECONDS)!!
        assertEquals(AapTouch.PRESS, got.action)
        assertEquals(640, got.pointers.single().x)

        // Sensor: night mode on.
        car.send(2, AapId.SENSOR_EVENT, ProtoWriter().message(10, ProtoWriter().bool(1, true)).toByteArray())
        assertEquals(true, sensors.poll(5, TimeUnit.SECONDS)!!.night)

        // Ping, then shutdown.
        car.send(0, AapId.PING_REQUEST, ProtoWriter().int(1, 777L).toByteArray())
        val pong = car.read()
        assertEquals(AapId.PING_RESPONSE, pong.id)
        assertEquals(777L, ProtoReader.fields(pong.body).first(1)?.varint)

        // The phone's Stop button: hand the screen back (video focus released) and ask the car to
        // shut down before the link drops, so the car does not freeze on the last frame. The car's
        // reply ends the session promptly, well before the force-close timeout.
        Thread { session.stop() }.start()
        assertEquals(AapId.VIDEO_FOCUS_REQUEST, car.read().id)
        val shutdown = car.read()
        assertEquals(AapId.SHUTDOWN_REQUEST, shutdown.id)
        car.send(0, AapId.SHUTDOWN_RESPONSE, ByteArray(0))
        phone.join(3000)
        assertEquals(AapSession.State.CLOSED, session.state)
        assertTrue(log.toString(), log.contains("Maker Model X"))
    }

    /** A byte pipe that does not care which thread writes, unlike java.io's piped streams. */
    private class Pipe {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        private var current = ByteArray(0)
        private var pos = 0
        @Volatile private var closed = false

        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed) throw java.io.IOException("closed")
                chunks.put(b.copyOfRange(off, off + len))
            }
            override fun close() { closed = true; chunks.put(ByteArray(0)) }
        }

        val input = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                while (pos >= current.size) {
                    if (closed && chunks.isEmpty()) return -1
                    current = chunks.poll(10, TimeUnit.SECONDS) ?: return -1
                    pos = 0
                    if (current.isEmpty() && closed) return -1
                }
                val n = minOf(len, current.size - pos)
                System.arraycopy(current, pos, b, off, n)
                pos += n
                return n
            }
            override fun close() { closed = true; chunks.put(ByteArray(0)) }
        }
    }

    /** The head unit: TLS client, trusting whatever the phone presents. */
    private class FakeCar(private val input: InputStream, private val output: OutputStream) {
        private val engine: SSLEngine = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), null)
        }.createSSLEngine().apply {
            useClientMode = true
            enabledProtocols = arrayOf("TLSv1.2")
        }
        private var inNet = ByteBuffer.allocate(1 shl 18)
        private val partial = ByteArrayOutputStream()
        var lastMessageFrames = 0

        fun handshake() {
            engine.beginHandshake()
            while (true) {
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        val out = ByteBuffer.allocate(1 shl 16)
                        engine.wrap(ByteBuffer.allocate(0), out)
                        out.flip()
                        val bytes = ByteArray(out.remaining()).also { out.get(it) }
                        // Coalesce everything the client has to say before the phone answers.
                        if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                            pending.write(bytes)
                        } else {
                            pending.write(bytes)
                            send(0, AapId.SSL_HANDSHAKE, pending.toByteArray(), encrypted = false)
                            pending.reset()
                        }
                    }
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        inNet.flip()
                        val app = ByteBuffer.allocate(1 shl 16)
                        val r = engine.unwrap(inNet, app)
                        inNet.compact()
                        if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                            val m = read()
                            assertEquals(AapId.SSL_HANDSHAKE, m.id)
                            inNet.put(m.body)
                        }
                    }
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> while (true) engine.delegatedTask?.run() ?: break
                    else -> return
                }
            }
        }

        private val pending = ByteArrayOutputStream()

        fun send(channel: Int, id: Int, body: ByteArray, encrypted: Boolean = true, control: Boolean = false) {
            val payload = byteArrayOf((id ushr 8).toByte(), id.toByte()) + body
            val data = if (encrypted) encrypt(payload) else payload
            val flags = AapFrame.FLAG_BULK or (if (encrypted) AapFrame.FLAG_ENCRYPTED else 0) or (if (control) AapFrame.FLAG_CONTROL else 0)
            output.write(FrameIo.encode(channel, flags, data, payload.size))
            output.flush()
        }

        fun read(): AapMessage {
            var frames = 0
            while (true) {
                val frame = FrameIo.read(input)
                frames++
                val plain = if (frame.encrypted) decrypt(frame.payload) else frame.payload
                when (frame.position) {
                    AapFrame.FLAG_BULK -> { lastMessageFrames = frames; return AapMessage.parse(frame.channel, frame.control, frame.encrypted, plain) }
                    AapFrame.FLAG_FIRST -> { partial.reset(); partial.write(plain) }
                    AapFrame.FLAG_MIDDLE -> partial.write(plain)
                    AapFrame.FLAG_LAST -> {
                        partial.write(plain)
                        lastMessageFrames = frames
                        return AapMessage.parse(frame.channel, frame.control, frame.encrypted, partial.toByteArray())
                    }
                }
            }
        }

        private fun encrypt(plain: ByteArray): ByteArray {
            val src = ByteBuffer.wrap(plain)
            val result = ByteArrayOutputStream()
            while (src.hasRemaining()) {
                val out = ByteBuffer.allocate(engine.session.packetBufferSize)
                engine.wrap(src, out)
                out.flip()
                result.write(out.array(), 0, out.limit())
            }
            return result.toByteArray()
        }

        private fun decrypt(cipher: ByteArray): ByteArray {
            inNet.put(cipher)
            inNet.flip()
            val out = ByteArrayOutputStream()
            while (inNet.hasRemaining()) {
                val app = ByteBuffer.allocate(1 shl 16)
                val r = engine.unwrap(inNet, app)
                app.flip()
                out.write(app.array(), 0, app.limit())
                if (r.status != SSLEngineResult.Status.OK) break
            }
            inNet.compact()
            return out.toByteArray()
        }
    }
}

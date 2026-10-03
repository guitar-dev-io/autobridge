package dev.autobridge.remotestream

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import dev.autobridge.bridge.BridgeLog
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Decodes an H.264 Annex-B elementary stream straight onto a [Surface].
 *
 * The output is the car's own surface, so decoded frames are never copied into the app: the codec
 * renders into the buffer the head unit composites. That is the whole reason this path can carry
 * video at all on a phone that is simultaneously running a WebView and a projection.
 *
 * ## Getting started without a container
 *
 * A raw elementary stream has no header to configure the codec from. The parameter sets travel in
 * the stream itself, so the first access unit containing an SPS is held back, used as `csd-0`,
 * and then fed through normally. Frames that arrive before that are dropped rather than queued —
 * they cannot be decoded without the parameter sets anyway, and a host that starts with a
 * keyframe (which every sane encoder does) loses nothing.
 *
 * ## Threading
 *
 * The codec runs in asynchronous mode on its own [HandlerThread], so [submit] never blocks the
 * transport's reader. Input buffers arrive through the callback; a frame that arrives with no
 * buffer free is dropped, because a remote stream is live and a backlog is worse than a gap.
 */
class H264Decoder(private val onError: (String) -> Unit) {

    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private var surface: Surface? = null

    @Volatile private var configured = false
    @Volatile private var released = false

    /** Access units waiting for a free input buffer. */
    private val pending = ConcurrentLinkedQueue<ByteArray>()
    private val freeInputs = ConcurrentLinkedQueue<Int>()

    @Volatile var framesDecoded = 0L
        private set

    @Volatile var framesDropped = 0L
        private set

    fun setSurface(surface: Surface?) {
        this.surface = surface
        if (surface == null) stop()
    }

    /**
     * Feeds one access unit.
     *
     * Returns false when the unit was dropped — before configuration, with no surface, or with
     * the input queue full. The caller uses that only for counting; there is nothing useful it
     * can do about a dropped frame on a live stream.
     */
    fun submit(accessUnit: ByteArray): Boolean {
        if (released || accessUnit.isEmpty()) return false
        val output = surface ?: return false.also { framesDropped++ }

        if (!configured) {
            if (!containsParameterSet(accessUnit)) {
                framesDropped++
                return false
            }
            if (!configure(accessUnit, output)) return false
        }

        if (pending.size >= MAX_PENDING) {
            pending.poll()
            framesDropped++
        }
        pending.add(accessUnit)
        drain()
        return true
    }

    private fun configure(csd: ByteArray, output: Surface): Boolean {
        return runCatching {
            val handlerThread = HandlerThread("AutoBridge-H264").apply { start() }
            thread = handlerThread
            val created = MediaCodec.createDecoderByType(MIME)
            codec = created

            // The real dimensions come back as an output-format change once the SPS is parsed;
            // these only have to be plausible enough for the codec to allocate against.
            val format = MediaFormat.createVideoFormat(MIME, 1280, 720).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            }
            created.setCallback(callback, Handler(handlerThread.looper))
            created.configure(format, output, null, 0)
            created.start()
            configured = true
            BridgeLog.i("remote.decoder_started", "csdBytes" to csd.size)
            true
        }.getOrElse { error ->
            BridgeLog.e("remote.decoder_configure_failed", "reason" to error.message)
            onError(error.message ?: "decoder configure failed")
            stop()
            false
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            freeInputs.add(index)
            drain()
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            runCatching {
                // true: hand the frame to the surface. This is the render call — there is no
                // intermediate bitmap anywhere in this path.
                codec.releaseOutputBuffer(index, true)
            }
            framesDecoded++
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            BridgeLog.e(
                "remote.decoder_error",
                "diagnostic" to error.diagnosticInfo,
                "recoverable" to error.isRecoverable,
                "transient" to error.isTransient
            )
            this@H264Decoder.onError(error.diagnosticInfo ?: "decoder error")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            BridgeLog.i(
                "remote.decoder_format",
                "size" to "${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)}"
            )
        }
    }

    private fun drain() {
        val active = codec ?: return
        while (true) {
            if (released) return
            val unit = pending.peek() ?: return
            val index = freeInputs.poll() ?: return
            pending.poll()
            val ok = runCatching {
                val buffer = active.getInputBuffer(index) ?: return@runCatching false
                buffer.clear()
                buffer.put(unit)
                active.queueInputBuffer(index, 0, unit.size, System.nanoTime() / 1000, 0)
                true
            }.getOrElse {
                BridgeLog.w("remote.decoder_queue_failed", "reason" to it.message)
                false
            }
            if (!ok) return
        }
    }

    /** Stops and frees the codec. Idempotent, and safe from any thread. */
    fun stop() {
        if (codec == null && thread == null) return
        configured = false
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { thread?.quitSafely() }
        thread = null
        pending.clear()
        freeInputs.clear()
        BridgeLog.i("remote.decoder_stopped", "decoded" to framesDecoded, "dropped" to framesDropped)
    }

    fun release() {
        released = true
        stop()
        surface = null
    }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        /** Dropping is better than unbounded growth; this is ~1s at 30fps. */
        private const val MAX_PENDING = 32

        /**
         * True when [data] is enough to configure a decoder.
         *
         * Delegates to [H264Nal], which holds the bitstream reading so it can be unit tested
         * without `MediaCodec` on the classpath.
         */
        fun containsParameterSet(data: ByteArray): Boolean = H264Nal.containsParameterSet(data)
    }
}

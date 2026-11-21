package dev.autobridge.aapdirect

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import dev.autobridge.aap.AapVideoConfig

/**
 * H.264 baseline encoder whose input is a Surface: the car display renders into it and every
 * encoded frame goes straight to the Android Auto video channel.
 *
 * Baseline because that is the one profile every head unit decodes; no B-frames, so frames leave
 * in display order and the car can show each one as it arrives. When the screen is still, the
 * encoder repeats the last frame every 100 ms so the car keeps getting a stream.
 */
class CarVideoEncoder(
    private val config: AapVideoConfig,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (presentationUs: Long, data: ByteArray, keyFrame: Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface
    @Volatile private var running = true
    private val thread: Thread

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate(config))
            setInteger(MediaFormat.KEY_FRAME_RATE, config.framesPerSecond)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        thread = Thread({ drain() }, "aa-direct-encoder").apply { start() }
    }

    fun requestKeyFrame() {
        if (!running) return
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun release() {
        running = false
        runCatching { thread.join(500) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 20_000L)
                if (index < 0) continue
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes, 0, info.size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        onConfig(bytes)
                    } else {
                        onFrame(info.presentationTimeUs, bytes, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                    }
                }
                codec.releaseOutputBuffer(index, false)
            }
        } catch (e: Exception) {
            if (running) onError("encoder: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private companion object {
        fun bitrate(config: AapVideoConfig): Int {
            val pixels = config.width.toLong() * config.height
            return when {
                pixels <= 800L * 480 -> 2_500_000
                pixels <= 1280L * 720 -> 5_000_000
                else -> 8_000_000
            } * (if (config.framesPerSecond >= 60) 3 else 2) / 2
        }
    }
}

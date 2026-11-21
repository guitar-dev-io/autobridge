package dev.autobridge.aapdirect

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.SystemClock
import dev.autobridge.aap.AapChannel

/**
 * Sends the phone's media sound to the car's music channel.
 *
 * Android only lets an app record what other apps play through playback capture, which needs a
 * MediaProjection (the screen-capture consent) and covers apps that allow capture - media,
 * games and unknown usages. Calls and notifications are not captured. The phone keeps playing
 * the sound too; the capture is a copy, not a re-route.
 */
class CarAudioCapture(
    private val projection: MediaProjection,
    private val channel: AapChannel.Audio,
    private val send: (presentationUs: Long, pcm: ByteArray, length: Int) -> Boolean,
) {
    @Volatile private var running = true
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    /** Starts capturing. RECORD_AUDIO is checked by the caller. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val rate = channel.sampleRate.takeIf { it > 0 } ?: 48000
        val stereo = channel.channels != 1
        val mask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val chunk = rate / 50 * (if (stereo) 4 else 2) // 20 ms of 16-bit PCM
        val minBuffer = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val created = runCatching {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, chunk * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build()
        }.onFailure { DirectLog.add("sound: could not start capture: ${it.message}") }.getOrNull() ?: return false
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            DirectLog.add("sound: capture did not initialise")
            created.release()
            return false
        }
        record = created
        created.startRecording()
        DirectLog.add("sound: sending phone media to the car (${rate} Hz, ${if (stereo) "stereo" else "mono"})")
        thread = Thread({ pump(created, chunk) }, "aa-direct-audio").apply { start() }
        return true
    }

    fun stop() {
        running = false
        runCatching { thread?.join(300) }
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }

    private fun pump(record: AudioRecord, chunk: Int) {
        val buffer = ByteArray(chunk)
        while (running) {
            var filled = 0
            while (filled < chunk && running) {
                val n = record.read(buffer, filled, chunk - filled)
                if (n <= 0) { if (n < 0) running = false; break }
                filled += n
            }
            if (filled > 0) runCatching { send(SystemClock.elapsedRealtimeNanos() / 1000, buffer, filled) }
        }
    }
}

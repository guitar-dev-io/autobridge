package dev.autobridge.desktop.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.LocalSocket
import android.net.LocalSocketAddress
import dev.autobridge.desktop.DesktopLog
import java.io.File

/**
 * Plays the browser's audio on the phone (and so through Android Auto's media stream).
 *
 * PulseAudio in the runtime mixes every browser into a null sink and serves its monitor as raw
 * 48 kHz stereo s16le PCM on a Unix socket in this app's private storage (see assets
 * desktop/pulse.pa). This reads that socket into an [AudioTrack].
 *
 * Audio focus is requested when sound actually starts, not when the session starts, so a silent
 * page never takes the car's audio away from the radio. While focus is lost the samples are
 * dropped (the page keeps playing silently); the bridge does not pause the web page itself.
 */
class BrowserAudioBridge(private val context: Context, private val socket: File) {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var muted = false
        private set
    @Volatile var bytesPlayed = 0L
        private set
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var hasFocus = false
    /** Set when another app took focus for good; cleared once the page has been quiet a while. */
    @Volatile private var yielded = false
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "desktop-audio").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        abandonFocus()
    }

    private fun loop() {
        // Audio threads get the scheduler's attention first, so a busy browser does not starve it.
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        while (running) {
            val local = LocalSocket()
            var track: AudioTrack? = null
            try {
                local.connect(LocalSocketAddress(socket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                track = newTrack()
                track.play()
                val buffer = ByteArray(BUFFER_BYTES)
                val input = local.inputStream
                var silentFor = 0L
                var filled = 0
                var framesWritten = 0L
                while (running) {
                    val got = input.read(buffer, filled, buffer.size - filled)
                    if (got < 0) break
                    filled += got
                    // A stream read can end mid-frame. Playing a half frame shifts every later
                    // sample by two bytes, which swaps left and right (or turns into noise), so
                    // only whole frames go out and the rest waits for the next read.
                    val read = usableBytes(filled)
                    if (read == 0) continue
                    val audible = !isSilent(buffer, read)
                    if (audible) {
                        silentFor = 0
                        // Never grab focus back from whatever just took it (radio, navigation
                        // prompt that became permanent); wait for the page to go quiet first.
                        if (!hasFocus && !yielded) requestFocus()
                    } else {
                        silentFor += read
                        // Two seconds of silence: give the car's audio back.
                        if (silentFor > SAMPLE_RATE * FRAME_BYTES * 2L) {
                            if (hasFocus) abandonFocus()
                            yielded = false
                        }
                    }
                    if (hasFocus && !muted) {
                        // The page's clock and the speaker's clock drift apart, and a backlog
                        // after a stall plays late for as long as the page runs. When more than
                        // MAX_QUEUE_FRAMES are waiting, drop this chunk instead of queueing it:
                        // a 20 ms gap now, instead of sound that stays a fraction of a second behind.
                        val queued = framesWritten - (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
                        if (queued <= MAX_QUEUE_FRAMES) {
                            val written = track.write(buffer, 0, read)
                            if (written < 0) throw IllegalStateException("AudioTrack write failed: $written")
                            framesWritten += written / FRAME_BYTES
                            bytesPlayed += written
                        }
                    }
                    filled -= read
                    if (filled > 0) System.arraycopy(buffer, read, buffer, 0, filled)
                }
            } catch (e: Exception) {
                if (running) DesktopLog.w("audio bridge reconnecting", e)
            } finally {
                runCatching { track?.stop(); track?.release() }
                runCatching { local.close() }
            }
            if (running) try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
        }
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            // PulseAudio hands over its monitor in bursts of about 50 ms, so the buffer must hold a
            // few of them or write() blocks and the burst arrives late; its fullness, not its
            // size, sets the delay, and MAX_QUEUE_FRAMES caps that.
            .setBufferSizeInBytes(maxOf(min * 2, MAX_QUEUE_FRAMES * FRAME_BYTES * 2))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(ATTRIBUTES)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> { hasFocus = true; muted = false }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> muted = false
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> muted = true
                    AudioManager.AUDIOFOCUS_LOSS -> { hasFocus = false; muted = false; yielded = true }
                }
            }
            .build()
        focusRequest = request
        hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        muted = false
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        hasFocus = false
    }

    private fun isSilent(buffer: ByteArray, length: Int): Boolean {
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)
            if (sample > SILENCE || sample < -SILENCE) return false
            i += 2
        }
        return true
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_BYTES = 4
        private const val BUFFER_BYTES = SAMPLE_RATE * FRAME_BYTES / 25 // 40 ms per read
        /** Longest wait allowed in the AudioTrack: 150 ms, above PulseAudio's 50 ms bursts. */
        private const val MAX_QUEUE_FRAMES = SAMPLE_RATE * 150 / 1000
        private const val SILENCE = 8

        /** How many of [filled] bytes are whole stereo 16-bit frames. */
        internal fun usableBytes(filled: Int): Int = filled - filled % FRAME_BYTES
        private val ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}

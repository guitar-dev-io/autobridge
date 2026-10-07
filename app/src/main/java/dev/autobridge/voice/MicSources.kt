package dev.autobridge.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import androidx.core.content.ContextCompat

/**
 * The phone's own microphone through [AudioRecord], with the VOICE_RECOGNITION source - the tuning
 * Android applies for speech recognisers (no aggressive noise gate, AGC as the platform sees fit).
 */
class PhoneMicSource(private val context: Context) : PcmSource {
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission") // Checked right here; the caller asked for it first.
    override fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        val minBuffer = AudioRecord.getMinBufferSize(
            WhisperEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false
        val created = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WhisperEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, WhisperEngine.SAMPLE_RATE) // at least half a second of slack
            )
        }.getOrNull() ?: return false
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            return false
        }
        val started = runCatching { created.startRecording() }.isSuccess &&
            created.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) {
            created.release()
            return false
        }
        record = created
        return true
    }

    override fun read(buffer: ShortArray): Int = record?.read(buffer, 0, buffer.size) ?: -1

    override fun release() {
        val current = record ?: return
        record = null
        runCatching { current.stop() }
        current.release()
    }
}

/**
 * The car's microphone through the Car App Library ([CarAudioRecord]): 16 kHz mono 16-bit PCM from
 * the head unit. Needs Car App API level 5 on the host and Android 13 on the phone; the caller
 * checks both before choosing this source (see [dev.autobridge.car.CarVoiceInput]).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class CarMicSource(private val carContext: CarContext) : PcmSource {
    private var record: CarAudioRecord? = null
    private var bytes = ByteArray(0)

    override fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        val created = runCatching { CarAudioRecord.create(carContext) }.getOrNull() ?: return false
        if (runCatching { created.startRecording() }.isFailure) return false
        record = created
        return true
    }

    override fun read(buffer: ShortArray): Int {
        val current = record ?: return -1
        if (bytes.size != buffer.size * 2) bytes = ByteArray(buffer.size * 2)
        val read = current.read(bytes, 0, bytes.size)
        if (read < 0) return read
        return PcmConversion.bytesToShorts(bytes, read, buffer)
    }

    override fun release() {
        val current = record ?: return
        record = null
        runCatching { current.stopRecording() }
    }
}

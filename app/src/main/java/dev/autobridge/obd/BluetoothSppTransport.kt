package dev.autobridge.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import dev.autobridge.obd.transport.ObdTimeoutException
import dev.autobridge.obd.transport.ObdTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Classic Bluetooth serial (SPP) to an already-paired ELM327 adapter - what nearly every
 * "OBD2 Bluetooth" dongle is. BLE-only adapters are not covered yet.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked by the caller before it builds this.
class BluetoothSppTransport(private val device: BluetoothDevice) : ObdTransport {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun open() {
        var last: IOException? = null
        // Some adapters refuse the secure SPP socket; the insecure one is the usual fallback.
        for (insecure in listOf(false, true)) {
            try {
                val s = if (insecure) device.createInsecureRfcommSocketToServiceRecord(SPP) else device.createRfcommSocketToServiceRecord(SPP)
                s.connect()
                socket = s; input = s.inputStream; output = s.outputStream
                return
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("Bluetooth connect failed")
    }

    override fun write(command: String) {
        val out = output ?: throw IOException("not connected")
        out.write((command + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val stream = input ?: throw IOException("not connected")
        val buffer = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (stream.available() > 0) {
                val b = stream.read()
                if (b < 0) throw IOException("adapter closed the connection")
                if (b == '>'.code) return buffer.toString()
                buffer.append(b.toChar())
            } else {
                if (System.currentTimeMillis() > deadline) throw ObdTimeoutException("no prompt after ${timeoutMs}ms")
                Thread.sleep(8)
            }
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

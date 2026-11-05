package dev.autobridge.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import dev.autobridge.obd.transport.BleChar
import dev.autobridge.obd.transport.BleLayout
import dev.autobridge.obd.transport.BleService
import dev.autobridge.obd.transport.ObdTimeoutException
import dev.autobridge.obd.transport.ObdTransport
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * ELM327 over Bluetooth LE: the adapter's serial line is a notify characteristic in and a write
 * characteristic out, found by [BleLayout]. The callbacks are queued into the blocking
 * [ObdTransport] shape the session expects.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked by the caller before it builds this.
@Suppress("DEPRECATION") // The setValue/writeCharacteristic forms are the only ones that work from API 29.
class BleTransport(private val context: Context, private val device: BluetoothDevice) : ObdTransport {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val incoming = LinkedBlockingQueue<Int>()
    private val steps = Semaphore(0)
    @Volatile private var failure: String? = null
    @Volatile private var chunk = 20

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == 0) {
                if (!g.requestMtu(185)) g.discoverServices()
            } else {
                failure = "Bluetooth LE disconnected (status $status)"
                incoming.offer(-1)
                steps.release(4)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == 0) chunk = (mtu - 3).coerceIn(20, 180)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != 0) failure = "Service discovery failed ($status)"
            steps.release()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != 0) failure = "Could not enable notifications ($status)"
            steps.release()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            steps.release()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.forEach { incoming.offer(it.toInt() and 0xFF) }
        }
    }

    override fun open() {
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) ?: throw IOException("Bluetooth LE unavailable")
        gatt = g
        await("connect", 12_000)          // released by onServicesDiscovered
        failure?.let { close(); throw IOException(it) }

        val services = g.services.map { s ->
            BleService(s.uuid.toString(), s.characteristics.map { c ->
                val p = c.properties
                BleChar(
                    c.uuid.toString(),
                    canNotify = p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
                    canWrite = p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
                    canWriteNoResponse = p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                )
            })
        }
        val serial = BleLayout.pick(services) ?: run { close(); throw IOException("This Bluetooth LE device has no serial characteristic.") }
        val service = g.getService(UUID.fromString(serial.serviceUuid))
        val notify = service.getCharacteristic(UUID.fromString(serial.notify))
        writeChar = service.getCharacteristic(UUID.fromString(serial.write))

        g.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(CCCD)
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
            await("enable notifications", 5_000)
            failure?.let { close(); throw IOException(it) }
        }
    }

    override fun write(command: String) {
        val g = gatt ?: throw IOException("not connected")
        val c = writeChar ?: throw IOException("not connected")
        incoming.clear() // anything still queued belongs to the previous command
        val bytes = (command + "\r").toByteArray(Charsets.US_ASCII)
        c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + chunk)
            c.value = bytes.copyOfRange(offset, end)
            steps.drainPermits()
            if (!g.writeCharacteristic(c)) throw IOException("Bluetooth LE write failed")
            await("write", 3_000)
            failure?.let { throw IOException(it) }
            offset = end
        }
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw ObdTimeoutException("no prompt after ${timeoutMs}ms")
            val b = incoming.poll(left, TimeUnit.MILLISECONDS) ?: throw ObdTimeoutException("no prompt after ${timeoutMs}ms")
            if (b < 0) throw IOException(failure ?: "adapter closed the connection")
            if (b == '>'.code) return sb.toString()
            sb.append(b.toChar())
        }
    }

    override fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null; writeChar = null
    }

    private fun await(what: String, ms: Long) {
        if (!steps.tryAcquire(ms, TimeUnit.MILLISECONDS)) throw ObdTimeoutException("Bluetooth LE: $what timed out")
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

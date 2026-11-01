package dev.autobridge.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * An ELM327 adapter over Bluetooth LE.
 *
 * The vLinker FD+ talks on service 0x18F0: commands are written to 0x2AF1 and answers arrive as
 * notifications on 0x2AF0. Other adapters use the FFF0, FFE0 or Nordic UART services; when none of
 * the known ones matches, any service with a notifying and a writable characteristic is used.
 *
 * The caller must hold the Bluetooth permissions ([ObdPermissions]); without them the first call
 * throws [SecurityException], which the connection reports as the reason it could not connect.
 */
@SuppressLint("MissingPermission")
class BleTransport(private val context: Context, private val address: String) : ObdTransport {

    override var onChunk: ((String) -> Unit)? = null
    override var onClosed: ((String?) -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    private var mtu = DEFAULT_MTU

    @Volatile private var connected = CompletableDeferred<Unit>()
    @Volatile private var mtuChanged = CompletableDeferred<Unit>()
    @Volatile private var discovered = CompletableDeferred<Unit>()
    @Volatile private var subscribed = CompletableDeferred<Unit>()
    @Volatile private var written = CompletableDeferred<Boolean>()
    @Volatile private var ready = false
    @Volatile private var closedByUs = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val why = "Bluetooth disconnected (status $status)"
                if (!ready) {
                    val failure = IOException(why)
                    connected.completeExceptionally(failure)
                    mtuChanged.complete(Unit)
                    discovered.completeExceptionally(failure)
                    subscribed.completeExceptionally(failure)
                } else if (!closedByUs) {
                    onClosed?.invoke(why)
                }
                written.complete(false)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            mtuChanged.complete(Unit)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) discovered.complete(Unit)
            else discovered.completeExceptionally(IOException("Service discovery failed ($status)"))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) subscribed.complete(Unit)
            else subscribed.completeExceptionally(IOException("Could not subscribe to answers ($status)"))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            written.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+ delivers the value here; older releases call the overload below.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(value)
        }

        @Deprecated("Replaced by the overload with a value, from API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { deliver(it) }
        }
    }

    private fun deliver(bytes: ByteArray) {
        onChunk?.invoke(String(bytes, Charsets.ISO_8859_1))
    }

    override suspend fun connect() {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: throw IOException("This device has no Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)

        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Could not start the Bluetooth connection")
        gatt = g
        withTimeoutOrNull(CONNECT_MS) { connected.await() } ?: throw IOException("The adapter did not respond")

        // A bigger MTU is a nicety (commands are short); a refusal or silence is not an error.
        if (g.requestMtu(REQUESTED_MTU)) withTimeoutOrNull(2_000) { mtuChanged.await() }

        if (!g.discoverServices()) throw IOException("Could not read the adapter's services")
        withTimeoutOrNull(8_000) { discovered.await() } ?: throw IOException("The adapter's services did not load")

        val (write, notify) = pickCharacteristics(g)
        writer = write
        if (!g.setCharacteristicNotification(notify, true)) throw IOException("Could not subscribe to answers")
        val cccd = notify.getDescriptor(CCCD) ?: throw IOException("The adapter cannot send answers")
        val indicate = (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
        val enable = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!writeDescriptor(g, cccd, enable)) throw IOException("Could not subscribe to answers")
        withTimeoutOrNull(5_000) { subscribed.await() } ?: throw IOException("The adapter did not accept the subscription")
        ready = true
    }

    override suspend fun write(text: String) {
        val g = gatt ?: throw IOException("Not connected")
        val target = writer ?: throw IOException("Not connected")
        val noResponse = (target.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 &&
            (target.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        val chunk = (mtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < bytes.size) {
            val part = bytes.copyOfRange(offset, minOf(offset + chunk, bytes.size))
            written = CompletableDeferred()
            if (!writeCharacteristic(g, target, part, type)) throw IOException("Could not send to the adapter")
            val ok = withTimeoutOrNull(WRITE_MS) { written.await() }
            // Writes without a response are not always confirmed; silence is fine for those.
            if (ok == false || (ok == null && !noResponse)) throw IOException("The adapter did not take the command")
            offset += chunk
        }
    }

    override fun close() {
        closedByUs = true
        val g = gatt
        gatt = null
        runCatching { g?.disconnect() }
        runCatching { g?.close() }
    }

    private fun pickCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic> {
        for (profile in PROFILES) {
            val service = g.getService(profile.service) ?: continue
            val write = service.getCharacteristic(profile.write) ?: continue
            val notify = service.getCharacteristic(profile.notify) ?: continue
            return write to notify
        }
        for (service in g.services) {
            val notify = service.characteristics.firstOrNull {
                (it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
            } ?: continue
            val write = service.characteristics.firstOrNull {
                (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
            } ?: continue
            return write to notify
        }
        throw IOException("This does not look like an OBD adapter")
    }

    private fun writeCharacteristic(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        value: ByteArray,
        type: Int,
    ): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            c.writeType = type
            c.value = value
            g.writeCharacteristic(c)
        }
    }

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = value
                g.writeDescriptor(d)
            }
        }

    private class Profile(val service: UUID, val write: UUID, val notify: UUID)

    companion object {
        private const val DEFAULT_MTU = 23
        private const val REQUESTED_MTU = 185
        private const val CONNECT_MS = 15_000L
        private const val WRITE_MS = 3_000L

        fun uuid16(short: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

        val CCCD: UUID = uuid16(0x2902)

        /** Services the scanner treats as a sign of an OBD adapter. */
        val OBD_SERVICES: List<UUID> = listOf(uuid16(0x18F0), uuid16(0xFFF0), uuid16(0xFFE0))

        private val PROFILES = listOf(
            Profile(uuid16(0x18F0), uuid16(0x2AF1), uuid16(0x2AF0)), // vLinker FD / FD+
            Profile(uuid16(0xFFF0), uuid16(0xFFF2), uuid16(0xFFF1)), // VeePeak and many generic ELM327
            Profile(uuid16(0xFFE0), uuid16(0xFFE1), uuid16(0xFFE1)),
            Profile(
                UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            ), // Nordic UART
        )
    }
}

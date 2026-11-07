package dev.autobridge.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/** An adapter seen while scanning. [likely] marks one that looks like an OBD adapter. */
data class AdapterHit(
    val name: String?,
    val address: String,
    val rssi: Int,
    val likely: Boolean,
    /** Already paired with the phone: listed even when it is not advertising right now. */
    val paired: Boolean = false,
)

object ObdAdapterMatcher {
    private val NAMES = listOf(
        "VLINKER", "V-LINK", "VGATE", "ICAR", "OBD", "ELM", "VEEPEAK", "LELINK", "KONNWEI", "CARISTA",
    )

    fun isLikely(name: String?, services: List<UUID>): Boolean {
        val upper = name?.uppercase().orEmpty()
        if (NAMES.any { upper.contains(it) }) return true
        return services.any { it in BleTransport.OBD_SERVICES }
    }
}

object ObdPermissions {
    /** What Android needs before this app may scan for and open an adapter. */
    fun required(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun missing(context: Context): List<String> = required().filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }
}

object ObdScanner {
    /**
     * Adapters the phone is already paired with. A scan only sees devices that are advertising, and
     * an adapter that is on but held by another connection, or just woke up, may not be; the paired
     * list still shows it. Classic-only devices (speakers, car kits) are left out: this app talks
     * Bluetooth LE.
     */
    @SuppressLint("MissingPermission")
    fun paired(context: Context): List<AdapterHit> = runCatching {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return@runCatching emptyList()
        adapter.bondedDevices.orEmpty()
            .filter { it.type != android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC }
            .map {
                val name = runCatching { it.name }.getOrNull()
                AdapterHit(name, it.address, 0, ObdAdapterMatcher.isLikely(name, emptyList()), paired = true)
            }
    }.getOrDefault(emptyList())

    /** Adapters in range until the collector stops. Needs [ObdPermissions]. */
    @SuppressLint("MissingPermission")
    fun scan(context: Context): Flow<AdapterHit> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth is off"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val services = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                trySend(
                    AdapterHit(
                        name = name,
                        address = result.device.address,
                        rssi = result.rssi,
                        likely = ObdAdapterMatcher.isLikely(name, services),
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Bluetooth scan failed ($errorCode)"))
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}

package dev.autobridge.aapdirect

import android.app.Activity
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle

/**
 * Receives USB_ACCESSORY_ATTACHED for the Android Auto accessory identity. Android grants the
 * accessory permission to the app that handles this intent, so this is the one place the
 * connection can start without asking again. Shows nothing: it hands the accessory to
 * [DirectProjectionService] and closes. The phone stays on whatever it was showing.
 */
class UsbAccessoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }
        if (accessory != null) {
            DirectLog.add("car attached: ${accessory.manufacturer} / ${accessory.model} ${accessory.version.orEmpty()}")
            DirectProjectionService.start(this, accessory)
        }
        finish()
    }
}

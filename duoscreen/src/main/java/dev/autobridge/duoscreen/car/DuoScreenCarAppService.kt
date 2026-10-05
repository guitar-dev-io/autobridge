package dev.autobridge.duoscreen.car

import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Separate from [dev.autobridge.car.AutoBridgeCarAppService] on purpose (see this feature's
 * manifest comment in src/projection/AndroidManifest.xml): Duo Screen's privileged multi-display
 * launching is isolated from the template route that ships in every flavor.
 */
class DuoScreenCarAppService : CarAppService() {
    private companion object {
        const val TAG = "AutoBridgeDuoCarService"
    }

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR // Development/DHU/personal-use only, same as the main service.

    override fun onCreateSession(): Session {
        Log.i(TAG, "onCreateSession")
        return DuoScreenSession()
    }
}

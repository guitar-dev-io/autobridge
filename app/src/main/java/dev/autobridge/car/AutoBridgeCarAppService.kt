package dev.autobridge.car

import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class AutoBridgeCarAppService : CarAppService() {
    private companion object {
        const val TAG = "AutoBridgeCarService"
    }

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR // Development/DHU/personal-use only.

    override fun onCreateSession(): Session {
        Log.i(TAG, "onCreateSession")
        return AutoBridgeSession()
    }
}

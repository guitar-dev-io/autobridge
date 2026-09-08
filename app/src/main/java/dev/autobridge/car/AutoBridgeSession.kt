package dev.autobridge.car

import android.content.Intent
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.Session

class AutoBridgeSession : Session() {
    private companion object {
        const val TAG = "AutoBridgeCarSession"
    }

    override fun onCreateScreen(intent: Intent): Screen {
        Log.i(TAG, "onCreateScreen action=${intent.action} data=${intent.data}")
        return try {
            CarDashboardScreen(carContext)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to create MirrorCarScreen", error)
            throw error
        }
    }
}

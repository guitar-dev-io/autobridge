package dev.autobridge.duoscreen.car

import android.content.Intent
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.logging.StructuredLog

class DuoScreenSession : Session() {
    private companion object {
        const val TAG = "AutoBridgeDuoSession"
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                StructuredLog.i(TAG, "Duo Screen session created")
            }

            override fun onDestroy(owner: LifecycleOwner) {
                StructuredLog.i(TAG, "Duo Screen session destroyed")
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        Log.i(TAG, "onCreateScreen action=${intent.action}")
        return DuoScreenScreen(carContext)
    }
}

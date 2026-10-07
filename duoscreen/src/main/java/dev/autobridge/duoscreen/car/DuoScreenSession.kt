package dev.autobridge.duoscreen.car

import android.content.Intent
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.duoscreen.DuoScreenHost
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

            /**
             * The car connection is gone (head unit switched off, cable pulled, Android Auto
             * closed), not just the screen left for a moment — that is onStop. Nothing can come
             * back to this session, so it ends now: the keep-alive window [DuoScreenHost] arms
             * when the Screen goes would otherwise keep every pane app running on a display nobody
             * can see and the phone held awake for it, which is the "stuck on the car" state after
             * switching the head unit off. Releasing the displays hands the apps back to the phone.
             */
            override fun onDestroy(owner: LifecycleOwner) {
                val ended = DuoScreenHost.release()
                StructuredLog.i(TAG, "Duo Screen session destroyed; panes released=$ended")
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        Log.i(TAG, "onCreateScreen action=${intent.action}")
        return DuoScreenScreen(carContext)
    }
}

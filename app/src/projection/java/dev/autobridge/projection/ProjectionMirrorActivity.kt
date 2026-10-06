package dev.autobridge.projection

import android.os.Bundle
import com.google.android.apps.auto.sdk.CarActivity

/**
 * Bridge Mirror as its own icon on Android Auto: a [ProjectionMirrorPane] filling the car display.
 *
 * Listed only when switched on (Settings > Bridge Web on Android Auto), because a second projection
 * service costs Bridge Web its split screen beside Maps. The same pane is available inside Bridge
 * Web without that cost (its menu > Mirror phone screen), which is the way to use the mirror while
 * keeping the split.
 */
class ProjectionMirrorActivity : CarActivity() {
    private var pane: ProjectionMirrorPane? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The host relays out its panels as navigation comes and goes; rebuilding for each of those
        // would drop the surface and restart the mirror.
        setIgnoreConfigChanges(-1)
        carUiController.statusBarController.hideAppHeader()
        carUiController.menuController.hideMenuButton()
        val view = ProjectionMirrorPane(this)
        pane = view
        setContentView(view)
        view.start()
    }

    override fun onResume() {
        super.onResume()
        pane?.refreshStatus()
    }

    override fun onDestroy() {
        pane?.stop()
        pane = null
        super.onDestroy()
    }
}

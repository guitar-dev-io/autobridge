package dev.autobridge.obd

import android.widget.LinearLayout

/**
 * The Play build has no coding page: nothing in it can write to a module. The real page is in
 * src/coding, compiled into the sideloaded flavors only.
 */
internal class ObdCodingPage private constructor() {
    fun build(body: LinearLayout) = Unit

    companion object {
        fun create(host: ObdToolsHost): ObdCodingPage? = null
    }
}

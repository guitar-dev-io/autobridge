package dev.autobridge.obd

import android.app.Activity
import android.view.View
import dev.autobridge.obd.elm.ElmSession
import dev.autobridge.obd.uds.EcuInfo
import dev.autobridge.obd.uds.UdsInspector

/**
 * What [ObdToolsActivity] lends the coding page. The page lives in the sideload source set
 * (src/coding), so the Play build carries no write path at all; this is the seam it is built on.
 */
internal interface ObdToolsHost {
    val activity: Activity
    val accent: Int
    val thai: Boolean
    val modules: List<EcuInfo>?
    val busyModule: String?
    val codingBusy: Boolean

    fun render()
    fun toast(text: String)
    fun tabs(items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit): View
    fun chips(items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit): View

    /** Runs [work] against one addressed module while the car is not reported moving. */
    fun <T> onModule(ecu: EcuInfo, work: (UdsInspector) -> T, done: (T) -> Unit)

    /** Runs [work] with the gauges paused, only while the car reports that it is stopped. */
    fun <T> stationary(work: (ElmSession) -> T, done: (T) -> Unit)

    /** Runs read-only [work] with the gauges paused, while the car is not reported moving. */
    fun <T> reading(work: (ElmSession) -> T, done: (T) -> Unit)
}

package dev.autobridge.speed

import dev.autobridge.obd.ObdManager
import dev.autobridge.obd.obd2.PidValue
import dev.autobridge.obd.obd2.Pids

/**
 * OBD-II speed (PID 0x0D, km/h) from the connected adapter. Available only while an adapter is
 * connected and the car lists the PID as supported; it adds the PID to the poll set while a
 * subscriber is listening and withdraws it afterwards.
 */
class ObdSpeedSource : SpeedSource {
    override val origin: SpeedOrigin = SpeedOrigin.OBD

    private var listener: ObdManager.Listener? = null

    override fun isAvailable(): Boolean {
        val s = ObdManager.status
        return s.state == ObdManager.State.CONNECTED && Pids.SPEED in s.supportedPids
    }

    override fun start(onSample: (SpeedSample) -> Unit) {
        stop()
        val l = object : ObdManager.Listener {
            override fun onValues(values: Map<Int, PidValue>) {
                values[Pids.SPEED]?.let { onSample(SpeedSample(it.value.toFloat(), SpeedOrigin.OBD, valid = true)) }
            }

            override fun onStatus(status: ObdManager.Status) {
                if (status.state != ObdManager.State.CONNECTED) onSample(SpeedSample(0f, SpeedOrigin.OBD, valid = false))
            }
        }
        listener = l
        ObdManager.addListener(l)
        ObdManager.requestPids(this, setOf(Pids.SPEED))
    }

    override fun stop() {
        listener?.let { ObdManager.removeListener(it) }
        listener = null
        ObdManager.requestPids(this, emptySet())
    }
}

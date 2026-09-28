package dev.autobridge.speed

/**
 * Optional OBD-II speed source. Stub for now: no adapter integration is wired, so it reports
 * unavailable and never emits. It exists so [SpeedManager] can include OBD in its fallback order
 * once a real Bluetooth/Wi-Fi ELM327 pipeline is added, without changing the manager or callers.
 *
 * To enable later: connect to the adapter, poll PID 0x0D (vehicle speed, km/h), and call
 * onSample(SpeedSample(kmh, SpeedOrigin.OBD, valid = true)) from the read loop.
 */
class ObdSpeedSource : SpeedSource {
    override val origin: SpeedOrigin = SpeedOrigin.OBD

    @Volatile
    private var connected = false

    override fun isAvailable(): Boolean = connected

    override fun start(onSample: (SpeedSample) -> Unit) {
        // No-op until an OBD adapter pipeline is implemented.
    }

    override fun stop() {
        // No-op.
    }
}

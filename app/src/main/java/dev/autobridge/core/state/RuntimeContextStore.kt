package dev.autobridge.core.state

import android.os.SystemClock
import dev.autobridge.core.model.AudioMode
import dev.autobridge.core.model.AutoBridgeMode
import dev.autobridge.core.model.Environment
import dev.autobridge.core.model.Feature
import dev.autobridge.core.model.RotationMode
import dev.autobridge.core.model.RuntimeContext
import dev.autobridge.core.model.ScaleMode
import dev.autobridge.core.model.VehicleProfile
import dev.autobridge.core.model.VehicleState
import dev.autobridge.core.policy.DefaultModeProvider
import dev.autobridge.core.policy.EnvironmentDetector
import dev.autobridge.safety.ParkingStateStore
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local state boundary shared by phone UI, Android Auto screens, and policy evaluation.
 * Durable settings remain in their repositories; this object owns only live session state.
 */
object RuntimeContextStore {
    private val initialContext = RuntimeContext(
        mode = DefaultModeProvider.mode,
        environment = EnvironmentDetector.detect(),
        vehicleState = ParkingStateStore.vehicleState,
        sessionStartedAtElapsedMs = SystemClock.elapsedRealtime()
    )
    private val _context = MutableStateFlow(initialContext)
    private val _events = MutableSharedFlow<RuntimeContext>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val context: StateFlow<RuntimeContext> = _context.asStateFlow()
    val events: SharedFlow<RuntimeContext> = _events.asSharedFlow()
    val mode: AutoBridgeMode get() = _context.value.mode
    val vehicleState: VehicleState get() = _context.value.vehicleState

    init {
        ParkingStateStore.addListener { legacyState ->
            val state = when (legacyState) {
                ParkingStateStore.State.PARKED -> VehicleState.PARKED
                ParkingStateStore.State.MOVING -> VehicleState.MOVING
                ParkingStateStore.State.UNKNOWN -> VehicleState.UNKNOWN
            }
            update { it.copy(vehicleState = state) }
        }
    }

    fun setConnected(connected: Boolean) {
        update { it.copy(connected = connected) }
    }

    fun setSimulatedConnection(connected: Boolean): Boolean {
        if (mode != AutoBridgeMode.LAB || _context.value.environment == Environment.REAL_CAR) return false
        setConnected(connected)
        return true
    }

    fun setVehicleProfile(profile: VehicleProfile?) {
        update { it.copy(vehicleProfile = profile) }
    }

    fun setCurrentFeature(feature: Feature?, packageName: String? = _context.value.currentPackageName) {
        update { it.copy(currentFeature = feature, currentPackageName = packageName) }
    }

    fun setDisplayPreferences(
        scaleMode: ScaleMode,
        rotationMode: RotationMode,
        audioMode: AudioMode,
        fullscreen: Boolean
    ) {
        update {
            it.copy(
                scaleMode = scaleMode,
                rotationMode = rotationMode,
                audioMode = audioMode,
                fullscreen = fullscreen
            )
        }
    }

    /**
     * Changes environment only through an explicit LAB control. REAL_CAR is always allowed as a
     * reset target; no environment selection can alter the real vehicle signal itself.
     */
    fun setEnvironment(environment: Environment): Boolean {
        if (mode != AutoBridgeMode.LAB && environment != Environment.REAL_CAR) return false
        EnvironmentDetector.setOverride(environment)
        update { it.copy(environment = environment) }
        return true
    }

    fun resetEnvironment() {
        EnvironmentDetector.clearOverride()
        update { it.copy(environment = EnvironmentDetector.detect()) }
    }

    /**
     * LAB simulator entry point. It intentionally refuses to write a simulated state for a real
     * car environment; production CAR_SPEED updates still arrive through ParkingStateStore.
     */
    fun setSimulatedVehicleState(state: VehicleState): Boolean {
        if (mode != AutoBridgeMode.LAB || _context.value.environment == Environment.REAL_CAR) return false
        ParkingStateStore.update(state)
        return true
    }

    private fun update(transform: (RuntimeContext) -> RuntimeContext) {
        val next = transform(_context.value)
        if (next == _context.value) return
        _context.value = next
        _events.tryEmit(next)
    }
}

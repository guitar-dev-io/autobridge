package dev.autobridge.safety

import dev.autobridge.BuildConfig
import dev.autobridge.core.model.AutoBridgeMode
import dev.autobridge.core.model.Environment
import dev.autobridge.core.policy.EnvironmentDetector
import dev.autobridge.core.state.RuntimeContextStore

/** Pure build/runtime gate for the emulator-only LAB vehicle simulator. */
object DevModeEvaluator {
    fun isEnabled(
        mode: AutoBridgeMode,
        environment: Environment,
        buildDevMode: Boolean,
        deviceIsEmulator: Boolean
    ): Boolean = buildDevMode && deviceIsEmulator &&
        mode == AutoBridgeMode.LAB && environment != Environment.REAL_CAR
}

/** Compatibility facade for older UI code; mode and environment now come from the core runtime. */
object DevMode {
    val isEnabled: Boolean
        get() = DevModeEvaluator.isEnabled(
            mode = RuntimeContextStore.mode,
            environment = RuntimeContextStore.context.value.environment,
            buildDevMode = BuildConfig.DEV_MODE,
            deviceIsEmulator = EnvironmentDetector.isEmulator()
        )

    val environmentLabel: String
        get() = if (RuntimeContextStore.mode != AutoBridgeMode.LAB) {
            "off"
        } else {
            EnvironmentDetector.detect().name
        }
}

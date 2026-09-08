package dev.autobridge.core.policy

import android.os.Build
import dev.autobridge.core.model.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Detects the safest default environment. DHU and test-bench selection is explicit in LAB rather
 * than guessed from a phone build, because Android does not expose a reliable projected-host API.
 */
object EnvironmentDetector {
    private val detected = detectDeviceEnvironment()
    private val _environment = MutableStateFlow(detected)

    val environment: StateFlow<Environment> = _environment.asStateFlow()
    val detectedEnvironment: Environment get() = detected

    fun detect(): Environment = _environment.value

    /** Applies an explicit LAB/test-bench selection to the shared runtime state. */
    fun setOverride(environment: Environment) {
        _environment.value = environment
    }

    fun clearOverride() {
        _environment.value = detected
    }

    fun isEmulator(): Boolean = detectDeviceEnvironment() == Environment.EMULATOR

    private fun detectDeviceEnvironment(): Environment {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val model = Build.MODEL.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val product = Build.PRODUCT.orEmpty()
        return if (
            fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            model.contains("emulator", ignoreCase = true) ||
            model.contains("android sdk built for", ignoreCase = true) ||
            hardware.contains("ranchu", ignoreCase = true) ||
            hardware.contains("goldfish", ignoreCase = true) ||
            product.contains("sdk", ignoreCase = true) ||
            product.contains("emulator", ignoreCase = true)
        ) {
            Environment.EMULATOR
        } else {
            Environment.REAL_CAR
        }
    }
}

package dev.autobridge.core.policy

import dev.autobridge.core.model.AutoBridgeMode
import dev.autobridge.core.model.Environment
import dev.autobridge.core.model.Feature
import dev.autobridge.core.model.RuntimeContext
import dev.autobridge.core.model.VehicleState
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.safety.BypassPolicyStore
import dev.autobridge.safety.SafetyEnforcement

/** Result of evaluating a feature at a specific point in the runtime lifecycle. */
data class FeatureDecision(
    val feature: Feature,
    val allowed: Boolean,
    val reason: String,
    val requiresParked: Boolean
)

/**
 * Single policy boundary for mode, environment, and vehicle-state decisions.
 *
 * Callers may retain defensive checks at low-level Android boundaries, but feature entry points
 * should ask this policy rather than inspecting BuildConfig, speed, or mode independently.
 *
 * ## Parked-only features while the gate only reports
 *
 * [FeatureDecision.requiresParked] stays true whatever the vehicle is doing: it says what kind of
 * feature this is, not what was decided about it. When [enforceParkedOnly] is off - the default,
 * see [SafetyEnforcement] - a moving vehicle is named in [FeatureDecision.reason] and the feature is
 * still allowed.
 *
 * The decision no longer rewrites the context it was handed. Pretending the car was parked made
 * every caller believe it, including the ones that only wanted to display the state, and left the
 * denial branch untested. That branch is kept and exercised by passing `enforceParkedOnly = true`.
 */
class FeaturePolicy(
    private val contextProvider: () -> RuntimeContext = { RuntimeContextStore.context.value },
    private val enforceParkedOnly: Boolean = SafetyEnforcement.isBlocking
) {
    private val parkedOnlyFeatures = setOf(
        Feature.MIRROR,
        Feature.TOUCH,
        Feature.QUICK_APPS,
        Feature.APP_LAUNCHER,
        Feature.BROWSER,
        Feature.VIDEO,
        Feature.AUDIO_CAPTURE,
        Feature.SCREEN_OFF
    )

    fun decide(feature: Feature, context: RuntimeContext = contextProvider()): FeatureDecision {
        val requiresParked = feature in parkedOnlyFeatures

        // Runtime bypass (BypassPolicyStore) sits on top of the stock gate. When it is off, the
        // original mode + PARKED logic below runs unchanged, so the safety defaults are intact.
        val bypassMode = BypassPolicyStore.overridesMode
        val bypassParked = BypassPolicyStore.overridesParked

        val modeAllows = bypassMode || when (context.mode) {
            AutoBridgeMode.SAFE -> feature in setOf(Feature.MEDIA, Feature.QUICK_APPS)
            AutoBridgeMode.PERSONAL -> true
            AutoBridgeMode.LAB -> true
        }

        if (!modeAllows) {
            return FeatureDecision(feature, false, "Disabled in ${context.mode.name} mode", requiresParked)
        }

        if (requiresParked && context.vehicleState != VehicleState.PARKED) {
            val state = context.vehicleState.name.lowercase()
            return when {
                bypassParked ->
                    FeatureDecision(feature, true, "Vehicle is $state; bypass override active", true)
                enforceParkedOnly ->
                    FeatureDecision(feature, false, "${feature.name} requires PARKED; vehicle is $state", true)
                else ->
                    FeatureDecision(feature, true, "Vehicle is $state; parked-only gate is reporting, not blocking", true)
            }
        }

        // LAB is expansive only on controlled environments. On a real car it never bypasses the
        // production movement signal; the state gate above remains authoritative.
        if (context.mode == AutoBridgeMode.LAB && context.environment == Environment.REAL_CAR) {
            return FeatureDecision(feature, true, "LAB on real car; real vehicle state enforced", requiresParked)
        }

        val reason = if (bypassMode) "Bypass override active" else "Enabled by ${context.mode.name}"
        return FeatureDecision(feature, true, reason, requiresParked)
    }

    fun isAvailable(feature: Feature, context: RuntimeContext = contextProvider()): Boolean =
        decide(feature, context).allowed

    fun enabledFeatures(context: RuntimeContext = contextProvider()): Set<Feature> =
        Feature.entries.filterTo(linkedSetOf()) { isAvailable(it, context) }

    fun denialMessage(feature: Feature, context: RuntimeContext = contextProvider()): String =
        decide(feature, context).reason

    companion object {
        /** Application-wide policy used by existing Android entry points until Hilt is introduced. */
        val app = FeaturePolicy()
    }
}

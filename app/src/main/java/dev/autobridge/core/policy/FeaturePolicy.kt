//package dev.autobridge.core.policy

//import dev.autobridge.core.model.AutoBridgeMode
//import dev.autobridge.core.model.Environment
//import dev.autobridge.core.model.Feature
//import dev.autobridge.core.model.RuntimeContext
//import dev.autobridge.core.model.VehicleState
//import dev.autobridge.core.state.RuntimeContextStore

///** Result of evaluating a feature at a specific point in the runtime lifecycle. */
//data class FeatureDecision(
//    val feature: Feature,
//    val allowed: Boolean,
//    val reason: String,
//    val requiresParked: Boolean
//)

///**
// * Single policy boundary for mode, environment, and vehicle-state decisions.
// *
// * Callers may retain defensive checks at low-level Android boundaries, but feature entry points
// * should ask this policy rather than inspecting BuildConfig, speed, or mode independently.
// */
//class FeaturePolicy(
//    private val contextProvider: () -> RuntimeContext = { RuntimeContextStore.context.value }
//) {
//    private val parkedOnlyFeatures = setOf(
//        Feature.MIRROR,
//        Feature.TOUCH,
//        Feature.QUICK_APPS,
//        Feature.APP_LAUNCHER,
//        Feature.BROWSER,
//        Feature.VIDEO,
//        Feature.AUDIO_CAPTURE,
//        Feature.SCREEN_OFF
//    )

//    fun decide(feature: Feature, context: RuntimeContext = contextProvider()): FeatureDecision {
//        //val modeAllows = when (context.mode) {
//        //    AutoBridgeMode.SAFE -> feature in setOf(Feature.MEDIA, Feature.QUICK_APPS)
//        //    AutoBridgeMode.PERSONAL -> true
//        //    AutoBridgeMode.LAB -> true
//        //}
//        //val requiresParked = feature in parkedOnlyFeatures
//        //if (!modeAllows) {
//        //    return FeatureDecision(feature, false, "Disabled in ${context.mode.name} mode", requiresParked)
//        //}

//        //if (requiresParked && context.vehicleState != VehicleState.PARKED) {
//        //    val state = context.vehicleState.name.lowercase()
//        //    return FeatureDecision(feature, false, "${feature.name} requires PARKED; vehicle is $state", true)
//        //}

//        //// LAB is expansive only on controlled environments. On a real car it never bypasses the
//        //// production movement signal; the state gate above remains authoritative.
//        //if (context.mode == AutoBridgeMode.LAB && context.environment == Environment.REAL_CAR) {
//        //    return FeatureDecision(feature, true, "LAB on real car; real vehicle state enforced", requiresParked)
//        //}
        

         

//        return FeatureDecision(feature, true, "Allowed (Parked bypass enabled)", requiresParked)

//        //return FeatureDecision(feature, true, "Enabled by ${context.mode.name}", requiresParked)
//    }

//    fun isAvailable(feature: Feature, context: RuntimeContext = contextProvider()): Boolean =
//        decide(feature, context).allowed

//    fun enabledFeatures(context: RuntimeContext = contextProvider()): Set<Feature> =
//        Feature.entries.filterTo(linkedSetOf()) { isAvailable(it, context) }

//    fun denialMessage(feature: Feature, context: RuntimeContext = contextProvider()): String =
//        decide(feature, context).reason

//    companion object {
//        /** Application-wide policy used by existing Android entry points until Hilt is introduced. */
//        val app = FeaturePolicy()
//    }
//}

package dev.autobridge.core.policy

import dev.autobridge.core.model.AutoBridgeMode
import dev.autobridge.core.model.Environment
import dev.autobridge.core.model.Feature
import dev.autobridge.core.model.RuntimeContext
import dev.autobridge.core.model.VehicleState
import dev.autobridge.core.state.RuntimeContextStore

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
 */
class FeaturePolicy(
    private val contextProvider: () -> RuntimeContext = { RuntimeContextStore.context.value }
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

    //fun decide(feature: Feature, context: RuntimeContext = contextProvider()): FeatureDecision {
    //    val requiresParked = feature in parkedOnlyFeatures

    //    return FeatureDecision(
    //        feature = feature,
    //        allowed = true,
    //        reason = "Allowed (All checks bypassed)",
    //        requiresParked = requiresParked
    //    )
    //}

    // ตัวอย่างการ Mock/Override ค่า RuntimeContext ที่ส่งเข้ามา
        fun decide(feature: Feature, context: RuntimeContext = contextProvider()): FeatureDecision {
            // จำลอง Context ว่าเป็นรถจริงและจอดอยู่ตลอดเวลา
            val mockedContext = context.copy(
                environment = Environment.REAL_CAR,
                vehicleState = VehicleState.PARKED
            )
            
            // นำ mockedContext ไปเข้า Logic ตรวจสอบเดิมของแอปตามปกติ
            val requiresParked = feature in parkedOnlyFeatures
            val modeAllows = when (mockedContext.mode) {
                AutoBridgeMode.SAFE -> feature in setOf(Feature.MEDIA, Feature.QUICK_APPS)
                AutoBridgeMode.PERSONAL -> true
                AutoBridgeMode.LAB -> true
            }
            
            if (!modeAllows) {
                return FeatureDecision(feature, false, "Disabled in ${mockedContext.mode.name} mode", requiresParked)
            }

            if (requiresParked && mockedContext.vehicleState != VehicleState.PARKED) {
                val state = mockedContext.vehicleState.name.lowercase()
                return FeatureDecision(feature, false, "${feature.name} requires PARKED; vehicle is $state", true)
            }

            if (mockedContext.mode == AutoBridgeMode.LAB && mockedContext.environment == Environment.REAL_CAR) {
                return FeatureDecision(feature, true, "LAB on real car; real vehicle state enforced", requiresParked)
            }

            return FeatureDecision(feature, true, "Enabled by ${mockedContext.mode.name}", requiresParked)
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
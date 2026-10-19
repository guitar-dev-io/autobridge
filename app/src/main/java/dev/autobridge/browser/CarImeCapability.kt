package dev.autobridge.browser

/**
 * The decision — pure half — for whether the car browser may raise the real system keyboard
 * (Gboard) over the Android Auto projection instead of drawing [CarSurfaceKeyboard].
 *
 * The [gate] is the necessary precondition, evaluated without touching any live window so it is
 * unit-tested on the JVM ([CarImeCapabilityTest]). The actual "did an IME appear?" answer is the
 * stateful runtime probe owned by [CarWebRenderer] (it summons the IME on the hardware window and
 * watches `WindowInsets.Type.ime()`); its tri-state result is [ImeProbeState]. Any inconclusive
 * probe resolves to [ImeProbeState.UNAVAILABLE] so the user always falls back to the safe canvas
 * keyboard — "the user always has a keyboard" is the top invariant.
 */
internal object CarImeCapability {
    /**
     * The necessary preconditions, evaluated without a live window so they unit-test on the JVM.
     * All must hold before the probe is even attempted; any false forces the canvas keyboard.
     *
     *  - [sdkInt] >= 35 (Build.VERSION_CODES.VANILLA_ICE_CREAM, Android 15): below this the platform
     *    does not surface an IME over a virtual display it does not own, regardless of host.
     *  - [hardwareMode]: only the HARDWARE render path has a real window to host a focused editor;
     *    LEGACY_CANVAS has no window and can never show an IME. The caller passes
     *    `activeMode == CarBrowserRenderMode.HARDWARE` (the live value, not the requested one).
     */
    fun gate(sdkInt: Int, hardwareMode: Boolean): Boolean =
        sdkInt >= 35 && hardwareMode // Build.VERSION_CODES.VANILLA_ICE_CREAM
}

/**
 * The runtime-probe outcome, cached per renderer session. [UNKNOWN] until the first keyboard open
 * with the gate passing runs the lazy probe; [AVAILABLE] once an IME was observed; [UNAVAILABLE] on
 * any inconclusive outcome (timeout, null insets, exception, missing IMM, no window) — which always
 * routes to the canvas keyboard.
 */
internal enum class ImeProbeState { UNKNOWN, AVAILABLE, UNAVAILABLE }

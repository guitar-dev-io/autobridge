package dev.autobridge.car

/**
 * A car destination contributed by the build flavor through [CarExtensions], listed in
 * [CarHomeMoreScreen] beside the app's own rows.
 *
 * This is how features that only the sideload flavors carry (Multi-app, the desktop browser
 * engines) are reached from inside AutoBridge instead of from Android Auto launcher entries of
 * their own: AutoBridge stays the single Android Auto app, and the safe (Play) flavor, whose
 * [CarExtensions] lists nothing, is unchanged.
 */
internal data class CarExtensionEntry(
    val title: String,
    val placement: Placement,
    val action: () -> Unit
) {
    /** Which group of the More list the row joins; see [CarHomeMoreScreen]. */
    enum class Placement { FREQUENT, CONTENT }
}

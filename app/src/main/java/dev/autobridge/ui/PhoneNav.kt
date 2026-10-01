package dev.autobridge.ui

/**
 * Phone navigation model: which destinations exist, which four are bottom-bar tabs, which tab a
 * child page lights up, and how Back walks out of a drill-down.
 *
 * Pure Kotlin so the rules are unit-tested without a device. [dev.autobridge.MainActivity] owns
 * the views; this object only answers "where am I and where does Back go".
 */
object PhoneNav {

    /** Every phone destination. Root tabs come first; everything else is a child page. */
    enum class Route {
        HOME,
        CONTROL,
        APPS,
        SETTINGS,

        // Children of CONTROL
        CONTROL_HISTORY,

        // Children of APPS
        PROFILE,

        // Children of SETTINGS
        MIRROR_SETTINGS,
        INPUT_TOUCH,
        CAR_CONNECTION,
        PROFILES,
        AGENT_COMMANDS,
        ADVANCED,
        DEVELOPER,
        DEBUG,
        ABOUT,

        // Children of HOME
        HOME_MUSIC,
        HOME_TV_RADIO,
        HOME_MORE
    }

    /** The bottom navigation, in order. */
    data class Tab(val route: Route, val glyph: String, val label: String)

    val tabs: List<Tab> = listOf(
        Tab(Route.HOME, "⌂", "Home"),
        Tab(Route.CONTROL, "➤", "Control"),
        Tab(Route.APPS, "▦", "Apps"),
        Tab(Route.SETTINGS, "⚙", "Settings")
    )

    fun isRoot(route: Route): Boolean = tabs.any { it.route == route }

    /** The tab that stays highlighted while [route] is on screen. */
    fun tabFor(route: Route): Route = when (route) {
        Route.HOME, Route.HOME_MUSIC, Route.HOME_TV_RADIO, Route.HOME_MORE -> Route.HOME
        Route.CONTROL, Route.CONTROL_HISTORY -> Route.CONTROL
        Route.APPS, Route.PROFILE -> Route.APPS
        Route.SETTINGS,
        Route.MIRROR_SETTINGS,
        Route.INPUT_TOUCH,
        Route.CAR_CONNECTION,
        Route.PROFILES,
        Route.AGENT_COMMANDS,
        Route.ADVANCED,
        Route.DEVELOPER,
        Route.DEBUG,
        Route.ABOUT -> Route.SETTINGS
    }

    /** Parent used when a child is restored without a back stack (e.g. after process death). */
    fun parentOf(route: Route): Route = when (route) {
        Route.DEVELOPER, Route.DEBUG -> Route.ADVANCED
        else -> tabFor(route)
    }

    /** Detail pages that hide the bottom bar so Back is the only way out. */
    fun hidesBottomBar(route: Route): Boolean =
        route == Route.PROFILE || route == Route.DEVELOPER || route == Route.DEBUG

    /**
     * Decodes a saved route name. Names from the previous five-tab layout map onto their new
     * home ("REMOTE" became Control, "DEVICES" moved under Settings, "CONTROL_CENTER" was folded
     * into Control/Settings), and anything unknown falls back to Home instead of crashing.
     */
    fun parse(name: String?): Route = when (name) {
        null -> Route.HOME
        "REMOTE" -> Route.CONTROL
        "DEVICES" -> Route.CAR_CONNECTION
        "CONTROL_CENTER" -> Route.CONTROL
        else -> Route.entries.firstOrNull { it.name == name } ?: Route.HOME
    }

    /**
     * Back stack for the phone shell. Selecting a tab clears it; opening a child pushes the page
     * it was opened from, so Back returns to wherever the user actually came from.
     */
    class BackStack(initial: List<Route> = emptyList()) {
        private val stack = ArrayDeque(initial)

        val entries: List<Route> get() = stack.toList()

        /** Records a forward move from [from] to [to]. */
        fun onNavigate(from: Route?, to: Route) {
            when {
                isRoot(to) -> stack.clear()
                from == null || from == to -> Unit
                else -> {
                    // Re-opening a page already on the stack unwinds to it instead of looping.
                    val existing = stack.indexOf(to)
                    if (existing >= 0) {
                        while (stack.size > existing) stack.removeLast()
                    } else {
                        stack.addLast(from)
                    }
                }
            }
        }

        /**
         * Where Back goes from [current]: the previous page, the parent of an orphaned child, Home
         * for a non-Home tab, or null when Back should leave the app.
         */
        fun back(current: Route): Route? {
            if (stack.isNotEmpty()) return stack.removeLast()
            if (!isRoot(current)) return parentOf(current)
            return if (current != Route.HOME) Route.HOME else null
        }
    }
}

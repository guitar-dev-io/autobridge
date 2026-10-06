package dev.autobridge.ui

/**
 * Phone navigation model: which destinations exist, and how Back walks out of a drill-down.
 *
 * Home is the only root — there is no bottom tab bar. Every other destination (Control, Apps &
 * profiles, Settings, and everything under them) is a child reached from Home, directly or
 * through Settings, and Back always walks back the way the user came, landing on Home last.
 *
 * Pure Kotlin so the rules are unit-tested without a device. [dev.autobridge.MainActivity] owns
 * the views; this object only answers "where am I and where does Back go".
 */
object PhoneNav {

    /** Every phone destination. [HOME] is the only root; everything else is a child page. */
    enum class Route {
        HOME,
        CONTROL,
        APPS,
        SETTINGS,

        // Children of CONTROL
        CONTROL_HISTORY,

        // Children of APPS (merged into Settings > Apps & profiles)
        PROFILE,

        // Children of SETTINGS
        MIRROR_SETTINGS,
        INPUT_TOUCH,
        CAR_CONNECTION,
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

    fun isRoot(route: Route): Boolean = route == Route.HOME

    /**
     * The immediate parent of [route] — where Back lands when there is no recorded back-stack
     * entry (e.g. after process death restores a child with an empty stack).
     */
    fun parentOf(route: Route): Route = when (route) {
        Route.HOME -> Route.HOME
        Route.CONTROL -> Route.HOME
        Route.CONTROL_HISTORY -> Route.CONTROL
        Route.APPS -> Route.SETTINGS
        Route.PROFILE -> Route.APPS
        Route.SETTINGS -> Route.HOME
        Route.MIRROR_SETTINGS,
        Route.INPUT_TOUCH,
        Route.CAR_CONNECTION,
        Route.AGENT_COMMANDS,
        Route.ADVANCED,
        Route.ABOUT -> Route.SETTINGS
        Route.DEVELOPER, Route.DEBUG -> Route.ADVANCED
        Route.HOME_MUSIC, Route.HOME_TV_RADIO, Route.HOME_MORE -> Route.HOME
    }

    /**
     * Decodes a saved route name. Names from older layouts map onto their current home ("REMOTE"
     * became Control, "DEVICES" moved under Settings, "CONTROL_CENTER" was folded into
     * Control/Settings, "PROFILES" merged into Apps & profiles), and anything unknown falls back
     * to Home instead of crashing.
     */
    fun parse(name: String?): Route = when (name) {
        null -> Route.HOME
        "REMOTE" -> Route.CONTROL
        "DEVICES" -> Route.CAR_CONNECTION
        "CONTROL_CENTER" -> Route.CONTROL
        "PROFILES" -> Route.APPS
        else -> Route.entries.firstOrNull { it.name == name } ?: Route.HOME
    }

    /**
     * Back stack for the phone shell. Navigating to Home clears it; opening a child pushes the
     * page it was opened from, so Back returns to wherever the user actually came from.
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
         * Where Back goes from [current]: the previous page, the parent of an orphaned child, or
         * null (leave the app) from Home.
         */
        fun back(current: Route): Route? {
            if (stack.isNotEmpty()) return stack.removeLast()
            if (current == Route.HOME) return null
            return parentOf(current)
        }
    }
}

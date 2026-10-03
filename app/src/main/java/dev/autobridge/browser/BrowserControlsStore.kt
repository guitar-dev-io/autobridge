package dev.autobridge.browser

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.edit
import dev.autobridge.R

/**
 * What the floating button does when it is tapped.
 *
 * The button is the one control available in every chrome state, so which action it carries is the
 * single biggest lever the user has over how the browser feels. Someone who lives in the menu wants
 * [MENU]; someone who switches between two sites all day wants [TABS] and never opens the menu at
 * all. Fixing it to one behaviour makes the other user press two things for every one.
 */
enum class FloatingButtonAction(@StringRes val labelRes: Int, val glyph: String) {
    MENU(R.string.fab_action_menu, "☰"),
    TABS(R.string.fab_action_tabs, "▣"),
    NEW_TAB(R.string.fab_action_new_tab, "+"),
    HOME(R.string.fab_action_home, "⌂"),
    ADDRESS(R.string.fab_action_address, "⌨"),
    FULLSCREEN(R.string.fab_action_fullscreen, "⛶"),
    ;

    /**
     * The name to show for this action. A string *id* rather than the text, because an enum
     * constant is built once per process: a label captured at class-init would keep whatever
     * language was in force then and survive a language change unchanged.
     */
    fun label(context: Context): String = context.getString(labelRes)
}

/**
 * User preferences for the browser's own on-screen controls, shared by the car surface and the
 * phone activity.
 *
 * These exist because there is no single right answer for how much chrome belongs over a page in a
 * car. Auto-hiding the toolbar gives the page every pixel but costs a tap to get the address back;
 * pinning it costs 52dp forever. Both are reasonable, so both are offered, and the default is the
 * one that keeps the page largest.
 *
 * Stored in the same preference file as [BrowserDefaults] and [BrowserUserAgentStore] so the whole
 * browser reads one file.
 */
object BrowserControlsStore {
    private const val PREFS_NAME = "autobridge_browser"
    private const val KEY_ALWAYS_SHOW_URL_BAR = "controls_always_show_url_bar"
    private const val KEY_HIDE_URL_BAR = "controls_hide_url_bar"
    private const val KEY_ALWAYS_SHOW_FLOATING_BUTTON = "controls_always_show_floating_button"
    private const val KEY_FLOATING_BUTTON_ACTION = "controls_floating_button_action"
    private const val KEY_FLOATING_BUTTON_ON_LEFT = "controls_floating_button_on_left"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Keeps the toolbar (and with it the address pill) on screen instead of letting it fade after
     * [ChromeVisibility.DEFAULT_AUTO_HIDE_MS] of no interaction.
     *
     * Off by default: the auto-hiding toolbar is what lets the page own the whole surface, and the
     * floating button means nothing is unreachable while it is away.
     */
    fun alwaysShowUrlBar(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALWAYS_SHOW_URL_BAR, false)

    fun setAlwaysShowUrlBar(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ALWAYS_SHOW_URL_BAR, enabled) }
    }

    /**
     * Removes the toolbar/address bar entirely: it is never drawn, never hit-tested, and neither
     * the edge-reveal band nor the handle can recall it. The floating button remains the way to
     * reach the menu, back/forward and the address input, so nothing becomes unreachable.
     *
     * On by default: the address bar over a car page is the reported clutter ("it just covers the
     * page and I never use it"), and the floating button already reaches everything the bar did.
     * When on it wins over [alwaysShowUrlBar] — asking to hide the bar and to pin it are
     * contradictory, and "hide it, I never use it" is the stronger, more explicit intent.
     */
    fun hideUrlBar(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_URL_BAR, true)

    fun setHideUrlBar(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_HIDE_URL_BAR, enabled) }
    }

    /**
     * Keeps the floating button on screen at all times. When off it fades with the toolbar, so a
     * page that has been left alone is drawn with nothing over it at all.
     *
     * On by default — a control that has to be summoned before it can be used is the problem the
     * floating button exists to solve.
     */
    fun alwaysShowFloatingButton(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALWAYS_SHOW_FLOATING_BUTTON, true)

    fun setAlwaysShowFloatingButton(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ALWAYS_SHOW_FLOATING_BUTTON, enabled) }
    }

    fun floatingButtonAction(context: Context): FloatingButtonAction =
        runCatching {
            FloatingButtonAction.valueOf(
                prefs(context).getString(KEY_FLOATING_BUTTON_ACTION, FloatingButtonAction.MENU.name).orEmpty()
            )
        }.getOrDefault(FloatingButtonAction.MENU)

    fun setFloatingButtonAction(context: Context, action: FloatingButtonAction) {
        prefs(context).edit { putString(KEY_FLOATING_BUTTON_ACTION, action.name) }
    }

    /**
     * Puts the floating button in the bottom-left corner instead of the bottom-right.
     *
     * The right corner is where video players, "next" buttons and chat inputs tend to live, so a
     * fixed right-hand button can sit over exactly the control the user wants. A free drag is not
     * possible on the car surface (the host's scroll callback carries no start point, so a drag on
     * the button cannot be told apart from a page scroll), so the side is a setting instead.
     */
    fun floatingButtonOnLeft(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOATING_BUTTON_ON_LEFT, false)

    fun setFloatingButtonOnLeft(context: Context, onLeft: Boolean) {
        prefs(context).edit { putBoolean(KEY_FLOATING_BUTTON_ON_LEFT, onLeft) }
    }
}

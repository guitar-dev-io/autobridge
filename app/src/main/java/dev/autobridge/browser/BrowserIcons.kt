package dev.autobridge.browser

import androidx.annotation.DrawableRes
import dev.autobridge.R

/**
 * One Material Icon per browser action/role, shared by the car's Canvas-drawn chrome
 * ([CarWebRenderer], via [DrawerItem.icon]) and the phone's native Views ([BrowserActivity],
 * [BrowserMenuSheet], [MoreActionsSheet]).
 *
 * Before this, each surface drew its own literal Unicode glyph per call site — the car and the
 * phone never shared a single constant, so the same action could (and did) look different on
 * each. Routing every icon-bearing control through one of these instead means there is exactly
 * one place that decides what "Back" or "Fullscreen" looks like, and every consumer draws it at
 * its own declared [AutoUiSizes] token, which is what makes icons read as one proportioned set
 * rather than a bag of differently-weighted glyphs.
 *
 * All resources are plain 24dp/24x24-viewport vectors with a solid white fill, tinted by the
 * consumer (`ImageView`/`ImageButton` on the phone, [CarWebRenderer]'s bitmap cache on the car) —
 * the same convention [R.drawable.ic_action_refresh] and [R.drawable.ic_car_home] already use.
 */
enum class BrowserIcon(@DrawableRes val resId: Int) {
    BACK(R.drawable.ic_browser_back),
    FORWARD(R.drawable.ic_browser_forward),
    RELOAD(R.drawable.ic_browser_reload),
    CLOSE(R.drawable.ic_browser_close),
    FULLSCREEN_ENTER(R.drawable.ic_browser_fullscreen),
    FULLSCREEN_EXIT(R.drawable.ic_browser_fullscreen_exit),
    MENU(R.drawable.ic_browser_more_vert),
    /** The browser's own start page — distinct from [APP_HOME], which leaves the browser. */
    HOME_PAGE(R.drawable.ic_car_home),
    /** Leaves the browser for AutoBridge's dashboard; see [DrawerAction.APP_HOME]. */
    APP_HOME(R.drawable.ic_browser_exit_to_app),
    BOOKMARK_LIST(R.drawable.ic_browser_star_border),
    BOOKMARK_ADD(R.drawable.ic_browser_star),
    SETTINGS(R.drawable.ic_browser_settings),
    MORE(R.drawable.ic_browser_more),
    DESKTOP_MODE(R.drawable.ic_browser_desktop),
    SPLIT_LAYOUT(R.drawable.ic_browser_split),
    /** New tab and zoom-in share the same plain "+" glyph, as the old literal text did. */
    ADD(R.drawable.ic_browser_add),
    REMOVE(R.drawable.ic_browser_remove),
    TABS(R.drawable.ic_browser_tabs),
    HISTORY(R.drawable.ic_browser_history),
    DOWNLOAD(R.drawable.ic_browser_download),
    /** Find-in-page and the address bar's search affordance share one magnifying glass. */
    SEARCH(R.drawable.ic_browser_search),
    COPY(R.drawable.ic_browser_copy),
    PASTE(R.drawable.ic_browser_paste),
    OPEN_EXTERNAL(R.drawable.ic_browser_open_external),
    AGENT(R.drawable.ic_browser_agent),
    MUSIC(R.drawable.ic_browser_music),
    PLAY(R.drawable.ic_browser_play),
    LIBRARY(R.drawable.ic_browser_library),
    DELETE(R.drawable.ic_browser_delete),
    INFO(R.drawable.ic_browser_info),
    SUPPORT(R.drawable.ic_browser_support),
    LICENSES(R.drawable.ic_browser_licenses),
    CODE(R.drawable.ic_browser_code),
    CAR(R.drawable.ic_browser_car),
    RECEIVE(R.drawable.ic_browser_receive),
    LOCK(R.drawable.ic_browser_lock),
    WARNING(R.drawable.ic_browser_warning),
    CHEVRON_RIGHT(R.drawable.ic_browser_chevron_right),
}

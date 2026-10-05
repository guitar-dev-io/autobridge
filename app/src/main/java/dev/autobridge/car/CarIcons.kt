package dev.autobridge.car

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import dev.autobridge.R

/**
 * Icons for the header actions that used to carry a text title.
 *
 * A header's end action may not carry a custom title. `ActionsConstraints.ACTIONS_CONSTRAINTS_HEADER`
 * and `ACTIONS_CONSTRAINTS_MULTI_HEADER` in androidx.car.app both leave `maxCustomTitles` at its
 * default of 0 and set `requireActionIcons`, so a titled, icon-less end action is invalid.
 *
 * Nothing in the *client* library enforces that. `Header.Builder.addEndHeaderAction` accepts the
 * action without checking, and `MULTI_HEADER` is referenced by no builder in the library at all —
 * it exists for the **host** to validate against. The Desktop Head Unit's host (build 2022-03-30)
 * let those templates through, so this was invisible in every DHU session; a real car's host
 * rejected the template and threw back across the binder, which reaches the app as:
 *
 * ```
 * java.lang.RuntimeException: java.lang.IllegalArgumentException:
 *   Action list exceeded max number of 0 actions with custom titles
 *     at androidx.car.app.utils.RemoteUtils.lambda$dispatchCallFromHost$0(RemoteUtils.java:153)
 * ```
 *
 * The app died on opening a screen with no way to see why on the head unit. Thirteen end header
 * actions across eleven screens had this shape; they now take an icon from here and no title.
 *
 * [androidx.car.app.model.Action.Builder] in 1.7.0 has no `setContentDescription`, so an icon-only
 * action carries no text label at all - the glyph has to stand on its own.
 */
object CarIcons {
    fun of(context: CarContext, @DrawableRes resourceId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(context, resourceId)).build()

    @DrawableRes val CLEAR = R.drawable.ic_action_clear
    @DrawableRes val MANAGE = R.drawable.ic_action_manage
    @DrawableRes val DONE = R.drawable.ic_action_done
    @DrawableRes val ADD = R.drawable.ic_action_add
    @DrawableRes val EDIT = R.drawable.ic_action_edit
    @DrawableRes val REFRESH = R.drawable.ic_action_refresh
    @DrawableRes val LIBRARY = R.drawable.ic_action_library
    @DrawableRes val CONTROLS = R.drawable.ic_action_controls
    @DrawableRes val APPS = R.drawable.ic_action_apps
    @DrawableRes val SIGNAL = R.drawable.ic_action_signal
}

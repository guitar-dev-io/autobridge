package dev.autobridge.browser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.R

/**
 * The car browser menu as ordinary views, for the two car browsers that are real view trees:
 * Bridge Web (the projection route) and the browser in a Duo Screen pane.
 *
 * The Android Auto template browser draws the same menu on a Canvas from the same
 * [BrowserDrawerModel] lists. This builds rows from [BrowserDrawerModel.carMenu], so all three
 * offer the same entries, in the same order, under the same names; each host only supplies its
 * colours and what tapping an entry does.
 */
object CarMenuList {

    /** The host's colours. Everything else (labels, icons, order, state) comes from the model. */
    data class Style(
        val text: Int,
        val textSecondary: Int,
        val rowFill: Int,
        val accent: Int,
        val onAccent: Int,
    )

    /**
     * The menu rows for [state], top to bottom: the primary entry (accent-filled), Back / Reload /
     * Forward side by side, then the remaining entries one per row, except the split screen's
     * three, which share a row as they share one on the template sheet. A disabled entry (Back
     * with nothing behind it, Swap sides with no split up) is drawn dimmed and does nothing.
     */
    fun build(
        context: Context,
        state: BrowserMenuState,
        style: Style,
        onAction: (DrawerAction) -> Unit,
    ): List<View> {
        val items = BrowserDrawerModel.carMenu(state)
        val views = ArrayList<View>()
        items.firstOrNull()?.let { views += row(context, it, style, onAction, primary = true) }
        val emitted = HashSet<DrawerAction>()
        items.drop(1).forEach { item ->
            if (item.action in emitted) return@forEach
            val group = GROUPS.firstOrNull { item.action in it }
            if (group != null) {
                val members = items.filter { it.action in group }
                emitted += members.map { it.action }
                views += buttonRow(context, members, style, onAction)
            } else {
                views += row(context, item, style, onAction, primary = false)
            }
        }
        return views
    }

    /** Entries drawn side by side as one row of buttons rather than a row each. */
    private val GROUPS = listOf(
        setOf(DrawerAction.NAV_BACK, DrawerAction.RELOAD, DrawerAction.NAV_FORWARD),
        setOf(DrawerAction.SPLIT_LAYOUT, DrawerAction.SIDE_SHOW_PAGE, DrawerAction.SWAP_SPLIT_SIDES),
    )

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun background(context: Context, fill: Int, ripple: Int): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(ripple),
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(14).toFloat()
            setColor(fill)
        },
        null
    )

    /** The switches report their state as On/Off; a tab count or ✓ shows as given. */
    private fun detail(context: Context, item: DrawerItem): String = when (item.action) {
        DrawerAction.TOGGLE_DESKTOP, DrawerAction.TOGGLE_FULLSCREEN, DrawerAction.PIN_TOOLBAR ->
            context.getString(if (item.on) R.string.browser_menu_on else R.string.browser_menu_off)
        else -> item.value.takeIf { it != "✓" }.orEmpty()
    }

    private fun icon(context: Context, item: DrawerItem, tint: Int): ImageView = ImageView(context).apply {
        setImageResource(item.icon.resId)
        setColorFilter(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun row(
        context: Context,
        item: DrawerItem,
        style: Style,
        onAction: (DrawerAction) -> Unit,
        primary: Boolean,
    ): View {
        val fg = if (primary) style.onAccent else style.text
        val label = item.label(context)
        val state = detail(context, item)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = background(context, if (primary) style.accent else style.rowFill, style.textSecondary)
            minimumHeight = context.dp(60)
            setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
            contentDescription = if (state.isBlank()) label else "$label, $state"
            isEnabled = item.enabled
            alpha = if (item.enabled) 1f else 0.4f
            isClickable = item.enabled
            isFocusable = item.enabled
            if (item.enabled) setOnClickListener { onAction(item.action) }
            addView(icon(context, item, fg), LinearLayout.LayoutParams(context.dp(26), context.dp(26)).apply {
                marginEnd = context.dp(14)
            })
            addView(TextView(context).apply {
                text = label
                setTextColor(fg)
                textSize = 17f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                if (primary) typeface = Typeface.create(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (state.isNotBlank()) {
                addView(TextView(context).apply {
                    text = state
                    setTextColor(if (primary) style.onAccent else style.textSecondary)
                    textSize = 15f
                    setPadding(context.dp(8), 0, 0, 0)
                })
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(6) }
        }
    }

    /** Up to three equal buttons on one line (Back / Reload / Forward, the split row). */
    private fun buttonRow(
        context: Context,
        items: List<DrawerItem>,
        style: Style,
        onAction: (DrawerAction) -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEachIndexed { index, item ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = background(context, style.rowFill, style.textSecondary)
                minimumHeight = context.dp(64)
                setPadding(context.dp(4), context.dp(8), context.dp(4), context.dp(8))
                contentDescription = item.label(context)
                alpha = if (item.enabled) 1f else 0.4f
                isClickable = item.enabled
                isFocusable = item.enabled
                if (item.enabled) setOnClickListener { onAction(item.action) }
                addView(icon(context, item, style.text), LinearLayout.LayoutParams(context.dp(26), context.dp(26)))
                addView(TextView(context).apply {
                    text = item.label(context)
                    setTextColor(style.text)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = context.dp(6)
            })
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(6) }
    }
}

/**
 * "About" for the car browsers that have no access to the Android Auto diagnostics screen: what is
 * running and on what, as label/value pairs, so a report from the car names the build, the WebView
 * and the display the page was laid out for.
 */
object CarBrowserAbout {
    fun lines(context: Context): List<Pair<String, String>> {
        val metrics = context.resources.displayMetrics
        val webView = runCatching {
            androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context)
                ?.let { "${it.packageName} ${it.versionName}" }
        }.getOrNull() ?: "-"
        return listOf(
            context.getString(R.string.browser_about_version) to
                "${dev.autobridge.BuildConfig.VERSION_NAME} (${dev.autobridge.BuildConfig.VERSION_CODE})",
            context.getString(R.string.browser_about_webview) to webView,
            context.getString(R.string.car_diag_car_display) to
                "${metrics.widthPixels}×${metrics.heightPixels} @ ${metrics.densityDpi} dpi",
        )
    }
}
